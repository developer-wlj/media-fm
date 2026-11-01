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
package androidx.media3.mpvplayer.media;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LocalClearKeyLicense;
import androidx.media3.common.util.Util;
import java.util.Map;

/** Detects the DRM configurations that mpv is able to render. */
public final class MpvDrmSupport {

  private MpvDrmSupport() {}

  /**
   * Returns whether mpv can render the given media item.
   *
   * <p>Items without a DRM configuration are always supported. DRM protected items are supported
   * for DASH, HLS and other streaming formats when they carry a local ClearKey license, either as
   * an inline JSON web key set or as an HTTP(S) license URI.
   *
   * @param mediaItem The media item to inspect.
   * @return Whether mpv can render the media item.
   */
  public static boolean supports(MediaItem mediaItem) {
    @Nullable MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
    @Nullable MediaItem.DrmConfiguration drmConfiguration =
        localConfiguration == null ? null : localConfiguration.drmConfiguration;
    if (drmConfiguration == null || drmConfiguration.scheme == null) {
      return true;
    }
    int contentType = inferContentType(localConfiguration.uri, localConfiguration.mimeType);
    if (contentType != C.CONTENT_TYPE_DASH
        && contentType != C.CONTENT_TYPE_HLS
        && contentType != C.CONTENT_TYPE_OTHER) {
      return false;
    }
    Map<String, String> localLicense = LocalClearKeyLicense.parse(drmConfiguration);
    return !localLicense.isEmpty() || isHttpLicense(drmConfiguration);
  }

  private static boolean isHttpLicense(MediaItem.DrmConfiguration drmConfiguration) {
    Uri licenseUri = drmConfiguration.licenseUri;
    if (licenseUri == null || drmConfiguration.getKeySetId() != null) {
      return false;
    }
    String scheme = licenseUri.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  private static int inferContentType(Uri uri, @Nullable String mimeType) {
    if (mimeType == null) {
      return Util.inferContentType(uri);
    }
    switch (mimeType) {
      case MimeTypes.APPLICATION_M3U8:
        return C.CONTENT_TYPE_HLS;
      case MimeTypes.APPLICATION_SS:
        return C.CONTENT_TYPE_SS;
      case MimeTypes.APPLICATION_MPD:
        return C.CONTENT_TYPE_DASH;
      case MimeTypes.APPLICATION_RTSP:
        return C.CONTENT_TYPE_RTSP;
      default:
        return C.CONTENT_TYPE_OTHER;
    }
  }
}
