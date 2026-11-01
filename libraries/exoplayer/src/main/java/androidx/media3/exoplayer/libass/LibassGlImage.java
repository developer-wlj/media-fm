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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** A GPU backed image of a rendered {@link LibassFrame}. */
@UnstableApi
final class LibassGlImage {

  public final int textureId;
  public final int x;
  public final int y;
  public final int width;
  public final int height;
  public final float red;
  public final float green;
  public final float blue;
  public final float alpha;
  public final FloatBuffer vertexBuffer;

  public LibassGlImage(int textureId, LibassImage image) {
    this.textureId = textureId;
    this.x = image.x;
    this.y = image.y;
    this.width = image.width;
    this.height = image.height;
    int color = image.color;
    this.red = ((color >>> 24) & 255) / 255.0f;
    this.green = ((color >>> 16) & 255) / 255.0f;
    this.blue = ((color >>> 8) & 255) / 255.0f;
    this.alpha = 1.0f - ((color & 255) / 255.0f);
    this.vertexBuffer =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
    this.vertexBuffer.put(new float[8]).position(0);
  }
}
