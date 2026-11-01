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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.SubtitleSelectionState;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.text.SecondaryTextOutput;
import androidx.media3.exoplayer.trackselection.SecondaryTextTrackSelector;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Controls the subtitle rendering of a player backed by libass.
 *
 * <p>The controller manages the selection of the secondary subtitle track, whose cues are emitted
 * to a {@link SecondaryTextOutput}, and feeds the format of ASS secondary tracks to the {@link
 * LibassPlaybackSession} for rendering by libass.
 */
@UnstableApi
public final class LibassSubtitleController implements Player.Listener, AutoCloseable {

  private final Player player;
  private final LibassPlaybackSession playbackSession;
  private final SecondaryTextTrackSelector.Factory selectorFactory;
  @Nullable private final SecondaryTextOutput secondaryTextOutput;
  private final Object lock;

  @Nullable private TrackSelectionOverride manualSecondarySelection;
  @Nullable private TrackSelectionOverride activeSecondarySelection;
  private boolean autoSelectionEnabled;
  private boolean closed;

  /**
   * Creates a controller.
   *
   * @param player The player whose subtitles are controlled.
   * @param playbackSession The libass playback session of the player.
   * @param selectorFactory The factory that created the {@link SecondaryTextTrackSelector} of the
   *     player.
   * @param secondaryTextOutput The output of the secondary text renderer, or null if the player has
   *     no secondary text renderer.
   */
  public LibassSubtitleController(
      Player player,
      LibassPlaybackSession playbackSession,
      SecondaryTextTrackSelector.Factory selectorFactory,
      @Nullable SecondaryTextOutput secondaryTextOutput) {
    this.player = player;
    this.playbackSession = playbackSession;
    this.selectorFactory = selectorFactory;
    this.secondaryTextOutput = secondaryTextOutput;
    this.lock = new Object();
    this.autoSelectionEnabled = true;
    playbackSession.setPlayer(player);
    player.addListener(this);
    updateSecondarySelection();
  }

  /** Returns the output of the secondary text renderer, or null. */
  @Nullable
  public SecondaryTextOutput getSecondaryTextOutput() {
    return secondaryTextOutput;
  }

  /** Returns the factory that created the {@link SecondaryTextTrackSelector} of the player. */
  public SecondaryTextTrackSelector.Factory getSecondaryTextTrackSelectorFactory() {
    return selectorFactory;
  }

  /**
   * Returns which subtitle track selections currently feed the primary and the secondary subtitle
   * outputs.
   */
  public SubtitleSelectionState getSubtitleSelectionState() {
    Tracks tracks = player.getCurrentTracks();
    return new SubtitleSelectionState(
        getPrimarySelection(tracks), resolveSelection(tracks, activeSecondarySelection));
  }

  /**
   * Sets the track selection override that feeds the secondary subtitle output, or null to clear
   * the manual selection.
   */
  public void setSecondaryTextTrackSelectionOverride(
      @Nullable TrackSelectionOverride selection) {
    synchronized (lock) {
      manualSecondarySelection = selection;
    }
    updateSecondarySelection();
  }

  /** Sets whether the secondary subtitle track is selected automatically. */
  public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {
    synchronized (lock) {
      autoSelectionEnabled = enabled;
    }
    updateSecondarySelection();
  }

  @Override
  public void onTracksChanged(Tracks tracks) {
    updateSecondarySelection();
  }

  @Override
  public void onPlaybackStateChanged(@Player.State int playbackState) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      // Keep the selection: tracks may become available again after a re-prepare.
      updateSecondarySelection();
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      manualSecondarySelection = null;
      activeSecondarySelection = null;
      player.removeListener(this);
      selectorFactory.setSecondarySelection(null);
      playbackSession.setSecondarySubtitleFormat(null);
    }
  }

  private void updateSecondarySelection() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      Tracks tracks = player.getCurrentTracks();
      @Nullable TrackSelectionOverride selection = manualSecondarySelection;
      selection = resolveSelection(tracks, selection);
      if (selection == null && autoSelectionEnabled) {
        selection = selectAutoSecondarySelection(tracks);
      }
      activeSecondarySelection = selection;
      selectorFactory.setSecondarySelection(selection);
      @Nullable Format secondaryFormat = null;
      if (selection != null) {
        Format format = getTrackFormat(tracks, selection);
        // Only ASS tracks are rendered by libass; other formats are rendered as plain cues.
        if (format != null && LibassSubtitleParserFactory.isAssFormat(format)) {
          secondaryFormat = format;
        }
      }
      playbackSession.setSecondarySubtitleFormat(secondaryFormat);
    }
  }

  @Nullable
  private static TrackSelectionOverride getPrimarySelection(Tracks tracks) {
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);
      if (group.getType() != C.TRACK_TYPE_TEXT) {
        continue;
      }
      for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
        if (group.isTrackSelected(trackIndex)) {
          return new TrackSelectionOverride(group.getMediaTrackGroup(), trackIndex);
        }
      }
    }
    return null;
  }

  @Nullable
  private static TrackSelectionOverride selectAutoSecondarySelection(Tracks tracks) {
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);
      if (group.getType() != C.TRACK_TYPE_TEXT) {
        continue;
      }
      for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
        if (group.isTrackSupported(trackIndex) && !group.isTrackSelected(trackIndex)) {
          return new TrackSelectionOverride(group.getMediaTrackGroup(), trackIndex);
        }
      }
    }
    return null;
  }

  @Nullable
  private static TrackSelectionOverride resolveSelection(
      Tracks tracks, @Nullable TrackSelectionOverride selection) {
    if (selection == null || selection.trackIndices.size() != 1) {
      return null;
    }
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);
      if (!group.getMediaTrackGroup().equals(selection.mediaTrackGroup)) {
        continue;
      }
      int trackIndex = selection.trackIndices.get(0);
      if (trackIndex >= 0
          && trackIndex < group.length
          && group.isTrackSupported(trackIndex)
          && !group.isTrackSelected(trackIndex)) {
        return selection;
      }
      return null;
    }
    return null;
  }

  @Nullable
  private static Format getTrackFormat(Tracks tracks, TrackSelectionOverride selection) {
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);
      if (!group.getMediaTrackGroup().equals(selection.mediaTrackGroup)) {
        continue;
      }
      int trackIndex = selection.trackIndices.get(0);
      if (trackIndex >= 0 && trackIndex < group.length) {
        return group.getTrackFormat(trackIndex);
      }
      return null;
    }
    return null;
  }
}
