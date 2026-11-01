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

import android.graphics.Rect;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.text.SubtitleTranscript;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The libass state of a single media item. Tracks, embedded fonts and render options are kept per
 * media item so that preloaded media items do not interfere with the currently playing one.
 */
@UnstableApi
final class LibassMediaSession implements AutoCloseable {

  private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
  private static final int MAX_TOTAL_FONT_BYTES = 128 * 1024 * 1024;

  public final LibassConfiguration configuration;
  public final String fontsDirectory;
  public final boolean libassEnabled;

  public final HashMap<String, LibassTrackSession> trackSessions;
  public final HashSet<String> failedTrackKeys;
  public final ArrayList<EmbeddedFont> embeddedFonts;
  public final AtomicBoolean closed;

  @Nullable public SubtitleTranscript transcript;

  @Nullable public Format primaryFormat;
  @Nullable public Format secondaryFormat;
  @Nullable public LibassNativeLibrary nativeLibrary;

  public int frameWidth;
  public int frameHeight;
  public int storageWidth;
  public int storageHeight;
  public final Rect viewport = new Rect();
  public double pixelAspectRatio = 1.0;
  public int colorSpace = -1;
  public int colorTransfer = -1;

  public float bottomPositionFraction = 1.0f;
  public float secondaryBottomPositionFraction = 1.0f;
  public float fontScale = 1.0f;
  public boolean applyFontScale;
  @Nullable public String defaultFontFamily;
  public long secondaryOffsetUs;
  public long totalFontBytes;

  /** Creates a media session. */
  public LibassMediaSession(LibassConfiguration configuration, boolean libassEnabled) {
    this.configuration = configuration;
    this.fontsDirectory = configuration.fontsDirectory == null ? "" : configuration.fontsDirectory;
    this.libassEnabled = libassEnabled;
    this.trackSessions = new HashMap<>();
    this.failedTrackKeys = new HashSet<>();
    this.embeddedFonts = new ArrayList<>();
    this.closed = new AtomicBoolean();
    this.defaultFontFamily = configuration.defaultFontFamily;
  }

  /** Sets the transcript used to index the subtitle content of the media item. */
  public void setTranscript(@Nullable SubtitleTranscript transcript) {
    this.transcript = transcript;
  }

  /** Ensures a track session for the format's track key exists, creating it if necessary. */
  @Nullable
  public LibassTrackSession getOrCreateTrackSession(Format format) {
    if (!libassEnabled || closed.get()) {
      return null;
    }
    String trackKey = SubtitleTranscript.getFormatTrackId(format);
    LibassTrackSession existing = trackSessions.get(trackKey);
    if (existing != null) {
      return existing;
    }
    if (failedTrackKeys.contains(trackKey)) {
      return null;
    }
    LibassTrackSession trackSession;
    LibassNativeLibrary library = nativeLibrary;
    boolean createdLibrary = false;
    try {
      if (library == null) {
        library = new LibassNativeLibrary(configuration.fontConfig);
        createdLibrary = true;
        for (int i = 0; i < embeddedFonts.size(); i++) {
          EmbeddedFont font = embeddedFonts.get(i);
          library.addFont(font.name, font.data);
        }
      }
      trackSession = new LibassTrackSession(library, fontsDirectory, trackKey, defaultFontFamily);
    } catch (RuntimeException | Error e) {
      if (createdLibrary && library != null) {
        library.close();
      }
      failedTrackKeys.add(trackKey);
      return null;
    }
    if (createdLibrary) {
      nativeLibrary = library;
    }
    trackSession.setVideoColorInfo(colorTransfer, colorSpace);
    trackSession.setFontScale(applyFontScale, fontScale);
    trackSession.setBottomPositionFraction(getBottomPositionFractionForTrack(trackKey));
    trackSession.setCacheLimits(
        configuration.maximumGlyphCount, configuration.maximumBitmapCacheSizeMb);
    if (frameWidth != 0) {
      applyFrameSize(trackSession);
    }
    @Nullable List<byte[]> initializationData = format.initializationData;
    if (initializationData != null && initializationData.size() == 1) {
      byte[] codecPrivate = initializationData.get(0);
      synchronized (trackSession) {
        trackSession.processCodecPrivate(codecPrivate, /* offset= */ 0, codecPrivate.length);
      }
    }
    trackSessions.put(trackKey, trackSession);
    return trackSession;
  }

  /** Adds an embedded font, which is picked up by track sessions created afterwards. */
  public void addEmbeddedFont(String name, byte[] data) {
    if (!libassEnabled
        || closed.get()
        || data.length == 0
        || data.length > MAX_FONT_BYTES
        || totalFontBytes + data.length > MAX_TOTAL_FONT_BYTES) {
      return;
    }
    for (int i = 0; i < embeddedFonts.size(); i++) {
      if (embeddedFonts.get(i).matches(name, data)) {
        return;
      }
    }
    embeddedFonts.add(new EmbeddedFont(name, data));
    totalFontBytes += data.length;
  }

  /** Sets the primary subtitle format rendered by this session. */
  public boolean setPrimaryFormat(@Nullable Format primaryFormat) {
    @Nullable Format effectiveSecondaryFormat =
        secondaryFormat != null && isSameTrack(primaryFormat, secondaryFormat)
            ? null
            : secondaryFormat;
    if (Objects.equals(this.primaryFormat, primaryFormat)
        && Objects.equals(this.secondaryFormat, effectiveSecondaryFormat)) {
      return false;
    }
    this.primaryFormat = primaryFormat;
    this.secondaryFormat = effectiveSecondaryFormat;
    refreshTrackBottomPositionFractions();
    return true;
  }

  /** Sets the secondary subtitle format rendered by this session. */
  public boolean setSecondaryFormat(@Nullable Format secondaryFormat) {
    @Nullable Format effectiveSecondaryFormat =
        secondaryFormat != null && isSameTrack(primaryFormat, secondaryFormat)
            ? null
            : secondaryFormat;
    if (Objects.equals(this.secondaryFormat, effectiveSecondaryFormat)) {
      return false;
    }
    this.secondaryFormat = effectiveSecondaryFormat;
    refreshTrackBottomPositionFractions();
    return true;
  }

  private boolean isSameTrack(@Nullable Format first, @Nullable Format second) {
    if (first == null || second == null) {
      return false;
    }
    if (first.equals(second)) {
      return true;
    }
    String firstTrackKey = resolveTrackKey(first);
    String secondTrackKey = resolveTrackKey(second);
    return firstTrackKey != null && firstTrackKey.equals(secondTrackKey);
  }

  @Nullable
  private String resolveTrackKey(Format format) {
    return SubtitleTranscript.resolveTrackKey(format, trackSessions.keySet());
  }

  private float getBottomPositionFractionForTrack(String trackKey) {
    @Nullable String secondaryTrackKey =
        secondaryFormat == null ? null : resolveTrackKey(secondaryFormat);
    return trackKey.equals(secondaryTrackKey)
        ? secondaryBottomPositionFraction
        : bottomPositionFraction;
  }

  private void refreshTrackBottomPositionFractions() {
    for (Map.Entry<String, LibassTrackSession> entry : trackSessions.entrySet()) {
      entry.getValue().setBottomPositionFraction(getBottomPositionFractionForTrack(entry.getKey()));
    }
  }

  /** Sets the fraction of the frame height the primary subtitles are anchored to. */
  public synchronized void setBottomPositionFraction(float fraction) {
    Assertions.checkArgument(Float.isFinite(fraction), "Bottom position must be finite");
    if (this.bottomPositionFraction == fraction) {
      return;
    }
    this.bottomPositionFraction = fraction;
    refreshTrackBottomPositionFractions();
  }

  /** Sets the fraction of the frame height the secondary subtitles are anchored to. */
  public synchronized void setSecondaryBottomPositionFraction(float fraction) {
    Assertions.checkArgument(Float.isFinite(fraction), "Secondary bottom position must be finite");
    if (this.secondaryBottomPositionFraction == fraction) {
      return;
    }
    this.secondaryBottomPositionFraction = fraction;
    refreshTrackBottomPositionFractions();
  }

  /** Sets the font scale and whether it is applied on top of the styles of the subtitles. */
  public synchronized void setFontScale(boolean applyScale, float scale) {
    Assertions.checkArgument(
        Float.isFinite(scale) && scale > 0.0f, "Font scale must be finite and positive");
    if (this.fontScale == scale && this.applyFontScale == applyScale) {
      return;
    }
    this.fontScale = scale;
    this.applyFontScale = applyScale;
    for (LibassTrackSession trackSession : trackSessions.values()) {
      trackSession.setFontScale(applyScale, scale);
    }
  }

  /** Sets the default font family, or null to use the configured default. */
  public synchronized void setDefaultFontFamily(@Nullable String fontFamily) {
    if (Objects.equals(this.defaultFontFamily, fontFamily)) {
      return;
    }
    this.defaultFontFamily = fontFamily;
    for (LibassTrackSession trackSession : trackSessions.values()) {
      trackSession.setFonts(fontsDirectory, fontFamily);
    }
  }

  /** Sets the style override, or clears it when null. */
  public synchronized void setStyleOverride(
      @Nullable LibassStyleOverride styleOverride, @Nullable String fontFamily) {
    for (LibassTrackSession trackSession : trackSessions.values()) {
      trackSession.setStyleOverride(styleOverride, fontFamily);
    }
  }

  /** Sets the frame and storage sizes together with the video viewport. */
  public synchronized void setFrameSize(
      int frameWidth,
      int frameHeight,
      int storageWidth,
      int storageHeight,
      Rect viewport,
      double pixelAspectRatio) {
    Assertions.checkArgument(
        frameWidth > 0 && frameHeight > 0 && storageWidth > 0 && storageHeight > 0,
        "Frame and storage sizes must be positive");
    Assertions.checkArgument(!viewport.isEmpty(), "Video viewport must not be empty");
    Assertions.checkArgument(
        Double.isFinite(pixelAspectRatio) && pixelAspectRatio >= 0.0,
        "Pixel aspect ratio must be finite and non-negative");
    if (this.frameWidth == frameWidth
        && this.frameHeight == frameHeight
        && this.storageWidth == storageWidth
        && this.storageHeight == storageHeight
        && this.pixelAspectRatio == pixelAspectRatio
        && this.viewport.equals(viewport)) {
      return;
    }
    this.frameWidth = frameWidth;
    this.frameHeight = frameHeight;
    this.storageWidth = storageWidth;
    this.storageHeight = storageHeight;
    this.pixelAspectRatio = pixelAspectRatio;
    this.viewport.set(viewport);
    for (LibassTrackSession trackSession : trackSessions.values()) {
      applyFrameSize(trackSession);
    }
  }

  private void applyFrameSize(LibassTrackSession trackSession) {
    synchronized (trackSession) {
      trackSession.setStorageSize(storageWidth, storageHeight);
      trackSession.setFrameSize(
          frameWidth,
          frameHeight,
          viewport.left,
          viewport.top,
          viewport.right,
          viewport.bottom,
          pixelAspectRatio);
    }
  }

  /** Sets the video color information used for tone mapping. */
  public synchronized boolean setVideoColorInfo(int colorSpace, int colorTransfer) {
    if (this.colorSpace == colorSpace && this.colorTransfer == colorTransfer) {
      return false;
    }
    this.colorSpace = colorSpace;
    this.colorTransfer = colorTransfer;
    for (LibassTrackSession trackSession : trackSessions.values()) {
      synchronized (trackSession) {
        trackSession.setVideoColorInfo(colorTransfer, colorSpace);
      }
    }
    return true;
  }

  /** Sets the offset between the primary and the secondary subtitle times. */
  public synchronized void setSecondaryOffsetUs(long secondaryOffsetUs) {
    this.secondaryOffsetUs = secondaryOffsetUs;
  }

  /**
   * Renders the primary and the secondary frame at the given position.
   *
   * @param positionUs The playback position, in microseconds.
   * @param allowPartial Whether partially rendered frames may be returned.
   * @return The rendered frames, or null if nothing is rendered.
   */
  @Nullable
  public synchronized OutputFrame renderFrames(long positionUs, boolean allowPartial) {
    if (closed.get() || (primaryFormat == null && secondaryFormat == null) || frameWidth == 0) {
      return null;
    }
    LibassFrame primaryFrame = renderFrame(primaryFormat, positionUs, allowPartial);
    LibassFrame secondaryFrame =
        renderFrame(secondaryFormat, positionUs - secondaryOffsetUs, allowPartial);
    if (primaryFrame == null && secondaryFrame == null) {
      return null;
    }
    return new OutputFrame(primaryFrame, secondaryFrame);
  }

  @Nullable
  private LibassFrame renderFrame(@Nullable Format format, long positionUs, boolean allowPartial) {
    if (format == null || frameWidth == 0) {
      return null;
    }
    String trackKey = resolveTrackKey(format);
    if (trackKey == null) {
      return null;
    }
    LibassTrackSession trackSession = trackSessions.get(trackKey);
    if (trackSession == null) {
      return null;
    }
    synchronized (trackSession) {
      return trackSession.renderFrame(C.usToMs(positionUs), allowPartial);
    }
  }

  @Override
  public void close() {
    closed.set(true);
    releaseInternal();
  }

  /** Marks the session as closed and releases its native resources asynchronously. */
  public void releaseAsync() {
    closed.set(true);
    LibassNativeLibrary.releaseInBackground(this::releaseInternal);
  }

  private void releaseInternal() {
    for (LibassTrackSession trackSession : trackSessions.values()) {
      trackSession.close();
    }
    trackSessions.clear();
    failedTrackKeys.clear();
    if (nativeLibrary != null) {
      nativeLibrary.close();
      nativeLibrary = null;
    }
    embeddedFonts.clear();
    totalFontBytes = 0;
  }

  /** The primary and the secondary frames rendered for one position. */
  public static final class OutputFrame {

    /** The frame of the primary subtitle, or null. */
    @Nullable public final LibassFrame primaryFrame;
    /** The frame of the secondary subtitle, or null. */
    @Nullable public final LibassFrame secondaryFrame;

    public OutputFrame(@Nullable LibassFrame primaryFrame, @Nullable LibassFrame secondaryFrame) {
      this.primaryFrame = primaryFrame;
      this.secondaryFrame = secondaryFrame;
    }
  }

  private static final class EmbeddedFont {

    public final String name;
    public final byte[] data;

    public EmbeddedFont(String name, byte[] data) {
      this.name = name;
      this.data = data;
    }

    public boolean matches(String name, byte[] data) {
      return this.name.equals(name) && this.data.length == data.length;
    }
  }
}
