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
import java.util.Objects;

/** An override of the subtitle style used by the libass subtitle renderer. */
@UnstableApi
public final class LibassStyleOverride {

  /** The edge type values of the style override. */
  public static final int EDGE_TYPE_NONE = 0;
  /** The edge type value for an outline edge. */
  public static final int EDGE_TYPE_OUTLINE = 1;
  /** The edge type value for a drop shadow edge. */
  public static final int EDGE_TYPE_DROP_SHADOW = 2;

  /** The default font scale of the style override. */
  public static final float DEFAULT_FONT_SCALE = 38.0f;

  /** The font family used when the override is active. */
  public final String fontFamily;
  /** The foreground color of the text, in ARGB format. */
  public final int foregroundColor;
  /** The background color of the text, in ARGB format. */
  public final int backgroundColor;
  /** The edge type of the text. */
  public final int edgeType;
  /** The edge color of the text, in ARGB format. */
  public final int edgeColor;
  /** The edge width of the text, in pixels. */
  public final float edgeWidth;
  /** The shadow offset of the text, in pixels. */
  public final float shadowOffset;

  private LibassStyleOverride(Builder builder) {
    this.fontFamily = builder.fontFamily;
    this.foregroundColor = builder.foregroundColor;
    this.backgroundColor = builder.backgroundColor;
    this.edgeType = builder.edgeType;
    this.edgeColor = builder.edgeColor;
    this.edgeWidth = builder.edgeWidth;
    this.shadowOffset = builder.shadowOffset;
  }

  /** A builder for {@link LibassStyleOverride} instances. */
  public static final class Builder {

    private String fontFamily = "sans-serif";
    private int foregroundColor;
    private int backgroundColor;
    private int edgeType;
    private int edgeColor;
    private float edgeWidth;
    private float shadowOffset;

    /** Sets the font family used when the override is active. */
    public Builder setFontFamily(String fontFamily) {
      this.fontFamily = fontFamily;
      return this;
    }

    /** Sets the foreground color of the text, in ARGB format. */
    public Builder setForegroundColor(int foregroundColor) {
      this.foregroundColor = foregroundColor;
      return this;
    }

    /** Sets the background color of the text, in ARGB format. */
    public Builder setBackgroundColor(int backgroundColor) {
      this.backgroundColor = backgroundColor;
      return this;
    }

    /** Sets the edge type of the text. */
    public Builder setEdgeType(int edgeType) {
      this.edgeType = edgeType;
      return this;
    }

    /** Sets the edge color of the text, in ARGB format. */
    public Builder setEdgeColor(int edgeColor) {
      this.edgeColor = edgeColor;
      return this;
    }

    /** Sets the edge width of the text, in pixels. */
    public Builder setEdgeWidth(float edgeWidth) {
      this.edgeWidth = edgeWidth;
      return this;
    }

    /** Sets the shadow offset of the text, in pixels. */
    public Builder setShadowOffset(float shadowOffset) {
      this.shadowOffset = shadowOffset;
      return this;
    }

    /** Builds the style override. */
    public LibassStyleOverride build() {
      return new LibassStyleOverride(this);
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LibassStyleOverride)) {
      return false;
    }
    LibassStyleOverride that = (LibassStyleOverride) o;
    return foregroundColor == that.foregroundColor
        && backgroundColor == that.backgroundColor
        && edgeType == that.edgeType
        && edgeColor == that.edgeColor
        && Float.compare(that.edgeWidth, edgeWidth) == 0
        && Float.compare(that.shadowOffset, shadowOffset) == 0
        && fontFamily.equals(that.fontFamily);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        fontFamily,
        foregroundColor,
        backgroundColor,
        edgeType,
        edgeColor,
        edgeWidth,
        shadowOffset);
  }
}
