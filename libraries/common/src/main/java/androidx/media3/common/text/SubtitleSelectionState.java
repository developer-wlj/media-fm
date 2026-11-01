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
package androidx.media3.common.text;

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.Objects;

/** Describes which subtitle track selections currently feed the transcript outputs. */
@UnstableApi
public final class SubtitleSelectionState {

  /** A state without any primary or secondary selection. */
  public static final SubtitleSelectionState EMPTY =
      new SubtitleSelectionState(/* primarySelection= */ null, /* activeSecondarySelection= */ null);

  /** The track selection feeding the primary subtitle output, or null. */
  @Nullable public final TrackSelectionOverride primarySelection;
  /** The track selection feeding the secondary subtitle output, or null. */
  @Nullable public final TrackSelectionOverride activeSecondarySelection;
  /** The selectable candidates for the secondary subtitle output. */
  public final ImmutableList<TrackSelectionOverride> secondaryCandidates;
  /** The secondary selection requested by the user, or null. */
  @Nullable public final TrackSelectionOverride requestedSecondarySelection;
  /** Whether the requested secondary selection was promoted to the primary output. */
  public final boolean secondaryPromotedToPrimary;

  /**
   * Creates a state.
   *
   * @param primarySelection The selection feeding the primary output, or null.
   * @param activeSecondarySelection The selection feeding the secondary output, or null.
   */
  public SubtitleSelectionState(
      @Nullable TrackSelectionOverride primarySelection,
      @Nullable TrackSelectionOverride activeSecondarySelection) {
    this(
        primarySelection,
        activeSecondarySelection,
        /* secondaryCandidates= */ ImmutableList.of(),
        /* requestedSecondarySelection= */ null,
        /* secondaryPromotedToPrimary= */ false);
  }

  /**
   * Creates a state.
   *
   * @param primarySelection The selection feeding the primary output, or null.
   * @param activeSecondarySelection The selection feeding the secondary output, or null.
   * @param secondaryCandidates The selectable candidates for the secondary output.
   * @param requestedSecondarySelection The requested secondary selection, or null.
   * @param secondaryPromotedToPrimary Whether the requested secondary selection was promoted to
   *     the primary output.
   */
  public SubtitleSelectionState(
      @Nullable TrackSelectionOverride primarySelection,
      @Nullable TrackSelectionOverride activeSecondarySelection,
      java.util.List<TrackSelectionOverride> secondaryCandidates,
      @Nullable TrackSelectionOverride requestedSecondarySelection,
      boolean secondaryPromotedToPrimary) {
    this.primarySelection = primarySelection;
    this.activeSecondarySelection = activeSecondarySelection;
    this.secondaryCandidates = ImmutableList.copyOf(secondaryCandidates);
    this.requestedSecondarySelection = requestedSecondarySelection;
    this.secondaryPromotedToPrimary = secondaryPromotedToPrimary;
  }

  /** Returns whether {@code override} is the selection feeding the primary output. */
  public boolean isPrimary(TrackSelectionOverride override) {
    return Objects.equals(primarySelection, override);
  }

  /** Returns whether {@code override} is the selection feeding the secondary output. */
  public boolean isActiveSecondary(TrackSelectionOverride override) {
    return Objects.equals(activeSecondarySelection, override);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SubtitleSelectionState)) {
      return false;
    }
    SubtitleSelectionState that = (SubtitleSelectionState) o;
    return Objects.equals(primarySelection, that.primarySelection)
        && Objects.equals(activeSecondarySelection, that.activeSecondarySelection);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hashCode(primarySelection)
        + Objects.hashCode(activeSecondarySelection);
  }
}
