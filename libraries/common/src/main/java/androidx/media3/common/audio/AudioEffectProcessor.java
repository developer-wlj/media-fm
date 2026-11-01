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
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An {@link AudioProcessor} that applies {@link AudioEffectParameters} to 16-bit PCM: channel
 * mixing (mode, balance, center gain), volume boost and preamp, a stability compressor, a
 * software equalizer for SDK levels without hardware equalizer support, and an output limiter.
 */
@UnstableApi
public final class AudioEffectProcessor extends BaseAudioProcessor {

  private static final float LIMITER_CEILING = 0.98f;

  @Nullable private AudioEffectParameters parameters;
  private boolean softwareEqualizer;

  private final ByteBuffer carryBuffer = ByteBuffer.allocate(64);
  private ByteBuffer workBuffer = ByteBuffer.allocate(4096);

  @Nullable private float[][] channelMix;
  @Nullable private BiquadFilter[] equalizerFilters;
  private float gain;
  private float compressorThreshold;
  private float compressorRatio;
  private float compressorMakeup;
  private float compressorMix;
  private boolean limiterEnabled;

  /** Creates an idle processor. Call {@link #setParameters} to activate it. */
  public AudioEffectProcessor() {}

  /** Applies the given parameters, replacing any previous configuration. */
  public void setParameters(AudioEffectParameters parameters, boolean softwareEqualizer) {
    this.parameters = parameters;
    this.softwareEqualizer = softwareEqualizer;
    updateConfiguration();
  }

  /** Removes all effects, returning the processor to its idle pass-through state. */
  public void resetParameters() {
    this.parameters = null;
    this.softwareEqualizer = false;
    updateConfiguration();
  }

  @Override
  public boolean isActive() {
    return parameters != null;
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    updateConfiguration();
    return inputAudioFormat;
  }

  @Override
  protected void onFlush() {
    carryBuffer.clear();
    if (equalizerFilters != null) {
      for (BiquadFilter filter : equalizerFilters) {
        filter.reset();
      }
    }
  }

  @Override
  protected void onReset() {
    parameters = null;
    channelMix = null;
    equalizerFilters = null;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    AudioEffectParameters current = parameters;
    if (current == null || !current.hasEffect()) {
      replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer);
      return;
    }
    ByteBuffer workBuffer = prepareWorkBuffer(inputBuffer);
    ByteBuffer outputBuffer = replaceOutputBuffer(workBuffer.remaining());
    int channelCount = inputAudioFormat.channelCount;
    float[] frame = new float[channelCount];
    int frameBytes = 2 * channelCount;
    while (workBuffer.remaining() >= frameBytes) {
      for (int channel = 0; channel < channelCount; channel++) {
        frame[channel] = workBuffer.getShort() / 32768.0f;
      }
      if (channelMix != null) {
        frame = mixChannels(frame, channelMix);
      }
      for (int channel = 0; channel < channelCount; channel++) {
        float sample = frame[channel] * gain;
        if (compressorRatio > 1.0f) {
          float compressed = compress(sample, compressorThreshold, compressorRatio) * compressorMakeup;
          sample = compressorMix < 1.0f ? sample + (compressed - sample) * compressorMix : compressed;
        }
        if (equalizerFilters != null) {
          sample = equalizerFilters[channel].process(sample, inputAudioFormat.sampleRate);
        }
        if (limiterEnabled) {
          sample = Math.max(-LIMITER_CEILING, Math.min(LIMITER_CEILING, sample));
        }
        outputBuffer.putShort((short) Math.max(-32768, Math.min(32767, Math.round(sample * 32768.0f))));
      }
    }
    carryBuffer.clear();
    carryBuffer.put(workBuffer);
    carryBuffer.flip();
    outputBuffer.flip();
  }

  private ByteBuffer prepareWorkBuffer(ByteBuffer inputBuffer) {
    int frameBytes = 2 * inputAudioFormat.channelCount;
    if (carryBuffer.hasRemaining() || inputBuffer.remaining() < frameBytes) {
      workBuffer.clear();
      if (workBuffer.capacity() < carryBuffer.remaining() + inputBuffer.remaining()) {
        workBuffer =
            ByteBuffer.allocate(carryBuffer.remaining() + inputBuffer.remaining());
      }
      workBuffer.put(carryBuffer);
      workBuffer.put(inputBuffer);
      workBuffer.flip();
      return workBuffer;
    }
    return inputBuffer;
  }

  private void updateConfiguration() {
    AudioEffectParameters current = parameters;
    channelMix = null;
    equalizerFilters = null;
    gain = 1.0f;
    compressorThreshold = 0.0f;
    compressorRatio = 1.0f;
    compressorMakeup = 1.0f;
    compressorMix = 0.0f;
    limiterEnabled = false;
    if (current == null || !current.hasEffect()) {
      return;
    }
    int channelCount = inputAudioFormat.channelCount;
    if (channelCount >= 2 && channelCount <= 8) {
      float[][] mix = identityMix(channelCount);
      if (AudioChannelMode.isAvailable(current.getChannelMode(), channelCount)) {
        switch (AudioChannelMode.resolve(current.getChannelMode(), channelCount)) {
          case AudioChannelMode.STEREO:
            mix = AudioChannelMix.compose(mix, AudioChannelMix.createStereoMix(channelCount, false));
            break;
          case AudioChannelMode.MONO:
            mix = AudioChannelMix.compose(mix, AudioChannelMix.createMonoMix(channelCount));
            break;
          case AudioChannelMode.REVERSE:
            mix = AudioChannelMix.compose(mix, AudioChannelMix.createStereoMix(channelCount, true));
            break;
          default:
            break;
        }
      }
      if (current.getBalance() != 0.0f) {
        mix = AudioChannelMix.compose(mix, AudioChannelMix.createFrontBalanceMix(channelCount, current.getBalance()));
      }
      if (current.hasCenterGain(channelCount)) {
        mix = AudioChannelMix.compose(mix, AudioChannelMix.createFrontCenterGainMix(channelCount, current.getCenterGainFactor()));
      }
      channelMix = mix;
    }
    gain = current.getBoostFactor() * current.getPreampFactor();
    float stability = Math.max(0.0f, Math.min(1.0f, current.getStability()));
    if (stability > 0.0f || current.hasBands()) {
      compressorThreshold = 0.25f - 0.08f * stability;
      compressorRatio = 1.4f + 2.6f * stability;
      compressorMakeup = 1.0f + 0.8f * stability;
      compressorMix = stability > 0.0f ? 0.35f + 0.65f * stability : 0.0f;
    }
    if (softwareEqualizer && current.hasBands()) {
      int[] frequencies = current.getEqualizerFrequenciesHz();
      short[] levels = current.getEqualizerLevelsMillibels();
      BiquadFilter[] filters = new BiquadFilter[channelCount];
      for (int channel = 0; channel < channelCount; channel++) {
        filters[channel] = new BiquadFilter(frequencies, levels);
      }
      equalizerFilters = filters;
    }
    limiterEnabled = current.shouldLimitOutput(channelCount);
  }

  private static float[] mixChannels(float[] frame, float[][] mix) {
    float[] result = new float[mix.length];
    for (int output = 0; output < mix.length; output++) {
      float value = 0.0f;
      for (int input = 0; input < frame.length; input++) {
        value += mix[output][input] * frame[input];
      }
      result[output] = value;
    }
    return result;
  }

  private static float[][] identityMix(int channelCount) {
    float[][] mix = new float[channelCount][channelCount];
    for (int channel = 0; channel < channelCount; channel++) {
      mix[channel][channel] = 1.0f;
    }
    return mix;
  }

  private static float compress(float sample, float threshold, float ratio) {
    float absolute = Math.abs(sample);
    if (absolute <= threshold) {
      return sample;
    }
    float compressed = threshold + (absolute - threshold) / ratio;
    return Math.signum(sample) * compressed;
  }

  /** Cascaded peaking biquad equalizer with one section per band. */
  private static final class BiquadFilter {

    private final int[] frequencies;
    private final short[] levels;
    private final float[] inputState;
    private final float[] outputState;

    BiquadFilter(int[] frequencies, short[] levels) {
      this.frequencies = Arrays.copyOf(frequencies, frequencies.length);
      this.levels = levels;
      this.inputState = new float[frequencies.length];
      this.outputState = new float[frequencies.length];
    }

    float process(float sample, int sampleRate) {
      float value = sample;
      for (int band = 0; band < frequencies.length; band++) {
        value = processBand(band, value, sampleRate);
      }
      return value;
    }

    private float processBand(int band, float sample, int sampleRate) {
      float level = levels[band] / 100.0f;
      if (level == 0.0f) {
        return sample;
      }
      float frequency = Math.max(20.0f, Math.min(sampleRate / 2.0f - 1.0f, frequencies[band]));
      float omega = (float) (2.0 * Math.PI * frequency / sampleRate);
      float alpha = (float) (Math.sin(omega) / (2.0 * Math.sqrt(2.0) * Math.pow(10.0, level / 40.0)));
      float a0 = 1.0f + alpha;
      float b0 = (1.0f + alpha * decay(level)) / a0;
      float b2 = (1.0f - alpha * decay(level)) / a0;
      float a1 = -2.0f * (float) Math.cos(omega) / a0;
      float a2 = (1.0f - alpha) / a0;
      float input = sample - a1 * inputState[band] - a2 * outputState[band];
      float output = b0 * input + a1 * inputState[band] + b2 * outputState[band];
      inputState[band] = input;
      outputState[band] = output;
      return output;
    }

    private static float decay(float level) {
      return (float) Math.pow(10.0, level / 40.0);
    }

    void reset() {
      java.util.Arrays.fill(inputState, 0.0f);
      java.util.Arrays.fill(outputState, 0.0f);
    }
  }
}
