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
package androidx.media3.exoplayer.iso;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import java.util.Locale;

/** Utilities for detecting optical disc images that need dedicated preparation. */
public final class IsoDiscPreparation {

  /** The MIME type used to mark ISO image media items. */
  private static final String ISO_MIME_TYPE = "video/x-iso";

  private IsoDiscPreparation() {}

  /**
   * Returns whether the media item addresses an optical disc image.
   *
   * @param mediaItem The media item to inspect.
   * @return Whether the media item is backed by an ISO image.
   */
  public static boolean isIso(MediaItem mediaItem) {
    MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
    if (localConfiguration == null) {
      return false;
    }
    if (ISO_MIME_TYPE.equals(localConfiguration.mimeType)) {
      return true;
    }
    Uri uri = localConfiguration.uri;
    String path = uri.getPath();
    return path != null && path.toLowerCase(Locale.US).endsWith(".iso");
  }
}
