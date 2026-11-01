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

import android.graphics.Rect;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.text.SubtitleOffsets;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.exoplayer.text.SubtitleTranscript;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A playback session of the libass subtitle renderer.
 *
 * <p>The session owns the native libass state of the {@linkplain MediaItem media items} of a
 * player, renders ASS subtitles into a {@link LibassRenderView} and provides the media source
 * components that feed subtitle data into the renderer.
 */
@UnstableApi
public final class LibassPlaybackSession implements AutoCloseable {

  private final LibassConfiguration configuration;
  private final boolean libassEnabled;
  private final HashMap<MediaItem, LibassMediaSession> mediaSessions;
  private final HashSet<MediaItem> retainedMediaItems;
  private final Object lock;

  private float bottomPositionFraction;
  private float secondaryBottomPositionFraction;
  private float fontScale;
  private boolean applyFontScale;
  @Nullable private String defaultFontFamily;
  @Nullable private LibassStyleOverride styleOverride;
  @Nullable private String styleOverrideFontFamily;

  @Nullable private LibassMediaSession currentMediaSession;
  @Nullable private LibassMediaSession secondaryMediaSession;
  @Nullable private LibassMediaSession preloadMediaSession;
  @Nullable private MediaItem currentMediaItem;
  @Nullable private MediaItem preloadMediaItem;
  @Nullable private Player player;
  @Nullable private WeakReference<LibassRenderView> renderViewReference;

  private int videoWidth;
  private int videoHeight;
  private float videoPixelAspectRatio = 1.0f;
  private int renderWidth;
  private int renderHeight;
  @Nullable private Rect renderViewport;
  private boolean closed;

  /**
   * Creates a playback session.
   *
   * @param configuration The libass configuration.
   * @param libassEnabled Whether libass rendering is enabled. When false, the session is inert and
   *     {@link #isAvailable()} returns false.
   */
  public LibassPlaybackSession(LibassConfiguration configuration, boolean libassEnabled) {
    this.configuration = configuration;
    this.libassEnabled = libassEnabled && LibassLibrary.isAvailable();
    this.mediaSessions = new HashMap<>();
    this.retainedMediaItems = new HashSet<>();
    this.lock = new Object();
    this.bottomPositionFraction = 1.0f;
    this.secondaryBottomPositionFraction = 1.0f;
    this.fontScale = 1.0f;
    this.defaultFontFamily = configuration.defaultFontFamily;
  }

  /** Returns whether libass rendering is available. */
  public boolean isAvailable() {
    return libassEnabled && !closed;
  }

  /**
   * Returns a {@link Renderer} that follows the playback clock and drives the rendering of libass
   * frames.
   */
  public Renderer createClockRenderer() {
    return new LibassClockRenderer(this);
  }

  /**
   * Prepares the media source components used to play the given media item with libass.
   *
   * @param mediaItem The media item being played.
   * @param extractorsFactory The extractors factory used by the media source.
   * @param transcript The transcript the subtitle content is indexed into.
   * @return The media components to use for the media item.
   */
  public MediaComponents createMediaComponents(
      MediaItem mediaItem, ExtractorsFactory extractorsFactory, SubtitleTranscript transcript) {
    LibassMediaSession mediaSession = getOrCreateMediaSession(mediaItem);
    mediaSession.setTranscript(transcript);
    LibassSubtitleParserFactory subtitleParserFactory =
        new LibassSubtitleParserFactory(mediaSession, new DefaultSubtitleParserFactory(), false);
    extractorsFactory.setSubtitleParserFactory(subtitleParserFactory);
    return new MediaComponents(extractorsFactory, subtitleParserFactory);
  }

  /** Sets the player whose media items are rendered by this session. */
  public void setPlayer(@Nullable Player player) {
    synchronized (lock) {
      if (closed || this.player == player) {
        return;
      }
      if (this.player != null) {
        this.player.removeListener(playerListener);
      }
      this.player = player;
      if (player == null) {
        for (LibassMediaSession mediaSession : mediaSessions.values()) {
          mediaSession.setSecondaryFormat(null);
        }
        currentMediaSession = null;
        secondaryMediaSession = null;
        return;
      }
      player.addListener(playerListener);
      MediaItem mediaItem = player.getCurrentMediaItem();
      if (mediaItem != null) {
        setCurrentMediaItemInternal(mediaItem);
      }
      updateSecondaryOffsets();
    }
  }

  /** Sets the media item that is being preloaded. */
  public void setPreloadMediaItem(@Nullable MediaItem mediaItem) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      this.preloadMediaItem = mediaItem;
      if (mediaItem != null) {
        preloadMediaSession = getOrCreateMediaSession(mediaItem);
      } else {
        preloadMediaSession = null;
      }
      evictMediaSessions();
    }
  }

  /** Sets the fraction of the frame height the primary subtitles are anchored to. */
  public void setBottomPositionFraction(float fraction) {
    synchronized (lock) {
      bottomPositionFraction = fraction;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setBottomPositionFraction(fraction);
      }
      requestRender();
    }
  }

  /** Sets the fraction of the frame height the secondary subtitles are anchored to. */
  public void setSecondaryBottomPositionFraction(float fraction) {
    synchronized (lock) {
      secondaryBottomPositionFraction = fraction;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setSecondaryBottomPositionFraction(fraction);
      }
      requestRender();
    }
  }

  /**
   * Sets the font scale and whether it is applied on top of the styles defined by the subtitles.
   */
  public void setFontScale(float scale, boolean applyScale) {
    synchronized (lock) {
      fontScale = scale;
      applyFontScale = applyScale;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setFontScale(applyScale, scale);
      }
      requestRender();
    }
  }

  /** Sets the default font family, or null to use the configured default. */
  public void setDefaultFontFamily(@Nullable String fontFamily) {
    synchronized (lock) {
      defaultFontFamily = fontFamily;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setDefaultFontFamily(fontFamily);
      }
      requestRender();
    }
  }

  /** Sets the style override applied to all subtitles, or clears it when null. */
  public void setStyleOverride(
      @Nullable LibassStyleOverride styleOverride, @Nullable String fontFamily) {
    synchronized (lock) {
      this.styleOverride = styleOverride;
      this.styleOverrideFontFamily = fontFamily;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setStyleOverride(styleOverride, fontFamily);
      }
      requestRender();
    }
  }

  /** Sets the size of the video, used as the libass frame size. */
  public void setVideoSize(int width, int height, float pixelAspectRatio) {
    synchronized (lock) {
      this.videoWidth = width;
      this.videoHeight = height;
      this.videoPixelAspectRatio = pixelAspectRatio;
      applyFrameSizes();
    }
  }

  /** Sets the pixel size of the render output, or zero to follow the video size. */
  public void setRenderSize(int width, int height) {
    synchronized (lock) {
      this.renderWidth = width;
      this.renderHeight = height;
      applyFrameSizes();
    }
  }

  /** Sets the viewport of the video within the render output. */
  public void setRenderViewport(@Nullable Rect viewport) {
    synchronized (lock) {
      this.renderViewport = viewport == null ? null : new Rect(viewport);
      applyFrameSizes();
    }
  }

  /** Sets the view the libass frames are rendered into, or null to detach it. */
  public void setRenderView(@Nullable LibassRenderView renderView) {
    synchronized (lock) {
      if (renderView == null) {
        LibassRenderView previous = renderViewReference == null ? null : renderViewReference.get();
        if (previous != null) {
          previous.setMediaSession(null);
          previous.release();
        }
        renderViewReference = null;
        return;
      }
      renderViewReference = new WeakReference<>(renderView);
      renderView.setMediaSession(currentMediaSession);
      renderView.setFrameSize(videoWidth, videoHeight);
    }
  }

  /** Returns the session of the currently playing media item, or null. */
  @Nullable
  public LibassMediaSession getCurrentMediaSession() {
    synchronized (lock) {
      return currentMediaSession;
    }
  }

  /** Sets the format of the secondary subtitle rendered by libass, or null to clear it. */
  public void setSecondarySubtitleFormat(@Nullable Format format) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      secondaryMediaSession = currentMediaSession;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.setSecondaryFormat(format);
      }
      requestRender();
    }
  }

  /** Called by the {@link LibassClockRenderer} for every rendered playback position. */
  /* package */ void onClockTick(long positionUs, boolean redrawForced) {
    LibassRenderView renderView = renderViewReference == null ? null : renderViewReference.get();
    if (renderView == null) {
      return;
    }
    renderView.requestRender(positionUs);
  }

  private void requestRender() {
    LibassRenderView renderView = renderViewReference == null ? null : renderViewReference.get();
    if (renderView != null) {
      renderView.requestRender();
    }
  }

  private void applyFrameSizes() {
    int frameWidth = videoWidth > 0 ? videoWidth : 0;
    int frameHeight = videoHeight > 0 ? videoHeight : 0;
    int storageWidth = renderWidth > 0 ? renderWidth : frameWidth;
    int storageHeight = renderHeight > 0 ? renderHeight : frameHeight;
    Rect viewport =
        renderViewport != null ? renderViewport : new Rect(0, 0, frameWidth, frameHeight);
    LibassRenderView renderView = renderViewReference == null ? null : renderViewReference.get();
    if (renderView != null) {
      renderView.setFrameSize(storageWidth, storageHeight);
    }
    for (LibassMediaSession mediaSession : mediaSessions.values()) {
      if (frameWidth > 0) {
        mediaSession.setFrameSize(
            frameWidth, frameHeight, storageWidth, storageHeight, viewport, videoPixelAspectRatio);
      }
    }
    requestRender();
  }

  private void updateSecondaryOffsets() {
    Player player = this.player;
    if (player == null) {
      return;
    }
    SubtitleOffsets offsets = player.getSubtitleOffsets();
    long secondaryOffsetUs = C.msToUs(offsets.secondaryMs);
    for (LibassMediaSession mediaSession : mediaSessions.values()) {
      mediaSession.setSecondaryOffsetUs(secondaryOffsetUs);
    }
    requestRender();
  }

  private LibassMediaSession getOrCreateMediaSession(MediaItem mediaItem) {
    LibassMediaSession mediaSession = mediaSessions.get(mediaItem);
    if (mediaSession == null) {
      mediaSession = new LibassMediaSession(configuration, libassEnabled);
      synchronized (lock) {
        mediaSession.setBottomPositionFraction(bottomPositionFraction);
        mediaSession.setSecondaryBottomPositionFraction(secondaryBottomPositionFraction);
        mediaSession.setFontScale(applyFontScale, fontScale);
        mediaSession.setDefaultFontFamily(defaultFontFamily);
        mediaSession.setStyleOverride(styleOverride, styleOverrideFontFamily);
      }
      mediaSessions.put(mediaItem, mediaSession);
    }
    return mediaSession;
  }

  private void setCurrentMediaItemInternal(MediaItem mediaItem) {
    if (Objects.equals(currentMediaItem, mediaItem)) {
      return;
    }
    currentMediaItem = mediaItem;
    currentMediaSession = getOrCreateMediaSession(mediaItem);
    LibassRenderView renderView = renderViewReference == null ? null : renderViewReference.get();
    if (renderView != null) {
      renderView.setMediaSession(currentMediaSession);
    }
    evictMediaSessions();
  }

  private void evictMediaSessions() {
    retainedMediaItems.clear();
    if (currentMediaItem != null) {
      retainedMediaItems.add(currentMediaItem);
    }
    if (preloadMediaItem != null) {
      retainedMediaItems.add(preloadMediaItem);
    }
    Player player = this.player;
    if (player != null) {
      androidx.media3.common.Timeline timeline = player.getCurrentTimeline();
      androidx.media3.common.Timeline.Window window = new androidx.media3.common.Timeline.Window();
      for (int i = 0; i < timeline.getWindowCount(); i++) {
        timeline.getWindow(i, window);
        if (window.mediaItem != null) {
          retainedMediaItems.add(window.mediaItem);
        }
      }
    }
    Iterator<Map.Entry<MediaItem, LibassMediaSession>> iterator =
        mediaSessions.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<MediaItem, LibassMediaSession> entry = iterator.next();
      if (!retainedMediaItems.contains(entry.getKey())) {
        entry.getValue().releaseAsync();
        iterator.remove();
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      if (player != null) {
        player.removeListener(playerListener);
        player = null;
      }
      LibassRenderView renderView = renderViewReference == null ? null : renderViewReference.get();
      if (renderView != null) {
        renderView.setMediaSession(null);
        renderView.release();
      }
      renderViewReference = null;
      for (LibassMediaSession mediaSession : mediaSessions.values()) {
        mediaSession.releaseAsync();
      }
      mediaSessions.clear();
      currentMediaSession = null;
      secondaryMediaSession = null;
      preloadMediaSession = null;
      currentMediaItem = null;
      preloadMediaItem = null;
    }
  }

  private final Player.Listener playerListener =
      new Player.Listener() {
        @Override
        public void onMediaItemTransition(
            @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
          synchronized (lock) {
            if (mediaItem != null) {
              setCurrentMediaItemInternal(mediaItem);
            }
          }
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
          synchronized (lock) {
            setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio);
          }
        }

        @Override
        public void onEvents(Player player, Player.Events events) {
          synchronized (lock) {
            if (events.contains(Player.EVENT_TRACKS_CHANGED)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
              updateSecondaryOffsets();
            }
            MediaItem mediaItem = player.getCurrentMediaItem();
            if (mediaItem != null) {
              setCurrentMediaItemInternal(mediaItem);
            }
            requestRender();
          }
        }
      };

  /** The media source components of a media item played with libass. */
  public static final class MediaComponents {

    /** The extractors factory that feeds subtitle samples to libass. */
    public final ExtractorsFactory extractorsFactory;
    /** The subtitle parser factory that feeds ASS samples to libass. */
    public final SubtitleParser.Factory subtitleParserFactory;

    /** Creates media components. */
    public MediaComponents(
        ExtractorsFactory extractorsFactory, SubtitleParser.Factory subtitleParserFactory) {
      this.extractorsFactory = extractorsFactory;
      this.subtitleParserFactory = subtitleParserFactory;
    }
  }
}
