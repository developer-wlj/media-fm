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

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the requested {@link DecoderMode decoder modes} for audio and video tracks and applies them
 * to every {@link DecodeTrackSelector} created through this manager.
 *
 * <p>The manager is attached to an {@link ExoPlayer} before use so that mode changes can be
 * restricted to the player's application thread. Created track selectors are updated in place,
 * which lets the player switch between platform and FFmpeg based decoders without being rebuilt.
 */
@UnstableApi
public final class DecoderManager implements TrackSelector.Factory {

  private final List<DecodeTrackSelector> trackSelectors;

  private DecoderMode audioMode;
  private DecoderMode videoMode;

  @Nullable private ExoPlayer player;

  /**
   * Creates a manager holding the given initial decoder modes.
   *
   * @param audioMode The initial decoder mode for audio tracks.
   * @param videoMode The initial decoder mode for video tracks.
   */
  public DecoderManager(DecoderMode audioMode, DecoderMode videoMode) {
    this.trackSelectors = new ArrayList<>();
    this.audioMode = audioMode;
    this.videoMode = videoMode;
  }

  /**
   * Attaches the manager to a player. Mode changes are only accepted on the application thread of
   * the attached player.
   *
   * @param player The player this manager is used with.
   */
  public synchronized void attach(ExoPlayer player) {
    Assertions.checkState(this.player == null, "Already attached");
    this.player = player;
  }

  /** Detaches the manager from its player. */
  public synchronized void detach() {
    checkApplicationThread();
    player = null;
  }

  /** Returns the current decoder mode for audio tracks. */
  public DecoderMode getAudioMode() {
    return audioMode;
  }

  /** Returns the current decoder mode for video tracks. */
  public DecoderMode getVideoMode() {
    return videoMode;
  }

  /**
   * Sets the decoder mode for audio tracks, keeping the video mode unchanged.
   *
   * @param audioMode The decoder mode for audio tracks.
   */
  public synchronized void selectAudioDecoder(DecoderMode audioMode) {
    setDecoderModes(audioMode, this.videoMode);
  }

  /**
   * Sets the decoder mode for video tracks, keeping the audio mode unchanged.
   *
   * @param videoMode The decoder mode for video tracks.
   */
  public synchronized void selectVideoDecoder(DecoderMode videoMode) {
    setDecoderModes(this.audioMode, videoMode);
  }

  /**
   * Sets the decoder modes for audio and video tracks and updates all track selectors created by
   * this manager.
   *
   * @param audioMode The decoder mode for audio tracks.
   * @param videoMode The decoder mode for video tracks.
   */
  public synchronized void setDecoderModes(DecoderMode audioMode, DecoderMode videoMode) {
    checkApplicationThread();
    if (this.audioMode == audioMode && this.videoMode == videoMode) {
      return;
    }
    this.audioMode = audioMode;
    this.videoMode = videoMode;
    for (int i = 0; i < trackSelectors.size(); i++) {
      DecodeTrackSelector trackSelector = trackSelectors.get(i);
      trackSelector.setRendererDecodePreferences(
          getDecodeMode(audioMode), getDecodeMode(videoMode));
    }
  }

  /**
   * Creates a {@link DecodeTrackSelector} initialized with the current decoder modes. The selector
   * is kept referenced so that later mode changes are applied to it.
   *
   * @param context Any {@link Context}.
   * @return A new {@link DecodeTrackSelector}.
   */
  @Override
  public synchronized DecodeTrackSelector createTrackSelector(Context context) {
    DecodeTrackSelector trackSelector = new DecodeTrackSelector(context);
    trackSelector.setRendererDecodePreferences(getDecodeMode(audioMode), getDecodeMode(videoMode));
    trackSelectors.add(trackSelector);
    return trackSelector;
  }

  private void checkApplicationThread() {
    if (player != null) {
      Looper applicationLooper = player.getApplicationLooper();
      Assertions.checkState(Looper.myLooper() == applicationLooper);
    }
  }

  private static @C.DecodeMode int getDecodeMode(DecoderMode decoderMode) {
    switch (decoderMode) {
      case AUTO:
      case HARDWARE:
        return C.DECODE_HARDWARE;
      case SOFTWARE:
      case FFMPEG:
      default:
        return C.DECODE_SOFTWARE;
    }
  }
}
