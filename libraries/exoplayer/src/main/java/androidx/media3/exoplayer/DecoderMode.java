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
package androidx.media3.exoplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;

/** The decoder preference for a track type rendered by ExoPlayer. */
@UnstableApi
public enum DecoderMode {

  /** Let ExoPlayer choose the decoder. */
  AUTO,

  /** Use hardware accelerated platform decoders. */
  HARDWARE,

  /** Use software platform decoders. */
  SOFTWARE,

  /** Use FFmpeg based extension renderers. */
  FFMPEG;

  /**
   * Returns the decoder mode used by the given codec, or {@code null} when the codec cannot be
   * attributed to a single mode.
   *
   * @param mediaCodecInfo The platform codec information.
   * @return {@link #HARDWARE} for hardware accelerated codecs, {@link #SOFTWARE} for software only
   *     codecs, and {@code null} otherwise.
   */
  public static @Nullable DecoderMode of(MediaCodecInfo mediaCodecInfo) {
    if (mediaCodecInfo.hardwareAccelerated) {
      return HARDWARE;
    }
    if (mediaCodecInfo.softwareOnly) {
      return SOFTWARE;
    }
    return null;
  }

  /**
   * Converts this mode to the equivalent {@link androidx.media3.common.DecoderMode common mode}.
   */
  public androidx.media3.common.DecoderMode toCommonDecoderMode() {
    switch (this) {
      case AUTO:
        return androidx.media3.common.DecoderMode.AUTO;
      case HARDWARE:
        return androidx.media3.common.DecoderMode.HARDWARE;
      case SOFTWARE:
        return androidx.media3.common.DecoderMode.SOFTWARE;
      case FFMPEG:
      default:
        return androidx.media3.common.DecoderMode.FFMPEG;
    }
  }

  /**
   * Converts a {@link androidx.media3.common.DecoderMode common mode} to the equivalent mode for
   * ExoPlayer.
   *
   * @param decoderMode The common decoder mode.
   * @return The equivalent decoder mode for ExoPlayer.
   */
  public static DecoderMode fromCommonDecoderMode(
      androidx.media3.common.DecoderMode decoderMode) {
    switch (decoderMode) {
      case AUTO:
        return AUTO;
      case HARDWARE:
        return HARDWARE;
      case SOFTWARE:
        return SOFTWARE;
      case FFMPEG:
      default:
        return FFMPEG;
    }
  }
}
