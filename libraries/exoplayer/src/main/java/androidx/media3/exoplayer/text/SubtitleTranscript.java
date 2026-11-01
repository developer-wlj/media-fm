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
package androidx.media3.exoplayer.text;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Collects the decoded text content of the subtitle tracks of a media item.
 *
 * <p>Content is indexed per track. Tracks are identified by a track key derived from the track's
 * {@link Format}: formats coming from the media container use their format id, while sideloaded
 * subtitle tracks use a key derived from their label and language.
 */
@UnstableApi
public final class SubtitleTranscript {

  private static final String TAG = "SubtitleTranscript";

  private static final int MAX_TRACK_BYTES = 4 * 1024 * 1024;
  private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
  private static final int MAX_SAMPLE_BYTES = 32 * 1024 * 1024;

  private final HashMap<String, TrackState> trackStates;
  private final HashMap<String, MediaItem.SubtitleConfiguration> externalTrackConfigurations;
  private final HashSet<Format> requestedExternalFormats;
  private boolean closed;
  private long totalBytes;

  /** Creates an empty transcript. */
  public SubtitleTranscript() {
    trackStates = new HashMap<>();
    externalTrackConfigurations = new HashMap<>();
    requestedExternalFormats = new HashSet<>();
  }

  /**
   * Creates a transcript that indexes the sideloaded subtitle tracks of the given media item.
   *
   * @param mediaItem The media item whose {@linkplain MediaItem.SubtitleConfiguration subtitle
   *     configurations} are tracked.
   */
  public SubtitleTranscript(MediaItem mediaItem) {
    this();
    if (mediaItem.localConfiguration == null) {
      return;
    }
    for (int i = 0; i < mediaItem.localConfiguration.subtitleConfigurations.size(); i++) {
      MediaItem.SubtitleConfiguration configuration =
          mediaItem.localConfiguration.subtitleConfigurations.get(i);
      String trackKey =
          configuration.id == null || configuration.id.isEmpty()
              ? getExternalTrackKey(
                  new Format.Builder()
                      .setLabel(configuration.label)
                      .setLanguage(configuration.language)
                      .build())
              : configuration.id;
      externalTrackConfigurations.put(trackKey, configuration);
    }
  }

  /** Returns the track key used for sideloaded subtitle tracks without an explicit id. */
  public static String getExternalTrackKey(Format format) {
    return "external:" + Objects.toString(format.label, "") + ':' + Objects.toString(format.language, "");
  }

  /** Returns the track id of the format, or the external track key when the id is unset. */
  public static String getFormatTrackId(Format format) {
    return format.id == null || format.id.isEmpty() ? getExternalTrackKey(format) : format.id;
  }

  /**
   * Resolves the track key of the format against the known track keys. Format ids may be prefixed
   * with the id of an enclosing playlist entry, in which case the prefixes are stripped until a
   * known key is found.
   */
  @Nullable
  public static String resolveTrackKey(Format format, Set<String> trackKeys) {
    String trackKey = getFormatTrackId(format);
    while (!trackKeys.contains(trackKey)) {
      int separatorIndex = trackKey.indexOf(':');
      if (separatorIndex <= 0) {
        return null;
      }
      for (int i = 0; i < separatorIndex; i++) {
        if (!Character.isDigit(trackKey.charAt(i))) {
          return null;
        }
      }
      trackKey = trackKey.substring(separatorIndex + 1);
      if (trackKey.isEmpty()) {
        trackKey = getExternalTrackKey(format);
        return trackKeys.contains(trackKey) ? trackKey : null;
      }
    }
    return trackKey;
  }

  /**
   * Adds the cues of a parsed subtitle sample to the transcript.
   *
   * @param format The format of the subtitle track.
   * @param cuesWithTiming The parsed cues.
   * @param sampleStartTimeUs The start time of the sample the cues were parsed from, or {@link
   *     C#TIME_UNSET} if unknown.
   */
  public synchronized void addCues(
      Format format, CuesWithTiming cuesWithTiming, long sampleStartTimeUs) {
    if (closed) {
      return;
    }
    long startTimeUs =
        cuesWithTiming.startTimeUs == C.TIME_UNSET
            ? (sampleStartTimeUs == C.TIME_UNSET ? 0 : sampleStartTimeUs)
            : sampleStartTimeUs == C.TIME_UNSET
                ? cuesWithTiming.startTimeUs
                : cuesWithTiming.startTimeUs + sampleStartTimeUs;
    long endTimeUs =
        cuesWithTiming.durationUs == C.TIME_UNSET
            ? C.TIME_UNSET
            : startTimeUs + cuesWithTiming.durationUs;
    if (endTimeUs != C.TIME_UNSET && endTimeUs < startTimeUs) {
      return;
    }
    TrackState trackState = getOrCreateTrackState(getFormatTrackId(format));
    for (int i = 0; i < cuesWithTiming.cues.size(); i++) {
      Cue cue = cuesWithTiming.cues.get(i);
      CharSequence text = cue.text;
      boolean image = text == null && cue.bitmap != null;
      String textString = text == null ? "" : text.toString().trim();
      if (textString.isEmpty() && !image) {
        continue;
      }
      if (trackState.entries.add(new TimedText(startTimeUs, endTimeUs, textString, image))) {
        long bytes = textString.length() * 2L + 64;
        trackState.bytes += bytes;
        totalBytes += bytes;
        trackState.cachedContent = null;
        trackState.cachedEntries = null;
        evict();
      }
    }
  }

  /**
   * Parses the raw data of a subtitle sample and adds the resulting cues to the transcript. The
   * data must match the format's sample mime type.
   *
   * @return Whether the data could be parsed.
   */
  public synchronized boolean addSampleData(
      Format format, byte[] data, int offset, int length, long sampleStartTimeUs) {
    if (closed) {
      return false;
    }
    try {
      SubtitleParser subtitleParser = new DefaultSubtitleParserFactory().create(format);
      subtitleParser.parse(
          data,
          offset,
          length,
          SubtitleParser.OutputOptions.allCues(),
          cuesWithTiming -> addCues(format, cuesWithTiming, sampleStartTimeUs));
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "Unable to index subtitle sample", e);
      setState(format, SubtitleContent.State.ERROR);
      return false;
    }
  }

  /**
   * Sets the state of the subtitle track. The state is not downgraded from a terminal state, and a
   * {@link SubtitleContent.State#LOADING LOADING} state is ignored once entries have been indexed.
   */
  public synchronized void setState(Format format, SubtitleContent.State state) {
    if (closed) {
      return;
    }
    String trackKey = resolveTrackKey(format, trackStates.keySet());
    TrackState trackState = getOrCreateTrackState(trackKey == null ? getFormatTrackId(format) : trackKey);
    if (trackState.state == SubtitleContent.State.ERROR
        || trackState.state == SubtitleContent.State.UNSUPPORTED
        || trackState.state == state) {
      return;
    }
    if (state == SubtitleContent.State.LOADING && !trackState.entries.isEmpty()) {
      return;
    }
    trackState.state = state;
    trackState.cachedContent = null;
  }

  /**
   * Marks the sideloaded subtitle track loaded from the given uri as errored, unless its content
   * has already been indexed completely.
   */
  public synchronized void markExternalTrackErrored(Uri uri) {
    if (closed) {
      return;
    }
    for (Map.Entry<String, MediaItem.SubtitleConfiguration> entry :
        externalTrackConfigurations.entrySet()) {
      MediaItem.SubtitleConfiguration configuration = entry.getValue();
      if (configuration == null || !configuration.uri.equals(uri)) {
        continue;
      }
      Format format =
          new Format.Builder().setId(entry.getKey()).build();
      TrackState trackState = getOrCreateTrackState(entry.getKey());
      if (trackState.state != SubtitleContent.State.COMPLETE
          && trackState.state != SubtitleContent.State.PARTIAL) {
        setState(format, SubtitleContent.State.ERROR);
      }
    }
  }

  /**
   * Replaces the content of the subtitle track with the given content, for example the content
   * loaded from a sideloaded subtitle file.
   */
  public synchronized void setContent(Format format, SubtitleContent content) {
    if (closed) {
      return;
    }
    String trackKey = resolveTrackKey(format, trackStates.keySet());
    if (trackKey == null) {
      trackKey = getFormatTrackId(format);
    }
    TrackState trackState = getOrCreateTrackState(trackKey);
    totalBytes -= trackState.bytes;
    TrackState replacement = new TrackState();
    replacement.state = content.state;
    replacement.truncated = content.truncated;
    for (int i = 0; i < content.entries.size(); i++) {
      SubtitleContent.Entry entry = content.entries.get(i);
      if (entry.startTimeUs == C.TIME_UNSET) {
        continue;
      }
      if (entry.endTimeUs != C.TIME_UNSET && entry.endTimeUs <= entry.startTimeUs) {
        continue;
      }
      if (replacement.entries.add(
          new TimedText(entry.startTimeUs, entry.endTimeUs, entry.text, entry.image))) {
        long bytes = entry.text.length() * 2L + 64;
        replacement.bytes += bytes;
        totalBytes += bytes;
      }
    }
    trackStates.put(trackKey, replacement);
  }

  /** Returns the content of the subtitle track, without applying an offset. */
  public synchronized SubtitleContent getContent(Format format) {
    return getContent(format, /* offsetUs= */ 0, /* endOffsetUs= */ C.TIME_UNSET);
  }

  /**
   * Returns the content of the subtitle track.
   *
   * @param format The format of the subtitle track.
   * @param offsetUs The offset applied to the entry times, in microseconds.
   * @param endOffsetUs The exclusive upper bound applied to shifted entry times, or {@link
   *     C#TIME_UNSET} for no bound.
   */
  public synchronized SubtitleContent getContent(Format format, long offsetUs, long endOffsetUs) {
    @Nullable String trackKey = resolveTrackKey(format, trackStates.keySet());
    if (trackKey == null) {
      return new SubtitleContent(
          SubtitleContent.State.LOADING, ImmutableList.of(), /* truncated= */ false);
    }
    TrackState trackState = trackStates.get(trackKey);
    ImmutableList<TimedText> entries = getEntries(trackState);
    ImmutableList.Builder<SubtitleContent.Entry> listBuilder = ImmutableList.builder();
    for (int i = 0; i < entries.size(); i++) {
      TimedText entry = entries.get(i);
      long startTimeUs = entry.startTimeUs + offsetUs;
      long endTimeUs =
          entry.endTimeUs == C.TIME_UNSET ? C.TIME_UNSET : entry.endTimeUs + offsetUs;
      if (endOffsetUs != C.TIME_UNSET && startTimeUs >= endOffsetUs) {
        continue;
      }
      listBuilder.add(new SubtitleContent.Entry(startTimeUs, endTimeUs, entry.text, entry.image));
    }
    return new SubtitleContent(trackState.state, listBuilder.build(), trackState.truncated);
  }

  /** Returns whether the content of the format was requested for loading. */
  public synchronized boolean isExternalContentLoaded(Format format) {
    return requestedExternalFormats.contains(format);
  }

  /** Records that loading the content of the format was requested. */
  public synchronized void setExternalContentLoaded(Format format) {
    requestedExternalFormats.add(format);
  }

  /** Clears the record that loading the content of the format was requested. */
  public synchronized void removeExternalContentLoaded(Format format) {
    requestedExternalFormats.remove(format);
  }

  /** Returns the sideloaded subtitle configuration matching the format's track key, or null. */
  @Nullable
  public synchronized MediaItem.SubtitleConfiguration getExternalTrackConfiguration(Format format) {
    String trackKey = resolveTrackKey(format, externalTrackConfigurations.keySet());
    return trackKey == null ? null : externalTrackConfigurations.get(trackKey);
  }

  /** Returns whether the format is backed by a sideloaded subtitle configuration. */
  public synchronized boolean isExternalTrack(Format format) {
    return resolveTrackKey(format, externalTrackConfigurations.keySet()) != null;
  }

  /** Clears the transcript and prevents further indexing. */
  public synchronized void clear() {
    closed = true;
    trackStates.clear();
    externalTrackConfigurations.clear();
    requestedExternalFormats.clear();
    totalBytes = 0;
  }

  private ImmutableList<TimedText> getEntries(TrackState trackState) {
    if (trackState.cachedEntries == null) {
      trackState.cachedEntries = ImmutableList.copyOf(trackState.entries);
    }
    return trackState.cachedEntries;
  }

  private TrackState getOrCreateTrackState(String trackKey) {
    TrackState trackState = trackStates.get(trackKey);
    if (trackState == null) {
      trackState = new TrackState();
      trackStates.put(trackKey, trackState);
    }
    return trackState;
  }

  private void evict() {
    for (TrackState trackState : trackStates.values()) {
      evict(trackState);
    }
    while (totalBytes > MAX_TOTAL_BYTES && !trackStates.isEmpty()) {
      TrackState largest = null;
      for (TrackState trackState : trackStates.values()) {
        if (largest == null || trackState.bytes > largest.bytes) {
          largest = trackState;
        }
      }
      if (largest == null || largest.bytes <= 0) {
        totalBytes = 0;
        return;
      }
      evict(largest);
    }
  }

  private void evict(TrackState trackState) {
    if (trackState == null || trackState.entries.isEmpty()) {
      return;
    }
    Iterator<TimedText> iterator = trackState.entries.iterator();
    TimedText first = iterator.next();
    iterator.remove();
    long bytes = first.text.length() * 2L + 64;
    trackState.bytes -= bytes;
    totalBytes -= bytes;
    trackState.state = SubtitleContent.State.TRUNCATED;
    trackState.cachedEntries = null;
    trackState.cachedContent = null;
  }

  private static final class TrackState {

    public final TreeSet<TimedText> entries =
        new TreeSet<>(
            (first, second) -> {
              int result = Long.compare(first.startTimeUs, second.startTimeUs);
              if (result != 0) {
                return result;
              }
              result = Long.compare(first.endTimeUs, second.endTimeUs);
              if (result != 0) {
                return result;
              }
              return first.text.compareTo(second.text);
            });
    @Nullable public ImmutableList<TimedText> cachedEntries;
    @Nullable public SubtitleContent cachedContent;
    public long bytes;
    public SubtitleContent.State state = SubtitleContent.State.LOADING;
    public boolean truncated;
  }

  private static final class TimedText {

    public final long startTimeUs;
    public final long endTimeUs;
    public final String text;
    public final boolean image;

    public TimedText(long startTimeUs, long endTimeUs, String text, boolean image) {
      this.startTimeUs = startTimeUs;
      this.endTimeUs = endTimeUs;
      this.text = text;
      this.image = image;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof TimedText)) {
        return false;
      }
      TimedText that = (TimedText) o;
      return startTimeUs == that.startTimeUs
          && endTimeUs == that.endTimeUs
          && image == that.image
          && text.equals(that.text);
    }

    @Override
    public int hashCode() {
      return Objects.hash(startTimeUs, endTimeUs, text, image);
    }
  }
}
