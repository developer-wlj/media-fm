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
import com.google.common.collect.ImmutableList;

/** A rendered frame of the libass subtitle renderer, containing one or more images. */
@UnstableApi
public final class LibassFrame {

  /** Whether the frame differs from the previously rendered frame. */
  public final boolean changed;

  /** The images of the frame. */
  public final ImmutableList<LibassImage> images;

  /**
   * Creates a frame.
   *
   * @param changed Whether the frame differs from the previously rendered frame.
   * @param images The images of the frame, or null for an empty frame.
   */
  public LibassFrame(int changed, @Nullable LibassImage[] images) {
    this.changed = changed != 0;
    this.images =
        images == null ? ImmutableList.of() : ImmutableList.copyOf(images);
  }

  /** Returns whether the frame contains any images. */
  public boolean isEmpty() {
    return images.isEmpty();
  }
}
