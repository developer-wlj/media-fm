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
package androidx.media3.exoplayer.hls;

/** JNI bindings for the CMG decryption runtime. */
final class CmgNativeRuntime {

  private static final int NATIVE_API_VERSION = 2;

  private static boolean available;
  private static boolean loadAttempted;

  private CmgNativeRuntime() {}

  /** Returns whether the native CMG runtime can be used on this device. */
  public static synchronized boolean isAvailable() {
    if (!loadAttempted) {
      boolean loaded;
      loadAttempted = true;
      try {
        System.loadLibrary("cmg_decrypt");
        loaded = nativeApiVersion() == NATIVE_API_VERSION;
      } catch (LinkageError | SecurityException e) {
        loaded = false;
      }
      available = loaded;
    }
    return available;
  }

  public static native synchronized boolean configure(String configuration);

  public static native synchronized void configureLocation(String location);

  public static native synchronized byte[] decodeStateNal(byte[] data);

  public static native synchronized int decodeVideoNalInPlace(
      byte[] data, int offset, int length);

  public static native synchronized boolean initialize();

  private static native int nativeApiVersion();

  public static native synchronized void reset();

  public static native synchronized void setClock(long timeMs);

  public static native synchronized void setPlayerTag(String tag);

  public static native synchronized boolean updateSession();
}
