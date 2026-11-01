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
package androidx.media3.extractor.iso.sacd;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.IsoConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SacdTocParser {

  private static final String TAG = "SacdTocParser";

  private static final boolean NATIVE_AVAILABLE;

  static {
    boolean loaded;
    try {
      System.loadLibrary("isoJNI");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      loaded = false;
    }
    NATIVE_AVAILABLE = loaded;
  }

  private static final int[] MASTER_TOC_SECTORS = {510, 520, 530};
  private static final byte[] MAGIC_SACDMTOC = {'S', 'A', 'C', 'D', 'M', 'T', 'O', 'C'};
  private static final int MASTER_TOC_AREA_1_TOC_1_OFFSET = 0x40;
  private static final int MASTER_TOC_AREA_1_TOC_2_OFFSET = 0x44;
  private static final int MASTER_TOC_AREA_2_TOC_1_OFFSET = 0x48;
  private static final int MASTER_TOC_AREA_2_TOC_2_OFFSET = 0x4C;

  private static final byte[] MAGIC_TWOCHTOC = {'T', 'W', 'O', 'C', 'H', 'T', 'O', 'C'};
  private static final byte[] MAGIC_MULCHTOC = {'M', 'U', 'L', 'C', 'H', 'T', 'O', 'C'};
  private static final byte[] MAGIC_SACDTRL1 = {'S', 'A', 'C', 'D', 'T', 'R', 'L', '1'};
  private static final byte[] MAGIC_SACDTRL2 = {'S', 'A', 'C', 'D', 'T', 'R', 'L', '2'};

  private static final int AREA_TOC_NUM_TRACKS_OFFSET = 0x45;
  private static final int AREA_TOC_FRAME_FORMAT_OFFSET = 0x15;
  private static final int AREA_TOC_CHANNEL_COUNT_OFFSET = 0x20;
  private static final int AREA_TOC_TOTAL_PLAYTIME_OFFSET = 0x40;
  private static final int AREA_TOC_AUDIO_START_OFFSET = 0x48;
  private static final int AREA_TOC_AUDIO_END_OFFSET = 0x4C;
  private static final int AREA_TOC_SIZE_OFFSET = 0x0A;
  private static final int MAX_AREA_TOC_SECTORS = 96;

  private static final int MAGIC_SIZE = 8;
  private static final int TRL1_START_LSN_ARRAY_OFFSET = MAGIC_SIZE;
  private static final int TRL1_LENGTH_LSN_ARRAY_OFFSET = MAGIC_SIZE + 255 * 4;

  private static final int TRL2_DURATION_ARRAY_OFFSET = MAGIC_SIZE + 255 * 4;

  public static boolean isSacd(CacheDataReader reader) throws IOException {
    byte[] magic = new byte[MAGIC_SIZE];
    for (int sector : MASTER_TOC_SECTORS) {
      int read = reader.read((long) sector * IsoConstants.SECTOR_SIZE, magic, 0, MAGIC_SIZE);
      if (read == MAGIC_SIZE && Arrays.equals(magic, MAGIC_SACDMTOC)) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  public static SacdStructure parse(CacheDataReader reader) throws IOException {
    if (NATIVE_AVAILABLE) {
      long[][] areas = nativeParse(reader);
      if (areas != null && areas.length == 2) {
        return new SacdStructure(
            areaFromNative(areas[0], SacdArea.TYPE_STEREO),
            areaFromNative(areas[1], SacdArea.TYPE_MULTI));
      }
      Log.w(TAG, "SACD: invalid native result");
      return null;
    }
    IOException lastError = null;
    for (int sector : MASTER_TOC_SECTORS) {
      try {
        byte[] masterSector = readSector(reader, sector);
        if (Arrays.equals(Arrays.copyOf(masterSector, MAGIC_SIZE), MAGIC_SACDMTOC)) {
          return parseMasterToc(reader, masterSector);
        }
      } catch (IOException e) {
        lastError = e;
      }
    }
    throw new IOException("SACD: no usable master TOC copy", lastError);
  }

  /**
   * Converts one native area row into a {@link SacdArea}.
   *
   * <p>The row layout is {@code [encoding, channelCount, audioStartSector, audioEndSector,
   * trackCount, (trackNumber, trackChannelCount, startLsn, lengthLsn, durationUs) * trackCount]}.
   */
  @Nullable
  private static SacdArea areaFromNative(long[] area, int expectedAreaType) throws IOException {
    if (area == null) {
      return null;
    }
    if (area.length >= 5) {
      long trackCount = area[4];
      if (trackCount >= 0 && trackCount <= 255 && area.length == trackCount * 5 + 5) {
        List<SacdTrack> tracks = new ArrayList<>((int) trackCount);
        for (int i = 5; i < area.length; i += 5) {
          tracks.add(
              new SacdTrack(
                  (int) area[i], (int) area[i + 1], area[i + 2], area[i + 3], area[i + 4]));
        }
        int audioEncoding = (int) area[0];
        int channelCount = (int) area[1];
        long audioStartSector = area[2];
        long audioEndSector = area[3];
        return new SacdArea(
            expectedAreaType,
            audioEncoding,
            channelCount,
            audioStartSector,
            audioEndSector,
            tracks);
      }
    }
    Log.w(TAG, "SACD: invalid native area data");
    return null;
  }

  private static native long[][] nativeParse(CacheDataReader reader);

  private static SacdStructure parseMasterToc(CacheDataReader reader, byte[] masterSector)
      throws IOException {
    ByteBuffer masterBb = ByteBuffer.wrap(masterSector).order(ByteOrder.BIG_ENDIAN);
    long area1Toc1 = masterBb.getInt(MASTER_TOC_AREA_1_TOC_1_OFFSET) & 0xFFFFFFFFL;
    long area1Toc2 = masterBb.getInt(MASTER_TOC_AREA_1_TOC_2_OFFSET) & 0xFFFFFFFFL;
    long area2Toc1 = masterBb.getInt(MASTER_TOC_AREA_2_TOC_1_OFFSET) & 0xFFFFFFFFL;
    long area2Toc2 = masterBb.getInt(MASTER_TOC_AREA_2_TOC_2_OFFSET) & 0xFFFFFFFFL;
    SacdArea firstArea = tryParseArea(reader, area1Toc1, area1Toc2, SacdArea.TYPE_STEREO);
    SacdArea secondArea = tryParseArea(reader, area2Toc1, area2Toc2, SacdArea.TYPE_MULTI);
    if ((area1Toc1 > 0 || area1Toc2 > 0) && firstArea == null) {
      throw new IOException("SACD: no valid stereo area TOC");
    }
    if ((area2Toc1 > 0 || area2Toc2 > 0) && secondArea == null) {
      throw new IOException("SACD: no valid multichannel area TOC");
    }
    if (firstArea == null && secondArea == null) {
      throw new IOException("SACD: no valid audio area TOC");
    }
    return new SacdStructure(firstArea, secondArea);
  }

  @Nullable
  private static SacdArea tryParseArea(
      CacheDataReader reader, long primaryTocSector, long backupTocSector, int expectedAreaType) {
    SacdArea area = tryParseArea(reader, primaryTocSector, expectedAreaType);
    return area != null ? area : tryParseArea(reader, backupTocSector, expectedAreaType);
  }

  @Nullable
  private static SacdArea tryParseArea(
      CacheDataReader reader, long areaTocSector, int expectedAreaType) {
    if (areaTocSector <= 0) {
      return null;
    }
    try {
      byte[] areaSector = readSector(reader, areaTocSector);
      SacdArea area = parseArea(reader, areaSector, areaTocSector);
      return area.type == expectedAreaType ? area : null;
    } catch (IOException ignored) {
      return null;
    }
  }

  private static SacdArea parseArea(CacheDataReader reader, byte[] areaSector, long areaTocSector)
      throws IOException {
    ByteBuffer areaBb = ByteBuffer.wrap(areaSector).order(ByteOrder.BIG_ENDIAN);
    byte[] areaMagic = Arrays.copyOf(areaSector, MAGIC_SIZE);
    boolean isStereo = Arrays.equals(areaMagic, MAGIC_TWOCHTOC);
    boolean isMulti = Arrays.equals(areaMagic, MAGIC_MULCHTOC);
    if (!isStereo && !isMulti) {
      throw new IOException(
          "SACD: unrecognised area TOC magic at sector "
              + areaTocSector
              + " (got: "
              + Util.toHexString(areaMagic)
              + ")");
    }
    int type = isStereo ? SacdArea.TYPE_STEREO : SacdArea.TYPE_MULTI;
    int numTracks = areaSector[AREA_TOC_NUM_TRACKS_OFFSET] & 0xFF;
    int frameFormat = areaSector[AREA_TOC_FRAME_FORMAT_OFFSET] & 0x0F;
    int audioEncoding = (frameFormat == 0) ? SacdArea.ENCODING_DST : SacdArea.ENCODING_DSD;
    int channelCount = areaSector[AREA_TOC_CHANNEL_COUNT_OFFSET] & 0xFF;
    if (channelCount == 0) {
      channelCount = (type == SacdArea.TYPE_STEREO) ? 2 : 6;
    }
    long audioStartSector = areaBb.getInt(AREA_TOC_AUDIO_START_OFFSET) & 0xFFFFFFFFL;
    long audioEndSector = areaBb.getInt(AREA_TOC_AUDIO_END_OFFSET) & 0xFFFFFFFFL;
    long areaDurationUs =
        Util.scaleLargeTimestamp(
            ((areaSector[AREA_TOC_TOTAL_PLAYTIME_OFFSET] & 0xFF) * 60L
                        + (areaSector[AREA_TOC_TOTAL_PLAYTIME_OFFSET + 1] & 0xFF))
                    * 75L
                + (areaSector[AREA_TOC_TOTAL_PLAYTIME_OFFSET + 2] & 0xFF),
            C.MICROS_PER_SECOND,
            75L);
    int areaTocSectors = areaBb.getShort(AREA_TOC_SIZE_OFFSET) & 0xFFFF;
    areaTocSectors = Util.constrainValue(areaTocSectors, 1, MAX_AREA_TOC_SECTORS);
    List<SacdTrack> tracks =
        parseTracks(
            reader,
            areaTocSector,
            areaTocSectors,
            numTracks,
            channelCount,
            audioStartSector,
            audioEndSector,
            areaDurationUs);
    return new SacdArea(
        type, audioEncoding, channelCount, audioStartSector, audioEndSector, tracks);
  }

  private static List<SacdTrack> parseTracks(
      CacheDataReader reader,
      long areaTocSector,
      int areaTocSectors,
      int numTracks,
      int channelCount,
      long audioStartSector,
      long audioEndSector,
      long areaDurationUs)
      throws IOException {
    List<SacdTrack> tracks = new ArrayList<>(numTracks);
    if (numTracks == 0) {
      return tracks;
    }
    byte[] trl1Data = null;
    byte[] trl2Data = null;
    for (int i = 1; i < areaTocSectors; i++) {
      byte[] data = readSector(reader, areaTocSector + i);
      byte[] magic = Arrays.copyOf(data, MAGIC_SIZE);
      if (Arrays.equals(magic, MAGIC_SACDTRL1)) {
        trl1Data = data;
      } else if (Arrays.equals(magic, MAGIC_SACDTRL2)) {
        trl2Data = data;
      }
      if (trl1Data != null && trl2Data != null) {
        break;
      }
    }
    if (trl1Data == null) {
      long lengthLsn = Math.max(0, audioEndSector - audioStartSector + 1);
      tracks.add(new SacdTrack(1, channelCount, audioStartSector, lengthLsn, areaDurationUs));
      return tracks;
    }
    ByteBuffer trl1Bb = ByteBuffer.wrap(trl1Data).order(ByteOrder.BIG_ENDIAN);
    ByteBuffer trl2Bb =
        (trl2Data != null) ? ByteBuffer.wrap(trl2Data).order(ByteOrder.BIG_ENDIAN) : null;
    for (int t = 0; t < numTracks; t++) {
      int startOff = TRL1_START_LSN_ARRAY_OFFSET + t * 4;
      int lengthOff = TRL1_LENGTH_LSN_ARRAY_OFFSET + t * 4;
      if (lengthOff + 4 > IsoConstants.SECTOR_SIZE) {
        break;
      }
      long startLsn = trl1Bb.getInt(startOff) & 0xFFFFFFFFL;
      long lengthLsn = trl1Bb.getInt(lengthOff) & 0xFFFFFFFFL;
      if (lengthLsn == 0) {
        continue;
      }
      long durationUs = 0;
      if (trl2Bb != null) {
        int durOff = TRL2_DURATION_ARRAY_OFFSET + t * 4;
        if (durOff + 4 <= IsoConstants.SECTOR_SIZE) {
          int durMin = trl2Bb.get(durOff) & 0xFF;
          int durSec = trl2Bb.get(durOff + 1) & 0xFF;
          int durFrame = trl2Bb.get(durOff + 2) & 0xFF;
          durationUs =
              Util.scaleLargeTimestamp(
                  (durMin * 60L + durSec) * 75L + durFrame, C.MICROS_PER_SECOND, 75L);
        }
      }
      if (durationUs <= 0) {
        durationUs = lengthLsn * 2040L * 1_000_000L / (channelCount * 352_800L);
      }
      tracks.add(new SacdTrack(t + 1, channelCount, startLsn, lengthLsn, durationUs));
    }
    if (tracks.isEmpty()) {
      long lengthLsn = Math.max(0, audioEndSector - audioStartSector + 1);
      tracks.add(new SacdTrack(1, channelCount, audioStartSector, lengthLsn, areaDurationUs));
    }
    return tracks;
  }

  private static byte[] readSector(CacheDataReader reader, long sector) throws IOException {
    byte[] buf = new byte[IsoConstants.SECTOR_SIZE];
    int read = reader.read(sector * IsoConstants.SECTOR_SIZE, buf, 0, IsoConstants.SECTOR_SIZE);
    if (read < IsoConstants.SECTOR_SIZE) {
      throw new IOException("SACD: short read at sector " + sector + ": " + read + " bytes");
    }
    return buf;
  }
}
