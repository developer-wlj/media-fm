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
package androidx.media3.common;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Objects;

/** The current state of the optical disc menu playback, as exposed by a {@link Player}. */
@UnstableApi
public final class DiscMenuState {

  /** Availability of the disc menu is not determined yet. */
  public static final int AVAILABILITY_PENDING = 0;

  /** No disc menu can be opened for the current media item. */
  public static final int AVAILABILITY_UNAVAILABLE = 1;

  /** A disc menu can be opened for the current media item. */
  public static final int AVAILABILITY_AVAILABLE = 2;

  /** No disc menu interaction is in progress. */
  public static final int PHASE_IDLE = 0;

  /** A disc menu open request is pending. */
  public static final int PHASE_OPENING = 1;

  /** A disc menu is active and receives navigation input. */
  public static final int PHASE_ACTIVE = 2;

  /** The state reported when no disc menu controller is available. */
  public static final DiscMenuState EMPTY =
      new DiscMenuState(AVAILABILITY_UNAVAILABLE, PHASE_IDLE, /* navigationPlaybackOngoing= */ false);

  /**
   * Availability of the disc menu. One of {@link #AVAILABILITY_PENDING}, {@link
   * #AVAILABILITY_UNAVAILABLE} or {@link #AVAILABILITY_AVAILABLE}.
   */
  public final int availability;

  /** Current menu phase. One of {@link #PHASE_IDLE}, {@link #PHASE_OPENING} or {@link #PHASE_ACTIVE}. */
  public final int phase;

  /** Whether navigation driven playback is currently ongoing, e.g. inside a menu or a still. */
  public final boolean navigationPlaybackOngoing;

  public DiscMenuState(int availability, int phase, boolean navigationPlaybackOngoing) {
    this.availability = availability;
    this.phase = phase;
    this.navigationPlaybackOngoing = navigationPlaybackOngoing;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DiscMenuState)) {
      return false;
    }
    DiscMenuState that = (DiscMenuState) o;
    return availability == that.availability
        && phase == that.phase
        && navigationPlaybackOngoing == that.navigationPlaybackOngoing;
  }

  @Override
  public int hashCode() {
    return Objects.hash(availability, phase, navigationPlaybackOngoing);
  }

  @Override
  public String toString() {
    return "DiscMenuState{availability="
        + availability
        + ", phase="
        + phase
        + ", navigationPlaybackOngoing="
        + navigationPlaybackOngoing
        + "}";
  }
}
