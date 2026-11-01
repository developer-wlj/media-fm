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
package androidx.media3.common.audio;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;

/** Immutable set of parameters describing the audio effects to apply to a playback. */
@UnstableApi
public final class AudioEffectParameters {

  /** Parameters that request no effect at all. */
  public static final AudioEffectParameters DEFAULT = new Builder().build();

  private final ImmutableList<Integer> equalizerFrequencies;
  private final short[] equalizerLevels;
  private final float stability;
  private final int boostMillibels;
  private final int preampMillibels;
  private final int centerGainMillibels;
  private final float balance;
  private final int channelMode;
  private final boolean loudnessEnabled;

  private AudioEffectParameters(Builder builder) {
    this.equalizerFrequencies =
        ImmutableList.copyOf(Arrays.stream(builder.frequencies).boxed().iterator());
    this.equalizerLevels = builder.levels;
    this.stability = builder.stability;
    this.boostMillibels = builder.boostMillibels;
    this.preampMillibels = builder.preampMillibels;
    this.centerGainMillibels = builder.centerGainMillibels;
    this.balance = builder.balance;
    this.channelMode = builder.channelMode;
    this.loudnessEnabled = builder.loudnessEnabled;
  }

  /** Returns whether any effect would produce an audible change. */
  public boolean hasEffect() {
    return hasBands()
        || stability > 0.0f
        || boostMillibels > 0
        || preampMillibels != 0
        || balance != 0.0f
        || AudioChannelMode.clamp(channelMode) != AudioChannelMode.AUTO
        || centerGainMillibels > 0
        || loudnessEnabled;
  }

  /** Returns whether equalizer band levels are present. */
  public boolean hasBands() {
    return equalizerLevels.length > 0;
  }

  /** Returns whether any equalizer band boosts its level above zero. */
  public boolean hasBoostedBands() {
    for (short level : equalizerLevels) {
      if (level > 0) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether center gain can apply to playback with the given channel count. */
  public static boolean isCenterGainAvailable(int channelCount) {
    return channelCount == 6 || channelCount == 8;
  }

  /** Returns whether center gain applies to playback with the given channel count. */
  public boolean hasCenterGain(int channelCount) {
    return centerGainMillibels > 0 && isCenterGainAvailable(channelCount);
  }

  /** Returns the linear center gain factor, or 1.0 when disabled. */
  public float getCenterGainFactor() {
    return toLinear(centerGainMillibels);
  }

  /** Returns the linear boost factor. */
  public float getBoostFactor() {
    return toLinear(boostMillibels);
  }

  /** Returns the linear preamp factor. */
  public float getPreampFactor() {
    return toLinear(preampMillibels);
  }

  /** Returns the stability amount in the 0..1 range. */
  public float getStability() {
    return stability;
  }

  /** Returns the left/right balance in the -1..1 range. */
  public float getBalance() {
    return balance;
  }

  /** Returns the requested channel mode, one of the {@link AudioChannelMode} constants. */
  public int getChannelMode() {
    return AudioChannelMode.clamp(channelMode);
  }

  /** Returns whether loudness normalization is requested. */
  public boolean isLoudnessEnabled() {
    return loudnessEnabled;
  }

  /** Returns the boost level in millibels. */
  public int getBoostMillibels() {
    return boostMillibels;
  }

  /** Returns the preamp level in millibels. */
  public int getPreampMillibels() {
    return preampMillibels;
  }

  /** Returns the center gain level in millibels. */
  public int getCenterGainMillibels() {
    return centerGainMillibels;
  }

  /** Returns the equalizer band center frequencies in Hz. */
  public int[] getEqualizerFrequenciesHz() {
    int[] frequencies = new int[equalizerFrequencies.size()];
    for (int i = 0; i < frequencies.length; i++) {
      frequencies[i] = equalizerFrequencies.get(i);
    }
    return frequencies;
  }

  /** Returns the equalizer band levels in millibels. */
  public short[] getEqualizerLevelsMillibels() {
    return Arrays.copyOf(equalizerLevels, equalizerLevels.length);
  }

  /** Returns whether the output needs to be limited to avoid clipping with these parameters. */
  public boolean shouldLimitOutput(int channelCount) {
    return stability > 0.0f
        || boostMillibels > 0
        || loudnessEnabled
        || hasCenterGain(channelCount)
        || hasBoostedBands()
        || AudioChannelMode.isAvailable(getChannelMode(), channelCount);
  }

  private static float toLinear(int millibels) {
    if (millibels == 0) {
      return 1.0f;
    }
    return (float) Math.pow(10.0, millibels / 2000.0);
  }

  /** Builder for {@link AudioEffectParameters}. */
  public static final class Builder {

    private int[] frequencies = new int[0];
    private short[] levels = new short[0];
    private float stability;
    private int boostMillibels;
    private int preampMillibels;
    private int centerGainMillibels;
    private float balance;
    private int channelMode = AudioChannelMode.AUTO;
    private boolean loudnessEnabled;

    /** Sets the equalizer band center frequencies in Hz and levels in millibels. */
    public Builder setEqualizerBands(int[] frequencies, short[] levels) {
      this.frequencies = frequencies != null ? Arrays.copyOf(frequencies, frequencies.length) : new int[0];
      this.levels = levels != null ? Arrays.copyOf(levels, levels.length) : new short[0];
      return this;
    }

    /** Sets the stability amount, in the 0..1 range. */
    public Builder setStability(float stability) {
      this.stability = stability;
      return this;
    }

    /** Sets the volume boost in millibels. */
    public Builder setBoostMillibels(int boostMillibels) {
      this.boostMillibels = boostMillibels;
      return this;
    }

    /** Sets the preamp level in millibels. */
    public Builder setPreampMillibels(int preampMillibels) {
      this.preampMillibels = preampMillibels;
      return this;
    }

    /** Sets the center gain in millibels. */
    public Builder setCenterGainMillibels(int centerGainMillibels) {
      this.centerGainMillibels = centerGainMillibels;
      return this;
    }

    /** Sets the left/right balance in the -1..1 range. */
    public Builder setBalance(float balance) {
      this.balance = balance;
      return this;
    }

    /** Sets the requested channel mode, one of the {@link AudioChannelMode} constants. */
    public Builder setChannelMode(int channelMode) {
      this.channelMode = AudioChannelMode.clamp(channelMode);
      return this;
    }

    /** Sets whether loudness normalization is enabled. */
    public Builder setLoudnessEnabled(boolean loudnessEnabled) {
      this.loudnessEnabled = loudnessEnabled;
      return this;
    }

    /** Builds the parameters. */
    public AudioEffectParameters build() {
      return new AudioEffectParameters(this);
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AudioEffectParameters)) {
      return false;
    }
    AudioEffectParameters that = (AudioEffectParameters) o;
    return Float.compare(that.stability, stability) == 0
        && boostMillibels == that.boostMillibels
        && preampMillibels == that.preampMillibels
        && centerGainMillibels == that.centerGainMillibels
        && Float.compare(that.balance, balance) == 0
        && channelMode == that.channelMode
        && loudnessEnabled == that.loudnessEnabled
        && Arrays.equals(equalizerLevels, that.equalizerLevels)
        && equalizerFrequencies.equals(that.equalizerFrequencies);
  }

  @Override
  public int hashCode() {
    int result = equalizerFrequencies.hashCode();
    result = 31 * result + Arrays.hashCode(equalizerLevels);
    result = 31 * result + (stability != 0.0f ? Float.floatToIntBits(stability) : 0);
    result = 31 * result + boostMillibels;
    result = 31 * result + preampMillibels;
    result = 31 * result + centerGainMillibels;
    result = 31 * result + (balance != 0.0f ? Float.floatToIntBits(balance) : 0);
    result = 31 * result + channelMode;
    result = 31 * result + (loudnessEnabled ? 1 : 0);
    return result;
  }
}
