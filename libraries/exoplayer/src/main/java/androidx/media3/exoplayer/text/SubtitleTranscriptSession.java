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
package androidx.media3.exoplayer.text;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.text.SubtitleContent;
import com.google.common.collect.ImmutableList;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Manages the {@linkplain SubtitleTranscript transcripts} of the media items played by a player.
 *
 * <p>The session keeps one {@link SubtitleTranscript} per media item, loads the content of
 * sideloaded subtitle tracks through a {@link SubtitleContentLoader} and exposes the content of
 * the currently selected tracks.
 */
@UnstableApi
public final class SubtitleTranscriptSession implements Player.Listener, AutoCloseable {

  private static final String TAG = "SubtitleTranscriptSession";

  private final HashMap<MediaItem, SubtitleTranscript> transcripts;
  private final HashSet<MediaItem> retainedMediaItems;
  private final HashMap<Format, Future<?>> pendingLoads;
  @Nullable private SubtitleContentLoader loader;
  @Nullable private Player player;
  @Nullable private MediaItem preloadMediaItem;
  @Nullable private ExecutorService executorService;
  private boolean contentEnabled;
  private boolean closed;

  /** Creates a session. */
  public SubtitleTranscriptSession() {
    transcripts = new HashMap<>();
    retainedMediaItems = new HashSet<>();
    pendingLoads = new HashMap<>();
  }

  /** Sets the loader used to load the content of sideloaded subtitle tracks. */
  public synchronized void setLoader(@Nullable SubtitleContentLoader loader) {
    cancelPendingLoads();
    this.loader = loader;
  }

  /** Sets the player whose media items are tracked. */
  public synchronized void setPlayer(@Nullable Player player) {
    if (closed || this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.removeListener(this);
    }
    this.player = player;
    if (player != null) {
      player.addListener(this);
      onTimelineChanged(player.getCurrentTimeline(), /* reason= */ 0);
    }
  }

  /**
   * Returns the transcript of the given media item, creating it if necessary.
   *
   * @param mediaItem The media item.
   * @return The transcript of the media item.
   * @throws IllegalStateException If the session is closed.
   */
  public synchronized SubtitleTranscript forMediaItem(MediaItem mediaItem) {
    if (closed) {
      throw new IllegalStateException("Session is closed");
    }
    if (!retainedMediaItems.contains(mediaItem) && !mediaItem.equals(getCurrentMediaItem())) {
      retainedMediaItems.add(mediaItem);
    }
    @Nullable SubtitleTranscript transcript = transcripts.get(mediaItem);
    if (transcript == null) {
      transcript = new SubtitleTranscript(mediaItem);
      transcripts.put(mediaItem, transcript);
    }
    return transcript;
  }

  /** Sets the media item that is being preloaded and whose transcript should be retained. */
  public synchronized void setPreloadMediaItem(@Nullable MediaItem mediaItem) {
    preloadMediaItem = mediaItem;
    if (mediaItem != null) {
      retainedMediaItems.remove(mediaItem);
      forMediaItem(mediaItem);
    }
    evict();
  }

  /**
   * Returns the content of the subtitle track selected by the given format.
   *
   * @param format The format of the selected subtitle track.
   * @param offsetMs The offset applied to the content entry times, in milliseconds.
   * @return The content of the track, or a {@link SubtitleContent} with the corresponding pending
   *     state if the content is not (yet) available.
   */
  public synchronized SubtitleContent getContent(Format format, long offsetMs) {
    @Nullable Player player = this.player;
    @Nullable SubtitleTranscript transcript =
        player == null ? null : transcripts.get(player.getCurrentMediaItem());
    if (player == null || transcript == null || closed) {
      return new SubtitleContent(
          SubtitleContent.State.LOADING, ImmutableList.of(), /* truncated= */ false);
    }
    ensureExternalContentLoaded(player.getCurrentMediaItem(), transcript, format);
    return transcript.getContent(format, C.msToUs(offsetMs), C.TIME_UNSET);
  }

  /** Sets whether subtitle content should be loaded and provided. */
  public synchronized void setContentEnabled(boolean enabled) {
    if (contentEnabled == enabled) {
      return;
    }
    contentEnabled = enabled;
    if (!enabled) {
      cancelPendingLoads();
    }
  }

  /** Returns whether loading the content of the track can be retried. */
  public synchronized boolean canRetryContent(Format format) {
    if (closed) {
      return false;
    }
    @Nullable Player player = this.player;
    @Nullable SubtitleTranscript transcript =
        player == null ? null : transcripts.get(player.getCurrentMediaItem());
    if (transcript == null) {
      return false;
    }
    MediaItem mediaItem = player.getCurrentMediaItem();
    return mediaItem != null
        && transcript.getExternalTrackConfiguration(format) != null
        && transcript.getContent(format).state == SubtitleContent.State.ERROR;
  }

  /** Retries loading the content of the track. */
  public synchronized void retryContent(Format format) {
    if (closed) {
      return;
    }
    @Nullable Player player = this.player;
    if (player == null) {
      return;
    }
    @Nullable SubtitleTranscript transcript = transcripts.get(player.getCurrentMediaItem());
    if (transcript == null) {
      return;
    }
    @Nullable Future<?> pendingLoad = pendingLoads.remove(format);
    if (pendingLoad != null) {
      pendingLoad.cancel(/* mayInterruptIfRunning= */ true);
    }
    transcript.removeExternalContentLoaded(format);
    ensureExternalContentLoaded(player.getCurrentMediaItem(), transcript, format);
  }

  @Override
  public synchronized void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
    evict();
  }

  @Override
  public synchronized void onMediaItemTransition(
      @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
    evict();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    cancelPendingLoads();
    shutdownExecutor();
    transcripts.clear();
    retainedMediaItems.clear();
    if (player != null) {
      player.removeListener(this);
      player = null;
    }
    preloadMediaItem = null;
  }

  private void ensureExternalContentLoaded(
      @Nullable MediaItem mediaItem, SubtitleTranscript transcript, Format format) {
    if (loader == null || !contentEnabled || mediaItem == null) {
      return;
    }
    @Nullable MediaItem.SubtitleConfiguration configuration =
        transcript.getExternalTrackConfiguration(format);
    if (configuration == null || transcript.isExternalContentLoaded(format)) {
      return;
    }
    transcript.setExternalContentLoaded(format);
    transcript.setState(format, SubtitleContent.State.LOADING);
    ExecutorService executorService = getOrCreateExecutor();
    MediaItem item = mediaItem;
    MediaItem.SubtitleConfiguration subtitleConfiguration = configuration;
    SubtitleContentLoader contentLoader = loader;
    pendingLoads.put(
        format,
        executorService.submit(
            () -> {
              try {
                SubtitleContent content =
                    contentLoader.load(item, subtitleConfiguration.uri, format);
                synchronized (SubtitleTranscriptSession.this) {
                  if (!closed) {
                    transcript.setContent(format, content);
                    pendingLoads.remove(format);
                  }
                }
              } catch (InterruptedIOException e) {
                // Load was cancelled. Keep the track pending so it can be retried.
              } catch (IOException e) {
                Log.w(TAG, "Unable to load subtitle content", e);
                synchronized (SubtitleTranscriptSession.this) {
                  if (!closed) {
                    transcript.markExternalTrackErrored(subtitleConfiguration.uri);
                    pendingLoads.remove(format);
                  }
                }
              } catch (RuntimeException e) {
                Log.w(TAG, "Unable to load subtitle content", e);
                synchronized (SubtitleTranscriptSession.this) {
                  if (!closed) {
                    transcript.markExternalTrackErrored(subtitleConfiguration.uri);
                    pendingLoads.remove(format);
                  }
                }
              }
            }));
  }

  @Nullable
  private MediaItem getCurrentMediaItem() {
    return player == null ? null : player.getCurrentMediaItem();
  }

  private void evict() {
    @Nullable MediaItem currentMediaItem = getCurrentMediaItem();
    retainedMediaItems.clear();
    if (currentMediaItem != null && player != null) {
      Timeline timeline = player.getCurrentTimeline();
      for (int i = 0; i < timeline.getWindowCount(); i++) {
        Timeline.Window window = new Timeline.Window();
        timeline.getWindow(i, window);
        if (window.mediaItem != null) {
          retainedMediaItems.add(window.mediaItem);
        }
      }
      retainedMediaItems.add(currentMediaItem);
    }
    if (preloadMediaItem != null) {
      retainedMediaItems.add(preloadMediaItem);
    }
    Iterator<Map.Entry<MediaItem, SubtitleTranscript>> iterator =
        transcripts.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<MediaItem, SubtitleTranscript> entry = iterator.next();
      if (!retainedMediaItems.contains(entry.getKey())) {
        entry.getValue().clear();
        iterator.remove();
      }
    }
  }

  private void cancelPendingLoads() {
    for (Future<?> pendingLoad : pendingLoads.values()) {
      pendingLoad.cancel(/* mayInterruptIfRunning= */ true);
    }
    pendingLoads.clear();
  }

  private ExecutorService getOrCreateExecutor() {
    if (executorService == null) {
      executorService = Executors.newCachedThreadPool();
    }
    return executorService;
  }

  private void shutdownExecutor() {
    if (executorService != null) {
      executorService.shutdownNow();
      executorService = null;
    }
  }
}
