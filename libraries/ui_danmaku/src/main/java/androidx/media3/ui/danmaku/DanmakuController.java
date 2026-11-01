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
 *
 */
package androidx.media3.ui.danmaku;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.ui.danmaku.fetcher.BiliFetcher;
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import androidx.media3.ui.danmaku.fetcher.IQIYIFetcher;
import androidx.media3.ui.danmaku.fetcher.MGTVFetcher;
import androidx.media3.ui.danmaku.fetcher.QQFetcher;
import androidx.media3.ui.danmaku.fetcher.YoukuFetcher;
import androidx.media3.ui.danmaku.parser.BiliParser;
import androidx.media3.ui.danmaku.parser.IQIYIParser;
import androidx.media3.ui.danmaku.parser.MGTVParser;
import androidx.media3.ui.danmaku.parser.Parser;
import androidx.media3.ui.danmaku.parser.QQParser;
import androidx.media3.ui.danmaku.parser.TxtParser;
import androidx.media3.ui.danmaku.parser.YoukuParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** Coordinates danmaku loading and rendering with a {@link Player}. */
@MainThread
@UnstableApi
public final class DanmakuController {

  /** Default amount of timeline content loaded ahead of the playback position. */
  public static final long DEFAULT_WINDOW_AHEAD_MS = 120_000;

  /** Default amount of timeline content retained behind the playback position. */
  public static final long DEFAULT_WINDOW_BEHIND_MS = 10_000;

  /** Default distance from the loaded window end that triggers extension. */
  public static final long DEFAULT_RELOAD_THRESHOLD_MS = 30_000;

  private static final long LOAD_CHECK_INTERVAL_MS = 30_000;
  private static final long BACKWARD_FILL_DELAY_MS = 2_000;
  private static final long BACKWARD_FILL_SEEK_DELAY_MS = 200L;
  private static final long POSITION_POLL_INTERVAL_MS = 1_000L;
  private final PlayerListener playerListener;
  private final List<Parser> parsers = new ArrayList<>();
  private final List<Fetcher> fetchers = new ArrayList<>();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final DanmakuSourceLoader sourceLoader = new DanmakuSourceLoader(mainHandler);
  private final DanmakuSegmentLoader segmentLoader;
  private final DanmakuTimeline timeline =
      new DanmakuTimeline(
          DEFAULT_WINDOW_AHEAD_MS, DEFAULT_WINDOW_BEHIND_MS, DEFAULT_RELOAD_THRESHOLD_MS);
  private final AtomicInteger loadGeneration = new AtomicInteger();
  private final Runnable positionPollRunnable =
      new Runnable() {
        @Override
        public void run() {
          Player currentPlayer = player;
          if (!enabled || danmakuView == null || currentPlayer == null) {
            return;
          }
          if (currentPlayer.isPlaying()) {
            long positionMs = currentPlayer.getCurrentPosition();
            if (timeline.needsExtension(positionMs, timeOffsetMs())) {
              extendWindowTo(positionMs, false);
            }
          }
          mainHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS);
        }
      };
  @Nullable private Player player;
  @Nullable private DanmakuView danmakuView;
  @Nullable private Listener listener;
  @Nullable private OkHttpClient okHttpClient;
  @Nullable private DataSource.Factory httpDataSourceFactory;
  @Nullable private HandlerThread loaderThread;
  @Nullable private Handler loaderHandler;
  @Nullable private Uri activeUri;
  @Nullable private Uri dataSourceUri;
  private DanmakuConfig config;
  private boolean viewDetached;
  private boolean dataSourceDirty;
  private boolean enabled = true;

  /** Creates a controller with default loading window and retry timings. */
  public DanmakuController() {
    this(LOAD_CHECK_INTERVAL_MS, BACKWARD_FILL_DELAY_MS, BACKWARD_FILL_SEEK_DELAY_MS);
  }

  @VisibleForTesting
  DanmakuController(
      long loadCheckIntervalMs, long backwardFillDelayMs, long backwardFillSeekDelayMs) {
    config = DanmakuConfig.DEFAULT;
    playerListener = new PlayerListener();
    segmentLoader =
        new DanmakuSegmentLoader(
            mainHandler,
            new SegmentHost(),
            loadCheckIntervalMs,
            backwardFillDelayMs,
            backwardFillSeekDelayMs);
    parsers.add(BiliParser.INSTANCE);
    parsers.add(TxtParser.INSTANCE);
    parsers.add(QQParser.INSTANCE);
    parsers.add(YoukuParser.INSTANCE);
    parsers.add(MGTVParser.INSTANCE);
    parsers.add(IQIYIParser.INSTANCE);
    fetchers.add(BiliFetcher.INSTANCE);
    fetchers.add(IQIYIFetcher.INSTANCE);
    fetchers.add(MGTVFetcher.INSTANCE);
    fetchers.add(QQFetcher.INSTANCE);
    fetchers.add(YoukuFetcher.INSTANCE);
  }

  private static boolean isHttpUri(Uri uri) {
    String scheme = uri.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  /** Sets the listener for loading events, or clears it if {@code listener} is {@code null}. */
  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  /** Registers a parser with priority over the built-in parsers. */
  public void registerParser(Parser parser) {
    parsers.add(0, checkNotNull(parser));
    markDataSourceDirty();
  }

  /** Registers a fetcher with priority over the built-in fetchers. */
  public void registerFetcher(Fetcher fetcher) {
    fetchers.add(0, checkNotNull(fetcher));
    markDataSourceDirty();
  }

  /**
   * Sets the HTTP client used by danmaku source loading, or clears it if {@code client} is {@code
   * null}.
   */
  public void setOkHttpClient(@Nullable OkHttpClient client) {
    if (okHttpClient == client) {
      return;
    }
    okHttpClient = client;
    httpDataSourceFactory = client != null ? new OkHttpDataSource.Factory(client) : null;
    if (dataSourceUri != null && isHttpUri(dataSourceUri)) {
      dataSourceDirty = true;
    }
  }

  /** Sets the danmaku source URI, or clears the current source if {@code uri} is {@code null}. */
  public void setDataSource(@Nullable Uri uri) {
    if (uri == null) {
      clearItems();
      return;
    }
    if (isCurrentDataSource(uri)) {
      refreshCurrentDataSource();
      return;
    }
    if (viewDetached) {
      deferDataSource(uri);
      return;
    }
    for (Fetcher fetcher : fetchers) {
      if (fetcher.accepts(uri)) {
        setDataSource(uri, null);
        return;
      }
    }
    boolean http = isHttpUri(uri);
    if (http && httpDataSourceFactory != null) {
      setDataSource(uri, httpDataSourceFactory);
      return;
    }
    if (danmakuView == null) {
      deferDataSource(uri);
      return;
    }
    setDataSource(uri, new DefaultDataSource.Factory(danmakuView.getContext()));
  }

  private void deferDataSource(Uri uri) {
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    timeline.clear();
    dataSourceUri = uri;
    dataSourceDirty = true;
  }

  /** Sets the danmaku source URI and optional data source factory. */
  public void setDataSource(Uri uri, @Nullable DataSource.Factory dataSourceFactory) {
    checkNotNull(uri);
    ensureLoaderThread();
    if (loaderHandler == null) {
      return;
    }
    int generation = loadGeneration.incrementAndGet();
    cancelActiveSession();
    @Nullable
    OkHttpClient loadClient =
        okHttpClient != null
            ? okHttpClient.newBuilder().dispatcher(new Dispatcher()).build()
            : null;
    @Nullable DataSource.Factory loadFactory = dataSourceFactory;
    if (loadClient != null && dataSourceFactory == httpDataSourceFactory) {
      loadFactory = new OkHttpDataSource.Factory(loadClient);
    }
    timeline.clear();
    if (danmakuView != null) {
      danmakuView.clear();
    }
    activeUri = uri;
    dataSourceUri = uri;
    dataSourceDirty = false;
    List<Parser> parserSnapshot = new ArrayList<>(parsers);
    List<Fetcher> fetcherSnapshot = new ArrayList<>(fetchers);
    long startPositionMs = player != null ? player.getCurrentPosition() : 0L;
    long durationMs = playerDurationMs();
    DanmakuSourceLoader.Request request =
        new DanmakuSourceLoader.Request(
            uri,
            loadFactory,
            loadClient,
            fetcherSnapshot,
            parserSnapshot,
            generation,
            startPositionMs,
            durationMs);
    sourceLoader.load(loaderHandler, request, new SourceListener());
  }

  private long playerDurationMs() {
    if (player == null) {
      return 0L;
    }
    long durationMs = player.getDuration();
    return durationMs == C.TIME_UNSET ? 0L : durationMs;
  }

  private void refreshCurrentDataSource() {
    timeline.invalidateWindow();
    if (player != null) {
      syncToPlayer(true);
    } else if (danmakuView != null) {
      extendWindowTo(0, true);
    }
  }

  /**
   * Sets the player used for playback synchronization, or detaches the current player if {@code
   * newPlayer} is {@code null}.
   */
  public void setPlayer(@Nullable Player newPlayer) {
    if (player == newPlayer) {
      return;
    }
    detachPlayer();
    player = newPlayer;
    attachPlayer(newPlayer);
    restartPositionPolling();
  }

  private void detachPlayer() {
    if (player != null) {
      player.removeListener(playerListener);
    }
    stopView();
  }

  private void attachPlayer(@Nullable Player newPlayer) {
    if (newPlayer != null) {
      newPlayer.addListener(playerListener);
      if (enabled) {
        syncToPlayer(true);
      }
    }
  }

  /** Sets the render view, or detaches the current view if {@code newView} is {@code null}. */
  public void setView(@Nullable DanmakuView newView) {
    if (danmakuView == newView) {
      return;
    }
    boolean detachingView = danmakuView != null && newView == null;
    detachView(newView == null);
    danmakuView = newView;
    viewDetached = detachingView;
    attachView(newView);
    restartPositionPolling();
  }

  private void detachView(boolean releaseLoading) {
    if (danmakuView == null) {
      return;
    }
    if (releaseLoading) {
      if (activeUri != null || segmentLoader.hasSession()) {
        markDataSourceDirty();
      }
      loadGeneration.incrementAndGet();
      cancelActiveSession();
      quitLoaderThread();
    }
    stopView();
  }

  private void attachView(@Nullable DanmakuView newView) {
    if (newView == null) {
      return;
    }
    newView.setConfig(config);
    newView.setDrawEnabled(enabled);
    timeline.invalidateWindow();
    if (dataSourceDirty && dataSourceUri != null) {
      setDataSource(dataSourceUri);
      return;
    }
    if (player != null && enabled) {
      syncToPlayer(true);
    } else if (player == null) {
      extendWindowTo(0, true);
    }
  }

  private void stopView() {
    if (danmakuView != null) {
      danmakuView.stop();
    }
  }

  /** Replaces the current source with {@code items}. */
  public void setItems(List<Danmaku> items) {
    checkNotNull(items);
    dataSourceUri = null;
    dataSourceDirty = false;
    replaceItems(items);
  }

  private void setLoadedItems(Uri uri, Danmaku[] items) {
    replaceSortedItems(items);
    dataSourceUri = uri;
    dataSourceDirty = false;
  }

  private void replaceItems(List<Danmaku> items) {
    prepareTimelineReplacement();
    timeline.setItems(items);
    finishTimelineReplacement();
  }

  private void replaceSortedItems(Danmaku[] items) {
    prepareTimelineReplacement();
    timeline.setSortedItems(items);
    finishTimelineReplacement();
  }

  private void prepareTimelineReplacement() {
    loadGeneration.incrementAndGet();
    cancelActiveSession();
  }

  private void finishTimelineReplacement() {
    if (player != null) {
      syncToPlayer(true);
    } else if (danmakuView != null) {
      extendWindowTo(0, true);
    }
  }

  /** Returns a snapshot of the currently loaded items, ordered by time. */
  public List<Danmaku> getItems() {
    return timeline.snapshot();
  }

  /** Clears all loaded and displayed items. */
  public void clearItems() {
    dataSourceUri = null;
    dataSourceDirty = false;
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    timeline.clear();
    if (danmakuView != null) {
      danmakuView.clear();
    }
  }

  /** Sends text at the current playback position. */
  public void sendNow(String text) {
    checkNotNull(text);
    if (danmakuView != null) {
      danmakuView.sendNow(text);
    }
  }

  /** Sends {@code danmaku} at the current playback position. */
  public void sendNow(Danmaku danmaku) {
    checkNotNull(danmaku);
    if (danmakuView != null) {
      danmakuView.sendNow(danmaku);
    }
  }

  /** Sets whether danmaku rendering is enabled. */
  public void setEnabled(boolean enabled) {
    if (this.enabled == enabled) {
      return;
    }
    this.enabled = enabled;
    if (danmakuView != null) {
      danmakuView.setDrawEnabled(enabled);
      if (!enabled) {
        stopView();
      }
    }
    if (enabled) {
      syncToPlayer(true);
    }
    restartPositionPolling();
  }

  /** Returns the current rendering configuration. */
  public DanmakuConfig getConfig() {
    return config;
  }

  /** Sets the rendering configuration. */
  public void setConfig(DanmakuConfig config) {
    checkNotNull(config);
    long oldOffset = this.config.timeOffsetMs;
    this.config = config;
    if (danmakuView == null) {
      return;
    }
    danmakuView.setConfig(config);
    if (oldOffset == config.timeOffsetMs) {
      return;
    }
    long positionMs = player != null ? player.getCurrentPosition() : 0L;
    if (segmentLoader.hasSession()) {
      segmentLoader.resetForTimeOffsetChange(positionMs, oldOffset, config.timeOffsetMs);
    }
    if (timeline.isRangeOutside(
        positionMs, config.timeOffsetMs, DanmakuView.MAX_ACTIVATION_WINDOW_MS)) {
      remapWindow(positionMs);
    }
  }

  /** Sets the timeline window sizes and extension threshold in milliseconds. */
  public void setWindowParams(long aheadMs, long behindMs, long thresholdMs) {
    timeline.setWindowParams(aheadMs, behindMs, thresholdMs);
  }

  /** Releases loading resources and detaches the player and view. */
  public void release() {
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    mainHandler.removeCallbacks(positionPollRunnable);
    detachPlayer();
    player = null;
    danmakuView = null;
    viewDetached = false;
    dataSourceUri = null;
    dataSourceDirty = false;
    timeline.clear();
    quitLoaderThread();
  }

  private void syncToPlayer(boolean forceSeek) {
    Player currentPlayer = player;
    DanmakuView currentView = danmakuView;
    if (!enabled || currentView == null || currentPlayer == null) {
      return;
    }
    long positionMs = currentPlayer.getCurrentPosition();
    currentView.setPlaybackSpeed(currentPlayer.getPlaybackParameters().speed);
    if (forceSeek) {
      if (segmentLoader.hasSession()) {
        resetSegmentCursors(positionMs);
      }
      syncAfterPositionDiscontinuity(currentPlayer, currentView, positionMs);
      return;
    }
    syncPlaybackState(currentPlayer, currentView, positionMs);
  }

  private void syncAfterPositionDiscontinuity(
      Player currentPlayer, DanmakuView currentView, long positionMs) {
    if (timeline.isOutside(positionMs, timeOffsetMs())) {
      extendWindowTo(positionMs, true);
    }
    if (currentPlayer.isPlaying()) {
      if (!currentView.isStarted()) {
        currentView.start(positionMs);
      } else {
        currentView.seekTo(positionMs);
      }
    } else if (currentView.isStarted()) {
      currentView.seekTo(positionMs);
      if (!currentView.isPaused()) {
        currentView.pause();
      }
    }
  }

  private void syncPlaybackState(Player currentPlayer, DanmakuView currentView, long positionMs) {
    extendWindowTo(positionMs, false);
    if (currentPlayer.isPlaying()) {
      if (!currentView.isStarted()) {
        currentView.start(positionMs);
      } else if (currentView.isPaused()) {
        currentView.syncPosition(positionMs);
        currentView.resume();
      } else {
        currentView.syncPosition(positionMs);
      }
    } else if (currentView.isStarted() && !currentView.isPaused()) {
      currentView.syncPosition(positionMs);
      currentView.pause();
    }
  }

  private void extendWindowTo(long positionMs, boolean forceReload) {
    DanmakuTimeline.Update update = timeline.extend(positionMs, timeOffsetMs(), forceReload);
    if (!update.changed || danmakuView == null) {
      return;
    }
    if (update.replace) {
      danmakuView.clear();
    }
    if (!update.items.isEmpty()) {
      danmakuView.addItems(update.items);
    }
  }

  private void remapWindow(long positionMs) {
    if (danmakuView != null) {
      danmakuView.replacePool(timeline.remap(positionMs, timeOffsetMs()));
    }
  }

  private long timeOffsetMs() {
    return config.timeOffsetMs;
  }

  @VisibleForTesting
  void resetSegmentCursors(long positionMs) {
    segmentLoader.reset(positionMs, timeOffsetMs());
  }

  private void mergeItems(Danmaku[] newItems) {
    if (newItems.length == 0) {
      return;
    }
    DanmakuTimeline.Merge merge = timeline.mergeSorted(newItems);
    if (merge.needsInitialWindow) {
      if (player != null) {
        syncToPlayer(true);
      } else if (danmakuView != null) {
        extendWindowTo(0, true);
      }
      return;
    }
    if (danmakuView != null) {
      long posMs = player != null ? player.getCurrentPosition() : 0L;
      List<Danmaku> visible = timeline.visibleItems(merge.incoming, posMs, timeOffsetMs());
      if (!visible.isEmpty()) {
        danmakuView.addItems(visible);
      }
    }
  }

  private void ensureLoaderThread() {
    if (loaderThread == null) {
      loaderThread = new HandlerThread("DanmakuLoader");
      loaderThread.start();
      loaderHandler = new Handler(loaderThread.getLooper());
    }
  }

  private void restartPositionPolling() {
    mainHandler.removeCallbacks(positionPollRunnable);
    if (enabled && player != null && danmakuView != null) {
      mainHandler.postDelayed(positionPollRunnable, POSITION_POLL_INTERVAL_MS);
    }
  }

  private void quitLoaderThread() {
    if (loaderThread == null) {
      return;
    }
    loaderThread.quitSafely();
    loaderThread = null;
    loaderHandler = null;
  }

  private void onSessionPrepared(
      Fetcher.Session session,
      int segmentDurationMs,
      int segmentCount,
      long startPositionMs,
      int generation) {
    timeline.clear();
    if (danmakuView != null) {
      danmakuView.clear();
    }
    Handler handler = loaderHandler;
    if (handler == null) {
      session.release();
      return;
    }
    long currentPositionMs = player != null ? player.getCurrentPosition() : startPositionMs;
    segmentLoader.start(
        handler,
        session,
        segmentDurationMs,
        segmentCount,
        currentPositionMs,
        timeOffsetMs(),
        generation);
  }

  private void cancelActiveSession() {
    segmentLoader.cancel();
    sourceLoader.cancel();
    activeUri = null;
  }

  private boolean isCurrentDataSource(Uri uri) {
    return !dataSourceDirty && uri.equals(dataSourceUri);
  }

  private void markDataSourceDirty() {
    if (dataSourceUri != null) {
      dataSourceDirty = true;
    }
  }

  /** Receives danmaku loading events. */
  public interface Listener {

    /** Called when a source has finished loading. */
    default void onLoadCompleted(Uri uri, int count) {}

    /** Called when segmented source loading advances. */
    default void onLoadProgress(Uri uri, int loaded, int total) {}

    /** Called when source loading fails. */
    default void onLoadError(Uri uri, IOException error) {}
  }

  private final class SourceListener implements DanmakuSourceLoader.Listener {

    @Override
    public void onSessionPrepared(
        DanmakuSourceLoader.Request request,
        Fetcher.Session session,
        int segmentDurationMs,
        int segmentCount) {
      if (loadGeneration.get() != request.generation) {
        session.release();
        return;
      }
      DanmakuController.this.onSessionPrepared(
          session, segmentDurationMs, segmentCount, request.startPositionMs, request.generation);
    }

    @Override
    public void onItemsLoaded(DanmakuSourceLoader.Request request, Danmaku[] items) {
      if (loadGeneration.get() != request.generation) {
        return;
      }
      sourceLoader.complete(request.generation);
      setLoadedItems(request.uri, items);
      if (listener != null) {
        listener.onLoadCompleted(request.uri, items.length);
      }
    }

    @Override
    public void onLoadError(DanmakuSourceLoader.Request request, IOException error) {
      if (loadGeneration.get() != request.generation) {
        return;
      }
      sourceLoader.complete(request.generation);
      if (request.uri.equals(dataSourceUri)) {
        dataSourceDirty = true;
      }
      activeUri = null;
      if (listener != null) {
        listener.onLoadError(request.uri, error);
      }
    }
  }

  private final class SegmentHost implements DanmakuSegmentLoader.Host {

    @Override
    public boolean isCurrent(int generation) {
      return loadGeneration.get() == generation;
    }

    @Override
    public boolean hasPlayer() {
      return player != null;
    }

    @Override
    public long currentPositionMs() {
      return player != null ? player.getCurrentPosition() : 0L;
    }

    @Override
    public long timeOffsetMs() {
      return DanmakuController.this.timeOffsetMs();
    }

    @Override
    public void onItems(Danmaku[] items) {
      mergeItems(items);
    }

    @Override
    public void onProgress(int loaded, int total) {
      if (listener != null && activeUri != null) {
        listener.onLoadProgress(activeUri, loaded, total);
      }
    }

    @Override
    public void onCompleted(int generation) {
      @Nullable Uri completedUri = activeUri;
      sourceLoader.complete(generation);
      activeUri = null;
      if (listener != null && completedUri != null) {
        listener.onLoadCompleted(completedUri, timeline.size());
      }
    }

    @Override
    public void onFailed(int segment, IOException cause) {
      @Nullable Uri failedUri = activeUri;
      IOException error =
          new IOException("Failed to load danmaku segment " + segment + " after retry", cause);
      if (failedUri != null && failedUri.equals(dataSourceUri)) {
        dataSourceDirty = true;
      }
      cancelActiveSession();
      if (listener != null && failedUri != null) {
        listener.onLoadError(failedUri, error);
      }
    }
  }

  private final class PlayerListener implements Player.Listener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      syncToPlayer(false);
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      syncToPlayer(false);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      syncToPlayer(false);
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
      if (danmakuView != null) {
        danmakuView.setPlaybackSpeed(playbackParameters.speed);
      }
    }

    @Override
    public void onPositionDiscontinuity(
        @NonNull Player.PositionInfo oldPosition,
        @NonNull Player.PositionInfo newPosition,
        @DiscontinuityReason int reason) {
      syncToPlayer(true);
    }
  }
}
