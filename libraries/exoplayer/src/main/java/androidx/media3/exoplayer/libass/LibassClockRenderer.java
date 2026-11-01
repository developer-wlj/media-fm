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

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.NoSampleRenderer;

/**
 * A renderer that follows the playback clock and triggers the rendering of libass frames at the
 * current playback position. It consumes no media.
 */
@UnstableApi
final class LibassClockRenderer extends NoSampleRenderer {

  private static final long RENDER_INTERVAL_US = 16666;

  private final LibassPlaybackSession playbackSession;

  private long lastPositionUs;
  private long lastRenderElapsedRealtimeUs;

  /** Creates a clock renderer for the given playback session. */
  public LibassClockRenderer(LibassPlaybackSession playbackSession) {
    this.playbackSession = playbackSession;
  }

  @Override
  public String getName() {
    return "LibassClockRenderer";
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean isEnded() {
    return true;
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
    lastPositionUs = positionUs;
    lastRenderElapsedRealtimeUs = C.TIME_UNSET;
    playbackSession.onClockTick(positionUs, /* redrawForced= */ true);
  }

  @Override
  protected void onRendererOffsetChanged(long offsetUs) {
    lastPositionUs = 0;
    lastRenderElapsedRealtimeUs = C.TIME_UNSET;
    playbackSession.onClockTick(0, /* redrawForced= */ true);
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    long elapsedRealtimeUsSinceLastRender = lastRenderElapsedRealtimeUs == C.TIME_UNSET
        ? Long.MAX_VALUE
        : elapsedRealtimeUs - lastRenderElapsedRealtimeUs;
    if (positionUs != lastPositionUs && elapsedRealtimeUsSinceLastRender >= RENDER_INTERVAL_US) {
      lastRenderElapsedRealtimeUs = elapsedRealtimeUs;
      lastPositionUs = positionUs;
      playbackSession.onClockTick(positionUs, /* redrawForced= */ false);
    } else if (lastRenderElapsedRealtimeUs == C.TIME_UNSET) {
      lastRenderElapsedRealtimeUs = elapsedRealtimeUs;
    }
  }
}
