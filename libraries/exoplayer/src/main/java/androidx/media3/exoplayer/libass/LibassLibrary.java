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

import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;

/** Configures and queries the underlying libass native library. */
final class LibassLibrary {

  private static final LibraryLoader LOADER =
      new LibraryLoader("media3ass") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  static {
    MediaLibraryInfo.registerModule("media3.exoplayer.libass");
  }

  /** Returns whether the underlying native library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  private LibassLibrary() {}
}
