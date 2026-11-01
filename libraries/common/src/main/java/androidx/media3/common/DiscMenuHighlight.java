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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;

/**
 * Highlight information for the currently selected button of a DVD disc menu.
 *
 * <p>The raw values are produced by the disc navigation engine. Index {@link #INDEX_LEFT}, {@link
 * #INDEX_TOP}, {@link #INDEX_RIGHT} and {@link #INDEX_BOTTOM} describe the highlighted button
 * rectangle in video coordinates, while {@link #INDEX_VIDEO_WIDTH} and {@link #INDEX_VIDEO_HEIGHT}
 * describe the coordinate space the rectangle is expressed in.
 */
@UnstableApi
public final class DiscMenuHighlight {

  /** Index of the value describing the selected button. */
  public static final int INDEX_BUTTON = 0;

  /** Index of the left edge of the highlight rectangle. */
  public static final int INDEX_LEFT = 1;

  /** Index of the top edge of the highlight rectangle. */
  public static final int INDEX_TOP = 2;

  /** Index of the right edge of the highlight rectangle. */
  public static final int INDEX_RIGHT = 3;

  /** Index of the bottom edge of the highlight rectangle. */
  public static final int INDEX_BOTTOM = 4;

  /** Index of an engine specific value. */
  public static final int INDEX_RESERVED = 5;

  /** Index of the video width of the highlight coordinate space. */
  public static final int INDEX_VIDEO_WIDTH = 6;

  /** Index of the video height of the highlight coordinate space. */
  public static final int INDEX_VIDEO_HEIGHT = 7;

  /** The raw highlight values as produced by the disc navigation engine. */
  public final int[] values;

  public DiscMenuHighlight(int[] values) {
    this.values = Arrays.copyOf(values, values.length);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DiscMenuHighlight)) {
      return false;
    }
    return Arrays.equals(values, ((DiscMenuHighlight) o).values);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values);
  }
}
