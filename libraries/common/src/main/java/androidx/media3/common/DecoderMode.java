/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * The decoder preference for a track type, regardless of the playback engine that renders it.
 *
 * <p>The mode is used to configure engines like {@code ExoPlayer} and mpv with a common vocabulary:
 * automatic selection, platform hardware decoding, platform software decoding, or decoding through
 * an FFmpeg based extension renderer.
 */
@UnstableApi
public enum DecoderMode {
  /** Let the engine choose the decoder. */
  AUTO,

  /** Use hardware accelerated platform decoders. */
  HARDWARE,

  /** Use software platform decoders. */
  SOFTWARE,

  /** Use FFmpeg based decoders. */
  FFMPEG
}
