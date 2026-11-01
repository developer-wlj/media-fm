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
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * The decoded content of a subtitle track, together with the state of its decoding. Content is
 * exposed as a flat list of {@link Entry entries} that playback UIs can merge across the primary
 * and secondary subtitle outputs.
 */
@UnstableApi
public final class SubtitleContent {

  /** A {@link SubtitleContent} indicating that the track cannot provide content. */
  public static final SubtitleContent UNSUPPORTED =
      new SubtitleContent(State.UNSUPPORTED, ImmutableList.of(), /* truncated= */ false);

  /** A {@link SubtitleContent} indicating that content is being decoded. */
  public static final SubtitleContent LOADING =
      new SubtitleContent(State.LOADING, ImmutableList.of(), /* truncated= */ false);

  /** The decoding state of the content. */
  public enum State {
    /** Content is being decoded. */
    LOADING,
    /** Decoding finished and all entries are available. */
    COMPLETE,
    /** Decoding finished but only part of the content could be decoded. */
    PARTIAL,
    /** Decoding finished but the content was cut off before its natural end. */
    TRUNCATED,
    /** Decoding failed. */
    ERROR,
    /** The track does not support content decoding. */
    UNSUPPORTED
  }

  /** A single decoded subtitle cue. */
  public static final class Entry {

    /** The start time of the cue, in microseconds. */
    public final long startTimeUs;
    /** The end time of the cue, in microseconds, or {@link C#TIME_UNSET}. */
    public final long endTimeUs;
    /** The text of the cue, or an empty string for image based cues. */
    public final String text;
    /** Whether the cue is an image based cue. */
    public final boolean image;

    /**
     * Creates an entry.
     *
     * @param startTimeUs The start time of the cue, in microseconds.
     * @param endTimeUs The end time of the cue, in microseconds, or {@link C#TIME_UNSET}.
     * @param text The text of the cue, or an empty string for image based cues.
     * @param image Whether the cue is an image based cue.
     */
    public Entry(long startTimeUs, long endTimeUs, String text, boolean image) {
      this.startTimeUs = startTimeUs;
      this.endTimeUs = endTimeUs;
      this.text = text;
      this.image = image;
    }
  }

  /** The decoding state. */
  public final State state;
  /** The decoded entries, ordered by start time. */
  public final List<Entry> entries;
  /** Whether the content was cut off before its natural end. */
  public final boolean truncated;

  /**
   * Creates content.
   *
   * @param state The decoding state.
   * @param entries The decoded entries, ordered by start time.
   * @param truncated Whether the content was cut off before its natural end.
   */
  public SubtitleContent(State state, List<Entry> entries, boolean truncated) {
    this.state = state;
    this.entries = ImmutableList.copyOf(entries);
    this.truncated = truncated;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SubtitleContent)) {
      return false;
    }
    SubtitleContent that = (SubtitleContent) o;
    return truncated == that.truncated
        && state == that.state
        && entries.equals(that.entries);
  }

  @Override
  public int hashCode() {
    int result = state.hashCode();
    result = 31 * result + entries.hashCode();
    result = 31 * result + (truncated ? 1 : 0);
    return result;
  }
}
