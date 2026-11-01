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
package androidx.media3.exoplayer.source;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;

/**
 * Wraps a {@link MediaSource} so that playback finishes {@code endTrimDurationMs} before the real
 * end of the media, letting the player skip trailing content such as closing credits.
 */
@UnstableApi
public final class EndTrimMediaSource extends WrappingMediaSource {

  private long endTrimDurationMs;

  @Nullable private Timeline childTimeline;

  /**
   * Creates a source that trims {@code endTrimDurationMs} off the end of {@code mediaSource}.
   *
   * @param mediaSource The wrapped child {@link MediaSource}.
   * @param endTrimDurationMs The duration to trim off the end of the media, in milliseconds. A
   *     value of {@code 0} or {@link C#TIME_UNSET} disables trimming.
   */
  public EndTrimMediaSource(MediaSource mediaSource, long endTrimDurationMs) {
    super(mediaSource);
    this.endTrimDurationMs = normalizeTrimDuration(endTrimDurationMs);
  }

  /** Updates the duration to trim off the end of the media, in milliseconds. */
  public void setEndTrimDurationMs(long endTrimDurationMs) {
    this.endTrimDurationMs = normalizeTrimDuration(endTrimDurationMs);
    republishTimeline();
  }

  /** Returns whether an end trim is currently configured. */
  public boolean isEndTrimActive() {
    return endTrimDurationMs > 0;
  }

  /**
   * Returns the duration of the wrapped media before trimming, in milliseconds, or {@link
   * C#TIME_UNSET} if unknown.
   */
  public long getOriginalDurationMs() {
    @Nullable Timeline timeline = childTimeline;
    if (timeline == null || timeline.isEmpty()) {
      return C.TIME_UNSET;
    }
    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
    return window.durationUs == C.TIME_UNSET ? C.TIME_UNSET : C.usToMs(window.durationUs);
  }

  @Override
  protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
    childTimeline = newTimeline;
    republishTimeline();
  }

  private void republishTimeline() {
    @Nullable Timeline timeline = childTimeline;
    if (timeline == null) {
      return;
    }
    refreshSourceInfo(
        endTrimDurationMs > 0
            ? new EndTrimmedTimeline(timeline, endTrimDurationMs)
            : timeline);
  }

  private static long normalizeTrimDuration(long endTrimDurationMs) {
    return endTrimDurationMs == C.TIME_UNSET ? 0 : Math.max(0, endTrimDurationMs);
  }

  private static final class EndTrimmedTimeline extends ForwardingTimeline {

    private final long endTrimDurationMs;

    public EndTrimmedTimeline(Timeline timeline, long endTrimDurationMs) {
      super(timeline);
      this.endTrimDurationMs = endTrimDurationMs;
    }

    @Override
    public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
      window = super.getWindow(windowIndex, window, defaultPositionProjectionUs);
      if (window.durationUs != C.TIME_UNSET) {
        long endTrimUs = C.msToUs(endTrimDurationMs);
        window.durationUs = Math.max(0, window.durationUs - endTrimUs);
      }
      return window;
    }
  }
}
