/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls;

import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decrypts CMG protected MPEG-2 transport streams in place.
 *
 * <p>A CMG segment is a raw {@code .ts} buffer of 188 byte packets whose elementary stream is
 * carried in a single tracked PID. The PES payload of that PID is assembled, split into H.264 NAL
 * units, decrypted through {@link CmgNativeRuntime}, and then written back into the original packet
 * slots. Continuity counters and the PCR PID are corrected afterwards.
 *
 * <p>This is a faithful port of the shipped implementation and is only ever invoked for {@code .ts}
 * segments served from a CMG host, so it cannot affect unrelated streams.
 */
@UnstableApi
/* package */ final class CmgDecryptor {

  private static final String TAG = "CmgDecryptor";
  private static final int TS_PACKET_SIZE = 188;
  private static final int TS_MAX_PAYLOAD = 183;
  private static final int TS_SYNC_BYTE = 0x47;
  private static final int H264_NAL_STATE = 7;
  private static final int H264_NAL_NON_IDR = 1;
  private static final int H264_NAL_IDR = 5;
  private static final int RECONFIGURE_NAL_THRESHOLD = 150;

  private CmgDecryptor() {}

  /** A located NAL start code: its byte offset and length (3 or 4). */
  private static final class StartCode {
    final int position;
    final int length;

    StartCode(int position, int length) {
      this.position = position;
      this.length = length;
    }
  }

  /** A half-open byte range {@code [start, end)} covering a single NAL body. */
  private static final class NalRange {
    final int start;
    final int end;

    NalRange(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  /** A packet slot recording where a PES slice lives inside the transport stream. */
  private static final class PacketMap {
    final int packetStart;
    final int payloadStart;
    final int payloadLength;

    PacketMap(int packetStart, int payloadStart, int payloadLength) {
      this.packetStart = packetStart;
      this.payloadStart = payloadStart;
      this.payloadLength = payloadLength;
    }

    /**
     * Rebuilds the adaptation field of the packet so that {@code fillLength} bytes of stuffing are
     * appended after the existing header, mirroring how the original implementation reflows a
     * shortened payload.
     */
    void rewriteHeader(int fillLength, byte[] ts) {
      int headerSpan = (payloadStart - packetStart) - 5;
      ts[packetStart + 4] = (byte) fillLength;
      if (fillLength == 0) {
        return;
      }
      int fillStart;
      if (headerSpan > 0) {
        fillStart = packetStart + 5 + headerSpan;
      } else {
        ts[packetStart + 5] = 0;
        fillStart = packetStart + 6;
      }
      Arrays.fill(ts, fillStart, packetStart + 5 + fillLength, (byte) -1);
    }
  }

  /** The assembled PES payload of the tracked PID across one or more packets. */
  private static final class PesPacket {
    final ByteArrayOutputStream payload = new ByteArrayOutputStream();
    final ArrayList<PacketMap> packets = new ArrayList<>();
    final byte[] header;
    final int pid;
    final int headerLength;
    final int declaredLength;

    PesPacket(byte[] ts, int pid, int payloadStart, int headerLength, int declaredLength) {
      this.header = Arrays.copyOfRange(ts, payloadStart, payloadStart + headerLength);
      this.pid = pid;
      this.headerLength = headerLength;
      this.declaredLength = declaredLength;
    }
  }

  /** A decrypted PES payload paired with the packet slots it must be written back to. */
  private static final class DecodedOutput {
    final PesPacket source;
    final byte[] data;

    DecodedOutput(PesPacket source, byte[] data) {
      this.source = source;
      this.data = data;
    }
  }

  /**
   * Decrypts a CMG transport segment in place.
   *
   * @param ts The full {@code .ts} segment buffer; modified in place on success.
   * @param session The owning CMG session.
   * @throws IOException If the session goes inactive or the native runtime rejects the stream.
   */
  public static void process(byte[] ts, CmgSession session) throws IOException {
    List<PesPacket> pesPackets;
    if (ts.length == 0 || ts.length % TS_PACKET_SIZE != 0) {
      return;
    }
    pesPackets = collectPesPackets(ts);
    if (pesPackets == null || pesPackets.isEmpty()) {
      return;
    }

    synchronized (CmgSession.LOCK) {
      if (session.isReleased()) {
        Log.w(TAG, "CMG session is released");
        return;
      }
      if (!CmgNativeRuntime.isAvailable()) {
        Log.w(TAG, "libcmg_decrypt.so is unavailable for this ABI");
        return;
      }
      session.ensurePlayerTag();
      session.configureRuntime();

      ArrayList<DecodedOutput> outputs = new ArrayList<>();
      for (int i = 0; i < pesPackets.size(); i++) {
        PesPacket pes = pesPackets.get(i);
        byte[] payload = truncatePayload(pes);
        if (payload == null) {
          continue;
        }
        DecodedOutput output = decryptPes(payload, session);
        if (output == null) {
          continue;
        }
        int totalPayloadAcrossPackets = 0;
        for (PacketMap map : pes.packets) {
          totalPayloadAcrossPackets += map.payloadLength;
        }
        int declared = pes.declaredLength;
        if (output.data.length + pes.headerLength > totalPayloadAcrossPackets
            || (declared != 0 && (output.data.length + pes.headerLength) - 6 > 65535)) {
          Log.w(TAG, "CMG output does not fit the source transport stream");
          return;
        }
        outputs.add(output);
      }

      writeBack(ts, pesPackets, outputs);
      fixUpContinuityAndPcr(ts, pesPackets);
    }
  }

  /**
   * Walks the 188 byte packets and groups the payload of the first PID that begins with a PES packet
   * start. Returns {@code null} if a malformed packet is encountered (matching the original's early
   * abort on non-sync/error/invalid packets).
   */
  private static List<PesPacket> collectPesPackets(byte[] ts) {
    ArrayList<PesPacket> result = new ArrayList<>();
    int trackedPid = -1;
    PesPacket current = null;
    for (int i = 0; i < ts.length; i += TS_PACKET_SIZE) {
      if ((ts[i] & 0xFF) != TS_SYNC_BYTE) {
        return Collections.emptyList();
      }
      byte second = ts[i + 1];
      if ((second & 0x80) != 0) {
        return Collections.emptyList();
      }
      int pid = ((second & 31) << 8) | (ts[i + 2] & 0xFF);
      int adaptationFieldControl = (ts[i + 3] >>> 4) & 3;
      if (adaptationFieldControl == 0) {
        return Collections.emptyList();
      }
      if (adaptationFieldControl == 2) {
        continue;
      }
      int payloadStart = i + 4;
      if (adaptationFieldControl == 3) {
        payloadStart += (ts[payloadStart] & 0xFF) + 1;
      }
      int packetEnd = i + TS_PACKET_SIZE;
      if (payloadStart > packetEnd) {
        return Collections.emptyList();
      }
      if (payloadStart == packetEnd) {
        continue;
      }
      boolean payloadUnitStart = (second & 0x40) != 0;
      if (trackedPid < 0 && payloadUnitStart && isPesStart(payloadStart, packetEnd, ts)) {
        trackedPid = pid;
      }
      if (pid != trackedPid) {
        continue;
      }
      if (payloadUnitStart) {
        if (current != null) {
          result.add(current);
        }
        if (isPesStart(payloadStart, packetEnd, ts)) {
          int headerLength = (ts[payloadStart + 8] & 0xFF) + 9;
          if (payloadStart + headerLength > packetEnd) {
            return Collections.emptyList();
          }
          int declaredLength = ((ts[payloadStart + 4] & 0xFF) << 8) | (ts[payloadStart + 5] & 0xFF);
          current = new PesPacket(ts, pid, payloadStart, headerLength, declaredLength);
          int sliceLength = packetEnd - payloadStart;
          current.packets.add(new PacketMap(i, payloadStart, sliceLength));
          current.payload.write(ts, payloadStart + headerLength, sliceLength - headerLength);
        } else {
          current = null;
        }
      } else if (current != null && payloadStart < packetEnd) {
        int sliceLength = packetEnd - payloadStart;
        current.payload.write(ts, payloadStart, sliceLength);
        current.packets.add(new PacketMap(i, payloadStart, sliceLength));
      }
    }
    if (current != null) {
      result.add(current);
    }
    return result;
  }

  /** Trims the assembled PES payload to its declared length, or null if inconsistent. */
  private static byte[] truncatePayload(PesPacket pes) {
    byte[] payload = pes.payload.toByteArray();
    if (pes.declaredLength == 0) {
      return payload;
    }
    int expected = pes.declaredLength - (pes.headerLength - 6);
    if (expected < 0 || payload.length < expected) {
      return null;
    }
    if (expected != payload.length) {
      payload = Arrays.copyOf(payload, expected);
    }
    return payload;
  }

  /**
   * Splits a PES payload into NAL units, decrypts the video/state NALs through the native runtime,
   * and reassembles them. Returns null if the runtime rejects the stream or no NAL was decrypted.
   */
  private static DecodedOutput decryptPes(byte[] payload, CmgSession session) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
    ArrayList<NalRange> ranges = new ArrayList<>();
    StartCode startCode = findStartCode(0, payload);
    while (startCode != null) {
      int nalStart = startCode.position + startCode.length;
      StartCode next = findStartCode(nalStart, payload);
      int nalEnd = next == null ? payload.length : next.position;
      if (nalStart < nalEnd) {
        ranges.add(new NalRange(nalStart, nalEnd));
      }
      startCode = next;
    }

    boolean anyDecrypted = false;
    int written = 0;
    for (int r = 0; r < ranges.size(); r++) {
      NalRange range = ranges.get(r);
      int nalStart = range.start;
      int nalEnd = range.end;
      int nalLength = nalEnd - nalStart;
      out.write(payload, written, nalStart - written);
      int nalType = payload[nalStart] & 31;

      if (session.isReleased()) {
        Log.w(TAG, "CMG session is released");
        return null;
      }
      if (nalType == H264_NAL_STATE) {
        if (session.incrementVideoNalCount() >= RECONFIGURE_NAL_THRESHOLD) {
          session.resetVideoNalCount();
        }
      }
      CmgNativeRuntime.setPlayerTag(session.getPlayerTag());
      CmgNativeRuntime.setClock(System.currentTimeMillis() + session.getClockOffsetMs());
      if (!CmgNativeRuntime.updateSession()) {
        session.invalidateVideoState();
        Log.w(TAG, "CMG runtime failed to update its session");
        return null;
      }

      byte[] decrypted;
      if (nalType == H264_NAL_STATE) {
        decrypted = Arrays.copyOfRange(payload, nalStart, nalStart + nalLength);
        if (nalLength > 2) {
          int state = decrypted[2] & 3;
          session.markVideoStateActive(state == 1 || state == 2);
        }
        if (CmgNativeRuntime.decodeStateNal(decrypted) == null) {
          session.invalidateVideoState();
          Log.w(TAG, "CMG runtime rejected H.264 state NAL");
          return null;
        } else if (nalLength > 2) {
          byte marker = decrypted[2];
          if ((marker & 3) != 0) {
            decrypted[2] = (byte) (marker & ~3);
          } else {
            decrypted = null;
          }
        } else {
          decrypted = null;
        }
      } else if (nalType == H264_NAL_NON_IDR || nalType == H264_NAL_IDR) {
        session.incrementVideoNalCount();
        if (session.isVideoStateActive()) {
          decrypted = Arrays.copyOfRange(payload, nalStart, nalStart + nalLength);
          int decodedLength =
              CmgNativeRuntime.decodeVideoNalInPlace(decrypted, 0, decrypted.length);
          if (decodedLength < 0 || decodedLength > decrypted.length) {
            session.invalidateVideoState();
            Log.w(TAG, "CMG runtime rejected H.264 video NAL");
            return null;
          } else if (decodedLength != decrypted.length) {
            decrypted = Arrays.copyOf(decrypted, decodedLength);
          }
        } else {
          decrypted = null;
        }
      } else {
        decrypted = null;
      }

      if (decrypted == null) {
        out.write(payload, nalStart, nalEnd - nalStart);
      } else {
        out.write(decrypted, 0, decrypted.length);
        anyDecrypted = true;
      }
      written = nalEnd;
    }
    out.write(payload, written, payload.length - written);
    return anyDecrypted ? new DecodedOutput(null, out.toByteArray()) : null;
  }

  /** Writes each decrypted PES payload back into the original packet slots. */
  private static void writeBack(byte[] ts, List<PesPacket> pesPackets, List<DecodedOutput> outputs) {
    for (int i = 0; i < outputs.size(); i++) {
      PesPacket pes = pesPackets.get(i);
      byte[] decrypted = outputs.get(i).data;
      ArrayList<PacketMap> maps = pes.packets;
      int headerLength = pes.headerLength;
      int totalLength = decrypted.length + headerLength;
      byte[] pesBuffer = new byte[totalLength];
      System.arraycopy(pes.header, 0, pesBuffer, 0, headerLength);
      System.arraycopy(decrypted, 0, pesBuffer, headerLength, decrypted.length);
      if (pes.declaredLength > 0) {
        int packetLength = totalLength - 6;
        pesBuffer[4] = (byte) (packetLength >>> 8);
        pesBuffer[5] = (byte) packetLength;
      }
      int offset = 0;
      for (PacketMap map : maps) {
        int remaining = totalLength - offset;
        int slotLength = map.payloadLength;
        if (remaining >= slotLength) {
          System.arraycopy(pesBuffer, offset, ts, map.payloadStart, slotLength);
          offset += slotLength;
        } else {
          int packetStart = map.packetStart;
          if (remaining == 0) {
            int p1 = packetStart + 1;
            ts[p1] = (byte) (ts[p1] & ~0x40);
            int p3 = packetStart + 3;
            ts[p3] = (byte) ((ts[p3] & 0xCF) | 0x20);
            map.rewriteHeader(TS_MAX_PAYLOAD, ts);
          } else {
            int p3 = packetStart + 3;
            ts[p3] = (byte) ((ts[p3] & 0xCF) | 0x30);
            map.rewriteHeader(TS_MAX_PAYLOAD - remaining, ts);
            System.arraycopy(pesBuffer, offset, ts, packetStart + TS_PACKET_SIZE - remaining, remaining);
            offset += remaining;
          }
        }
      }
    }
  }

  /** Renumbers continuity counters of the tracked PID and refreshes the PCR PID after shortening. */
  private static void fixUpContinuityAndPcr(byte[] ts, List<PesPacket> pesPackets) {
    int pcrPid = pesPackets.get(0).pid;
    int lastContinuityCounter = -1;
    for (int i = 0; i < ts.length; i += TS_PACKET_SIZE) {
      int pid = (((ts[i + 1] & 0xFF) & 31) << 8) | (ts[i + 2] & 0xFF);
      if (pid != pcrPid) {
        continue;
      }
      int headerIndex = i + 3;
      byte value = ts[headerIndex];
      boolean hasAdaptation = ((value >>> 4) & 1) != 0;
      int continuityCounter = value & 15;
      if (lastContinuityCounter < 0) {
        lastContinuityCounter = continuityCounter;
      } else if (hasAdaptation) {
        lastContinuityCounter = (lastContinuityCounter + 1) & 15;
      }
      ts[headerIndex] = (byte) ((value & 0xF0) | lastContinuityCounter);
    }
  }

  /** Locates the next 00 00 01 / 00 00 00 01 start code at or after {@code from}. */
  private static StartCode findStartCode(int from, byte[] data) {
    int i = Math.max(0, from);
    while (true) {
      int next = i + 2;
      if (next >= data.length) {
        return null;
      }
      if (data[i] == 0 && data[i + 1] == 0) {
        byte marker = data[next];
        if (marker == 1) {
          return new StartCode(i, 3);
        }
        int third = i + 3;
        if (third < data.length && marker == 0 && data[third] == 1) {
          return new StartCode(i, 4);
        }
      }
      i++;
    }
  }

  /** Whether {@code payloadStart} begins a PES packet (00 00 01 1110xxxx). */
  private static boolean isPesStart(int payloadStart, int packetEnd, byte[] ts) {
    return payloadStart + 9 <= packetEnd
        && ts[payloadStart] == 0
        && ts[payloadStart + 1] == 0
        && ts[payloadStart + 2] == 1
        && (ts[payloadStart + 3] & 0xF0) == 0xE0;
  }
}
