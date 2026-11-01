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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Loads a subtitle file through a {@link DataSource} and decodes it into a {@link SubtitleContent}.
 */
@UnstableApi
public final class DefaultSubtitleContentLoader {

  private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

  private final DataSource.Factory dataSourceFactory;

  /**
   * Creates an instance.
   *
   * @param dataSourceFactory The {@link DataSource.Factory} used to read the subtitle file.
   */
  public DefaultSubtitleContentLoader(DataSource.Factory dataSourceFactory) {
    this.dataSourceFactory = dataSourceFactory;
  }

  /**
   * Loads and decodes the subtitle file at {@code uri}.
   *
   * @param mediaItem The media item the subtitle track belongs to.
   * @param uri The uri of the subtitle file.
   * @param format The format of the subtitle track.
   * @return The decoded {@link SubtitleContent}, or {@link SubtitleContent#UNSUPPORTED} if the
   *     format is not supported.
   * @throws IOException If the subtitle file could not be read.
   */
  public SubtitleContent load(MediaItem mediaItem, Uri uri, Format format) throws IOException {
    @Nullable Format parseFormat = resolveParseFormat(format, uri);
    if (parseFormat == null) {
      return SubtitleContent.UNSUPPORTED;
    }
    DefaultSubtitleParserFactory parserFactory = new DefaultSubtitleParserFactory();
    if (!parserFactory.supportsFormat(parseFormat)) {
      return SubtitleContent.UNSUPPORTED;
    }
    byte[] data = read(uri);
    SubtitleParser parser = parserFactory.create(parseFormat);
    List<SubtitleContent.Entry> entries = new ArrayList<>();
    boolean parseFailed = false;
    try {
      parser.parse(
          data,
          /* offset= */ 0,
          data.length,
          SubtitleParser.OutputOptions.allCues(),
          cuesWithTiming -> addEntries(entries, cuesWithTiming));
    } catch (RuntimeException e) {
      if (entries.isEmpty()) {
        throw new IOException("Unable to decode subtitle file", e);
      }
      parseFailed = true;
    }
    sortEntries(entries);
    SubtitleContent.State state =
        parseFailed ? SubtitleContent.State.PARTIAL : SubtitleContent.State.COMPLETE;
    return new SubtitleContent(state, entries, /* truncated= */ false);
  }

  @Nullable
  private static Format resolveParseFormat(Format format, Uri uri) {
    if (format.sampleMimeType != null) {
      return format;
    }
    @Nullable String mimeType = inferMimeTypeFromExtension(uri);
    return mimeType == null ? null : format.buildUpon().setSampleMimeType(mimeType).build();
  }

  @Nullable
  private static String inferMimeTypeFromExtension(Uri uri) {
    @Nullable String fileName = uri.getLastPathSegment();
    if (fileName == null) {
      return null;
    }
    String lowerCase = fileName.toLowerCase();
    if (lowerCase.endsWith(".vtt")) {
      return MimeTypes.TEXT_VTT;
    }
    if (lowerCase.endsWith(".srt")) {
      return MimeTypes.APPLICATION_SUBRIP;
    }
    if (lowerCase.endsWith(".ssa") || lowerCase.endsWith(".ass")) {
      return MimeTypes.TEXT_SSA;
    }
    if (lowerCase.endsWith(".ttml") || lowerCase.endsWith(".xml") || lowerCase.endsWith(".dfxp")) {
      return MimeTypes.APPLICATION_TTML;
    }
    return null;
  }

  private static void addEntries(List<SubtitleContent.Entry> entries, CuesWithTiming cues) {
    long startTimeUs = cues.startTimeUs == C.TIME_UNSET ? 0 : cues.startTimeUs;
    long endTimeUs =
        cues.durationUs == C.TIME_UNSET || cues.durationUs == Long.MAX_VALUE
            ? C.TIME_UNSET
            : startTimeUs + cues.durationUs;
    for (int i = 0; i < cues.cues.size(); i++) {
      entries.add(createEntry(startTimeUs, endTimeUs, cues.cues.get(i)));
    }
  }

  private static SubtitleContent.Entry createEntry(long startTimeUs, long endTimeUs, Cue cue) {
    CharSequence text = cue.text;
    boolean image = text == null && cue.bitmap != null;
    return new SubtitleContent.Entry(
        startTimeUs, endTimeUs, text == null ? "" : text.toString().trim(), image);
  }

  private static void sortEntries(List<SubtitleContent.Entry> entries) {
    Collections.sort(
        entries,
        new Comparator<SubtitleContent.Entry>() {
          @Override
          public int compare(SubtitleContent.Entry first, SubtitleContent.Entry second) {
            return Long.compare(first.startTimeUs, second.startTimeUs);
          }
        });
  }

  private byte[] read(Uri uri) throws IOException {
    DataSpec dataSpec =
        new DataSpec.Builder().setUri(uri).setLength(C.LENGTH_UNSET).build();
    DataSource dataSource = dataSourceFactory.createDataSource();
    try {
      dataSource.open(dataSpec);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[64 * 1024];
      while (true) {
        int read = dataSource.read(buffer, /* offset= */ 0, buffer.length);
        if (read == C.RESULT_END_OF_INPUT) {
          break;
        }
        if (output.size() + read > MAX_FILE_BYTES) {
          throw new IOException("Subtitle file exceeds the size limit");
        }
        output.write(buffer, /* off= */ 0, read);
      }
      return output.toByteArray();
    } finally {
      dataSource.close();
    }
  }
}
