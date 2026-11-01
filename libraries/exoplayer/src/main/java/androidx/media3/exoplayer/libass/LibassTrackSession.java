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
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;

/** A native libass track that renders the events of a single subtitle track. */
@UnstableApi
final class LibassTrackSession implements AutoCloseable {

  private static final int MAX_PROCESSED_SAMPLES = 2048;

  public final String trackKey;
  @Nullable public String fontFamily;
  public long trackPtr;
  public boolean frameSizeSet;

  private final MessageDigest messageDigest;
  private final HashSet<ProcessedSample> processedSamples;

  /** Creates a track session in the given library. */
  public LibassTrackSession(
      LibassNativeLibrary library,
      String fontsDirectory,
      String trackKey,
      @Nullable String fontFamily) {
    this.trackKey = trackKey;
    this.fontFamily = fontFamily;
    try {
      this.messageDigest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
    long libraryPtr;
    synchronized (library) {
      Assertions.checkState(library.nativeLibraryPtr != 0, "LibassNativeLibrary is closed");
      libraryPtr = library.nativeLibraryPtr;
    }
    long trackPtr = LibassNative.nativeCreate(libraryPtr, fontsDirectory, fontFamily);
    Assertions.checkState(trackPtr != 0, "Failed to create libass track");
    this.trackPtr = trackPtr;
    this.processedSamples = new HashSet<>();
  }

  private static void checkDataRange(int offset, int length, byte[] data) {
    Assertions.checkArgument(offset >= 0 && length >= 0 && offset <= data.length - length);
  }

  private void checkOpen() {
    Assertions.checkState(trackPtr != 0, "LibassTrackSession is closed");
  }

  /** Processes initialization data, for example the script header of an ASS track. */
  public synchronized void processCodecPrivate(byte[] data, int offset, int length) {
    checkOpen();
    checkDataRange(offset, length, data);
    LibassNative.nativeProcessCodecPrivate(trackPtr, data, offset, length);
  }

  /** Processes subtitle data whose timings are part of the data itself. */
  public synchronized void processData(byte[] data, int offset, int length) {
    checkOpen();
    checkDataRange(offset, length, data);
    LibassNative.nativeProcessData(trackPtr, data, offset, length);
  }

  /** Processes subtitle data that belongs to the sample starting at {@code timeMs}. */
  public synchronized void processTimedData(byte[] data, int offset, int length, long timeMs) {
    checkOpen();
    checkDataRange(offset, length, data);
    ProcessedSample sample = new ProcessedSample(data, offset, length, timeMs, messageDigest);
    if (processedSamples.contains(sample)) {
      return;
    }
    LibassNative.nativeProcessTimedData(trackPtr, data, offset, length, timeMs);
    processedSamples.add(sample);
    if (processedSamples.size() > MAX_PROCESSED_SAMPLES) {
      processedSamples.clear();
    }
  }

  /** Processes a chunk of subtitle data covering the given time range. */
  public synchronized void processChunk(
      byte[] data, int offset, int length, long startTimeUs, long endTimeUs) {
    checkOpen();
    checkDataRange(offset, length, data);
    Assertions.checkArgument(endTimeUs >= 0, "Duration must be non-negative");
    LibassNative.nativeProcessChunk(
        trackPtr, data, offset, length, C.usToMs(startTimeUs), C.usToMs(endTimeUs));
  }

  /** Sets the fraction of the frame height the subtitles are anchored to. */
  public synchronized void setBottomPositionFraction(float fraction) {
    checkOpen();
    Assertions.checkArgument(Float.isFinite(fraction), "Bottom position must be finite");
    LibassNative.nativeSetBottomPositionFraction(trackPtr, fraction);
  }

  /** Sets the font scale and whether the scale is applied to user styles. */
  public synchronized void setFontScale(boolean applyScale, float scale) {
    checkOpen();
    Assertions.checkArgument(
        Float.isFinite(scale) && scale > 0.0f, "Font scale must be finite and positive");
    LibassNative.nativeSetFontScale(trackPtr, scale, applyScale);
  }

  /** Sets the style override, or clears it when {@code styleOverride} is null. */
  public synchronized void setStyleOverride(
      @Nullable LibassStyleOverride styleOverride, @Nullable String fontFamily) {
    checkOpen();
    long trackPtr = this.trackPtr;
    if (styleOverride == null) {
      LibassNative.nativeSetStyleOverride(
          trackPtr,
          /* override= */ false,
          fontFamily,
          /* fontScale= */ 0.0f,
          /* foregroundColor= */ 0,
          /* backgroundColor= */ 0,
          /* edgeType= */ 0,
          /* edgeColor= */ 0,
          /* edgeWidth= */ 0.0f,
          /* shadowOffset= */ 0.0f);
      return;
    }
    String effectiveFontFamily = fontFamily == null ? styleOverride.fontFamily : fontFamily;
    LibassNative.nativeSetStyleOverride(
        trackPtr,
        /* override= */ true,
        effectiveFontFamily,
        LibassStyleOverride.DEFAULT_FONT_SCALE,
        styleOverride.foregroundColor,
        styleOverride.backgroundColor,
        styleOverride.edgeType,
        styleOverride.edgeColor,
        styleOverride.edgeWidth,
        styleOverride.shadowOffset);
  }

  /** Sets the fonts used by the track. */
  public synchronized void setFonts(String fontsDirectory, String fontFamily) {
    checkOpen();
    if (java.util.Objects.equals(this.fontFamily, fontFamily)) {
      return;
    }
    this.fontFamily = fontFamily;
    LibassNative.nativeSetFonts(trackPtr, fontsDirectory, fontFamily);
  }

  /** Sets the frame size and video viewport used for rendering. */
  public synchronized void setFrameSize(
      int width,
      int height,
      int viewportLeft,
      int viewportTop,
      int viewportRight,
      int viewportBottom,
      double pixelAspectRatio) {
    checkOpen();
    Assertions.checkState(width > 0 && height > 0, "Frame size must be positive");
    Assertions.checkState(
        viewportRight > viewportLeft && viewportBottom > viewportTop,
        "Video viewport must not be empty");
    Assertions.checkState(
        Double.isFinite(pixelAspectRatio) && pixelAspectRatio >= 0.0,
        "Pixel aspect ratio must be finite and non-negative");
    LibassNative.nativeSetFrameSize(
        trackPtr, width, height, viewportLeft, viewportTop, viewportRight, viewportBottom, pixelAspectRatio);
    frameSizeSet = true;
  }

  /** Sets the storage size of the frame buffers. */
  public synchronized void setStorageSize(int width, int height) {
    checkOpen();
    Assertions.checkState(width > 0 && height > 0, "Storage size must be positive");
    LibassNative.nativeSetStorageSize(trackPtr, width, height);
  }

  /** Sets the cache limits of the renderer. */
  public synchronized void setCacheLimits(int maximumGlyphCount, int maximumBitmapCacheSizeMb) {
    checkOpen();
    Assertions.checkArgument(maximumGlyphCount >= 0 && maximumBitmapCacheSizeMb >= 0,
        "Cache limits must be non-negative");
    LibassNative.nativeSetCacheLimits(trackPtr, maximumGlyphCount, maximumBitmapCacheSizeMb);
  }

  /** Sets the video color information used for tone mapping, or null to unset it. */
  public synchronized void setVideoColorInfo(int transfer, int matrix) {
    checkOpen();
    LibassNative.nativeSetVideoColorInfo(trackPtr, transfer, matrix);
  }

  /** Renders the frame at the given position, in milliseconds. */
  public synchronized LibassFrame renderFrame(long positionMs, boolean allowPartial) {
    checkOpen();
    Assertions.checkState(frameSizeSet, "Frame size must be set before rendering");
    return LibassNative.nativeRenderFrame(trackPtr, positionMs, allowPartial, /* renderLast= */ true);
  }

  @Override
  public synchronized void close() {
    long trackPtr = this.trackPtr;
    if (trackPtr == 0) {
      return;
    }
    LibassNative.nativeRelease(trackPtr);
    this.trackPtr = 0;
    this.frameSizeSet = false;
    this.processedSamples.clear();
  }

  private static final class ProcessedSample {

    public final byte[] digest;
    public final int length;
    public final long timeMs;

    public ProcessedSample(
        byte[] data, int offset, int length, long timeMs, MessageDigest messageDigest) {
      messageDigest.reset();
      messageDigest.update(data, offset, length);
      this.digest = messageDigest.digest();
      this.length = length;
      this.timeMs = timeMs;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof ProcessedSample)) {
        return false;
      }
      ProcessedSample that = (ProcessedSample) o;
      return timeMs == that.timeMs && length == that.length && java.util.Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(digest) + ((Long.hashCode(timeMs) * 31 + length) * 31);
    }
  }
}
