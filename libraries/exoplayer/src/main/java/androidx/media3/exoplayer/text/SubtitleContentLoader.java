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
package androidx.media3.exoplayer.text;

import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;

/**
 * Loads the decoded content of a subtitle track.
 *
 * <p>Implementations are expected to fetch and decode the subtitle file referenced by the track's
 * {@link Format} so that its content can be shown without waiting for the corresponding cues to
 * appear on the playback timeline.
 */
@UnstableApi
public interface SubtitleContentLoader {

  /**
   * Loads the subtitle content for the given uri.
   *
   * @param mediaItem The media item the subtitle track belongs to.
   * @param uri The uri of the subtitle file.
   * @param format The format of the subtitle track.
   * @return The decoded {@link SubtitleContent}, or {@link SubtitleContent#UNSUPPORTED} if the uri
   *     cannot be handled.
   * @throws IOException If the subtitle file could not be read.
   */
  SubtitleContent load(MediaItem mediaItem, Uri uri, Format format) throws IOException;
}
