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

/** Native bindings for the libass subtitle renderer. */
final class LibassNative {

  private LibassNative() {}

  public static native void nativeAddFont(long libraryPtr, String name, byte[] data);

  public static native long nativeCreateLibrary(String fontConfig);

  public static native void nativeReleaseLibrary(long libraryPtr);

  public static native long nativeCreate(
      long libraryPtr, String fontsDirectory, String defaultFontFamily);

  public static native void nativeRelease(long trackPtr);

  public static native void nativeProcessCodecPrivate(long trackPtr, byte[] data, int offset, int length);

  public static native void nativeProcessData(long trackPtr, byte[] data, int offset, int length);

  public static native void nativeProcessTimedData(
      long trackPtr, byte[] data, int offset, int length, long timeMs);

  public static native void nativeProcessChunk(
      long trackPtr, byte[] data, int offset, int length, long startTimeMs, long endTimeMs);

  public static native LibassFrame nativeRenderFrame(
      long trackPtr, long positionMs, boolean allowPartial, boolean renderLast);

  public static native void nativeSetBottomPositionFraction(long trackPtr, float fraction);

  public static native void nativeSetFontScale(long trackPtr, float scale, boolean apply);

  public static native void nativeSetFonts(long trackPtr, String fontsDirectory, String fontFamily);

  public static native void nativeSetFrameSize(
      long trackPtr,
      int width,
      int height,
      int viewportLeft,
      int viewportTop,
      int viewportRight,
      int viewportBottom,
      double pixelAspectRatio);

  public static native void nativeSetStorageSize(long trackPtr, int width, int height);

  public static native void nativeSetStyleOverride(
      long trackPtr,
      boolean override,
      String fontFamily,
      float fontScale,
      int foregroundColor,
      int backgroundColor,
      int edgeType,
      int edgeColor,
      float edgeWidth,
      float shadowOffset);

  public static native void nativeSetVideoColorInfo(long trackPtr, int transfer, int matrix);

  public static native void nativeSetCacheLimits(long trackPtr, int maximumGlyphCount, int maximumBitmapCacheSizeMb);
}
