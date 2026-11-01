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
package androidx.media3.exoplayer.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.Arrays;

/**
 * Applies an {@link androidx.media3.common.audio.AudioEffectParameters} equalizer to the player's
 * audio session using the platform {@link DynamicsProcessing} effect. Supported from Android 9
 * (API 28); on older releases the caller should fall back to the software equalizer path.
 */
@UnstableApi
public final class AudioEqualizerController {

  private final Context context;

  @Nullable private Engine engine;

  /** Creates a controller bound to the given context. */
  public AudioEqualizerController(Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Applies the equalizer bands of {@code config} to the player's audio session.
   *
   * @return Whether the equalizer is active for this configuration.
   */
  public boolean apply(ExoPlayer player, androidx.media3.common.audio.AudioEffectParameters config) {
    if (Build.VERSION.SDK_INT < 28 || !config.hasBands()) {
      release();
      return false;
    }
    int[] frequencies = config.getEqualizerFrequenciesHz();
    short[] levels = config.getEqualizerLevelsMillibels();
    @Nullable Format audioFormat = player.getAudioFormat();
    int channelCount = audioFormat == null ? -1 : audioFormat.channelCount;
    int audioSessionId = player.getAudioSessionId();
    if (audioSessionId == 0) {
      audioSessionId = generateAudioSessionId();
      if (audioSessionId == 0) {
        release();
        return false;
      }
      player.setAudioSessionId(audioSessionId);
    }
    if (channelCount <= 0) {
      release();
      return false;
    }
    if (engine == null
        || !engine.matches(audioSessionId, channelCount, frequencies)
        || !engine.isValid()) {
      release();
      engine = Engine.create(audioSessionId, channelCount, frequencies);
    }
    if (engine == null) {
      return false;
    }
    return engine.apply(levels, config.shouldLimitOutput(channelCount));
  }

  /** Releases the platform effect. Safe to call multiple times. */
  public void release() {
    if (engine != null) {
      engine.release();
      engine = null;
    }
  }

  private int generateAudioSessionId() {
    AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager == null) {
      return 0;
    }
    int sessionId = audioManager.generateAudioSessionId();
    return sessionId == -1 ? 0 : sessionId;
  }

  @RequiresApi(28)
  private static final class Engine {

    private final DynamicsProcessing dynamicsProcessing;
    private final int audioSessionId;
    private final int channelCount;
    private final int[] frequencies;
    private short[] appliedLevels;
    private boolean appliedLimiter;
    private boolean released;

    private Engine(
        DynamicsProcessing dynamicsProcessing,
        int audioSessionId,
        int channelCount,
        int[] frequencies) {
      this.dynamicsProcessing = dynamicsProcessing;
      this.audioSessionId = audioSessionId;
      this.channelCount = channelCount;
      this.frequencies = frequencies;
      this.appliedLevels = new short[frequencies.length];
      this.appliedLimiter = false;
    }

    @Nullable
    static Engine create(int audioSessionId, int channelCount, int[] frequencies) {
      try {
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, frequencies.length);
        for (int band = 0; band < frequencies.length; band++) {
          eq.setBand(band, createBand(band, (short) 0, frequencies));
        }
        DynamicsProcessing.Config config =
            new DynamicsProcessing.Config.Builder(
                    /* variant= */ 0,
                    /* channelCount= */ channelCount,
                    /* preEqInEnabled= */ false,
                    /* preEqInBandCount= */ 0,
                    /* preEqOutEnabled= */ false,
                    /* preEqOutBandCount= */ 0,
                    /* postEqInEnabled= */ true,
                    /* postEqInBandCount= */ frequencies.length,
                    /* postEqOutEnabled= */ true)
                .setPostEqAllChannelsTo(eq)
                .setLimiterAllChannelsTo(createLimiter(false))
                .build();
        DynamicsProcessing dynamicsProcessing =
            new DynamicsProcessing(0, audioSessionId, config);
        try {
          if (dynamicsProcessing.setEnabled(true) == 0) {
            return new Engine(dynamicsProcessing, audioSessionId, channelCount, frequencies);
          }
          dynamicsProcessing.release();
          return null;
        } catch (RuntimeException e) {
          try {
            dynamicsProcessing.release();
          } catch (RuntimeException suppressed) {
            e.addSuppressed(suppressed);
          }
          throw e;
        }
      } catch (RuntimeException e) {
        return null;
      }
    }

    boolean matches(int audioSessionId, int channelCount, int[] frequencies) {
      return this.audioSessionId == audioSessionId
          && this.channelCount == channelCount
          && Arrays.equals(this.frequencies, frequencies);
    }

    boolean isValid() {
      return !released;
    }

    boolean apply(short[] levels, boolean limiter) {
      if (released) {
        return false;
      }
      try {
        for (int band = 0; band < frequencies.length; band++) {
          if (appliedLevels[band] != levels[band]) {
            dynamicsProcessing.setPostEqBandAllChannelsTo(
                band, createBand(band, levels[band], frequencies));
          }
        }
        if (appliedLimiter != limiter) {
          dynamicsProcessing.setLimiterAllChannelsTo(createLimiter(limiter));
        }
        appliedLevels = Arrays.copyOf(levels, levels.length);
        appliedLimiter = limiter;
        return true;
      } catch (RuntimeException e) {
        return false;
      }
    }

    void release() {
      if (released) {
        return;
      }
      released = true;
      try {
        dynamicsProcessing.release();
      } catch (RuntimeException ignored) {
        // The effect is already detached; nothing to recover.
      }
    }

    private static DynamicsProcessing.EqBand createBand(int band, short level, int[] frequencies) {
      float cutoff;
      if (band == frequencies.length - 1) {
        cutoff = Math.max(20000.0f, frequencies[band]);
      } else {
        cutoff = (float) Math.sqrt((double) frequencies[band] * (double) frequencies[band + 1]);
      }
      return new DynamicsProcessing.EqBand(true, cutoff, level / 100.0f);
    }

    private static DynamicsProcessing.Limiter createLimiter(boolean enabled) {
      return new DynamicsProcessing.Limiter(
          /* inUse= */ true,
          /* enabled= */ enabled,
          /* linkGroup= */ 0,
          /* attackTime= */ 1.0f,
          /* releaseTime= */ 60.0f,
          /* ratio= */ 20.0f,
          /* threshold= */ -0.2f,
          /* postGain= */ 0.0f);
    }
  }
}
