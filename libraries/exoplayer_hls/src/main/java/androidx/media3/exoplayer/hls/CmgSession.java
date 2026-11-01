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
package androidx.media3.exoplayer.hls;

import android.text.TextUtils;
import androidx.media3.common.util.Log;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Owns the stateful CMG native decryption runtime for a single {@link HlsMediaSource}.
 *
 * <p>The native library ({@code libcmg_decrypt}) exposes one global runtime, so this class
 * reference counts the {@link CmgDataSource}s that share it and only resets the native state once
 * the last user releases the session. Every mutation is guarded by {@link #LOCK}, which also
 * serializes access against the shared native runtime.
 */
/* package */ final class CmgSession {

  private static final String TAG = "CmgSession";

  /** Serializes access to the process-wide native runtime and the fields below. */
  static final Object LOCK = new Object();

  /** The token of the session that currently owns the configured native runtime, or null. */
  private static Object configuredOwner;

  private static final long MAX_CLOCK_DRIFT_MS = 86_400_000L;

  private final CmgConfiguration configuration;

  /** Identity token used to detect which session currently owns the configured runtime. */
  private final Object ownerToken = new Object();

  private String playerTag;
  private long clockOffsetMs;
  private long generation;
  private boolean videoStateActive;
  private int referenceCount;
  private int videoNalCount;
  private boolean released = true;

  CmgSession(CmgConfiguration configuration) {
    this.configuration = configuration;
  }

  CmgConfiguration getConfiguration() {
    return configuration;
  }

  /**
   * Acquires the session for a data source, returning the generation under which it was acquired.
   *
   * @return The current generation identifier.
   */
  long acquire() {
    synchronized (LOCK) {
      referenceCount++;
      if (referenceCount == 1) {
        released = false;
      }
      generation++;
      return generation;
    }
  }

  /**
   * Releases a previously acquired reference, resetting the native runtime if it was the last one.
   *
   * @param acquiredGeneration The generation returned by the matching {@link #acquire()}.
   */
  void release(long acquiredGeneration) {
    synchronized (LOCK) {
      if (referenceCount == 0) {
        return;
      }
      referenceCount--;
      if (referenceCount <= 0) {
        referenceCount = 0;
        released = true;
        generation++;
        playerTag = null;
        clockOffsetMs = 0;
        videoStateActive = false;
        videoNalCount = 0;
        if (configuredOwner == ownerToken) {
          try {
            CmgNativeRuntime.reset();
          } finally {
            configuredOwner = null;
          }
        }
      }
    }
  }

  /** Whether {@code acquiredGeneration} still matches the live generation of the session. */
  boolean isActive(long acquiredGeneration) {
    synchronized (LOCK) {
      return !released && generation == acquiredGeneration;
    }
  }

  /**
   * Configures and initializes the native runtime against the current clock and player tag.
   *
   * <p>Must be called while holding {@link #LOCK}.
   */
  void configureRuntime() {
    if (playerTag == null) {
      playerTag = Long.toString(System.currentTimeMillis() + clockOffsetMs);
    }
    CmgNativeRuntime.setClock(System.currentTimeMillis() + clockOffsetMs);
    CmgNativeRuntime.reset();
    configuredOwner = null;
    CmgNativeRuntime.configureLocation(configuration.getLocation());
    if (!CmgNativeRuntime.configure(playerTag)) {
      Log.w(TAG, "Unable to configure CMG runtime");
    } else if (!CmgNativeRuntime.initialize()) {
      Log.w(TAG, "Unable to initialize CMG runtime");
    } else {
      configuredOwner = ownerToken;
      videoStateActive = false;
      videoNalCount = 0;
    }
  }

  /** Returns the player tag to seed with the current clock if not already set. */
  String ensurePlayerTag() {
    if (playerTag == null) {
      playerTag = Long.toString(System.currentTimeMillis() + clockOffsetMs);
    }
    return playerTag;
  }

  String getPlayerTag() {
    return playerTag;
  }

  long getClockOffsetMs() {
    return clockOffsetMs;
  }

  /**
   * Whether the runtime is still in a usable state (not previously rejected by the native layer).
   */
  boolean isVideoStateActive() {
    return videoStateActive;
  }

  void invalidateVideoState() {
    videoStateActive = false;
    videoNalCount = 0;
  }

  void markVideoStateActive(boolean active) {
    videoStateActive = active;
  }

  int incrementVideoNalCount() {
    return ++videoNalCount;
  }

  void resetVideoNalCount() {
    videoNalCount = 0;
  }

  boolean isReleased() {
    return released;
  }

  /**
   * Derives the clock offset from an HTTP {@code Date} response header, if present and plausible.
   *
   * @param responseHeaders The response headers of a segment load.
   */
  void syncClockFromHeaders(Map<String, List<String>> responseHeaders) {
    String dateValue = null;
    for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
      if (entry.getKey() != null && TextUtils.equals("Date", entry.getKey())) {
        List<String> values = entry.getValue();
        if (!values.isEmpty()) {
          dateValue = values.get(0);
          break;
        }
      }
    }
    if (dateValue == null) {
      return;
    }
    SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    dateFormat.setLenient(false);
    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    try {
      java.util.Date date = dateFormat.parse(dateValue);
      if (date == null) {
        return;
      }
      long drift = date.getTime() - System.currentTimeMillis();
      if (Math.abs(drift) <= MAX_CLOCK_DRIFT_MS) {
        clockOffsetMs = drift;
      }
    } catch (ParseException e) {
      // Ignore unparsable dates; the offset is only a refinement for the native clock.
    }
  }

  /** Thrown when the segment exceeds the configured CMG size limit. */
  static final class CmgSizeException extends IOException {
    CmgSizeException() {
      super("Yangshipin CMG segment exceeds size limit");
    }
  }
}
