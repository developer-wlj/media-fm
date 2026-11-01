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
package androidx.media3.ui.libass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassRenderView;
import androidx.media3.exoplayer.libass.LibassStyleOverride;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.exoplayer.text.SecondaryTextOutput;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import java.util.List;
import java.util.function.Consumer;

/**
 * Binds a {@link PlayerView} to the libass subtitle renderer of a player.
 *
 * <p>The controller attaches a view that draws the libass rendered subtitles of the primary track
 * and a {@link SubtitleView} that shows the cues of the secondary subtitle track.
 */
@UnstableApi
public final class LibassPlayerViewController {

  private final LibassPlaybackSession playbackSession;
  private final LibassSubtitleController subtitleController;
  @Nullable private androidx.media3.common.util.Consumer<List<Cue>> secondaryCuesListener;

  @Nullable private PlayerView playerView;
  @Nullable private LibassRenderView renderView;
  @Nullable private SubtitleView secondarySubtitleView;
  @Nullable private Consumer<SubtitleView> secondarySubtitleViewConfigurator;
  private boolean closed;

  /**
   * Creates a controller.
   *
   * @param player The player whose subtitles are rendered.
   * @param playbackSession The libass playback session of the player.
   * @param subtitleController The libass subtitle controller of the player.
   */
  public LibassPlayerViewController(
      Player player,
      LibassPlaybackSession playbackSession,
      LibassSubtitleController subtitleController) {
    this.playbackSession = playbackSession;
    this.subtitleController = subtitleController;
    SecondaryTextOutput secondaryTextOutput = subtitleController.getSecondaryTextOutput();
    if (secondaryTextOutput != null) {
      secondaryCuesListener =
          cues -> {
            SubtitleView subtitleView = secondarySubtitleView;
            if (subtitleView != null) {
              subtitleView.setCues(cues);
            }
          };
      secondaryTextOutput.setCuesListener(secondaryCuesListener);
    }
  }

  /** Binds the controller to a {@link PlayerView}. */
  public void bind(PlayerView playerView) {
    if (closed || this.playerView == playerView) {
      return;
    }
    unbindFromCurrentView();
    this.playerView = playerView;
    ViewGroup contentFrame = getContentFrame(playerView);
    if (contentFrame == null) {
      return;
    }
    if (playbackSession.isAvailable()) {
      LibassRenderView renderView = new LibassRenderView(playerView.getContext());
      this.renderView = renderView;
      playbackSession.setRenderView(renderView);
      contentFrame.addView(
          renderView,
          new FrameLayout.LayoutParams(
              FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }
    SecondaryTextOutput secondaryTextOutput = subtitleController.getSecondaryTextOutput();
    if (secondaryTextOutput != null) {
      SubtitleView subtitleView = new SubtitleView(playerView.getContext());
      this.secondarySubtitleView = subtitleView;
      if (secondarySubtitleViewConfigurator != null) {
        secondarySubtitleViewConfigurator.accept(subtitleView);
      }
      contentFrame.addView(
          subtitleView,
          new FrameLayout.LayoutParams(
              FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
      subtitleView.setCues(secondaryTextOutput.getCues());
    }
  }

  /** Sets the consumer that configures the secondary subtitle view when it is created. */
  public void setSecondarySubtitleViewConfigurator(@Nullable Consumer<SubtitleView> configurator) {
    this.secondarySubtitleViewConfigurator = configurator;
    SubtitleView subtitleView = secondarySubtitleView;
    if (subtitleView != null && configurator != null) {
      configurator.accept(subtitleView);
    }
  }

  /**
   * Sets the style override applied to the subtitles rendered by libass.
   *
   * @param styleOverride The style to force, or null to use the styles of the subtitles.
   * @param fontFamily The font family used when the override is active, or null for the default.
   */
  public void setStyleOverride(
      @Nullable CaptionStyleCompat styleOverride, @Nullable String fontFamily) {
    if (closed) {
      return;
    }
    playbackSession.setDefaultFontFamily(fontFamily);
    playbackSession.setStyleOverride(toLibassStyleOverride(styleOverride, fontFamily), fontFamily);
  }

  /** Closes the controller and releases the attached views. */
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    SecondaryTextOutput secondaryTextOutput = subtitleController.getSecondaryTextOutput();
    if (secondaryTextOutput != null) {
      secondaryTextOutput.setCuesListener(null);
      secondaryCuesListener = null;
    }
    unbindFromCurrentView();
  }

  private void unbindFromCurrentView() {
    PlayerView playerView = this.playerView;
    this.playerView = null;
    if (playerView == null) {
      return;
    }
    if (secondarySubtitleView != null) {
      secondarySubtitleView.setCues(null);
      removeView(playerView, secondarySubtitleView);
      secondarySubtitleView = null;
    }
    if (renderView != null) {
      playbackSession.setRenderView(null);
      this.renderView = null;
    }
  }

  private static void removeView(PlayerView playerView, View view) {
    ViewGroup contentFrame = getContentFrame(playerView);
    ViewGroup parent = contentFrame != null ? contentFrame : playerView;
    if (view.getParent() == parent) {
      parent.removeView(view);
    }
  }

  @Nullable
  private static ViewGroup getContentFrame(PlayerView playerView) {
    View contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
    return contentFrame instanceof ViewGroup ? (ViewGroup) contentFrame : null;
  }

  @Nullable
  private static LibassStyleOverride toLibassStyleOverride(
      @Nullable CaptionStyleCompat styleOverride, @Nullable String fontFamily) {
    if (styleOverride == null) {
      return null;
    }
    int edgeType;
    switch (styleOverride.edgeType) {
      case CaptionStyleCompat.EDGE_TYPE_NONE:
        edgeType = LibassStyleOverride.EDGE_TYPE_NONE;
        break;
      case CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW:
        edgeType = LibassStyleOverride.EDGE_TYPE_DROP_SHADOW;
        break;
      default:
        edgeType = LibassStyleOverride.EDGE_TYPE_OUTLINE;
        break;
    }
    return new LibassStyleOverride.Builder()
        .setFontFamily(fontFamily == null ? "sans-serif" : fontFamily)
        .setForegroundColor(styleOverride.foregroundColor)
        .setBackgroundColor(styleOverride.backgroundColor)
        .setEdgeType(edgeType)
        .setEdgeColor(styleOverride.edgeColor)
        .setEdgeWidth(styleOverride.edgeWidth)
        .setShadowOffset(styleOverride.shadowOffset)
        .build();
  }
}
