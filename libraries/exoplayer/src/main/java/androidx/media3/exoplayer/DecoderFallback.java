/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED;
import static androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED;
import static androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED;
import static androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;

import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import java.util.EnumSet;

/**
 * Recovers from decoder failures by switching the affected track type to another {@link
 * DecoderMode}, in the order hardware to software to FFmpeg.
 *
 * <p>Every attempted mode is remembered per track type so that repeated failures walk through the
 * remaining modes, and {@link #reset} clears the remembered modes again.
 */
@UnstableApi
public final class DecoderFallback {

  private static final String FFMPEG_AUDIO_RENDERER_NAME = "FfmpegAudioRenderer";
  private static final String FFMPEG_VIDEO_RENDERER_NAME = "FfmpegVideoRenderer";

  private final DecoderManager decoderManager;
  private final EnumSet<DecoderMode> audioFallbackModes;
  private final EnumSet<DecoderMode> videoFallbackModes;

  /**
   * Creates a fallback handler that applies recovered modes through the given manager.
   *
   * @param decoderManager The manager used to read and change the current decoder modes.
   */
  public DecoderFallback(DecoderManager decoderManager) {
    this.decoderManager = decoderManager;
    this.audioFallbackModes = EnumSet.noneOf(DecoderMode.class);
    this.videoFallbackModes = EnumSet.noneOf(DecoderMode.class);
  }

  /**
   * Clears the modes already tried for the given track type.
   *
   * @param trackType One of {@link C#TRACK_TYPE_AUDIO} or {@link C#TRACK_TYPE_VIDEO}. Any other
   *     track type is ignored.
   */
  public void reset(@C.TrackType int trackType) {
    if (trackType == C.TRACK_TYPE_AUDIO) {
      audioFallbackModes.clear();
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      videoFallbackModes.clear();
    }
  }

  /**
   * Attempts to recover from a decoder failure by selecting a different decoder mode.
   *
   * @param error The playback error to inspect.
   * @return Whether a different decoder mode was applied to the player.
   */
  public boolean retry(ExoPlaybackException error) {
    if (error.type != ExoPlaybackException.TYPE_RENDERER) {
      return false;
    }
    int errorCode = error.errorCode;
    if (errorCode != ERROR_CODE_DECODER_INIT_FAILED
        && errorCode != ERROR_CODE_DECODER_QUERY_FAILED
        && errorCode != ERROR_CODE_DECODING_FAILED
        && errorCode != ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
      return false;
    }
    @C.TrackType int trackType = getTrackType(error);
    if (trackType != C.TRACK_TYPE_AUDIO && trackType != C.TRACK_TYPE_VIDEO) {
      return false;
    }
    boolean isAudio = trackType == C.TRACK_TYPE_AUDIO;
    DecoderMode currentMode =
        isAudio ? decoderManager.getAudioMode() : decoderManager.getVideoMode();
    EnumSet<DecoderMode> fallbackModes = isAudio ? audioFallbackModes : videoFallbackModes;
    EnumSet<DecoderMode> excludedModes = EnumSet.noneOf(DecoderMode.class);
    addFailedCodecModes(error, excludedModes);
    DecoderMode failedMode =
        errorCode != ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || currentMode == DecoderMode.AUTO
            ? getFailedMode(error, currentMode)
            : currentMode;
    if (failedMode != DecoderMode.AUTO) {
      excludedModes.add(failedMode);
    }
    fallbackModes.addAll(excludedModes);
    if (fallbackModes.contains(DecoderMode.FFMPEG)) {
      return false;
    }
    DecoderMode fallbackMode = DecoderMode.FFMPEG;
    if (failedMode == DecoderMode.HARDWARE && !fallbackModes.contains(DecoderMode.SOFTWARE)) {
      fallbackMode = DecoderMode.SOFTWARE;
    }
    fallbackModes.add(fallbackMode);
    if (isAudio) {
      decoderManager.selectAudioDecoder(fallbackMode);
    } else {
      decoderManager.selectVideoDecoder(fallbackMode);
    }
    return true;
  }

  private static @C.TrackType int getTrackType(ExoPlaybackException error) {
    Format format = error.rendererFormat;
    return format == null ? C.TRACK_TYPE_UNKNOWN : MimeTypes.getTrackType(format.sampleMimeType);
  }

  private static void addFailedCodecModes(
      ExoPlaybackException error, EnumSet<DecoderMode> excludedModes) {
    Exception rendererException = error.getRendererException();
    if (rendererException instanceof MediaCodecRenderer.DecoderInitializationException) {
      for (MediaCodecRenderer.DecoderInitializationException exception =
              (MediaCodecRenderer.DecoderInitializationException) rendererException;
          exception != null;
          exception = exception.fallbackDecoderInitializationException) {
        addCodecMode(exception.codecInfo, excludedModes);
      }
    }
  }

  private static DecoderMode getFailedMode(ExoPlaybackException error, DecoderMode currentMode) {
    if (isFfmpegRenderer(error.rendererName, getTrackType(error))) {
      return DecoderMode.FFMPEG;
    }
    DecoderMode failedMode = getFailedCodecMode(error);
    return failedMode == null ? currentMode : failedMode;
  }

  @Nullable
  private static DecoderMode getFailedCodecMode(ExoPlaybackException error) {
    Exception rendererException = error.getRendererException();
    if (rendererException instanceof MediaCodecDecoderException) {
      MediaCodecInfo codecInfo = ((MediaCodecDecoderException) rendererException).codecInfo;
      return codecInfo == null ? null : DecoderMode.of(codecInfo);
    }
    if (rendererException instanceof MediaCodecRenderer.DecoderInitializationException) {
      MediaCodecInfo codecInfo =
          ((MediaCodecRenderer.DecoderInitializationException) rendererException).codecInfo;
      return codecInfo == null ? null : DecoderMode.of(codecInfo);
    }
    return null;
  }

  private static void addCodecMode(
      @Nullable MediaCodecInfo mediaCodecInfo, EnumSet<DecoderMode> excludedModes) {
    if (mediaCodecInfo != null) {
      DecoderMode decoderMode = DecoderMode.of(mediaCodecInfo);
      if (decoderMode != null) {
        excludedModes.add(decoderMode);
      }
    }
  }

  private static boolean isFfmpegRenderer(@Nullable String rendererName, int trackType) {
    if (TextUtils.isEmpty(rendererName)) {
      return false;
    }
    if (trackType == C.TRACK_TYPE_AUDIO) {
      return FFMPEG_AUDIO_RENDERER_NAME.equals(rendererName);
    }
    if (trackType == C.TRACK_TYPE_VIDEO) {
      return FFMPEG_VIDEO_RENDERER_NAME.equals(rendererName);
    }
    return false;
  }
}
