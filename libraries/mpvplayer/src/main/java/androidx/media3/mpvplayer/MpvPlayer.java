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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceView;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.common.text.SubtitleSelectionState;
import androidx.media3.common.text.SubtitleOffsets;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.DiscMenuController;
import androidx.media3.common.DiscMenuHighlight;
import androidx.media3.common.DiscMenuState;
import androidx.media3.common.DiscMenuOverlay;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.audio.MpvAudioFilter;
import androidx.media3.mpvplayer.audio.MpvAudioFocusManager;
import androidx.media3.mpvplayer.core.MpvEffectController;
import androidx.media3.mpvplayer.core.MpvPlaybackEventState;
import androidx.media3.mpvplayer.core.MpvPlaybackPropertyUpdater;
import androidx.media3.mpvplayer.core.MpvPlaybackState;
import androidx.media3.mpvplayer.core.MpvPlayerInfo;
import androidx.media3.mpvplayer.core.MpvStateBuilder;
import androidx.media3.mpvplayer.media.MpvArtworkLoader;
import androidx.media3.mpvplayer.media.MpvChapterController;
import androidx.media3.mpvplayer.media.MpvEditionController;
import androidx.media3.mpvplayer.media.MpvEndFileGuard;
import androidx.media3.mpvplayer.media.MpvMediaLoader;
import androidx.media3.mpvplayer.media.MpvPlaylist;
import androidx.media3.mpvplayer.media.MpvSubtitleController;
import androidx.media3.mpvplayer.media.MpvTimelineController;
import androidx.media3.mpvplayer.nativebridge.MpvCommandDispatcher;
import androidx.media3.mpvplayer.nativebridge.MpvNativeLifecycle;
import androidx.media3.mpvplayer.nativebridge.MpvPlaybackErrorFactory;
import androidx.media3.mpvplayer.nativebridge.MpvPlaybackProperties;
import androidx.media3.mpvplayer.options.MpvOptions;
import androidx.media3.mpvplayer.seek.MpvSeekController;
import androidx.media3.mpvplayer.trackselection.MpvTrackController;
import androidx.media3.mpvplayer.video.MpvSurfaceController;
import androidx.media3.mpvplayer.video.MpvVideoEqualizer;
import androidx.media3.mpvplayer.video.MpvVideoState;
import androidx.media3.mpvplayer.video.MpvVideoTrackEnableGate;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class MpvPlayer extends SimpleBasePlayer implements DiscMenuController {

  /** No audio output is currently initialized. */
  public static final int AUDIO_EFFECTS_UNAVAILABLE = 0;

  /** Audio effects are supported. */
  public static final int AUDIO_EFFECTS_SUPPORTED = 1;

  /** The audio output is using encoded passthrough. */
  public static final int AUDIO_EFFECTS_UNSUPPORTED_PASSTHROUGH = 2;

  /** Support for applying audio effects to the currently initialized audio output. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    AUDIO_EFFECTS_UNAVAILABLE,
    AUDIO_EFFECTS_SUPPORTED,
    AUDIO_EFFECTS_UNSUPPORTED_PASSTHROUGH
  })
  public @interface AudioEffectsSupport {}

  /** No video track is currently selected. */
  public static final int VIDEO_EFFECTS_UNAVAILABLE = 0;

  /** Video effects are supported. */
  public static final int VIDEO_EFFECTS_SUPPORTED = 1;

  /** Direct Dolby Vision output bypasses video effects. */
  public static final int VIDEO_EFFECTS_UNSUPPORTED_DIRECT_DOLBY_VISION_OUTPUT = 2;

  /** Support for applying video effects to the currently selected video track. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    VIDEO_EFFECTS_UNAVAILABLE,
    VIDEO_EFFECTS_SUPPORTED,
    VIDEO_EFFECTS_UNSUPPORTED_DIRECT_DOLBY_VISION_OUTPUT
  })
  public @interface VideoEffectsSupport {}

  private static final String VIDEO_OUTPUT_MEDIACODEC_EMBED = "mediacodec_embed";
  private static final ListenableFuture<?> COMPLETED = Futures.immediateVoidFuture();
  private final Context applicationContext;
  private final MpvPlaylist playlist;
  private final Handler handler;
  private final MpvNativeLifecycle nativeLifecycle;
  private final MpvPlaybackErrorFactory errorFactory;
  private final MpvOptions options;
  private final MpvPlaybackProperties playbackProperties;
  private final MpvEffectController effectController;
  private final MpvCommandDispatcher commandDispatcher;

  @Nullable private androidx.media3.mpvplayer.MpvSubtitleContentLoader subtitleContentLoader;
  @Nullable private MpvDecoderMode videoDecoderMode;
  @Nullable private DataSource.Factory discDataSourceFactory;
  @Nullable private LogListener logListener;
  private final java.util.Map<String, String> scriptData = new java.util.HashMap<>();
  private final java.util.Map<Long, ScriptLoadCallback> pendingScriptLoads = new java.util.HashMap<>();
  private long nextScriptClientId;
  @Nullable private SubtitleOffsets subtitleOffsets;
  @Nullable private TrackSelectionOverride primarySubtitleSelection;
  @Nullable private TrackSelectionOverride secondarySubtitleSelection;
  private boolean subtitleContentEnabled = true;
  private boolean secondaryTextAutoSelection = true;
  private boolean verboseLoggingEnabled;
  private int cachedDiscNavigationActive = -1;
  private int cachedDiscMenuActive = -1;
  private int cachedDiscMenuSupported = -1;
  private DiscMenuState cachedDiscMenuState = DiscMenuState.EMPTY;
  private final MpvSurfaceController surfaceController;
  private final MpvAudioFocusManager audioFocusManager;
  private final MpvTrackController trackController;
  private final MpvPlaybackState playbackState;
  private final MpvPlayerInfo playerInfo;
  private final MpvPlaybackEventState playbackEventState;
  private final MpvPlaybackPropertyUpdater propertyUpdater;
  private final MpvVideoState videoState;
  private final MpvVideoTrackEnableGate videoTrackEnableGate;
  private final MpvEndFileGuard endFileGuard;
  private final MpvChapterController chapterController;
  private final MpvEditionController editionController;
  private final MpvSubtitleController subtitleController;
  private final MpvMediaLoader mediaLoader;
  private final MpvArtworkLoader artworkLoader;
  private final MpvTimelineController timelineController;
  private final MpvStateBuilder stateBuilder;
  private final MpvSeekController seekController;
  private @C.DecodeMode int decode;
  @Nullable private AudioOutputListener audioOutputListener;
  private boolean handlingPlayerCommand;

  private MpvPlayer(
      Context context,
      MpvPlayerConfig config,
      @C.DecodeMode int decode,
      Looper looper,
      @Nullable MpvDecoderMode videoDecoderMode,
      @Nullable DataSource.Factory discDataSourceFactory) {
    super(looper);
    this.applicationContext = context.getApplicationContext();
    this.handler = Util.createHandler(getApplicationLooper(), null);
    MpvPlayerComponents components =
        MpvPlayerComponents.create(this.applicationContext, this, config, this.handler);
    this.errorFactory = components.errorFactory;
    this.videoDecoderMode = videoDecoderMode;
    this.discDataSourceFactory = discDataSourceFactory;
    this.options = components.options;
    this.playbackProperties = components.playbackProperties;
    this.effectController = components.effectController;
    this.commandDispatcher = components.commandDispatcher;
    this.surfaceController = components.surfaceController;
    this.audioFocusManager = components.audioFocusManager;
    this.trackController = components.trackController;
    this.playbackState = components.playbackState;
    this.playerInfo = components.playerInfo;
    this.playbackEventState = components.playbackEventState;
    this.videoState = components.videoState;
    this.videoTrackEnableGate = components.videoTrackEnableGate;
    this.endFileGuard = components.endFileGuard;
    this.chapterController = components.chapterController;
    this.editionController = components.editionController;
    this.playlist = components.playlist;
    this.subtitleController = components.subtitleController;
    this.mediaLoader = components.mediaLoader;
    this.artworkLoader = components.artworkLoader;
    this.timelineController = components.timelineController;
    this.propertyUpdater = components.propertyUpdater;
    this.seekController = components.seekController;
    this.nativeLifecycle = components.nativeLifecycle;
    this.stateBuilder = components.stateBuilder;
    this.decode = decode;
  }

  public static boolean isAvailable() {
    return MpvLibrary.isAvailable();
  }

  public void setDecode(@C.DecodeMode int decode) {
    runOnPlayerLooper(() -> setDecodeInternal(decode));
  }

  public boolean addSubtitle(MediaItem.SubtitleConfiguration subtitle) {
    verifyApplicationThread();
    return !playerInfo.isReleased() && subtitleController.addSubtitle(checkNotNull(subtitle), true);
  }

  @Override
  protected boolean handleSelectChapter(MediaChapter chapter) {
    verifyApplicationThread();
    @Nullable MediaChapter currentChapter = chapterController.getChapter(chapter.index);
    if (playerInfo.isReleased()
        || currentChapter == null
        || currentChapter.timeUs == C.TIME_UNSET) {
      return false;
    }
    runOnPlayerLooper(
        () -> {
          if (chapterController.selectChapter(currentChapter.index)) {
            playbackState.setPositionMs(Util.usToMs(currentChapter.timeUs));
            invalidatePlayerState();
          }
        });
    return true;
  }

  @Override
  protected boolean handleSelectEdition(MediaEdition edition) {
    verifyApplicationThread();
    @Nullable MediaEdition currentEdition = editionController.getEdition(edition.index);
    if (playerInfo.isReleased() || currentEdition == null) {
      return false;
    }
    if (currentEdition.selected) {
      return true;
    }
    runOnPlayerLooper(
        () -> {
          if (editionController.selectEdition(currentEdition.index)) {
            chapterController.clear();
            invalidatePlayerState();
          }
        });
    return true;
  }

  private void setDecodeInternal(@C.DecodeMode int decode) {
    if (this.decode == decode) {
      return;
    }
    this.decode = decode;
    if (!nativeLifecycle.isInitialized()) {
      return;
    }
    boolean hardwareDecodeEnabled = isHardwareDecodeEnabled();
    @Nullable
    String hardwareDecode =
        options.setHardwareDecodeEnabled(
            hardwareDecodeEnabled, playbackProperties.getHardwareDecode());
    if (!hardwareDecodeEnabled) {
      applyRuntimeDolbyVisionOutputMode();
    }
    playbackProperties.setHardwareDecode(hardwareDecode);
    if (hardwareDecodeEnabled) {
      applyRuntimeDolbyVisionOutputMode();
    }
  }

  public void setSubtitleOptions(MpvSubtitleOptions subtitleOptions) {
    MpvPlayerConfig config =
        new MpvPlayerConfig.Builder()
            .addAndroidSubtitleOptions(applicationContext, checkNotNull(subtitleOptions))
            .build();
    runOnPlayerLooper(() -> subtitleController.setOptions(config));
  }

  public void setOsdSurfaceView(SurfaceView surfaceView) {
    runOnPlayerLooper(() -> surfaceController.setOsdOutput(checkNotNull(surfaceView)));
  }

  public void clearOsdSurfaceView(@Nullable SurfaceView surfaceView) {
    runOnPlayerLooper(() -> surfaceController.clearOsdOutput(surfaceView));
  }

  public void setVideoEqualizer(MpvVideoEqualizer videoEqualizer) {
    MpvVideoEqualizer requestedVideoEqualizer = checkNotNull(videoEqualizer);
    @VideoEffectsSupport int support = getVideoEffectsSupport();
    MpvVideoEqualizer checkedVideoEqualizer =
        support == VIDEO_EFFECTS_UNSUPPORTED_DIRECT_DOLBY_VISION_OUTPUT
            ? MpvVideoEqualizer.DEFAULT
            : sanitizeVideoEqualizer(requestedVideoEqualizer, options.isVideoSharpnessSupported());
    runOnPlayerLooper(() -> effectController.setVideoEqualizer(checkedVideoEqualizer));
  }

  /** Returns video effect support for the currently selected output and video track. */
  public @VideoEffectsSupport int getVideoEffectsSupport() {
    verifyApplicationThread();
    return playerInfo.isReleased()
        ? VIDEO_EFFECTS_UNAVAILABLE
        : getVideoEffectsSupport(playbackProperties.getCurrentVideoOutput());
  }

  public boolean isVideoSharpnessSupported() {
    verifyApplicationThread();
    return options.isVideoSharpnessSupported();
  }

  static @VideoEffectsSupport int getVideoEffectsSupport(@Nullable String currentVideoOutput) {
    if (currentVideoOutput == null || currentVideoOutput.isEmpty()) {
      return VIDEO_EFFECTS_UNAVAILABLE;
    }
    return VIDEO_OUTPUT_MEDIACODEC_EMBED.equals(currentVideoOutput)
        ? VIDEO_EFFECTS_UNSUPPORTED_DIRECT_DOLBY_VISION_OUTPUT
        : VIDEO_EFFECTS_SUPPORTED;
  }

  static MpvVideoEqualizer sanitizeVideoEqualizer(
      MpvVideoEqualizer videoEqualizer, boolean sharpnessSupported) {
    if (sharpnessSupported || videoEqualizer.getSharpness() == 0.0) {
      return videoEqualizer;
    }
    return MpvVideoEqualizer.create(
        videoEqualizer.getBrightness(),
        videoEqualizer.getContrast(),
        videoEqualizer.getSaturation(),
        videoEqualizer.getGamma(),
        videoEqualizer.getHue(),
        /* sharpness= */ 0.0);
  }

  @CanIgnoreReturnValue
  public boolean setAudioFilter(MpvAudioFilter audioFilter) {
    verifyApplicationThread();
    if (playerInfo.isReleased()) {
      return false;
    }
    return effectController.setAudioFilter(checkNotNull(audioFilter));
  }

  public void setAudioOutputListener(@Nullable AudioOutputListener listener) {
    verifyApplicationThread();
    if (!playerInfo.isReleased()) {
      audioOutputListener = listener;
    }
  }

  public int getAudioChannelCount() {
    verifyApplicationThread();
    if (playerInfo.isReleased()) {
      return Format.NO_VALUE;
    }
    @Nullable Integer channelCount = playbackProperties.getAudioChannelCount();
    return channelCount != null && channelCount > 0 ? channelCount : Format.NO_VALUE;
  }

  /** Returns audio effect support for the currently initialized audio output. */
  public @AudioEffectsSupport int getAudioEffectsSupport() {
    verifyApplicationThread();
    return playerInfo.isReleased()
        ? AUDIO_EFFECTS_UNAVAILABLE
        : getAudioEffectsSupport(playbackProperties.getAudioOutputFormat());
  }

  static @AudioEffectsSupport int getAudioEffectsSupport(@Nullable String audioOutputFormat) {
    if (audioOutputFormat == null || audioOutputFormat.isEmpty()) {
      return AUDIO_EFFECTS_UNAVAILABLE;
    }
    return audioOutputFormat.startsWith("spdif-")
        ? AUDIO_EFFECTS_UNSUPPORTED_PASSTHROUGH
        : AUDIO_EFFECTS_SUPPORTED;
  }

  @CanIgnoreReturnValue
  public boolean toggleGeneralStats() {
    verifyApplicationThread();
    return !playerInfo.isReleased()
        && nativeLifecycle.isInitialized()
        && commandDispatcher.toggleGeneralStats();
  }

  @Override
  protected State getState() {
    boolean reportRenderedFirstFrame = videoState.consumeFirstFrameEvent();
    State.Builder builder =
        stateBuilder.buildBaseState(
            playerInfo.createStateSnapshot(
                trackController.getParameters(), reportRenderedFirstFrame));
    builder.setDiscMenuState(computeDiscMenuState());
    if (!playlist.isEmpty()) {
      builder.setCurrentMediaItemIndex(playlist.currentIndex());
    }
    if (playerInfo.hasPendingDiscontinuity()) {
      builder.setPositionDiscontinuity(
          playerInfo.pendingDiscontinuityReason(), playerInfo.pendingDiscontinuityMs());
      playerInfo.clearPendingDiscontinuity();
    }
    return builder.build();
  }

  @Override
  protected ListenableFuture<?> handleSetMediaItems(
      List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    return handleCompletedCommand(
        () -> timelineController.setMediaItems(mediaItems, startIndex, startPositionMs));
  }

  @Override
  protected ListenableFuture<?> handlePrepare() {
    return handlePlayerCommand(this::prepareCurrentItem);
  }

  private ListenableFuture<?> prepareCurrentItem() {
    MediaItem item = playlist.current();
    if (item == null && playlist.isEmpty()) {
      playbackState.setEnded();
      playerInfo.clearPlayerError();
      invalidatePlayerState();
      return COMPLETED;
    }
    if (item == null) {
      return fail(
          errorFactory.create("No media item", null, PlaybackException.ERROR_CODE_BAD_VALUE));
    }
    if (!nativeLifecycle.ensureInitialized()) {
      return COMPLETED;
    }
    loadCurrent(item, playbackState.getPositionMs());
    return COMPLETED;
  }

  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setPlayWhenReady(
              playWhenReady,
              Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
              Player.PLAYBACK_SUPPRESSION_REASON_NONE);
          audioFocusManager.setPlayWhenReady(playWhenReady);
          setPauseProperty();
          seekController.clearTransitionFlags();
        });
  }

  @Override
  protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
    return handlePlayerCommand(
        () -> {
          long targetPositionMs =
              seekController.resolvePositionMs(mediaItemIndex, playlist.currentIndex(), positionMs);
          ListenableFuture<?> result = COMPLETED;
          if (isSeekToDifferentMediaItem(mediaItemIndex)) {
            if (!playlist.isValidIndex(mediaItemIndex)) {
              return COMPLETED;
            }
            seekToMediaItem(mediaItemIndex, targetPositionMs);
          } else {
            result = seekController.seekToPositionMs(targetPositionMs, seekCommand);
          }
          invalidatePlayerState();
          return result;
        });
  }

  private boolean isSeekToDifferentMediaItem(int mediaItemIndex) {
    return mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != playlist.currentIndex();
  }

  private void seekToMediaItem(int mediaItemIndex, long positionMs) {
    seekController.reset();
    playlist.setCurrentIndex(mediaItemIndex);
    playbackState.setPositionMs(positionMs);
    if (nativeLifecycle.isInitialized()) {
      loadCurrent(playlist.get(playlist.currentIndex()), playbackState.getPositionMs());
    }
  }

  @Override
  protected ListenableFuture<?> handleStop() {
    return handleCompletedCommand(
        () -> {
          nativeLifecycle.cancelPendingInitialization();
          long positionMs = playbackState.getPositionMs();
          if (stopActiveFile()) {
            releaseAudioFocus();
            playbackState.setIdle();
            resetCurrentMediaState(positionMs);
            playbackState.clearPendingSeek();
          }
        });
  }

  @Override
  protected ListenableFuture<?> handleRelease() {
    return handlePlayerCommand(
        () -> {
          if (!playerInfo.markReleased()) {
            return COMPLETED;
          }
          audioOutputListener = null;
          artworkLoader.release();
          handler.removeCallbacksAndMessages(null);
          resetSeekState();
          clearTransitionFlags();
          releaseAudioFocus();
          surfaceController.release();
          nativeLifecycle.release();
          return COMPLETED;
        });
  }

  @Override
  protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setRepeatMode(repeatMode);
          if (nativeLifecycle.isInitialized()) {
            playbackProperties.disableNativeLoop();
          }
        });
  }

  @Override
  protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
    return handleCompletedCommand(
        () -> {
          PlaybackParameters previousPlaybackParameters = playerInfo.getPlaybackParameters();
          playerInfo.setPlaybackParameters(playbackParameters);
          playbackProperties.updatePlaybackParameters(
              previousPlaybackParameters, playbackParameters);
        });
  }

  @Override
  protected ListenableFuture<?> handleSetTrackSelectionParameters(
      TrackSelectionParameters parameters) {
    return handleCompletedCommand(
        () -> {
          videoTrackEnableGate.onParametersChanging(parameters);
          trackController.setParameters(parameters);
          videoTrackEnableGate.onTrackSelectionApplied();
        });
  }

  @Override
  protected ListenableFuture<?> handleSetAudioOffsetMs(long audioOffsetMs) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setAudioOffsetMs(audioOffsetMs);
          playbackProperties.setAudioDelayMs(playerInfo.getAudioOffsetMs());
        });
  }

  @Override
  protected ListenableFuture<?> handleSetTextOffsetMs(long textOffsetMs) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setTextOffsetMs(textOffsetMs);
          playbackProperties.setTextDelayMs(playerInfo.getTextOffsetMs());
        });
  }

  @Override
  protected ListenableFuture<?> handleSetVolume(float volume, int flags) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setVolume(volume);
          setVolumeProperty();
        });
  }

  @Override
  protected ListenableFuture<?> handleSetAudioAttributes(
      AudioAttributes audioAttributes, boolean handleAudioFocus) {
    return handleCompletedCommand(
        () -> {
          playerInfo.setAudioAttributes(audioAttributes);
          audioFocusManager.setAudioAttributes(handleAudioFocus ? audioAttributes : null);
          if (playerInfo.isPlayWhenReady()) {
            audioFocusManager.requestForPlayback();
          }
        });
  }

  @Override
  protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
    return handleCompletedCommand(() -> surfaceController.setVideoOutput(videoOutput));
  }

  @Override
  protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
    return handleCompletedCommand(() -> surfaceController.clearVideoOutput(videoOutput));
  }

  @Override
  protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
    return handleCompletedCommand(() -> timelineController.addMediaItems(index, mediaItems));
  }

  @Override
  protected ListenableFuture<?> handleMoveMediaItems(int fromIndex, int toIndex, int newIndex) {
    return handleCompletedCommand(
        () -> timelineController.moveMediaItems(fromIndex, toIndex, newIndex));
  }

  @Override
  protected ListenableFuture<?> handleReplaceMediaItems(
      int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    return handleCompletedCommand(
        () -> timelineController.replaceMediaItems(fromIndex, toIndex, mediaItems));
  }

  @Override
  protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
    return handleCompletedCommand(() -> timelineController.removeMediaItems(fromIndex, toIndex));
  }

  void loadCurrent(MediaItem item, long startPositionMs) {
    mediaLoader.load(item, startPositionMs);
  }

  void onShutdown() {
    nativeLifecycle.onShutdown();
  }

  void onNativeSessionAvailable() {
    prepareCurrentItem();
  }

  void onAudioOutputChanged() {
    @Nullable AudioOutputListener listener = audioOutputListener;
    if (listener != null) {
      listener.onAudioOutputChanged();
    }
    effectController.onAudioOutputChanged();
  }

  void handleUnexpectedNativeShutdown() {
    fail(errorFactory.createUnexpectedShutdown());
  }

  boolean stopActiveFile() {
    if (hasActiveMpvFile()) {
      expectProgrammaticEndFile();
      if (!stopMpv()) {
        endFileGuard.cancelExpected();
        fail(errorFactory.createCommandFailure("stop"));
        return false;
      }
    }
    playbackState.stopActiveFile();
    seekController.reset();
    clearTransitionFlags();
    return true;
  }

  boolean hasActiveMpvFile() {
    if (!nativeLifecycle.isInitialized()) {
      return false;
    }
    return playbackProperties.hasActiveFile(playbackState.hasActiveFile(/* initialized= */ true));
  }

  void expectProgrammaticEndFile() {
    if (nativeLifecycle.isInitialized()) {
      endFileGuard.expect();
    }
  }

  void resetSeekState() {
    seekController.reset();
  }

  ListenableFuture<?> fail(PlaybackException error) {
    playerInfo.setPlayerError(error);
    playbackState.fail();
    endFileGuard.clear();
    resetSeekState();
    clearTransitionFlags();
    releaseAudioFocus();
    invalidatePlayerState();
    return COMPLETED;
  }

  boolean maybeLoadNextAfterEnd() {
    return timelineController.maybeLoadNextAfterEnd(playerInfo.getRepeatMode());
  }

  void resetCurrentMediaState(long startPositionMs) {
    playbackState.resetCurrentMedia(startPositionMs);
    resetCurrentMediaState();
  }

  void resetCurrentMediaState() {
    clearTransitionFlags();
    chapterController.clear();
    editionController.clear();
    trackController.clear();
    videoState.reset();
    artworkLoader.clear();
  }

  void clearTransitionFlags() {
    seekController.clearTransitionFlags();
    videoTrackEnableGate.clear();
    playbackEventState.setIgnoreRepeatOneBuffering(false);
  }

  void resetRenderedFirstFrame() {
    videoState.resetRenderedFirstFrame();
  }

  void setVolumeProperty() {
    playbackProperties.setVolume(playerInfo.getVolume(), audioFocusManager.getVolumeMultiplier());
  }

  void setPauseProperty() {
    playbackProperties.setPlayWhenReady(playerInfo.shouldPlay());
  }

  boolean isHardwareDecodeEnabled() {
    return decode == C.DECODE_HARDWARE;
  }

  void setPendingDiscontinuity(@Player.DiscontinuityReason int reason, long positionMs) {
    playerInfo.setPendingDiscontinuity(reason, positionMs);
  }

  boolean stopMpv() {
    return !nativeLifecycle.isInitialized() || commandDispatcher.stop();
  }

  void releaseAudioFocus() {
    audioFocusManager.release();
  }

  private ListenableFuture<?> handleCompletedCommand(Runnable command) {
    return handlePlayerCommand(
        () -> {
          command.run();
          invalidatePlayerState();
          return COMPLETED;
        });
  }

  void setDirectVideoOutputConfigured(boolean configured) {
    if (options.setDirectVideoOutputConfigured(configured)) {
      applyRuntimeDolbyVisionOutputMode();
    }
  }

  void setDirectVideoDisplay(@Nullable Display display) {
    if (options.setDirectVideoDisplay(display)) {
      applyRuntimeDolbyVisionOutputMode();
    }
  }

  void setDirectOsdOutputConfigured(boolean configured) {
    if (options.setDirectOsdOutputConfigured(configured)) {
      applyRuntimeDolbyVisionOutputMode();
    }
  }

  private void applyRuntimeDolbyVisionOutputMode() {
    if (nativeLifecycle.isInitialized()) {
      options.applyRuntimeDolbyVisionOutputMode(playbackProperties::setStringOptionOrProperty);
    }
  }

  private ListenableFuture<?> handlePlayerCommand(PlayerCommand command) {
    handlingPlayerCommand = true;
    try {
      return command.run();
    } finally {
      handlingPlayerCommand = false;
    }
  }

  boolean isMpvInitialized() {
    return nativeLifecycle.isInitialized();
  }

  void invalidatePlayerState() {
    if (!handlingPlayerCommand) {
      invalidateState();
    }
  }

  boolean isPlayWhenReadyInternal() {
    return playerInfo.isPlayWhenReady();
  }

  @Player.PlaybackSuppressionReason
  int getPlaybackSuppressionReasonInternal() {
    return playerInfo.getPlaybackSuppressionReason();
  }

  void setPlayWhenReadyInternal(
      boolean playWhenReady,
      @Player.PlayWhenReadyChangeReason int changeReason,
      @Player.PlaybackSuppressionReason int suppressionReason) {
    playerInfo.setPlayWhenReady(playWhenReady, changeReason, suppressionReason);
  }

  void setPlaybackSuppressionReasonInternal(
      @Player.PlaybackSuppressionReason int suppressionReason) {
    playerInfo.setPlaybackSuppressionReason(suppressionReason);
  }

  @Player.State
  int getInternalPlaybackState() {
    return playbackState.getState();
  }

  boolean hasInternalVideoTrack() {
    return trackController.haveTrack(C.TRACK_TYPE_VIDEO);
  }

  boolean isInternalVideoTrackSelected() {
    return trackController.isTrackTypeSelected(C.TRACK_TYPE_VIDEO);
  }

  void runOnPlayerLooper(Runnable runnable) {
    if (playerInfo.isReleased()) {
      return;
    }
    Util.postOrRun(
        handler,
        () -> {
          if (!playerInfo.isReleased()) {
            runnable.run();
          }
        });
  }

  void runOnPlayerLooperAfterRelease(Runnable runnable) {
    Util.postOrRun(handler, runnable);
  }

  void runOnPlayerLooperAndWait(Runnable runnable) {
    if (Looper.myLooper() == handler.getLooper()) {
      if (!playerInfo.isReleased()) {
        runnable.run();
      }
      return;
    }
    ConditionVariable completed = new ConditionVariable();
    if (!handler.post(
        () -> {
          try {
            if (!playerInfo.isReleased()) {
              runnable.run();
            }
          } finally {
            completed.open();
          }
        })) {
      return;
    }
    completed.blockUninterruptible();
  }

  private interface PlayerCommand {

    ListenableFuture<?> run();
  }

  public interface AudioOutputListener {

    void onAudioOutputChanged();
  }

  public static final class Builder {

    private final Context context;
    private MpvPlayerConfig config;
    private Looper looper;
    private @C.DecodeMode int decode;
    @Nullable private MpvDecoderMode videoDecoderMode;
    @Nullable private DataSource.Factory discDataSourceFactory;
    private boolean buildCalled;

    public Builder(Context context) {
      this.context = checkNotNull(context).getApplicationContext();
      this.config = new MpvPlayerConfig.Builder().build();
      this.looper = Looper.getMainLooper();
    }

    @CanIgnoreReturnValue
    public Builder setConfig(MpvPlayerConfig config) {
      checkState(!buildCalled);
      this.config = checkNotNull(config);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setDecode(@C.DecodeMode int decode) {
      checkState(!buildCalled);
      this.decode = decode;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setLooper(Looper looper) {
      checkState(!buildCalled);
      this.looper = checkNotNull(looper);
      return this;
    }

    /** Sets the initial video decoder mode for mpv. */
    @CanIgnoreReturnValue
    public Builder setVideoDecoderMode(MpvDecoderMode videoDecoderMode) {
      this.videoDecoderMode = videoDecoderMode;
      return this;
    }

    /** Sets the data source factory used for disc based sources. */
    @CanIgnoreReturnValue
    public Builder setDiscDataSourceFactory(@Nullable DataSource.Factory dataSourceFactory) {
      this.discDataSourceFactory = dataSourceFactory;
      return this;
    }

    public MpvPlayer build() {
      checkState(!buildCalled);
      buildCalled = true;
      return new MpvPlayer(context, config, decode, looper, videoDecoderMode, discDataSourceFactory);
    }
  }

  // DiscMenuController implementation, driven by mpv's built-in disc navigation.

  @Override
  public boolean open(String action, Consumer<OpenResult> callback) {
    if (!isSupportedDiscMenuAction(action) || playlist.currentUri() == null) {
      callback.accept(OpenResult.UNAVAILABLE);
      return false;
    }
    boolean dispatched = commandDispatcher.command("discnav", action);
    callback.accept(dispatched ? OpenResult.OPENED : OpenResult.UNAVAILABLE);
    return dispatched;
  }

  @Override
  public boolean sendAction(String action) {
    if (!isSupportedDiscMenuAction(action)) {
      return false;
    }
    return commandDispatcher.command("discnav", action);
  }

  @Override
  public boolean isActive() {
    Boolean active = commandDispatcher.getPropertyBoolean("disc-menu-active");
    return active != null && active;
  }

  @Override
  public boolean isNavigationPlayback() {
    Boolean navigationActive = commandDispatcher.getPropertyBoolean("disc-navigation-active");
    if (navigationActive != null) {
      return navigationActive;
    }
    Uri currentUri = playlist.currentUri();
    if (currentUri == null) {
      return false;
    }
    String path = currentUri.getPath();
    return path != null && path.toLowerCase().endsWith(".iso");
  }

  /** Updates the cached disc navigation state from the observed mpv property. */
  /* package */ boolean onDiscNavigationActiveProperty(boolean navigationActive) {
    cachedDiscNavigationActive = navigationActive ? 1 : 0;
    return updateCachedDiscMenuState();
  }

  /** Updates the cached disc menu state from the observed mpv property. */
  /* package */ boolean onDiscMenuActiveProperty(boolean menuActive) {
    cachedDiscMenuActive = menuActive ? 1 : 0;
    return updateCachedDiscMenuState();
  }

  /** Updates the cached disc menu support state from the observed mpv property. */
  /* package */ boolean onDiscMenuSupportedProperty(boolean menuSupported) {
    cachedDiscMenuSupported = menuSupported ? 1 : 0;
    return updateCachedDiscMenuState();
  }

  /** Returns the current disc menu state, preferring values observed from mpv events. */
  private DiscMenuState computeDiscMenuState() {
    boolean navigationPlaybackOngoing =
        cachedDiscNavigationActive >= 0 ? cachedDiscNavigationActive == 1 : isNavigationPlayback();
    if (!navigationPlaybackOngoing) {
      return DiscMenuState.EMPTY;
    }
    int availability;
    if (cachedDiscMenuSupported >= 0) {
      availability =
          cachedDiscMenuSupported == 1
              ? DiscMenuState.AVAILABILITY_AVAILABLE
              : DiscMenuState.AVAILABILITY_UNAVAILABLE;
    } else {
      Boolean supported = commandDispatcher.getPropertyBoolean("disc-menu-supported");
      availability =
          supported == null
              ? DiscMenuState.AVAILABILITY_PENDING
              : supported
                  ? DiscMenuState.AVAILABILITY_AVAILABLE
                  : DiscMenuState.AVAILABILITY_UNAVAILABLE;
    }
    if (availability != DiscMenuState.AVAILABILITY_AVAILABLE) {
      return new DiscMenuState(
          availability, DiscMenuState.PHASE_IDLE, /* navigationPlaybackOngoing= */ true);
    }
    boolean menuActive =
        cachedDiscMenuActive >= 0
            ? cachedDiscMenuActive == 1
            : Boolean.TRUE.equals(commandDispatcher.getPropertyBoolean("disc-menu-active"));
    return new DiscMenuState(
        DiscMenuState.AVAILABILITY_AVAILABLE,
        menuActive ? DiscMenuState.PHASE_ACTIVE : DiscMenuState.PHASE_IDLE,
        /* navigationPlaybackOngoing= */ true);
  }

  private boolean updateCachedDiscMenuState() {
    DiscMenuState newState = computeDiscMenuState();
    if (cachedDiscMenuState.equals(newState)) {
      return false;
    }
    cachedDiscMenuState = newState;
    return true;
  }

  private static boolean isSupportedDiscMenuAction(String action) {
    switch (action) {
      case "up":
      case "down":
      case "left":
      case "right":
      case "select":
      case "menu":
      case "title-menu":
      case "popup":
      case "prev":
        return true;
      default:
        return false;
    }
  }

  // Subtitle transcript and secondary text support.

  /** Sets the loader used to fetch subtitle file content for the transcript outputs. */
  public void setSubtitleContentLoader(
      @Nullable androidx.media3.mpvplayer.MpvSubtitleContentLoader loader) {
    this.subtitleContentLoader = loader;
  }

  @Override
  public SubtitleSelectionState getSubtitleSelectionState() {
    return new SubtitleSelectionState(primarySubtitleSelection, secondarySubtitleSelection);
  }

  @Override
  public SubtitleContent getSubtitleContentForSelection(TrackSelectionOverride override) {
    androidx.media3.mpvplayer.MpvSubtitleContentLoader loader = subtitleContentLoader;
    MediaItem currentItem = getCurrentMediaItem();
    if (loader == null
        || currentItem == null
        || currentItem.localConfiguration == null
        || !subtitleContentEnabled
        || (override != primarySubtitleSelection && override != secondarySubtitleSelection)) {
      return SubtitleContent.UNSUPPORTED;
    }
    Format selectedFormat = override.mediaTrackGroup.getFormat(0);
    for (MediaItem.SubtitleConfiguration configuration :
        currentItem.localConfiguration.subtitleConfigurations) {
      if (!matchesSelection(configuration, selectedFormat)) {
        continue;
      }
      try {
        Format configurationFormat =
            new Format.Builder()
                .setId(configuration.id)
                .setLabel(configuration.label)
                .setSampleMimeType(configuration.mimeType)
                .setLanguage(configuration.language)
                .build();
        return loader.load(currentItem, configuration.uri, configurationFormat);
      } catch (IOException e) {
        return SubtitleContent.UNSUPPORTED;
      }
    }
    return SubtitleContent.UNSUPPORTED;
  }

  private static boolean matchesSelection(
      MediaItem.SubtitleConfiguration configuration, Format selectedFormat) {
    if (configuration.id != null && configuration.id.equals(selectedFormat.id)) {
      return true;
    }
    if (configuration.label != null && configuration.label.equals(selectedFormat.label)) {
      return true;
    }
    return configuration.mimeType != null
        && configuration.mimeType.equals(selectedFormat.sampleMimeType)
        && configuration.language != null
        && configuration.language.equals(selectedFormat.language);
  }

  @Override
  public SubtitleOffsets getSubtitleOffsets() {
    return subtitleOffsets == null ? SubtitleOffsets.ZERO : subtitleOffsets;
  }

  @Override
  public void setSubtitleOffsets(SubtitleOffsets offsets) {
    this.subtitleOffsets = offsets == null ? SubtitleOffsets.ZERO : offsets;
  }

  @Override
  public void setSubtitleContentEnabled(boolean enabled) {
    this.subtitleContentEnabled = enabled;
  }

  @Override
  public boolean canRetrySubtitleContent(Format format) {
    return subtitleContentLoader != null && subtitleContentEnabled;
  }

  @Override
  public void retrySubtitleContent(Format format) {
    // Content is fetched on demand by {@link #getSubtitleContentForSelection}; nothing to redo.
  }

  /** Sets the track selection feeding the secondary subtitle output, or null to disable it. */
  public void setSecondaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride override) {
    this.secondarySubtitleSelection = override;
  }

  /** Sets whether the secondary subtitle output picks its track automatically. */
  public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {
    this.secondaryTextAutoSelection = enabled;
    if (enabled) {
      this.secondarySubtitleSelection = null;
    }
  }

  /** Returns whether the secondary subtitle output picks its track automatically. */
  public boolean isSecondaryTextTrackAutoSelectionEnabled() {
    return secondaryTextAutoSelection;
  }

  /** Clears the secondary subtitle track selection. */
  public void resetSecondaryTextTrackSelection() {
    this.secondarySubtitleSelection = null;
    this.secondaryTextAutoSelection = true;
  }

  /** Sets the primary subtitle track selection, or null when none is forced. */
  public void setPrimaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride override) {
    this.primarySubtitleSelection = override;
  }

  public void setVerboseLoggingEnabled(boolean verboseLoggingEnabled) {
    this.verboseLoggingEnabled = verboseLoggingEnabled;
  }

  /** Returns whether verbose logging is enabled for this player. */
  public boolean isVerboseLoggingEnabled() {
    return verboseLoggingEnabled;
  }

  // Script, logging and decoder mode support.

  /** Callback receiving mpv log messages. */
  public interface LogListener {

    /**
     * Called for every log message at or above the subscribed level.
     *
     * @param prefix The mpv log prefix identifying the subsystem.
     * @param level The mpv log level.
     * @param text The log message text.
     */
    void onLogMessage(String prefix, int level, String text);
  }

  /** Callback receiving the outcome of a script load request. */
  public interface ScriptLoadCallback {

    /**
     * Called when a script load finished.
     *
     * @param success Whether the script was loaded.
     * @param clientId The client identifier of the loaded script, or 0 on failure.
     */
    void onLoadFinished(boolean success, long clientId);
  }

  /** Subscribes to mpv log messages at the given level, or removes the listener with null. */
  public void setLogListener(int level, @Nullable LogListener logListener) {
    this.logListener = logListener;
  }

  /** Dispatches a log message to the registered listener, if any. */
  public void dispatchLogMessage(String prefix, int level, String text) {
    LogListener listener = logListener;
    if (listener != null) {
      listener.onLogMessage(prefix, level, text);
    }
  }

  /** Sets the scripts mpv loads when the player initializes. */
  public boolean setStartupScripts(java.util.List<java.io.File> scripts) {
    return commandDispatcher.setStartupScripts(scripts);
  }

  /** Loads a script at runtime and reports the outcome to {@code callback}. */
  public boolean loadScript(String path, @Nullable ScriptLoadCallback callback) {
    long clientId = ++nextScriptClientId;
    boolean dispatched = commandDispatcher.loadScript(path);
    if (callback != null) {
      if (dispatched) {
        pendingScriptLoads.put(clientId, callback);
      } else {
        callback.onLoadFinished(false, 0);
      }
    }
    return dispatched;
  }

  /** Unloads the script with the given client identifier. */
  public void unloadScript(long clientId) {
    pendingScriptLoads.remove(clientId);
    commandDispatcher.unloadScript(clientId);
  }

  /** Returns the value stored by a script under the given key, or an empty string. */
  public String getScriptData(String key) {
    String value = scriptData.get(key);
    return value == null ? "" : value;
  }

  /** Stores a script data value. Called by the scripting bridge. */
  public void putScriptData(String key, String value) {
    scriptData.put(key, value);
  }

  /** Returns the current input bindings configuration as JSON. */
  public String getInputBindings() {
    return commandDispatcher.getInputBindings();
  }

  /** Invokes an mpv script binding by name. */
  public boolean runScriptBinding(String binding) {
    return commandDispatcher.runScriptBinding(binding);
  }

  /** Returns the current video decoder mode. */
  public MpvDecoderMode getVideoDecoderMode() {
    MpvDecoderMode mode = videoDecoderMode;
    return mode == null ? MpvDecoderMode.AUTO : mode;
  }

  /** Sets the video decoder mode used for subsequent files. */
  public void setVideoDecoderMode(MpvDecoderMode mode) {
    this.videoDecoderMode = mode;
    if (mode != null) {
      commandDispatcher.setVideoDecoderMode(mode);
    }
  }

  /** Sets the data source factory used for disc based sources. */
  public void setDiscDataSourceFactory(@Nullable DataSource.Factory dataSourceFactory) {
    this.discDataSourceFactory = dataSourceFactory;
  }

  /** Returns the data source factory used for disc based sources, or null. */
  @Nullable
  public DataSource.Factory getDiscDataSourceFactory() {
    return discDataSourceFactory;
  }
}
