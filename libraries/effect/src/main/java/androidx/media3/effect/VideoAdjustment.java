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
package androidx.media3.effect;

import androidx.media3.common.util.UnstableApi;

/**
 * Describes a set of color and detail adjustments that can be applied to video frames through
 * {@link ColorToneAdjustEffect} and {@link DetailAdjustEffect}.
 */
@UnstableApi
public final class VideoAdjustment {

  /** An adjustment that leaves every pixel unchanged. */
  public static final VideoAdjustment DEFAULT = new Builder().build();

  private final float saturation;
  private final float contrast;
  private final float brightness;
  private final float gamma;
  private final float hue;
  private final float redGain;
  private final float blueGain;
  private final float sharpness;
  private final float threshold;
  private final float shadowLift;

  private VideoAdjustment(Builder builder) {
    this.saturation = builder.saturation;
    this.contrast = builder.contrast;
    this.brightness = builder.brightness;
    this.gamma = builder.gamma;
    this.hue = builder.hue;
    this.redGain = builder.redGain;
    this.blueGain = builder.blueGain;
    this.sharpness = builder.sharpness;
    this.threshold = builder.threshold;
    this.shadowLift = builder.shadowLift;
  }

  /** Returns the saturation multiplier, where 1.0 leaves saturation unchanged. */
  public float getSaturation() {
    return saturation;
  }

  /** Returns the contrast multiplier, where 1.0 leaves contrast unchanged. */
  public float getContrast() {
    return contrast;
  }

  /** Returns the brightness offset, where 0.0 leaves brightness unchanged. */
  public float getBrightness() {
    return brightness;
  }

  /** Returns the gamma exponent, where 1.0 leaves gamma unchanged. */
  public float getGamma() {
    return gamma;
  }

  /** Returns the hue rotation in degrees. */
  public float getHue() {
    return hue;
  }

  /** Returns the red channel gain multiplier. */
  public float getRedGain() {
    return redGain;
  }

  /** Returns the blue channel gain multiplier. */
  public float getBlueGain() {
    return blueGain;
  }

  /** Returns the unsharp mask strength, where 0.0 disables sharpening. */
  public float getSharpness() {
    return sharpness;
  }

  /** Returns the edge threshold below which pixels are not sharpened. */
  public float getThreshold() {
    return threshold;
  }

  /** Returns the amount shadows are lifted, where 0.0 disables lifting. */
  public float getShadowLift() {
    return shadowLift;
  }

  /** Returns whether the color related adjustments leave pixels unchanged. */
  public boolean isColorNoOp() {
    return saturation == 1.0f
        && contrast == 1.0f
        && brightness == 0.0f
        && redGain == 1.0f
        && blueGain == 1.0f;
  }

  /** Returns whether the tone related adjustments leave pixels unchanged. */
  public boolean isToneNoOp() {
    return gamma == 1.0f && hue == 0.0f;
  }

  /** Returns whether the detail related adjustments leave pixels unchanged. */
  public boolean isDetailNoOp() {
    return sharpness == 0.0f && shadowLift == 0.0f;
  }

  /** Returns whether every adjustment leaves pixels unchanged. */
  public boolean isNoOp() {
    return isColorNoOp() && isToneNoOp() && isDetailNoOp();
  }

  /** Builder for {@link VideoAdjustment}. */
  public static final class Builder {

    private float saturation = 1.0f;
    private float contrast = 1.0f;
    private float brightness;
    private float gamma = 1.0f;
    private float hue;
    private float redGain = 1.0f;
    private float blueGain = 1.0f;
    private float sharpness;
    private float threshold = 0.03f;
    private float shadowLift;

    /** Sets the saturation multiplier. */
    public Builder setSaturation(float saturation) {
      this.saturation = saturation;
      return this;
    }

    /** Sets the contrast multiplier. */
    public Builder setContrast(float contrast) {
      this.contrast = contrast;
      return this;
    }

    /** Sets the brightness offset. */
    public Builder setBrightness(float brightness) {
      this.brightness = brightness;
      return this;
    }

    /** Sets the gamma exponent. */
    public Builder setGamma(float gamma) {
      this.gamma = gamma;
      return this;
    }

    /** Sets the hue rotation in degrees. */
    public Builder setHue(float hue) {
      this.hue = hue;
      return this;
    }

    /** Sets the red channel gain multiplier. */
    public Builder setRedGain(float redGain) {
      this.redGain = redGain;
      return this;
    }

    /** Sets the blue channel gain multiplier. */
    public Builder setBlueGain(float blueGain) {
      this.blueGain = blueGain;
      return this;
    }

    /** Sets the unsharp mask strength. */
    public Builder setSharpness(float sharpness) {
      this.sharpness = sharpness;
      return this;
    }

    /** Sets the edge threshold below which pixels are not sharpened. */
    public Builder setThreshold(float threshold) {
      this.threshold = threshold;
      return this;
    }

    /** Sets the amount shadows are lifted. */
    public Builder setShadowLift(float shadowLift) {
      this.shadowLift = shadowLift;
      return this;
    }

    /** Builds the adjustment. */
    public VideoAdjustment build() {
      return new VideoAdjustment(this);
    }
  }
}
