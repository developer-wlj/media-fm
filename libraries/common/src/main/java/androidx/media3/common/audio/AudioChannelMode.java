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
package androidx.media3.common.audio;

import androidx.media3.common.util.UnstableApi;

/** Channel mode requested by the user for audio effects, resolved against the input channels. */
@UnstableApi
public final class AudioChannelMode {

  /** Leave the channel layout as-is; only balance-style effects may still apply. */
  public static final int AUTO = 0;
  /** Downmix the front stereo pair, keeping the other channels at reduced power. */
  public static final int STEREO = 1;
  /** Downmix everything into a single mono channel. */
  public static final int MONO = 2;
  /** Swap the left and right front channels. */
  public static final int REVERSE = 3;

  private static final int MIN_MODE = AUTO;
  private static final int MAX_MODE = REVERSE;

  private AudioChannelMode() {}

  /** Clamps a persisted mode value into the {@link #AUTO}..{@link #REVERSE} range. */
  public static int clamp(int mode) {
    return Math.max(MIN_MODE, Math.min(MAX_MODE, mode));
  }

  /**
   * Resolves a persisted mode against the channel count of the audio being played. The resolved
   * value is always one of the four mode constants and can be fed back to {@link #isAvailable}
   * and {@link #canBalance}.
   */
  public static int resolve(int mode, int channelCount) {
    return clamp(mode);
  }

  /**
   * Returns whether channel mixing effects can apply to audio with the given channel count.
   *
   * @param channelCount The number of channels of the audio being played.
   */
  public static boolean isAvailable(int channelCount) {
    return channelCount >= 2 && channelCount <= 8;
  }

  /** Returns whether the mode can produce an audible change for the given channel count. */
  public static boolean isAvailable(int mode, int channelCount) {
    if (channelCount < 2) {
      return mode == MONO && channelCount >= 1;
    }
    return true;
  }

  /** Returns whether left/right balance can apply for the mode. */
  public static boolean canBalance(int mode) {
    return mode != MONO;
  }

  /** Returns whether the mode only applies balance-style effects and no forced downmix. */
  public static boolean isAuto(int mode) {
    return mode == AUTO;
  }
}
