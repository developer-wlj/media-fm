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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * A pan and scale transform applied to the video output, usable by both hardware and software
 * player backends.
 */
@UnstableApi
public final class VideoTransform {

  /** The maximum scale factor accepted for {@link #scaleX} and {@link #scaleY}. */
  public static final float MAX_SCALE = 5.0f;

  /** A transform that leaves the video unchanged. */
  public static final VideoTransform IDENTITY = new VideoTransform(1.0f, 1.0f, 0.0f, 0.0f);

  /** The default transform, equivalent to {@link #IDENTITY}. */
  public static final VideoTransform DEFAULT = IDENTITY;

  /** The horizontal scale factor, where 1.0 leaves the width unchanged. */
  public final float scaleX;
  /** The vertical scale factor, where 1.0 leaves the height unchanged. */
  public final float scaleY;
  /** The horizontal pan, in the -1..1 range where -1 is fully left. */
  public final float panX;
  /** The vertical pan, in the -1..1 range where -1 is fully up. */
  public final float panY;

  /**
   * Creates a transform.
   *
   * @param scaleX The horizontal scale factor.
   * @param scaleY The vertical scale factor.
   * @param panX The horizontal pan, in the -1..1 range.
   * @param panY The vertical pan, in the -1..1 range.
   */
  public VideoTransform(float scaleX, float scaleY, float panX, float panY) {
    this.scaleX = clampScale(scaleX);
    this.scaleY = clampScale(scaleY);
    this.panX = clampPan(panX);
    this.panY = clampPan(panY);
  }

  private static float clampScale(float scale) {
    if (scale <= 0.0f) {
      return 1.0f;
    }
    return Math.min(MAX_SCALE, scale);
  }

  private static float clampPan(float pan) {
    return Math.max(-1.0f, Math.min(1.0f, pan));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VideoTransform)) {
      return false;
    }
    VideoTransform that = (VideoTransform) o;
    return Float.floatToIntBits(scaleX) == Float.floatToIntBits(that.scaleX)
        && Float.floatToIntBits(scaleY) == Float.floatToIntBits(that.scaleY)
        && Float.floatToIntBits(panX) == Float.floatToIntBits(that.panX)
        && Float.floatToIntBits(panY) == Float.floatToIntBits(that.panY);
  }

  @Override
  public int hashCode() {
    int result = Float.floatToIntBits(scaleX);
    result = 31 * result + Float.floatToIntBits(scaleY);
    result = 31 * result + Float.floatToIntBits(panX);
    result = 31 * result + Float.floatToIntBits(panY);
    return result;
  }
}
