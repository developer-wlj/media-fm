/* Copyright (C) 2024 The Android Open Source Project

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package androidx.media3.effect;

import android.view.Surface;
import androidx.media3.common.util.Assertions;

/* package */ final class NativeWindow {

  private static final boolean LIBRARY_AVAILABLE;

  static {
    boolean loaded;
    try {
      System.loadLibrary("media3effect");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      loaded = false;
    }
    LIBRARY_AVAILABLE = loaded;
  }

  private NativeWindow() {}

  /**
   * Sets the buffers geometry of the given surface's native window.
   *
   * @param surface The output surface.
   * @param width The desired buffer width, or 0 to keep the current width.
   * @param height The desired buffer height, or 0 to keep the current height.
   */
  public static void setBuffersGeometry(Surface surface, int width, int height) {
    Assertions.checkState(LIBRARY_AVAILABLE, "Failed to load the effect native library");
    int result = nativeSetBuffersGeometry(surface, width, height);
    Assertions.checkState(result == 0, "Failed to configure output buffers: " + result);
  }

  private static native int nativeSetBuffersGeometry(Surface surface, int width, int height);
}
