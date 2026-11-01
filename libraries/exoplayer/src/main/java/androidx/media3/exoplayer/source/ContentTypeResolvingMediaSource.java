/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

/**
 * A {@link MediaSource.Factory} wrapper that resolves the content type of a {@link MediaItem}
 * before creating the underlying {@link MediaSource}. Items without an explicit MIME type are
 * classified from the URI; items that stay unclassified fall back to progressive playback.
 */
@UnstableApi
public final class ContentTypeResolvingMediaSource extends WrappingMediaSource {

  /** Factory for {@link ContentTypeResolvingMediaSource}. */
  public static final class Factory implements MediaSource.Factory {

    private final MediaSource.Factory delegateFactory;
    private final DataSource.Factory dataSourceFactory;

    /**
     * Creates a factory wrapping {@code delegateFactory}, using {@code dataSourceFactory} for any
     * content type probing that the resolution may need.
     */
    public Factory(MediaSource.Factory delegateFactory, DataSource.Factory dataSourceFactory) {
      this.delegateFactory = delegateFactory;
      this.dataSourceFactory = dataSourceFactory;
    }

    @Override
    public Factory setDrmSessionManagerProvider(
        @Nullable DrmSessionManagerProvider drmSessionManagerProvider) {
      delegateFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
      return this;
    }

    @Override
    public Factory setLoadErrorHandlingPolicy(
        @Nullable LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      delegateFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
      return this;
    }

    @Override
    public int[] getSupportedTypes() {
      return delegateFactory.getSupportedTypes();
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
      return delegateFactory.createMediaSource(resolveMediaItemContentType(mediaItem));
    }

    private MediaItem resolveMediaItemContentType(MediaItem mediaItem) {
      MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
      if (localConfiguration == null) {
        return mediaItem;
      }
      @Nullable String mimeType = localConfiguration.mimeType;
      if (mimeType != null && MimeTypes.getTrackType(mimeType) != C.TRACK_TYPE_UNKNOWN) {
        return mediaItem;
      }
      Uri uri = localConfiguration.uri;
      if (uri == null) {
        return mediaItem;
      }
      @C.ContentType int contentType = Util.inferContentType(uri);
      if (contentType == C.CONTENT_TYPE_OTHER) {
        return mediaItem;
      }
      String resolvedMimeType = getMimeTypeFromContentType(contentType);
      if (resolvedMimeType == null || resolvedMimeType.equals(mimeType)) {
        return mediaItem;
      }
      return mediaItem.buildUpon().setMimeType(resolvedMimeType).build();
    }

    @Nullable
    private static String getMimeTypeFromContentType(@C.ContentType int contentType) {
      switch (contentType) {
        case C.CONTENT_TYPE_HLS:
          return MimeTypes.APPLICATION_M3U8;
        case C.CONTENT_TYPE_DASH:
          return MimeTypes.APPLICATION_MPD;
        case C.CONTENT_TYPE_SS:
          return MimeTypes.APPLICATION_MP4;
        default:
          return null;
      }
    }
  }

  private ContentTypeResolvingMediaSource(MediaSource mediaSource) {
    super(mediaSource);
  }
}
