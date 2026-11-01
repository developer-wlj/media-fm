/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.iso.udf;

import androidx.media3.common.CacheDataReader;
import androidx.media3.extractor.iso.IsoFileEntry;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * A native (JNI backed) UDF file system reading ISO images through a {@link CacheDataReader}.
 *
 * <p>Instances must be {@link #close() closed} after use. All methods are safe to call from any
 * thread, but only one thread may use an instance at a time.
 */
public final class NativeUdfFileSystem implements Closeable {

  private static final boolean available;

  /** Thrown when the image cannot be handled by the native UDF file system. */
  public static final class UnsupportedImageException extends IOException {

    public UnsupportedImageException(String message) {
      super(message);
    }
  }

  static {
    boolean loaded;
    try {
      System.loadLibrary("isoJNI");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      loaded = false;
    }
    available = loaded;
  }

  private long handle;

  /** Returns whether the native UDF file system can be used on this device. */
  public static boolean isAvailable() {
    return available;
  }

  /**
   * Opens a UDF image.
   *
   * @param reader The reader providing the image data.
   * @throws IOException If the native library is unavailable or the image could not be opened.
   */
  public NativeUdfFileSystem(CacheDataReader reader) throws IOException {
    if (!available) {
      throw new UnsupportedImageException("ISO native library is unavailable");
    }
    long handle = nativeOpen(reader);
    if (handle == 0) {
      throw new IOException("Could not open UDF image");
    }
    this.handle = handle;
  }

  /**
   * Finds a file inside the image.
   *
   * @param path The case-insensitive path of the file, e.g. {@code BDMV/PLAYLIST/00001.mpls}.
   * @return The file entry, or {@code null} if the path does not address a recorded file.
   * @throws IOException If the image is closed.
   */
  public synchronized IsoFileEntry findFile(String path) throws IOException {
    long handle = this.handle;
    if (handle == 0) {
      throw new IOException("UDF image is closed");
    }
    return nativeFindFile(handle, path);
  }

  /**
   * Lists the files of a directory inside the image.
   *
   * @param dirPath The case-insensitive path of the directory, or {@code ""} for the root.
   * @return The names of the files inside the directory.
   * @throws IOException If the image is closed.
   */
  public synchronized List<String> listFiles(String dirPath) throws IOException {
    long handle = this.handle;
    if (handle == 0) {
      throw new IOException("UDF image is closed");
    }
    return Arrays.asList(nativeListFiles(handle, dirPath));
  }

  @Override
  public synchronized void close() {
    long handle = this.handle;
    if (handle != 0) {
      nativeClose(handle);
      this.handle = 0;
    }
  }

  private static native long nativeOpen(CacheDataReader reader);

  private static native IsoFileEntry nativeFindFile(long handle, String path);

  private static native String[] nativeListFiles(long handle, String dirPath);

  private static native void nativeClose(long handle);
}
