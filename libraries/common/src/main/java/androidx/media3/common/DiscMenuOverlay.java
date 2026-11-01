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
import java.util.Arrays;

/**
 * A bitmap frame of an interactive Blu-ray (HDMV) disc menu overlay.
 *
 * <p>The overlay {@link #pixels} are 8888 ARGB values describing a {@link #width} by {@link
 * #height} image to draw on top of the video surface.
 */
@UnstableApi
public final class DiscMenuOverlay {

  /** Identifier of the overlay plane this frame belongs to. */
  public final int id;

  /** Width of the overlay in pixels. */
  public final int width;

  /** Height of the overlay in pixels. */
  public final int height;

  /** 8888 ARGB pixels of the overlay, or an empty array when the plane is fully transparent. */
  public final int[] pixels;

  public DiscMenuOverlay(int id, int width, int height, int[] pixels) {
    this.id = id;
    this.width = width;
    this.height = height;
    this.pixels = pixels != null ? Arrays.copyOf(pixels, pixels.length) : new int[0];
  }
}
