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
package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/** A single rendered image of a {@link LibassFrame}. */
@UnstableApi
public final class LibassImage {

  /** The x offset of the image, in pixels. */
  public final int x;
  /** The y offset of the image, in pixels. */
  public final int y;
  /** The width of the image, in pixels. */
  public final int width;
  /** The height of the image, in pixels. */
  public final int height;
  /** The color of the image in ARGB format, where the alpha channel holds the coverage. */
  public final int color;
  /** The alpha bitmap of the image, one byte per pixel. */
  @Nullable public final byte[] bitmap;

  /* package */ int textureId;

  /**
   * Creates an image.
   *
   * @param x The x offset of the image, in pixels.
   * @param y The y offset of the image, in pixels.
   * @param width The width of the image, in pixels.
   * @param height The height of the image, in pixels.
   * @param color The color of the image in ARGB format.
   * @param bitmap The alpha bitmap of the image, or null if the image already has a texture.
   * @param textureId The OpenGL texture id of the image, or 0.
   */
  public LibassImage(int x, int y, int width, int height, int color, @Nullable byte[] bitmap, int textureId) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.color = color;
    this.bitmap = bitmap;
    this.textureId = textureId;
  }
}
