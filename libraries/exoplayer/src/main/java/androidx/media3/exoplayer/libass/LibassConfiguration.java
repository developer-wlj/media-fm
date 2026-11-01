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

/** Configuration for the libass subtitle renderer. */
@UnstableApi
public final class LibassConfiguration {

  @Nullable public final String fontConfig;
  @Nullable public final String fontsDirectory;
  @Nullable public final String defaultFontFamily;
  public final int maximumRenderPixels;
  public final int maximumGlyphCount;
  public final int maximumBitmapCacheSizeMb;

  private LibassConfiguration(Builder builder) {
    this.fontConfig = builder.fontConfig;
    this.fontsDirectory = builder.fontsDirectory;
    this.defaultFontFamily = builder.defaultFontFamily;
    this.maximumRenderPixels = builder.maximumRenderPixels;
    this.maximumGlyphCount = builder.maximumGlyphCount;
    this.maximumBitmapCacheSizeMb = builder.maximumBitmapCacheSizeMb;
  }

  /** A builder for {@link LibassConfiguration} instances. */
  public static final class Builder {

    @Nullable private String fontConfig;
    @Nullable private String fontsDirectory;
    @Nullable private String defaultFontFamily;
    private int maximumRenderPixels;
    private int maximumGlyphCount;
    private int maximumBitmapCacheSizeMb;

    /**
     * Sets the path of the fontconfig configuration file, or null to use the default font
     * discovery.
     */
    public Builder setFontConfig(@Nullable String fontConfig) {
      this.fontConfig = fontConfig;
      return this;
    }

    /** Sets the directory containing additional fonts, or null for none. */
    public Builder setFontsDirectory(@Nullable String fontsDirectory) {
      this.fontsDirectory = fontsDirectory;
      return this;
    }

    /** Sets the default font family used when a style does not specify a font, or null. */
    public Builder setDefaultFontFamily(@Nullable String defaultFontFamily) {
      this.defaultFontFamily = defaultFontFamily;
      return this;
    }

    /** Sets the maximum number of pixels rendered per frame. */
    public Builder setMaximumRenderPixels(int maximumRenderPixels) {
      this.maximumRenderPixels = maximumRenderPixels;
      return this;
    }

    /** Sets the maximum number of glyphs rendered per frame. */
    public Builder setMaximumGlyphCount(int maximumGlyphCount) {
      this.maximumGlyphCount = maximumGlyphCount;
      return this;
    }

    /** Sets the maximum size of the rendered bitmap cache, in megabytes. */
    public Builder setMaximumBitmapCacheSizeMb(int maximumBitmapCacheSizeMb) {
      this.maximumBitmapCacheSizeMb = maximumBitmapCacheSizeMb;
      return this;
    }

    /** Builds the configuration. */
    public LibassConfiguration build() {
      return new LibassConfiguration(this);
    }
  }
}
