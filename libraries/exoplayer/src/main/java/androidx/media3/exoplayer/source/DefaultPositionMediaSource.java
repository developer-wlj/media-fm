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

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;

/**
 * Wraps a {@link MediaSource} so that playback in its first window starts at a fixed position
 * instead of the media's default start point. Useful for preloaded media that should resume from
 * a specific position once attached to a player.
 */
@UnstableApi
public final class DefaultPositionMediaSource extends WrappingMediaSource {

  private final long defaultPositionMs;

  /**
   * Creates a source whose first window starts at {@code defaultPositionMs}.
   *
   * @param mediaSource The wrapped child {@link MediaSource}.
   * @param defaultPositionMs The position playback should start at, in milliseconds.
   */
  public DefaultPositionMediaSource(MediaSource mediaSource, long defaultPositionMs) {
    super(mediaSource);
    this.defaultPositionMs = defaultPositionMs;
  }

  @Override
  protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
    refreshSourceInfo(new DefaultPositionTimeline(newTimeline, C.msToUs(defaultPositionMs)));
  }

  private static final class DefaultPositionTimeline extends ForwardingTimeline {

    private final long defaultPositionUs;

    public DefaultPositionTimeline(Timeline timeline, long defaultPositionUs) {
      super(timeline);
      this.defaultPositionUs = defaultPositionUs;
    }

    @Override
    public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
      window = super.getWindow(windowIndex, window, defaultPositionProjectionUs);
      if (window.durationUs == C.TIME_UNSET
          || (defaultPositionUs >= 0 && defaultPositionUs < window.durationUs)) {
        window.defaultPositionUs = defaultPositionUs;
      }
      return window;
    }
  }
}
