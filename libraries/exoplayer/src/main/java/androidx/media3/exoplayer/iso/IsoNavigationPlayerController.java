/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.iso;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.DiscMenuController;
import androidx.media3.common.DiscMenuHighlight;
import androidx.media3.common.DiscMenuOverlay;
import androidx.media3.common.DiscMenuState;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataReader;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.iso.sacd.SacdTocParser;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Controls disc navigation playback of ISO images on top of an {@link ExoPlayer}.
 *
 * <p>The controller prepares a navigation session for ISO media items, exposes the disc menu
 * through {@link DiscMenuController} and keeps the {@link DiscMenuState} of the player up to date.
 * All methods must be called on the player application thread.
 */
@UnstableApi
public final class IsoNavigationPlayerController implements DiscMenuController, Closeable {

  private static final String TAG = "IsoNavigation";

  private static final long STILL_POLL_INTERVAL_MS = 50;
  private static final long BDJ_OPEN_TIMEOUT_MS = 240000;
  private static final long READY_OPEN_TIMEOUT_MS = 15000;
  private static final long OPEN_TIMEOUT_MS = 3000;

  private final ExoPlayer player;
  private final Handler handler;
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

  @Nullable private MediaItem mediaItem;
  @Nullable private DataSource.Factory dataSourceFactory;
  @Nullable private IsoNavigationSession session;
  @Nullable private CacheDataReader reader;
  @Nullable private CancellationSignal preparationSignal;
  private boolean navigationStarted;
  private boolean openPending;
  @Nullable private Consumer<OpenResult> openCallback;
  @Nullable private Boolean actionAccepted;
  @Nullable private OpenResult openFailureResult;
  private long menuOpenStartedMs;
  private long generation;
  @Nullable private IsoNavigationSession.StillFrame trackedStill;
  private long stillRemainingMs;
  private long stillElapsedAnchorMs;
  private boolean stillSkipQueued;
  private DiscMenuState state =
      new DiscMenuState(DiscMenuState.AVAILABILITY_UNAVAILABLE, DiscMenuState.PHASE_IDLE,
          /* navigationPlaybackOngoing= */ false);

  private final Runnable ticker = this::updateNavigation;

  /** Creates a controller for the given player. */
  public IsoNavigationPlayerController(ExoPlayer player) {
    this.player = player;
    this.handler = new Handler(player.getApplicationLooper());
  }

  /** Returns the player the controller is attached to. */
  public Player getPlayer() {
    return player;
  }

  /**
   * Prepares disc navigation for the media item of the given media source.
   *
   * @param context The context used to prepare the BD-J runtime.
   * @param mediaSource The media source created for the media item.
   * @param dataSourceFactory The factory used to read the ISO image.
   * @return The media source to play, or {@code null} if playback cannot proceed.
   */
  @Nullable
  public MediaSource prepareMediaSource(
      Context context, MediaSource mediaSource, DataSource.Factory dataSourceFactory) {
    checkThread();
    closeNavigation();
    generation++;
    this.mediaItem = mediaSource.getMediaItem();
    this.dataSourceFactory = dataSourceFactory;
    if (mediaItem == null
        || mediaItem.localConfiguration == null
        || !IsoDiscPreparation.isIso(mediaItem)
        || !IsoNavigationSession.isAvailable()) {
      return mediaSource;
    }
    IsoDataReader reader = new IsoDataReader(dataSourceFactory, mediaItem.localConfiguration.uri);
    try {
      if (SacdTocParser.isSacd(reader)) {
        reader.close();
        return mediaSource;
      }
    } catch (IOException e) {
      Log.w(TAG, "Could not inspect disc image", e);
      closeQuietly(reader);
      return mediaSource;
    }
    IsoNavigationSession session;
    try {
      session = IsoNavigationSession.open(reader);
    } catch (IOException e) {
      Log.w(TAG, "Could not open disc navigation session", e);
      closeQuietly(reader);
      return mediaSource;
    }
    if (session == null) {
      closeQuietly(reader);
      return mediaSource;
    }
    this.reader = reader;
    this.session = session;
    if (session.hasMenu()) {
      prepareBdjRuntime(context);
    }
    session.addStateListener(this::onSessionStateChangedAsync);
    navigationStarted = true;
    updateState();
    handler.post(ticker);
    return mediaSource;
  }

  private void prepareBdjRuntime(Context context) {
    preparationSignal = new CancellationSignal();
    try {
      BdjRuntime.prepare(context, preparationSignal);
    } catch (BdjRuntime.MissingException e) {
      openFailureResult = OpenResult.RUNTIME_MISSING;
    } catch (InterruptedIOException e) {
      openFailureResult = OpenResult.UNAVAILABLE;
    } catch (IOException e) {
      Log.w(TAG, "BD-J runtime preparation failed", e);
      openFailureResult = OpenResult.UNAVAILABLE;
    }
  }

  /** Returns whether navigation playback is started for the current media item. */
  public boolean isStarted() {
    checkThread();
    return navigationStarted;
  }

  /** Returns the current disc menu state. */
  public DiscMenuState getDiscMenuState() {
    checkThread();
    return state;
  }

  /** Adds a listener that is notified when the disc menu state changed. */
  public void addListener(Runnable listener) {
    checkThread();
    listeners.add(listener);
  }

  /** Removes a previously added listener. */
  public void removeListener(Runnable listener) {
    checkThread();
    listeners.remove(listener);
  }

  @Override
  public boolean open(String action, Consumer<OpenResult> callback) {
    checkThread();
    int menuAction = mapMenuAction(action);
    if (menuAction != IsoNavigationSession.MENU_ACTION_MENU
        && menuAction != IsoNavigationSession.MENU_ACTION_TITLE_MENU
        && menuAction != IsoNavigationSession.MENU_ACTION_POPUP) {
      return false;
    }
    if (state.availability != DiscMenuState.AVAILABILITY_AVAILABLE || openPending) {
      return false;
    }
    openPending = true;
    actionAccepted = null;
    openCallback = callback;
    menuOpenStartedMs = SystemClock.elapsedRealtime();
    if (!navigationStarted || session == null) {
      OpenResult failureResult = openFailureResult;
      openPending = false;
      openCallback = null;
      updateState();
      if (failureResult != null) {
        callback.accept(failureResult);
        return true;
      }
      return false;
    }
    IsoNavigationSession currentSession = session;
    long currentGeneration = generation;
    boolean accepted =
        currentSession.sendMenuAction(
            menuAction,
            success ->
                handler.post(
                    () -> {
                      if (currentGeneration != generation) {
                        return;
                      }
                      actionAccepted = success;
                      updateNavigation();
                    }));
    if (!accepted) {
      actionAccepted = Boolean.FALSE;
    }
    updateState();
    updateNavigation();
    return true;
  }

  @Override
  public boolean sendAction(String action) {
    checkThread();
    int menuAction = mapMenuAction(action);
    if (menuAction < 0 || session == null) {
      return false;
    }
    return session.sendMenuAction(menuAction, /* callback= */ null);
  }

  @Override
  public boolean isActive() {
    checkThread();
    return state.phase == DiscMenuState.PHASE_ACTIVE;
  }

  @Override
  public boolean isNavigationPlayback() {
    checkThread();
    return navigationStarted;
  }

  @Override
  public boolean supportsPointer() {
    checkThread();
    return navigationStarted;
  }

  @Override
  @Nullable
  public DiscMenuHighlight getMenuHighlight() {
    checkThread();
    return session != null ? session.getMenuHighlight() : null;
  }

  @Override
  @Nullable
  public DiscMenuOverlay getMenuOverlay(int domain) {
    checkThread();
    DiscMenuOverlay overlay = session != null ? session.getMenuOverlay() : null;
    return overlay != null && overlay.id == domain ? overlay : null;
  }

  @Override
  public void close() {
    checkThread();
    generation++;
    openFailureResult = null;
    mediaItem = null;
    dataSourceFactory = null;
    closeNavigation();
    @Nullable Consumer<OpenResult> callback = openCallback;
    openCallback = null;
    actionAccepted = null;
    openPending = false;
    navigationStarted = false;
    updateState();
    if (callback != null) {
      callback.accept(OpenResult.CANCELLED);
    }
  }

  private void closeNavigation() {
    handler.removeCallbacks(ticker);
    trackedStill = null;
    stillRemainingMs = 0;
    stillElapsedAnchorMs = 0;
    stillSkipQueued = false;
    if (preparationSignal != null) {
      preparationSignal.cancel();
      preparationSignal = null;
    }
    if (session != null) {
      session.removeStateListener(this::onSessionStateChangedAsync);
      session.close();
      session = null;
    }
    if (reader != null) {
      closeQuietly(reader);
      reader = null;
    }
    navigationStarted = false;
  }

  private void onSessionStateChangedAsync() {
    handler.post(this::onSessionStateChanged);
  }

  private void onSessionStateChanged() {
    checkThread();
    updateNavigation();
  }

  private void updateNavigation() {
    if (session != null) {
      session.schedulePump();
      updateStillFrame();
    }
    updateState();
    if (openCallback != null) {
      checkOpenResult();
    }
    if (session != null || openCallback != null) {
      handler.removeCallbacks(ticker);
      handler.postDelayed(ticker, STILL_POLL_INTERVAL_MS);
    }
  }

  private void updateStillFrame() {
    IsoNavigationSession currentSession = session;
    IsoNavigationSession.StillFrame still =
        currentSession != null ? currentSession.getPendingStill() : null;
    if (!navigationStarted
        || still == null
        || still.discontinuityId != currentSession.getDiscontinuityId()) {
      trackedStill = null;
      stillRemainingMs = 0;
      stillElapsedAnchorMs = 0;
      stillSkipQueued = false;
      return;
    }
    if (trackedStill == null || trackedStill.id != still.id) {
      trackedStill = still;
      stillRemainingMs = still.durationMs;
      stillElapsedAnchorMs = 0;
      stillSkipQueued = false;
    }
    long now = SystemClock.elapsedRealtime();
    if (stillElapsedAnchorMs != 0) {
      stillRemainingMs -= now - stillElapsedAnchorMs;
    }
    boolean playing =
        player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady();
    stillElapsedAnchorMs = playing ? now : 0;
    if (!playing || stillRemainingMs > 0 || stillSkipQueued) {
      return;
    }
    if (currentSession.skipStill(still)) {
      stillSkipQueued = true;
    }
  }

  private void checkOpenResult() {
    @Nullable Consumer<OpenResult> callback = openCallback;
    if (callback == null) {
      return;
    }
    if (Boolean.FALSE.equals(actionAccepted)) {
      completeOpen(OpenResult.UNAVAILABLE);
      return;
    }
    IsoNavigationSession currentSession = session;
    if (currentSession == null) {
      completeOpen(OpenResult.CANCELLED);
      return;
    }
    if (Boolean.TRUE.equals(actionAccepted) && currentSession.isMenuActive()) {
      completeOpen(OpenResult.OPENED);
      return;
    }
    long elapsed = SystemClock.elapsedRealtime() - menuOpenStartedMs;
    long timeoutMs;
    if (currentSession.isBdj()) {
      timeoutMs = BDJ_OPEN_TIMEOUT_MS;
    } else if (player.getPlaybackState() == Player.STATE_READY) {
      timeoutMs = READY_OPEN_TIMEOUT_MS;
    } else {
      timeoutMs = OPEN_TIMEOUT_MS;
    }
    if (elapsed >= timeoutMs) {
      completeOpen(OpenResult.TIMED_OUT);
    }
  }

  private void completeOpen(OpenResult result) {
    @Nullable Consumer<OpenResult> callback = openCallback;
    openCallback = null;
    actionAccepted = null;
    openPending = false;
    updateState();
    if (callback != null) {
      callback.accept(result);
    }
  }

  private void updateState() {
    IsoNavigationSession currentSession = session;
    int availability =
        navigationStarted && currentSession != null && currentSession.hasMenu()
            ? DiscMenuState.AVAILABILITY_AVAILABLE
            : DiscMenuState.AVAILABILITY_UNAVAILABLE;
    int phase;
    if (openPending) {
      phase = DiscMenuState.PHASE_OPENING;
    } else if (currentSession != null && currentSession.isMenuActive()) {
      phase = DiscMenuState.PHASE_ACTIVE;
    } else {
      phase = DiscMenuState.PHASE_IDLE;
    }
    boolean navigationPlaybackOngoing = openPending || navigationStarted;
    DiscMenuState newState =
        new DiscMenuState(availability, phase, navigationPlaybackOngoing);
    if (newState.equals(state)) {
      return;
    }
    state = newState;
    player.setDiscMenuState(newState);
    for (Runnable listener : new ArrayList<>(listeners)) {
      if (!newState.equals(state)) {
        break;
      }
      listener.run();
    }
  }

  private static int mapMenuAction(String action) {
    switch (action) {
      case "up":
        return IsoNavigationSession.MENU_ACTION_UP;
      case "down":
        return IsoNavigationSession.MENU_ACTION_DOWN;
      case "left":
        return IsoNavigationSession.MENU_ACTION_LEFT;
      case "right":
        return IsoNavigationSession.MENU_ACTION_RIGHT;
      case "select":
        return IsoNavigationSession.MENU_ACTION_SELECT;
      case "menu":
        return IsoNavigationSession.MENU_ACTION_MENU;
      case "popup":
        return IsoNavigationSession.MENU_ACTION_POPUP;
      case "prev":
        return IsoNavigationSession.MENU_ACTION_PREV;
      case "title-menu":
        return IsoNavigationSession.MENU_ACTION_TITLE_MENU;
      default:
        return -1;
    }
  }

  private void checkThread() {
    if (Looper.myLooper() != player.getApplicationLooper()) {
      throw new IllegalStateException("Disc navigation must run on the player application thread");
    }
  }

  private static void closeQuietly(CacheDataReader reader) {
    try {
      if (reader instanceof Closeable) {
        ((Closeable) reader).close();
      }
    } catch (IOException e) {
      Log.w(TAG, "Could not close disc reader", e);
    }
  }
}
