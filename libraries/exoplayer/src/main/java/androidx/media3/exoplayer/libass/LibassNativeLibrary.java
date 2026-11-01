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
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A native libass library instance. Fonts can be added until it is closed. */
@UnstableApi
final class LibassNativeLibrary implements AutoCloseable {

  private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
  private static final int MAX_TOTAL_FONT_BYTES = 128 * 1024 * 1024;
  private static final Object releaseLock = new Object();
  @Nullable private static ExecutorService releaseExecutor;

  public long nativeLibraryPtr;
  public long totalFontBytes;

  /** Creates the native library with the given fontconfig configuration path. */
  public LibassNativeLibrary(@Nullable String fontConfig) {
    Assertions.checkState(LibassLibrary.isAvailable(), "libmedia3ass is unavailable");
    long nativeLibraryPtr = LibassNative.nativeCreateLibrary(fontConfig);
    Assertions.checkState(nativeLibraryPtr != 0, "Failed to create libass library");
    this.nativeLibraryPtr = nativeLibraryPtr;
  }

  /** Adds an embedded font to the library. */
  public synchronized void addFont(String name, byte[] data) {
    Assertions.checkState(nativeLibraryPtr != 0, "LibassNativeLibrary is closed");
    Assertions.checkArgument(!name.isEmpty(), "Font name must not be empty");
    Assertions.checkArgument(data.length <= MAX_FONT_BYTES, "Embedded font exceeds the per-font limit");
    Assertions.checkArgument(
        totalFontBytes + data.length <= MAX_TOTAL_FONT_BYTES,
        "Embedded fonts exceed the per-media limit");
    if (data.length == 0) {
      return;
    }
    LibassNative.nativeAddFont(nativeLibraryPtr, name, data);
    totalFontBytes += data.length;
  }

  @Override
  public synchronized void close() {
    long nativeLibraryPtr = this.nativeLibraryPtr;
    if (nativeLibraryPtr == 0) {
      return;
    }
    LibassNative.nativeReleaseLibrary(nativeLibraryPtr);
    this.nativeLibraryPtr = 0;
    this.totalFontBytes = 0;
  }

  /** Runs the given release action on a background thread. */
  public static void releaseInBackground(Runnable releaseRunnable) {
    synchronized (releaseLock) {
      if (releaseExecutor == null) {
        releaseExecutor = Executors.newSingleThreadExecutor();
      }
      releaseExecutor.execute(releaseRunnable);
    }
  }
}
