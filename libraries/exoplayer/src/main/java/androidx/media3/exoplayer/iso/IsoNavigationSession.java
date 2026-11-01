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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DiscMenuHighlight;
import androidx.media3.common.DiscMenuOverlay;
import androidx.media3.common.util.Log;
import androidx.media3.extractor.iso.sacd.SacdTocParser;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A native disc navigation session for DVD and Blu-ray ISO images.
 *
 * <p>The session drives the interactive playback of an image that was opened through a {@link
 * androidx.media3.common.CacheDataReader}. Instances must be {@link #close() closed} after use.
 */
public final class IsoNavigationSession implements Closeable {

  private static final String TAG = "IsoNavigationSession";

  /** Disc type reported for DVD images. */
  public static final int DISC_TYPE_DVD = 1;

  /** Disc type reported for Blu-ray images. */
  public static final int DISC_TYPE_BLURAY = 2;

  /** Disc menu navigation actions accepted by {@link #sendMenuAction}. */
  public static final int MENU_ACTION_UP = 1;
  public static final int MENU_ACTION_DOWN = 2;
  public static final int MENU_ACTION_LEFT = 3;
  public static final int MENU_ACTION_RIGHT = 4;
  public static final int MENU_ACTION_SELECT = 5;
  public static final int MENU_ACTION_MENU = 6;
  public static final int MENU_ACTION_POPUP = 7;
  public static final int MENU_ACTION_PREV = 8;
  public static final int MENU_ACTION_TITLE_MENU = 11;

  private static final int MAX_MENU_ACTIONS = 16;
  private static final int MAX_PUMP_ITERATIONS = 16;

  private static final boolean AVAILABLE;

  static {
    boolean loaded;
    try {
      System.loadLibrary("isoJNI");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      loaded = false;
    }
    AVAILABLE = loaded;
  }

  /** Returns whether the native disc navigation engine can be used on this device. */
  public static boolean isAvailable() {
    return AVAILABLE;
  }

  /**
   * Opens a disc navigation session.
   *
   * @param reader The reader providing the image data.
   * @return The session, or {@code null} if the native engine is unavailable.
   * @throws IOException If the image could not be opened.
   */
  @Nullable
  public static IsoNavigationSession open(androidx.media3.common.CacheDataReader reader)
      throws IOException {
    if (!AVAILABLE) {
      Log.w(TAG, "Disc navigation native library is unavailable");
      return null;
    }
    return new IsoNavigationSession(reader, /* flags= */ 0);
  }

  /** A single entry of a disc title's track listing. */
  public static final class TrackInfo {

    /** The stream number used to address the track, e.g. in {@code dvd-nav/subtitle/1}. */
    public final int streamNumber;

    /** The engine specific format of the track. */
    public final int format;

    /** The language of the track, or an empty string when unknown. */
    public final String language;

    public TrackInfo(int streamNumber, int format, String language) {
      this.streamNumber = streamNumber;
      this.format = format;
      this.language = language;
    }
  }

  /** The track listings of a disc title. */
  public static final class TitleInfo {

    /** The zero-based title index. */
    public final int index;

    /** The duration of the title in microseconds, or {@link C#TIME_UNSET} when unknown. */
    public final long durationUs;

    /** The number of playback angles of the title. */
    public final int angleCount;

    /** The chapter start times of the title in microseconds, unset values marked as such. */
    public final long[] chapterTimesUs;

    /** The clip names the title is composed of. */
    public final String[] clipNames;

    /** The audio tracks of the title. */
    public final List<TrackInfo> audioTracks;

    /** The subtitle tracks of the title. */
    public final List<TrackInfo> subtitleTracks;

    public TitleInfo(
        int index,
        long durationUs,
        int angleCount,
        long[] chapterTimesUs,
        String[] clipNames,
        List<TrackInfo> audioTracks,
        List<TrackInfo> subtitleTracks) {
      this.index = index;
      this.durationUs = durationUs;
      this.angleCount = angleCount;
      this.chapterTimesUs = chapterTimesUs;
      this.clipNames = clipNames;
      this.audioTracks = audioTracks;
      this.subtitleTracks = subtitleTracks;
    }
  }

  /** A finite still frame that should be skipped once its duration elapsed. */
  public static final class StillFrame {

    /** The engine specific identifier of the still. */
    public final long id;

    /** The discontinuity the still belongs to. */
    public final int discontinuityId;

    /** The display duration of the still in milliseconds. */
    public final long durationMs;

    public StillFrame(long id, int discontinuityId, long durationMs) {
      this.id = id;
      this.discontinuityId = discontinuityId;
      this.durationMs = durationMs;
    }
  }

  /** A command to execute against the native session. */
  public interface Command {

    /**
     * Applies the command.
     *
     * @param handle The native session handle.
     * @return Whether the command was applied.
     * @throws IOException If the command failed.
     */
    boolean apply(long handle) throws IOException;
  }

  private static final class MenuAction {

    public final int action;
    @Nullable public final java.util.function.Consumer<Boolean> callback;

    public MenuAction(int action, @Nullable java.util.function.Consumer<Boolean> callback) {
      this.action = action;
      this.callback = callback;
    }
  }

  private static final class SeekState {

    public final int discontinuityId;
    public final boolean timeSeekAllowed;

    public SeekState(int discontinuityId, boolean timeSeekAllowed) {
      this.discontinuityId = discontinuityId;
      this.timeSeekAllowed = timeSeekAllowed;
    }
  }

  private static final class TimelineSnapshot {

    public final int discontinuityId;
    public final int titleIndex;
    public final long startTimeUs;
    public final long durationUs;
    public final int flags;
    public final long reserved;
    public final boolean hasDuration;
    public final int[] chapterIndices;
    public final long[] chapterTimesUs;

    public TimelineSnapshot(
        int discontinuityId,
        int titleIndex,
        long startTimeUs,
        long durationUs,
        int flags,
        long reserved,
        boolean hasDuration,
        int[] chapterIndices,
        long[] chapterTimesUs) {
      this.discontinuityId = discontinuityId;
      this.titleIndex = titleIndex;
      this.startTimeUs = startTimeUs;
      this.durationUs = durationUs;
      this.flags = flags;
      this.reserved = reserved;
      this.hasDuration = hasDuration;
      this.chapterIndices = chapterIndices;
      this.chapterTimesUs = chapterTimesUs;
    }
  }

  private static final class DvdStreamInfo {

    public final int discontinuityId;
    public final int selectedStreamNumber;
    public final int[] values;

    public DvdStreamInfo(int discontinuityId, int selectedStreamNumber, int[] values) {
      this.discontinuityId = discontinuityId;
      this.selectedStreamNumber = selectedStreamNumber;
      this.values = values;
    }
  }

  // Fields accessed by the native engine. Do not rename.
  private volatile boolean bdjIdle;
  private volatile int discontinuityId;
  private volatile boolean infiniteStill;
  private volatile boolean menuActive;
  private volatile int menuDomain;
  private volatile boolean timeSeekAllowed;
  private volatile boolean titleEnded;
  private volatile boolean waitingForDrain;
  private volatile long stateVersion;
  private long finiteStillDurationMs;
  private long finiteStillId;

  private volatile long handle;
  private final int discType;
  private final boolean bdj;
  private volatile boolean hasMenu;
  private volatile IOException error;
  private volatile StillFrame pendingStill;
  private volatile DiscMenuHighlight menuHighlight;
  private volatile DiscMenuOverlay menuOverlay;
  private volatile TimelineSnapshot timeline;
  private volatile DvdStreamInfo dvdStreamInfo;
  private volatile SeekState seekState;
  private volatile List<TitleInfo> titles;
  private volatile long lastRefreshedStateVersion;
  private long waitGeneration;
  @Nullable private MenuAction pendingAction;

  private final CopyOnWriteArrayList<Runnable> stateListeners = new CopyOnWriteArrayList<>();
  private final ArrayBlockingQueue<MenuAction> actionQueue = new ArrayBlockingQueue<>(MAX_MENU_ACTIONS);
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "IsoNavigationSession");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
          });
  private final AtomicBoolean pumping = new AtomicBoolean();
  private final AtomicBoolean resuming = new AtomicBoolean();
  private final AtomicBoolean selecting = new AtomicBoolean();
  private final AtomicLong immediateAction = new AtomicLong();

  /**
   * Creates a session.
   *
   * @param reader The reader providing the image data.
   * @param flags The native open flags.
   * @throws IOException If the image could not be opened.
   */
  public IsoNavigationSession(androidx.media3.common.CacheDataReader reader, int flags)
      throws IOException {
    long handle = nativeOpen(reader, this, flags);
    if (handle == 0) {
      Log.e(TAG, "Could not open disc navigation session");
      throw new IOException("Could not open disc navigation session");
    }
    this.handle = handle;
    this.discType = nativeGetDiscType(handle);
    this.bdj = nativeIsBdj(handle);
    boolean hasMenu = nativeHasMenu(handle);
    if (hasMenu && discType == DISC_TYPE_DVD) {
      try {
        hasMenu = !SacdTocParser.isSacd(reader);
      } catch (IOException e) {
        Log.w(TAG, "Could not inspect DVD menu capability", e);
      }
    }
    this.hasMenu = hasMenu;
    this.lastRefreshedStateVersion = Long.MIN_VALUE;
    refreshState();
  }

  /** Returns the engine specific disc type. One of {@link #DISC_TYPE_DVD}, {@link #DISC_TYPE_BLURAY}. */
  public int getDiscType() {
    return discType;
  }

  /** Returns whether the disc uses BD-J menus. */
  public boolean isBdj() {
    return bdj;
  }

  /** Returns whether the disc has an interactive menu. */
  public boolean hasMenu() {
    return hasMenu;
  }

  /** Returns the pending error of the session, or {@code null} if it is healthy. */
  @Nullable
  public IOException getError() {
    return error;
  }

  /** Returns whether the session is prepared and has no error. */
  public boolean isHealthy() {
    return selecting.get() && handle != 0 && error == null;
  }

  /** Returns whether the current title has ended. */
  public boolean hasTitleEnded() {
    return titleEnded;
  }

  /** Returns whether the menu is currently active. */
  public boolean isMenuActive() {
    return menuActive;
  }

  /** Returns whether the engine is idle inside a BD-J menu. */
  public boolean isBdjIdle() {
    return bdjIdle;
  }

  /** Returns whether the engine shows an infinite still. */
  public boolean isInfiniteStill() {
    return infiniteStill;
  }

  /** Returns whether the engine is waiting for the current data to drain. */
  public boolean isWaitingForDrain() {
    return waitingForDrain;
  }

  /** Returns the id of the current playback discontinuity. */
  public int getDiscontinuityId() {
    return discontinuityId;
  }

  /** Returns the current menu domain of an interactive Blu-ray menu. */
  public int getMenuDomain() {
    return menuDomain;
  }

  /** Returns the finite still frame to skip, or {@code null}. */
  @Nullable
  public StillFrame getPendingStill() {
    return pendingStill;
  }

  /** Returns the highlight of the selected DVD menu button, or {@code null}. */
  @Nullable
  public DiscMenuHighlight getMenuHighlight() {
    return menuHighlight;
  }

  /** Returns the current overlay frame of an interactive Blu-ray menu, or {@code null}. */
  @Nullable
  public DiscMenuOverlay getMenuOverlay() {
    return menuOverlay;
  }

  /** Adds a listener that is notified on the pump thread when the seek state changed. */
  public void addStateListener(Runnable listener) {
    stateListeners.add(listener);
  }

  /** Removes a previously added state listener. */
  public void removeStateListener(Runnable listener) {
    stateListeners.remove(listener);
  }

  /**
   * Returns whether the session can seek by time within the given discontinuity.
   *
   * @param discontinuityId The discontinuity to check.
   * @return Whether time based seeking is allowed.
   */
  public boolean isTimeSeekAllowed(int discontinuityId) {
    SeekState state = seekState;
    return discontinuityId >= 0
        && state != null
        && state.discontinuityId == discontinuityId
        && handle != 0
        && state.timeSeekAllowed;
  }

  /**
   * Executes a command against the native session.
   *
   * @param command The command to execute.
   * @return Whether the command was applied.
   */
  public boolean performCommand(Command command) {
    if (handle != 0 && error == null) {
      try {
        if (command.apply(handle)) {
          refreshState();
          notifyStateChange();
          return true;
        }
      } catch (IOException e) {
        error = e;
        notifyStateChange();
      }
    }
    return false;
  }

  /**
   * Executes a command asynchronously on the pump thread.
   *
   * @param command The command to execute.
   * @return Whether the command was queued.
   */
  public boolean executeCommand(Command command) {
    if (handle == 0 || error != null) {
      return false;
    }
    try {
      executor.execute(
          () -> {
            try {
              synchronized (this) {
                if (handle != 0 && error == null) {
                  try {
                    if (command.apply(handle)) {
                      refreshState();
                      notifyStateChange();
                    }
                  } catch (Exception e) {
                    // The command or the native engine may throw pending exceptions.
                    error = toSessionError(e);
                    notifyStateChange();
                  }
                }
              }
            } finally {
              resuming.set(false);
            }
          });
      return true;
    } catch (RejectedExecutionException e) {
      return false;
    }
  }

  /**
   * Queues a disc menu action.
   *
   * @param action The action code. One of the {@code MENU_ACTION_*} constants.
   * @param callback The consumer invoked with the pump result of the action, or {@code null}.
   * @return Whether the action was queued.
   */
  public boolean sendMenuAction(int action, @Nullable java.util.function.Consumer<Boolean> callback) {
    if (action < 1
        || (action > MENU_ACTION_PREV && action != MENU_ACTION_TITLE_MENU)
        || !hasMenu
        || handle == 0
        || error != null
        || !actionQueue.offer(new MenuAction(action, callback))) {
      return false;
    }
    schedulePump();
    return true;
  }

  /**
   * Reads the next queued menu action code. Called by the native engine.
   *
   * @return The action code, or {@code 0} when no action is queued.
   */
  private long pollAction() {
    MenuAction action = actionQueue.poll();
    pendingAction = action;
    return action != null ? action.action : immediateAction.getAndSet(0);
  }

  /**
   * Returns the generation counter to pass to {@link #waitForRead}.
   *
   * @return The current wait generation.
   */
  public synchronized long getWaitGeneration() {
    return waitGeneration;
  }

  /**
   * Blocks the calling read until the session state changed or the engine requested a timeout.
   *
   * @param generation The generation observed before the read, see {@link #getWaitGeneration}.
   * @throws InterruptedIOException If the session was closed or the wait was interrupted.
   */
  public synchronized void waitForRead(long generation) throws InterruptedIOException {
    if (waitGeneration != generation) {
      return;
    }
    if (handle == 0) {
      throw new InterruptedIOException("Disc navigation session closed");
    }
    long timeoutMs = nativeGetWaitTimeoutMs(handle);
    try {
      if (timeoutMs < 0) {
        wait();
      } else {
        wait(Math.max(1, timeoutMs));
      }
      if (handle == 0) {
        throw new InterruptedIOException("Disc navigation session closed");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Disc navigation read interrupted");
    }
  }

  /** Schedules a pump iteration if none is in flight. */
  public void schedulePump() {
    if (error == null && pumping.compareAndSet(false, true)) {
      try {
        executor.execute(this::pump);
      } catch (RejectedExecutionException e) {
        pumping.set(false);
      }
    }
  }

  private void pump() {
    try {
      for (int i = 0; i < MAX_PUMP_ITERATIONS; i++) {
        MenuAction action;
        int result;
        synchronized (this) {
          if (handle == 0 || error != null) {
            break;
          }
          if (i > 0 && !hasPendingActions()) {
            break;
          }
          action = null;
          try {
            result = nativePump(handle);
            refreshState();
            notifyStateChange();
          } catch (Exception e) {
            // The native engine may throw pending exceptions at the call boundary.
            action = null;
            error = toSessionError(e);
            selecting.set(false);
            notifyStateChange();
            break;
          }
          action = pendingAction;
          pendingAction = null;
        }
        if (action != null && action.callback != null) {
          action.callback.accept(result > 0);
        }
      }
    } finally {
      pumping.set(false);
      synchronized (this) {
        if (handle != 0 && hasPendingActions()) {
          pumping.set(false);
          schedulePump();
        }
      }
    }
  }

  private static IOException toSessionError(Exception e) {
    return e instanceof IOException ? (IOException) e : new IOException("Disc navigation failed", e);
  }

  private synchronized boolean hasPendingActions() {
    return !actionQueue.isEmpty() || immediateAction.get() != 0;
  }

  /**
   * Reads navigation managed bytes from the disc.
   *
   * @param length The maximum number of bytes to read.
   * @param flags The read flags passed to the engine.
   * @param buffer The buffer to read into.
   * @return The number of bytes read.
   * @throws IOException If the session is closed or the read failed.
   */
  public synchronized int read(int length, int flags, byte[] buffer) throws IOException {
    if (handle == 0) {
      throw new IOException("Disc navigation session is closed");
    }
    if (length < 0 || buffer.length - length < 0) {
      throw new IndexOutOfBoundsException();
    }
    if (length == 0) {
      return 0;
    }
    IOException sessionError = error;
    if (sessionError != null) {
      throw sessionError;
    }
    int read = nativeRead(handle, buffer, 0, length, flags);
    refreshState();
    if (hasPendingActions()) {
      schedulePump();
    }
    return read;
  }

  /**
   * Reads bytes of a DVD image at the given logical position.
   *
   * @param position The logical position to read from.
   * @param buffer The buffer to read into.
   * @param param1 The first engine specific read parameter.
   * @param param2 The second engine specific read parameter.
   * @param length The maximum number of bytes to read.
   * @return The number of bytes read.
   * @throws IOException If the session is closed or the read failed.
   */
  public synchronized int readDvdBytes(
      long position, byte[] buffer, int param1, int param2, int length) throws IOException {
    if (handle == 0 || discType != DISC_TYPE_DVD) {
      throw new IOException("DVD session is closed");
    }
    if (length < 0 || buffer.length - length < 0) {
      throw new IndexOutOfBoundsException();
    }
    return nativeReadDvdBytes(handle, param1, param2, position, buffer, 0, length);
  }

  /**
   * Reads bytes of a Blu-ray replay stream.
   *
   * @param replayHandle The replay handle created by {@link #createBlurayReplay()}.
   * @param position The position to read from.
   * @param buffer The buffer to read into.
   * @param length The maximum number of bytes to read.
   * @return The number of bytes read.
   * @throws IOException If the replay session is closed.
   */
  public synchronized int readBlurayReplay(
      long replayHandle, long position, byte[] buffer, int length) throws IOException {
    ensureBlurayReplaySession();
    return nativeReadBlurayReplay(handle, replayHandle, position, buffer, 0, length);
  }

  /**
   * Appends a playback range to a Blu-ray replay stream.
   *
   * @param param1 The first engine specific append parameter.
   * @param param2 The second engine specific append parameter.
   * @param param3 The first engine specific append range.
   * @param param4 The second engine specific append range.
   * @throws IOException If the replay session is closed.
   */
  public synchronized void appendBlurayReplay(int param1, int param2, long param3, long param4)
      throws IOException {
    ensureBlurayReplaySession();
    nativeAppendBlurayReplay(handle, param3, param4, param1, param2);
  }

  /**
   * Creates a replay handle for the current Blu-ray playlist.
   *
   * @return The replay handle.
   * @throws IOException If the replay session is closed.
   */
  public synchronized long createBlurayReplay() throws IOException {
    ensureBlurayReplaySession();
    return nativeCreateBlurayReplay(handle, true);
  }

  /**
   * Creates a detached replay handle for the current Blu-ray playlist.
   *
   * @return The replay handle.
   * @throws IOException If the replay session is closed.
   */
  public synchronized long createDetachedBlurayReplay() throws IOException {
    ensureBlurayReplaySession();
    return nativeCreateBlurayReplay(handle, false);
  }

  /**
   * Releases a replay handle.
   *
   * @param replayHandle The replay handle to release.
   */
  public synchronized void releaseBlurayReplay(long replayHandle) {
    if (handle != 0) {
      nativeReleaseBlurayReplay(handle, replayHandle);
    }
  }

  private void ensureBlurayReplaySession() throws IOException {
    if (handle == 0 || discType != DISC_TYPE_BLURAY) {
      throw new IOException("Blu-ray replay session is closed");
    }
  }

  /**
   * Returns the index of the play item currently read in a Blu-ray image.
   *
   * @return The play item index, or {@code -1}.
   */
  public synchronized int getReadPlayItemIndex() {
    return handle == 0 || discType != DISC_TYPE_BLURAY ? -1 : nativeGetReadPlayItemIndex(handle);
  }

  /**
   * Returns the start time of the play item currently read in a Blu-ray image.
   *
   * @return The start time in microseconds, or {@link C#TIME_UNSET}.
   */
  public synchronized long getReadPlayItemStartTimeUs() {
    if (handle == 0 || discType != DISC_TYPE_BLURAY) {
      return C.TIME_UNSET;
    }
    long startTimeUs = nativeGetReadPlayItemStartTimeUs(handle);
    return startTimeUs >= 0 ? startTimeUs : C.TIME_UNSET;
  }

  /**
   * Returns the start time of the current playback segment.
   *
   * @return The start time in microseconds, or {@link C#TIME_UNSET}.
   */
  public synchronized long getSegmentStartTimeUs() {
    if (handle == 0 || (discType != DISC_TYPE_DVD && nativeGetCurrentTitleIndex(handle) == -1)) {
      return C.TIME_UNSET;
    }
    long startTimeUs = nativeGetSegmentStartTimeUs(handle);
    return startTimeUs >= 0 ? startTimeUs : C.TIME_UNSET;
  }

  /**
   * Returns the current title index.
   *
   * @return The title index, or {@code -1}.
   */
  public synchronized int getCurrentTitleIndex() {
    return handle != 0 ? nativeGetCurrentTitleIndex(handle) : -1;
  }

  /**
   * Seeks to the given time.
   *
   * @param timeUs The time to seek to in microseconds.
   * @return Whether the seek was applied.
   */
  public synchronized boolean seekToTimeUs(long timeUs) {
    return handle != 0 && timeUs >= 0 && nativeSeekToTimeUs(handle, timeUs);
  }

  /**
   * Selects the given title.
   *
   * @param titleIndex The title to select.
   * @return Whether the title was selected.
   */
  public synchronized boolean selectTitle(int titleIndex) {
    return handle != 0 && nativeSelectTitle(handle, titleIndex);
  }

  /**
   * Selects an audio or subtitle track of the current title.
   *
   * @param subtitle Whether to select a subtitle track.
   * @param trackNumber The engine specific track number to select.
   * @return Whether the track was selected.
   */
  public synchronized boolean selectTrack(boolean subtitle, int trackNumber) {
    return handle != 0 && nativeSelectTrack(handle, subtitle, trackNumber);
  }

  /**
   * Selects a track by its id, falling back to language matching.
   *
   * @param discontinuityId The expected discontinuity id.
   * @param subtitle Whether to select a subtitle track.
   * @param trackId The track id, e.g. {@code dvd-nav/audio/1}, or {@code null}.
   * @param language The preferred language tag, or {@code null}.
   * @return Whether the track was selected.
   */
  public boolean selectTrackById(
      int discontinuityId, boolean subtitle, @Nullable String trackId, @Nullable String language) {
    long handle = this.handle;
    if (discontinuityId < 0
        || this.discontinuityId != discontinuityId
        || nativeGetCurrentTitleIndex(handle) == -1) {
      return false;
    }
    String[] trackInfo =
        nativeGetTrackInfo(handle, nativeGetCurrentTitleIndex(handle), subtitle,
            nativeGetCurrentClipIndex(handle));
    int requestedNumber;
    if (discType == DISC_TYPE_DVD && trackId != null) {
      String prefix = subtitle ? "dvd-nav/subtitle/" : "dvd-nav/audio/";
      if (!trackId.startsWith(prefix)) {
        return false;
      }
      try {
        requestedNumber = Integer.parseInt(trackId.substring(prefix.length()));
        if (requestedNumber < 0) {
          requestedNumber = -1;
        } else if (requestedNumber >= (subtitle ? 32 : 8)) {
          requestedNumber = -1;
        }
      } catch (NumberFormatException e) {
        requestedNumber = -1;
      }
      if (requestedNumber == -1) {
        return false;
      }
      for (int i = 0; i + 3 < trackInfo.length; i += 4) {
        if (Integer.parseInt(trackInfo[i]) == requestedNumber) {
          return nativeSelectTrack(handle, subtitle, requestedNumber);
        }
      }
      return false;
    }
    if (discType == DISC_TYPE_BLURAY && trackId != null) {
      try {
        requestedNumber = Integer.parseInt(trackId.substring(trackId.lastIndexOf('/') + 1));
      } catch (NumberFormatException e) {
        requestedNumber = -1;
      }
    } else {
      requestedNumber = -1;
    }
    int nativeIndex = -1;
    for (int i = 0; i + 3 < trackInfo.length; i += 4) {
      if (requestedNumber >= 0 && Integer.parseInt(trackInfo[i + 1]) == requestedNumber) {
        nativeIndex = Integer.parseInt(trackInfo[i]);
        break;
      }
    }
    if (nativeIndex == -1 && language != null && !language.isEmpty()) {
      for (int i = 0; i + 3 < trackInfo.length; i++) {
        if (language.equals(trackInfo[i + 3])) {
          if (nativeIndex != -1) {
            return false;
          }
          nativeIndex = Integer.parseInt(trackInfo[i]);
        }
      }
    }
    if (nativeIndex == -1 && requestedNumber < 0 && trackInfo.length == 4) {
      nativeIndex = Integer.parseInt(trackInfo[0]);
    }
    return nativeIndex != -1 && nativeSelectTrack(handle, subtitle, nativeIndex);
  }

  /**
   * Selects the next title once the current data drained.
   *
   * @param discontinuityId The expected discontinuity id.
   * @param titleIndex The title to select.
   */
  public void selectTitleAfterDrain(int discontinuityId, int titleIndex) {
    if (handle == 0
        || error != null
        || titleIndex < 0
        || this.discontinuityId != discontinuityId
        || !titleEnded
        || menuActive
        || waitingForDrain
        || !selecting.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              synchronized (this) {
                if (handle != 0
                    && error == null
                    && this.discontinuityId == discontinuityId
                    && titleEnded
                    && !menuActive
                    && !waitingForDrain) {
                  try {
                    if (nativeSelectTitle(handle, titleIndex)) {
                      refreshState();
                      notifyStateChange();
                    }
                  } catch (Exception e) {
                    // The native engine may throw pending exceptions at the call boundary.
                    error = toSessionError(e);
                    notifyStateChange();
                  }
                }
              }
            } finally {
              selecting.set(false);
            }
          });
    } catch (RejectedExecutionException e) {
      selecting.set(false);
    }
  }

  /**
   * Resumes reading after the current data drained, e.g. at the end of a menu still.
   *
   * @param discontinuityId The expected discontinuity id.
   * @return Whether the resume was queued.
   */
  public boolean resumeAfterDrain(int discontinuityId) {
    if (error == null && waitingForDrain && this.discontinuityId == discontinuityId
        && resuming.compareAndSet(false, true)) {
      try {
        executor.execute(
            () -> {
              try {
                synchronized (this) {
                  if (handle != 0 && error == null) {
                    try {
                      boolean resumed =
                          discType == DISC_TYPE_DVD
                              ? nativeResumeDvdWait(handle, discontinuityId)
                              : nativeResumeBlurayPlaylist(handle, discontinuityId);
                      refreshState();
                      notifyStateChange();
                      if (resumed && hasPendingActions()) {
                        schedulePump();
                      }
                    } catch (Exception e) {
                      // The native engine may throw pending exceptions at the call boundary.
                      error = toSessionError(e);
                      selecting.set(false);
                      notifyStateChange();
                    }
                  }
                }
              } finally {
                resuming.set(false);
              }
            });
        return true;
      } catch (RejectedExecutionException e) {
        resuming.set(false);
      }
    }
    return false;
  }

  /**
   * Skips the given finite still once its duration elapsed.
   *
   * @param still The still to skip.
   * @return Whether the skip was queued.
   */
  public boolean skipStill(StillFrame still) {
    StillFrame current = pendingStill;
    if (current != null
        && current.id == still.id
        && current.discontinuityId == still.discontinuityId
        && error == null
        && resuming.compareAndSet(false, true)) {
      try {
        executor.execute(
            () -> {
              try {
                synchronized (this) {
                  if (handle != 0 && error == null) {
                    try {
                      nativeSkipFiniteStill(handle, still.discontinuityId, still.id);
                      refreshState();
                    } catch (Exception e) {
                      // The native engine may throw pending exceptions at the call boundary.
                      error = toSessionError(e);
                      selecting.set(false);
                      notifyStateChange();
                    }
                  }
                }
              } finally {
                resuming.set(false);
              }
            });
        return true;
      } catch (RejectedExecutionException e) {
        resuming.set(false);
      }
    }
    return false;
  }

  /**
   * Disables subtitle rendering of the engine for the given discontinuity.
   *
   * @param discontinuityId The expected discontinuity id.
   * @return Whether subtitles were disabled.
   */
  public boolean disableSubtitles(int discontinuityId) {
    long handle = this.handle;
    return discontinuityId >= 0
        && this.discontinuityId == discontinuityId
        && nativeDisableSubtitles(handle);
  }

  /**
   * Returns the DVD read position information.
   *
   * @param out The array of length three receiving the position information.
   * @throws IOException If the position information is unavailable.
   */
  public synchronized void getDvdReadPosition(long[] out) throws IOException {
    if (handle == 0 || discType != DISC_TYPE_DVD || out.length != 3) {
      throw new IOException("DVD read position is unavailable");
    }
    nativeGetDvdReadPosition(handle, out);
  }

  /**
   * Returns the stream metadata of a DVD discontinuity.
   *
   * @param discontinuityId The discontinuity to describe.
   * @return The stream metadata, or {@code null} if unavailable.
   */
  @Nullable
  public synchronized DvdStreamInfo getDvdStreamInfo(int discontinuityId) {
    if (handle != 0 && discType == DISC_TYPE_DVD && this.discontinuityId == discontinuityId) {
      DvdStreamInfo previous = dvdStreamInfo;
      int selectedStreamNumber =
          previous == null || previous.discontinuityId != discontinuityId
              ? -1
              : previous.selectedStreamNumber;
      int[] values = nativeGetDvdStreamInfo(handle, discontinuityId, selectedStreamNumber);
      if (values != null) {
        if (values.length != 108 || values[0] != discontinuityId) {
          throw new IllegalStateException("Invalid DVD stream metadata");
        }
        dvdStreamInfo = new DvdStreamInfo(discontinuityId, selectedStreamNumber, values);
      }
      DvdStreamInfo current = dvdStreamInfo;
      return current != null && current.discontinuityId == discontinuityId ? current : null;
    }
    return null;
  }

  /**
   * Returns the titles of the disc.
   *
   * @return An unmodifiable list of title information.
   */
  public List<TitleInfo> getTitles() {
    List<TitleInfo> currentTitles = titles;
    if (currentTitles != null) {
      return currentTitles;
    }
    synchronized (this) {
      if (handle == 0) {
        return Collections.emptyList();
      }
      if (titles != null) {
        return titles;
      }
      int titleCount = nativeGetTitleCount(handle);
      ArrayList<TitleInfo> result = new ArrayList<>(titleCount);
      for (int i = 0; i < titleCount; i++) {
        long[] titleInfo = nativeGetTitleInfo(handle, i);
        if (titleInfo != null) {
          long durationUs = titleInfo[0];
          int angleCount = (int) titleInfo[1];
          long[] chapterTimesUs = nativeGetChapterTimesUs(handle, i);
          for (int j = 0; j < chapterTimesUs.length; j++) {
            if (chapterTimesUs[j] < 0) {
              chapterTimesUs[j] = C.TIME_UNSET;
            }
          }
          String[] clipNames = nativeGetClipNames(handle, i);
          nativeGetClipTimesUs(handle, i);
          result.add(
              new TitleInfo(
                  i,
                  durationUs,
                  angleCount,
                  chapterTimesUs,
                  clipNames,
                  discType == DISC_TYPE_DVD
                      ? Collections.emptyList()
                      : getTrackInfos(i, 0, false),
                  discType == DISC_TYPE_DVD
                      ? Collections.emptyList()
                      : getTrackInfos(i, 0, true)));
        }
      }
      titles = Collections.unmodifiableList(result);
      refreshTimeline();
      return titles;
    }
  }

  /**
   * Returns the track listing of a title.
   *
   * @param titleIndex The title to describe.
   * @param clipIndex The clip of the title to describe.
   * @param subtitle Whether to list subtitle tracks.
   * @return An unmodifiable list of track information.
   */
  public synchronized List<TrackInfo> getTrackInfos(int titleIndex, int clipIndex,
      boolean subtitle) {
    if (handle == 0) {
      return Collections.emptyList();
    }
    String[] trackInfo = nativeGetTrackInfo(handle, titleIndex, subtitle, clipIndex);
    if (trackInfo == null) {
      return Collections.emptyList();
    }
    ArrayList<TrackInfo> result = new ArrayList<>(trackInfo.length / 4);
    for (int i = 0; i + 3 < trackInfo.length; i += 4) {
      Integer.parseInt(trackInfo[i]);
      result.add(
          new TrackInfo(
              Integer.parseInt(trackInfo[i + 1]),
              Integer.parseInt(trackInfo[i + 2]),
              trackInfo[i + 3]));
    }
    return Collections.unmodifiableList(result);
  }

  private synchronized void refreshState() {
    if (handle == 0) {
      return;
    }
    refreshTimeline();
    long version = stateVersion;
    if (lastRefreshedStateVersion == version) {
      return;
    }
    StillFrame still =
        finiteStillDurationMs > 0
            ? new StillFrame(finiteStillId, discontinuityId, finiteStillDurationMs)
            : null;
    pendingStill = still;
    if (discType == DISC_TYPE_DVD) {
      int[] highlight = menuActive ? nativeGetDvdMenuHighlight(handle) : null;
      menuHighlight = highlight != null ? new DiscMenuHighlight(highlight) : null;
    } else {
      refreshMenuOverlay();
    }
    lastRefreshedStateVersion = version;
    SeekState current = seekState;
    if (current == null
        || current.timeSeekAllowed != timeSeekAllowed
        || current.discontinuityId != discontinuityId) {
      seekState = new SeekState(discontinuityId, timeSeekAllowed);
      for (Runnable listener : stateListeners) {
        listener.run();
      }
    }
    waitGeneration++;
    notifyAll();
  }

  private synchronized void refreshMenuOverlay() {
    int[] info = nativeGetHdmvMenuOverlayInfo(handle, menuDomain);
    if (info == null) {
      return;
    }
    int[] pixels = null;
    if (info[3] != 0) {
      pixels = new int[info[1] * info[2]];
      if (!nativeCopyHdmvMenuOverlay(handle, info[0], pixels)) {
        return;
      }
    }
    menuDomain = info[0];
    menuOverlay = new DiscMenuOverlay(info[0], info[1], info[2], pixels);
  }

  private synchronized void refreshTimeline() {
    if (discType == DISC_TYPE_DVD) {
      TimelineSnapshot current = timeline;
      if (current != null && current.discontinuityId == discontinuityId) {
        return;
      }
      TimelineSnapshot snapshot = null;
      long[] navigationTimeline =
          handle == 0 ? null : nativeGetDvdNavigationTimeline(handle);
      int id = discontinuityId;
      if (navigationTimeline != null
          && navigationTimeline.length >= 7
          && (navigationTimeline.length - 7) % 2 == 0
          && navigationTimeline[0] == id) {
        int chapterCount = (navigationTimeline.length - 7) / 2;
        int[] chapterIndices = new int[chapterCount];
        long[] chapterTimesUs = new long[chapterCount];
        for (int i = 0; i < chapterCount; i++) {
          chapterIndices[i] = (int) navigationTimeline[i * 2 + 7];
          chapterTimesUs[i] = navigationTimeline[i * 2 + 8];
        }
        long startTimeUs = navigationTimeline[2];
        if (startTimeUs < 0) {
          startTimeUs = C.TIME_UNSET;
        }
        long durationUs = navigationTimeline[3];
        snapshot =
            new TimelineSnapshot(
                id,
                (int) navigationTimeline[1],
                startTimeUs,
                durationUs >= 0 ? durationUs : C.TIME_UNSET,
                (int) navigationTimeline[4],
                navigationTimeline[5],
                navigationTimeline[6] != 0,
                chapterIndices,
                chapterTimesUs);
      }
      timeline = snapshot;
      return;
    }
    int titleIndex = getCurrentTitleIndex();
    long startTimeUs = titleIndex == -1 ? C.TIME_UNSET : getSegmentStartTimeUs();
    long durationUs = C.TIME_UNSET;
    long[] chapterTimesUs = new long[0];
    List<TitleInfo> currentTitles = titles;
    if (titleIndex != -1 && currentTitles != null) {
      for (int i = 0; i < currentTitles.size(); i++) {
        TitleInfo title = currentTitles.get(i);
        if (title.index == titleIndex) {
          if (title.durationUs > 0) {
            chapterTimesUs = title.chapterTimesUs;
            durationUs = title.durationUs;
          }
          break;
        }
      }
    }
    TimelineSnapshot current = timeline;
    if (current != null
        && current.discontinuityId == discontinuityId
        && current.titleIndex == titleIndex
        && current.startTimeUs == startTimeUs
        && current.durationUs == durationUs) {
      return;
    }
    int[] chapterIndices = new int[chapterTimesUs.length];
    for (int i = 0; i < chapterIndices.length; i++) {
      chapterIndices[i] = i;
    }
    timeline =
        new TimelineSnapshot(
            discontinuityId,
            titleIndex,
            startTimeUs,
            durationUs,
            titleIndex == -1 ? 0 : 1,
            titleIndex,
            durationUs > 0,
            chapterIndices,
            chapterTimesUs);
  }

  private synchronized void notifyStateChange() {
    waitGeneration++;
    notifyAll();
  }

  @Override
  public void close() {
    ArrayList<MenuAction> pendingActions;
    synchronized (this) {
      executor.shutdownNow();
      selecting.set(false);
      if (handle != 0) {
        nativeClose(handle);
        handle = 0;
      }
      pendingActions = new ArrayList<>(actionQueue);
      actionQueue.clear();
      immediateAction.set(0);
      waitingForDrain = false;
      bdjIdle = false;
      timeSeekAllowed = false;
      seekState = null;
      stateListeners.clear();
      pendingStill = null;
      titleEnded = false;
      menuActive = false;
      menuHighlight = null;
      menuOverlay = null;
      titles = null;
      timeline = null;
      dvdStreamInfo = null;
      menuDomain = 0;
      waitGeneration++;
      notifyAll();
    }
    for (int i = 0; i < pendingActions.size(); i++) {
      java.util.function.Consumer<Boolean> callback = pendingActions.get(i).callback;
      if (callback != null) {
        callback.accept(Boolean.FALSE);
      }
    }
  }

  // Native methods. Do not rename.

  private static native long nativeOpen(
      androidx.media3.common.CacheDataReader reader, IsoNavigationSession session, int flags);

  private static native int nativeGetDiscType(long handle);

  private static native boolean nativeIsBdj(long handle);

  private static native boolean nativeHasMenu(long handle);

  private static native int nativePump(long handle);

  private static native int nativeRead(long handle, byte[] buffer, int offset, int length, int flags);

  private static native int nativeReadDvdBytes(
      long handle, int param1, int param2, long position, byte[] buffer, int offset, int length);

  private static native int nativeReadBlurayReplay(
      long handle, long replayHandle, long position, byte[] buffer, int offset, int length);

  private static native long nativeCreateBlurayReplay(long handle, boolean primary);

  private static native void nativeReleaseBlurayReplay(long handle, long replayHandle);

  private static native void nativeAppendBlurayReplay(
      long handle, long clipIndex, long startUs, int flags, int angle);

  private static native boolean nativeResumeDvdWait(long handle, int discontinuityId);

  private static native boolean nativeResumeBlurayPlaylist(long handle, int discontinuityId);

  private static native boolean nativeSeekToTimeUs(long handle, long timeUs);

  private static native boolean nativeSeekToChapter(long handle, int chapterIndex);

  private static native boolean nativeSelectTitle(long handle, int titleIndex);

  private static native boolean nativeSelectTrack(long handle, boolean subtitle, int trackNumber);

  private static native boolean nativeSkipFiniteStill(
      long handle, int discontinuityId, long stillId);

  private static native boolean nativeDisableSubtitles(long handle);

  private static native void nativeCheckBdjRuntimeCompatibility();

  private static native int[] nativeGetDvdMenuHighlight(long handle);

  private static native long[] nativeGetDvdNavigationTimeline(long handle);

  private static native void nativeGetDvdReadPosition(long handle, long[] out);

  private static native int[] nativeGetDvdStreamInfo(long handle, int discontinuityId,
      int selectedStreamNumber);

  private static native int[] nativeGetHdmvMenuOverlayInfo(long handle, int domain);

  private static native boolean nativeCopyHdmvMenuOverlay(long handle, int domain, int[] pixels);

  private static native long[] nativeGetTitleInfo(long handle, int titleIndex);

  private static native int nativeGetTitleCount(long handle);

  private static native long[] nativeGetChapterTimesUs(long handle, int titleIndex);

  private static native long[] nativeGetClipTimesUs(long handle, int titleIndex);

  private static native String[] nativeGetClipNames(long handle, int titleIndex);

  private static native long[][] nativeGetClipInfo(long handle, int clipIndex);

  private static native int nativeGetCurrentClipIndex(long handle);

  private static native int nativeGetCurrentTitleIndex(long handle);

  private static native int nativeGetReadPlayItemIndex(long handle);

  private static native long nativeGetReadPlayItemStartTimeUs(long handle);

  private static native long nativeGetSegmentStartTimeUs(long handle);

  private static native String[] nativeGetTrackInfo(
      long handle, int titleIndex, boolean subtitle, int clipIndex);

  private static native int nativeGetSelectedTrackIndex(long handle, boolean subtitle);

  private static native long nativeGetWaitTimeoutMs(long handle);

  private static native void nativeClose(long handle);
}
