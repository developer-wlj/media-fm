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
package androidx.media3.mpvplayer;

/** The decoder preference for video tracks rendered by mpv. */
public enum MpvDecoderMode {

  /** Let mpv choose the decoder, preferring hardware decoding with software fallback. */
  AUTO,

  /** Use hardware decoding without software fallback. */
  HARDWARE,

  /** Decode through FFmpeg in software. */
  FFMPEG;

  /**
   * Converts this mode to the equivalent {@link androidx.media3.common.DecoderMode common mode}.
   */
  public androidx.media3.common.DecoderMode toCommonDecoderMode() {
    switch (this) {
      case AUTO:
        return androidx.media3.common.DecoderMode.AUTO;
      case HARDWARE:
        return androidx.media3.common.DecoderMode.HARDWARE;
      case FFMPEG:
      default:
        return androidx.media3.common.DecoderMode.FFMPEG;
    }
  }

  /**
   * Converts a {@link androidx.media3.common.DecoderMode common mode} to the equivalent mode for
   * mpv. mpv does not use platform software decoders, so {@code
   * androidx.media3.common.DecoderMode#SOFTWARE} is not supported.
   *
   * @param decoderMode The common decoder mode.
   * @return The equivalent decoder mode for mpv.
   * @throws IllegalArgumentException If the mode is {@code
   *     androidx.media3.common.DecoderMode#SOFTWARE}.
   */
  public static MpvDecoderMode fromCommonDecoderMode(
      androidx.media3.common.DecoderMode decoderMode) {
    switch (decoderMode) {
      case AUTO:
        return AUTO;
      case HARDWARE:
        return HARDWARE;
      case FFMPEG:
        return FFMPEG;
      case SOFTWARE:
      default:
        throw new IllegalArgumentException(
            "mpv does not use platform software decoders: " + decoderMode);
    }
  }
}
