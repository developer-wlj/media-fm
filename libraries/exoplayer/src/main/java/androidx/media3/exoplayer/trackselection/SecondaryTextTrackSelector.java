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
package androidx.media3.exoplayer.trackselection;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelector.InvalidationListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.collect.ImmutableList;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link TrackSelector} that delegates the selection of the primary tracks to another selector
 * and forces the selection of a second, independent subtitle track into a dedicated text renderer.
 */
@UnstableApi
public final class SecondaryTextTrackSelector extends TrackSelector {

  private final TrackSelector delegate;
  private final @Nullable TrackSelectionOverride initialSecondarySelection;
  private volatile @Nullable TrackSelectionOverride secondarySelection;
  @Nullable private InvalidationListener invalidationListener;

  /**
   * Creates an instance.
   *
   * @param delegate The selector used for the primary track selection.
   * @param initialSecondarySelection The initial selection for the secondary subtitle track, or
   *     null.
   */
  public SecondaryTextTrackSelector(
      TrackSelector delegate, @Nullable TrackSelectionOverride initialSecondarySelection) {
    this.delegate = delegate;
    this.initialSecondarySelection = initialSecondarySelection;
    this.secondarySelection = initialSecondarySelection;
  }

  /** Sets the selection that is forced into the secondary text renderer. */
  public void setSecondarySelection(@Nullable TrackSelectionOverride secondarySelection) {
    this.secondarySelection = secondarySelection;
    invalidate(/* parameters= */ null);
  }

  /** Returns the selection that is forced into the secondary text renderer. */
  @Nullable
  public TrackSelectionOverride getSecondarySelection() {
    return secondarySelection;
  }

  @Override
  public void init(InvalidationListener listener, BandwidthMeter bandwidthMeter) {
    super.init(listener, bandwidthMeter);
    this.invalidationListener = listener;
    delegate.init(new ForwardingInvalidationListener(listener), bandwidthMeter);
  }

  @Override
  public void release() {
    super.release();
    invalidationListener = null;
    delegate.release();
  }

  @Override
  public TrackSelectorResult selectTracks(
      RendererCapabilities[] rendererCapabilities,
      TrackGroupArray trackGroups,
      MediaPeriodId periodId,
      Timeline timeline)
      throws ExoPlaybackException {
    TrackSelectorResult result = delegate.selectTracks(rendererCapabilities, trackGroups, periodId, timeline);
    @Nullable TrackSelectionOverride secondarySelection = this.secondarySelection;
    if (secondarySelection == null || secondarySelection.trackIndices.size() != 1) {
      return result;
    }
    int secondaryRendererIndex = getSecondaryTextRendererIndex(rendererCapabilities);
    if (secondaryRendererIndex == C.INDEX_UNSET) {
      return result;
    }
    int groupIndex = trackGroups.indexOf(secondarySelection.mediaTrackGroup);
    if (groupIndex == C.INDEX_UNSET) {
      return result;
    }
    int trackIndex = secondarySelection.trackIndices.get(0);
    if (trackIndex < 0 || trackIndex >= trackGroups.get(groupIndex).length) {
      return result;
    }
    @C.FormatSupport int formatSupport =
        RendererCapabilities.getFormatSupport(
            rendererCapabilities[secondaryRendererIndex]
                .supportsFormat(trackGroups.get(groupIndex).getFormat(trackIndex)));
    if (formatSupport != C.FORMAT_HANDLED && formatSupport != C.FORMAT_EXCEEDS_CAPABILITIES) {
      return result;
    }
    ExoTrackSelection[] selections = result.selections.clone();
    if (selections[secondaryRendererIndex] != null) {
      return result;
    }
    RendererConfiguration[] configurations = result.rendererConfigurations.clone();
    configurations[secondaryRendererIndex] = RendererConfiguration.DEFAULT;
    selections[secondaryRendererIndex] = new FixedTrackSelection(trackGroups.get(groupIndex), trackIndex);
    Tracks tracks = buildTracks(result.tracks, trackGroups.get(groupIndex), trackIndex);
    return new TrackSelectorResult(configurations, selections, tracks, result.info);
  }

  @Override
  public void onSelectionActivated(@Nullable Object info) {
    delegate.onSelectionActivated(info);
  }

  @Override
  public void onParametersActivated(@Nullable TrackSelectionParameters parameters) {
    delegate.onParametersActivated(parameters);
  }

  @Override
  public TrackSelectionParameters getParameters() {
    return delegate.getParameters();
  }

  @Override
  public void setParameters(TrackSelectionParameters parameters) {
    delegate.setParameters(parameters);
  }

  @Override
  public boolean isSetParametersSupported() {
    return delegate.isSetParametersSupported();
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    delegate.setAudioAttributes(audioAttributes);
  }

  @Override
  @Nullable
  public RendererCapabilities.Listener getRendererCapabilitiesListener() {
    return delegate.getRendererCapabilitiesListener();
  }

  private static int getSecondaryTextRendererIndex(RendererCapabilities[] rendererCapabilities) {
    int textRendererCount = 0;
    int secondaryRendererIndex = C.INDEX_UNSET;
    for (int i = 0; i < rendererCapabilities.length; i++) {
      if (rendererCapabilities[i].getTrackType() == C.TRACK_TYPE_TEXT) {
        textRendererCount++;
        secondaryRendererIndex = i;
      }
    }
    return textRendererCount >= 2 ? secondaryRendererIndex : C.INDEX_UNSET;
  }

  private static Tracks buildTracks(Tracks tracks, TrackGroup group, int trackIndex) {
    ImmutableList<Tracks.Group> groups = tracks.getGroups();
    List<Tracks.Group> newGroups = new ArrayList<>(groups.size());
    boolean replaced = false;
    for (int i = 0; i < groups.size(); i++) {
      Tracks.Group current = groups.get(i);
      if (!replaced && current.getMediaTrackGroup().equals(group)) {
        boolean[] trackSelected = new boolean[current.length];
        @C.FormatSupport int[] trackSupport = new int[current.length];
        for (int track = 0; track < current.length; track++) {
          trackSelected[track] = track == trackIndex;
          trackSupport[track] = current.getTrackSupport(track);
        }
        newGroups.add(
            new Tracks.Group(group, current.isAdaptiveSupported(), trackSupport, trackSelected));
        replaced = true;
      } else {
        newGroups.add(current);
      }
    }
    return replaced ? new Tracks(newGroups) : tracks;
  }

  private final class ForwardingInvalidationListener implements InvalidationListener {

    private final InvalidationListener listener;

    public ForwardingInvalidationListener(InvalidationListener listener) {
      this.listener = listener;
    }

    @Override
    public void onTrackSelectionsInvalidated(@Nullable TrackSelectionParameters parameters) {
      listener.onTrackSelectionsInvalidated(parameters);
    }

    @Override
    public void onRendererCapabilitiesChanged(Renderer renderer) {
      listener.onRendererCapabilitiesChanged(renderer);
    }
  }

  /** A {@link TrackSelector.Factory} that wraps the selectors created by another factory. */
  public static final class Factory implements TrackSelector.Factory {

    private final TrackSelector.Factory delegateFactory;
    private final Object lock;
    private final List<WeakReference<SecondaryTextTrackSelector>> selectors;
    @Nullable private TrackSelectionOverride secondarySelection;

    /**
     * Creates a factory.
     *
     * @param delegateFactory The factory creating the {@link TrackSelector delegates} used for the
     *     primary track selection.
     */
    public Factory(TrackSelector.Factory delegateFactory) {
      this.delegateFactory = delegateFactory;
      this.lock = new Object();
      this.selectors = new ArrayList<>();
    }

    @Override
    public TrackSelector createTrackSelector(Context context) {
      SecondaryTextTrackSelector selector =
          new SecondaryTextTrackSelector(delegateFactory.createTrackSelector(context), secondarySelection);
      synchronized (lock) {
        selectors.add(new WeakReference<>(selector));
      }
      return selector;
    }

    /**
     * Sets the selection that is forced into the secondary text renderer of every player using a
     * selector created by this factory.
     */
    public void setSecondarySelection(@Nullable TrackSelectionOverride secondarySelection) {
      List<SecondaryTextTrackSelector> liveSelectors;
      synchronized (lock) {
        this.secondarySelection = secondarySelection;
        liveSelectors = new ArrayList<>(selectors.size());
        for (int i = selectors.size() - 1; i >= 0; i--) {
          @Nullable SecondaryTextTrackSelector selector = selectors.get(i).get();
          if (selector == null) {
            selectors.remove(i);
          } else {
            liveSelectors.add(selector);
          }
        }
      }
      for (int i = 0; i < liveSelectors.size(); i++) {
        liveSelectors.get(i).setSecondarySelection(secondarySelection);
      }
    }

    /** Returns the last selection set through {@link #setSecondarySelection}. */
    @Nullable
    public TrackSelectionOverride getSecondarySelection() {
      return secondarySelection;
    }
  }
}
