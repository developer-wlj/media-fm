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
package androidx.media3.mpvplayer;

import androidx.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.RestrictTo;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Subtitle presentation options understood by {@link MpvPlayer}. */
public final class MpvSubtitleOptions {

  /** The default vertical offset applied when none is configured. */
  public static final double DEFAULT_VERTICAL_OFFSET = 0.0;
  /** The default brightness applied to bitmap subtitles when none is configured. */
  public static final float DEFAULT_BITMAP_SUBTITLE_BRIGHTNESS = 0.0f;

  public static final int SECONDARY_TRACK_OFF = -2;
  public static final int SECONDARY_TRACK_AUTO = -1;

  private static final int STYLE_ORIGINAL = 0;
  private static final int STYLE_SYSTEM = 1;
  private static final int STYLE_CUSTOM = 2;

  private final double position;
  private final boolean positionSet;
  private final double scale;
  private final boolean scaleSet;
  private final int style;
  private final int foregroundColor;
  private final int backgroundColor;
  private final int edgeType;
  private final int edgeColor;
  private final double edgeSize;
  private final double shadowOffset;
  private final boolean secondarySubtitleConfigured;
  private final int secondaryTrack;
  private final double secondaryPosition;
  private final boolean secondaryOverrideAssStyles;
  @Nullable private final String fontsDirectory;
  @Nullable private final String fontFamily;
  private final double verticalOffset;
  private final boolean verticalOffsetSet;
  private final float bitmapSubtitleBrightness;
  private final double secondaryVerticalOffset;
  private final boolean secondaryVerticalOffsetSet;

  private MpvSubtitleOptions(Builder builder) {
    position = builder.position;
    positionSet = builder.positionSet;
    scale = builder.scale;
    scaleSet = builder.scaleSet;
    style = builder.style;
    foregroundColor = builder.foregroundColor;
    backgroundColor = builder.backgroundColor;
    edgeType = builder.edgeType;
    edgeColor = builder.edgeColor;
    edgeSize = builder.edgeSize;
    shadowOffset = builder.shadowOffset;
    secondarySubtitleConfigured = builder.secondarySubtitleConfigured;
    secondaryTrack = builder.secondaryTrack;
    secondaryPosition = builder.secondaryPosition;
    secondaryOverrideAssStyles = builder.secondaryOverrideAssStyles;
    fontsDirectory = builder.fontsDirectory;
    fontFamily = builder.fontFamily;
    verticalOffset = builder.verticalOffset;
    verticalOffsetSet = builder.verticalOffsetSet;
    bitmapSubtitleBrightness = builder.bitmapSubtitleBrightness;
    secondaryVerticalOffset = builder.secondaryVerticalOffset;
    secondaryVerticalOffsetSet = builder.secondaryVerticalOffsetSet;
  }

  public double getPosition() {
    return position;
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public boolean isPositionSet() {
    return positionSet;
  }

  public double getScale() {
    return scale;
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public boolean isScaleSet() {
    return scaleSet;
  }

  public boolean usesSystemCaptionStyle() {
    return style == STYLE_SYSTEM;
  }

  public boolean hasCustomStyle() {
    return style == STYLE_CUSTOM;
  }

  public int getForegroundColor() {
    return foregroundColor;
  }

  public int getBackgroundColor() {
    return backgroundColor;
  }

  public int getEdgeType() {
    return edgeType;
  }

  public int getEdgeColor() {
    return edgeColor;
  }

  public double getEdgeSize() {
    return edgeSize;
  }

  public double getShadowOffset() {
    return shadowOffset;
  }

  public boolean isSecondarySubtitleConfigured() {
    return secondarySubtitleConfigured;
  }

  public int getSecondaryTrack() {
    return secondaryTrack;
  }

  public double getSecondaryPosition() {
    return secondaryPosition;
  }

  /** Returns the directory additional fonts are loaded from, or null. */
  @Nullable
  public String getFontsDirectory() {
    return fontsDirectory;
  }

  /** Returns the font family used for text subtitles, or null. */
  @Nullable
  public String getFontFamily() {
    return fontFamily;
  }

  /** Returns the vertical offset of the primary subtitles. */
  public double getVerticalOffset() {
    return verticalOffset;
  }

  /** Returns whether a vertical offset was configured. */
  public boolean isVerticalOffsetSet() {
    return verticalOffsetSet;
  }

  /** Returns the brightness applied to bitmap subtitles. */
  public float getBitmapSubtitleBrightness() {
    return bitmapSubtitleBrightness;
  }

  /** Returns the vertical offset of the secondary subtitles. */
  public double getSecondaryVerticalOffset() {
    return secondaryVerticalOffset;
  }

  /** Returns whether a secondary vertical offset was configured. */
  public boolean isSecondaryVerticalOffsetSet() {
    return secondaryVerticalOffsetSet;
  }

  public boolean shouldOverrideSecondaryAssStyles() {
    return secondaryOverrideAssStyles;
  }

  /** Builder for {@link MpvSubtitleOptions}. */
  public static final class Builder {

    private double position = 100.0;
    private boolean positionSet;
    private double scale = 1.0;
    private boolean scaleSet;
    private int style = STYLE_ORIGINAL;
    private int foregroundColor;
    private int backgroundColor;
    private int edgeType;
    private int edgeColor;
    private double edgeSize;
    private double shadowOffset;
    private boolean secondarySubtitleConfigured;
    private int secondaryTrack = SECONDARY_TRACK_OFF;
    private double secondaryPosition;
    private boolean secondaryOverrideAssStyles;
    @Nullable private String fontsDirectory;
    @Nullable private String fontFamily;
    private double verticalOffset = DEFAULT_VERTICAL_OFFSET;
    private boolean verticalOffsetSet;
    private float bitmapSubtitleBrightness = DEFAULT_BITMAP_SUBTITLE_BRIGHTNESS;
    private double secondaryVerticalOffset = DEFAULT_VERTICAL_OFFSET;
    private boolean secondaryVerticalOffsetSet;
    private boolean buildCalled;

    @CanIgnoreReturnValue
    public Builder setPosition(double position) {
      checkState(!buildCalled);
      this.position = position;
      positionSet = true;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setScale(double scale) {
      checkState(!buildCalled);
      this.scale = scale;
      scaleSet = true;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSystemCaptionStyle() {
      checkState(!buildCalled);
      style = STYLE_SYSTEM;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCustomStyle(
        int foregroundColor,
        int backgroundColor,
        int edgeType,
        int edgeColor,
        double edgeSize,
        double shadowOffset) {
      checkState(!buildCalled);
      style = STYLE_CUSTOM;
      this.foregroundColor = foregroundColor;
      this.backgroundColor = backgroundColor;
      this.edgeType = edgeType;
      this.edgeColor = edgeColor;
      this.edgeSize = edgeSize;
      this.shadowOffset = shadowOffset;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSecondarySubtitle(int trackId, double position, boolean overrideAssStyles) {
      checkState(!buildCalled);
      checkArgument(trackId >= SECONDARY_TRACK_OFF);
      secondarySubtitleConfigured = true;
      secondaryTrack = trackId;
      secondaryPosition = position;
      secondaryOverrideAssStyles = overrideAssStyles;
      return this;
    }

    /** Sets the directory additional fonts are loaded from. */
    @CanIgnoreReturnValue
    public Builder setFontsDirectory(@Nullable String fontsDirectory) {
      checkState(!buildCalled);
      this.fontsDirectory = fontsDirectory;
      return this;
    }

    /** Sets the font family used for text subtitles. */
    @CanIgnoreReturnValue
    public Builder setFontFamily(@Nullable String fontFamily) {
      checkState(!buildCalled);
      this.fontFamily = fontFamily;
      return this;
    }

    /** Sets the vertical offset of the primary subtitles, in the 0..100 range. */
    @CanIgnoreReturnValue
    public Builder setVerticalOffset(float verticalOffset) {
      checkState(!buildCalled);
      this.verticalOffset = verticalOffset;
      verticalOffsetSet = true;
      return this;
    }

    /** Sets the brightness applied to bitmap subtitles, in the -1..1 range. */
    @CanIgnoreReturnValue
    public Builder setBitmapSubtitleBrightness(float bitmapSubtitleBrightness) {
      checkState(!buildCalled);
      this.bitmapSubtitleBrightness = bitmapSubtitleBrightness;
      return this;
    }

    /** Sets the vertical position of the secondary subtitles, in the 0..100 range. */
    @CanIgnoreReturnValue
    public Builder setSecondarySubtitlePosition(double secondaryPosition) {
      checkState(!buildCalled);
      this.secondaryPosition = secondaryPosition;
      secondarySubtitleConfigured = true;
      return this;
    }

    /** Sets the time offset of the secondary subtitles, in seconds. */
    @CanIgnoreReturnValue
    public Builder setSecondaryVerticalOffset(double secondaryVerticalOffset) {
      checkState(!buildCalled);
      this.secondaryVerticalOffset = secondaryVerticalOffset;
      secondaryVerticalOffsetSet = true;
      return this;
    }

    /** Sets whether ASS style overrides apply to the secondary subtitles. */
    @CanIgnoreReturnValue
    public Builder setSecondaryAssStyleOverride(boolean enabled) {
      checkState(!buildCalled);
      this.secondaryOverrideAssStyles = enabled;
      secondarySubtitleConfigured = true;
      return this;
    }

    /**
     * Sets the default style for text subtitles.
     *
     * @param foregroundColor The text color as an ARGB int.
     * @param backgroundColor The background color as an ARGB int.
     * @param edgeType The edge type, one of {@code CaptionStyleCompat} edge types.
     * @param edgeColor The edge color as an ARGB int.
     * @param edgeWidth The edge width in pixels.
     * @param shadowOffset The shadow offset in pixels.
     */
    @CanIgnoreReturnValue
    public Builder setDefaultStyle(
        int foregroundColor,
        int backgroundColor,
        int edgeType,
        int edgeColor,
        float edgeWidth,
        float shadowOffset) {
      checkState(!buildCalled);
      style = STYLE_CUSTOM;
      this.foregroundColor = foregroundColor;
      this.backgroundColor = backgroundColor;
      this.edgeType = edgeType;
      this.edgeColor = edgeColor;
      this.edgeSize = edgeWidth;
      this.shadowOffset = shadowOffset;
      return this;
    }

    public MpvSubtitleOptions build() {
      checkState(!buildCalled);
      buildCalled = true;
      return new MpvSubtitleOptions(this);
    }
  }
}
