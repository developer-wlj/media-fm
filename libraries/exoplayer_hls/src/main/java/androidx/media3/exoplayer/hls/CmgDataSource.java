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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link DataSource} wrapper that transparently decrypts CMG protected transport stream segments.
 *
 * <p>For {@code .ts} segments served from a CMG host the whole segment is buffered, decrypted in
 * place through {@link CmgDecryptor}, and then served from memory. All other requests are passed
 * straight through to the wrapped data source. This mirrors how the shipped player hooks the CMG
 * runtime at the transport layer instead of the demuxer.
 */
@UnstableApi
/* package */ final class CmgDataSource implements DataSource {

  private static final String TAG = "CmgDataSource";
  private static final int MAX_SEGMENT_SIZE = 16 * 1024 * 1024;
  private static final int READ_BUFFER_SIZE = 183;
  private static final String CMG_HOST = "ysp.cctv.cn";

  private final DataSource delegate;
  private final CmgSession session;

  private boolean opened;
  private boolean passthrough;
  @Nullable private byte[] buffer;
  private int bufferOffset;
  private int bufferRemaining;

  CmgDataSource(DataSource delegate, CmgSession session) {
    this.delegate = delegate;
    this.session = session;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    delegate.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    opened = true;
    Uri uri = dataSpec.uri;
    @Nullable String path = uri.getPath();
    @Nullable String host = uri.getHost();
    if (path != null && host != null && path.toLowerCase(Locale.US).endsWith(".ts") && isCmgHost(host)) {
      return openCmgSegment(dataSpec);
    }
    passthrough = true;
    return delegate.open(dataSpec);
  }

  private long openCmgSegment(DataSpec dataSpec) throws IOException {
    passthrough = false;
    DataSpec wholeSegment =
        dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET).build();
    long declaredLength = delegate.open(wholeSegment);
    if (declaredLength > MAX_SEGMENT_SIZE) {
      Log.w(TAG, "Yangshipin CMG segment exceeds size limit");
      return 0;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(declaredLength >= 0 ? (int) declaredLength : 32768);
    byte[] readBuffer = new byte[READ_BUFFER_SIZE];
    int totalRead = 0;
    while (true) {
      int read = delegate.read(readBuffer, 0, readBuffer.length);
      if (read == C.RESULT_END_OF_INPUT) {
        break;
      }
      if (read != 0) {
        totalRead += read;
        if (totalRead > MAX_SEGMENT_SIZE) {
          Log.w(TAG, "Yangshipin CMG segment exceeds size limit");
          return 0;
        }
        out.write(readBuffer, 0, read);
      }
    }
    byte[] segment = out.toByteArray();
    long generation = session.acquire();
    try {
      session.syncClockFromHeaders(delegate.getResponseHeaders());
      CmgDecryptor.process(segment, session);
    } catch (IOException e) {
      session.invalidateVideoState();
      throw e;
    } catch (LinkageError | RuntimeException e) {
      session.invalidateVideoState();
      throw new IOException("CMG native runtime failed", e);
    } finally {
      session.release(generation);
    }

    buffer = segment;
    long requestedPosition = dataSpec.position;
    if (requestedPosition > segment.length) {
      throw new IOException("CMG segment position exceeds length");
    }
    bufferOffset = (int) requestedPosition;
    int available = segment.length - bufferOffset;
    if (dataSpec.length != C.LENGTH_UNSET) {
      available = (int) Math.min(available, dataSpec.length);
    }
    bufferRemaining = available;
    return available;
  }

  @Override
  public int read(byte[] target, int offset, int length) throws IOException {
    if (passthrough) {
      return delegate.read(target, offset, length);
    }
    if (length == 0) {
      return 0;
    }
    if (bufferRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    byte[] source = checkNotNullBuffer();
    int toRead = Math.min(length, bufferRemaining);
    System.arraycopy(source, bufferOffset, target, offset, toRead);
    bufferOffset += toRead;
    bufferRemaining -= toRead;
    return toRead;
  }

  private byte[] checkNotNullBuffer() {
    byte[] local = buffer;
    if (local == null) {
      throw new IllegalStateException("CMG buffer not initialized");
    }
    return local;
  }

  @Override
  public Uri getUri() {
    return delegate.getUri();
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return delegate.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    try {
      if (opened) {
        delegate.close();
      }
    } finally {
      opened = false;
      passthrough = false;
      buffer = null;
      bufferOffset = 0;
      bufferRemaining = 0;
    }
  }

  private static boolean isCmgHost(String host) {
    String normalized = host.toLowerCase(Locale.US);
    return normalized.equals(CMG_HOST) || normalized.endsWith("." + CMG_HOST);
  }

  /**
   * An {@link HlsDataSourceFactory} that wraps media chunk data sources with {@link CmgDataSource},
   * sharing a single {@link CmgSession} across all chunks of one {@link HlsMediaSource}.
   */
  /* package */ static final class Factory implements HlsDataSourceFactory {

    private final HlsDataSourceFactory delegate;
    private final CmgSession session;

    Factory(HlsDataSourceFactory delegate, CmgSession session) {
      this.delegate = delegate;
      this.session = session;
    }

    @Override
    public DataSource createDataSource(@C.DataType int dataType) {
      DataSource dataSource = delegate.createDataSource(dataType);
      if (dataType == C.DATA_TYPE_MEDIA) {
        return new CmgDataSource(dataSource, session);
      }
      return dataSource;
    }
  }
}
