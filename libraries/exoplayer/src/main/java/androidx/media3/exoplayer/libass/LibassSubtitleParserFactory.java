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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.text.SubtitleTranscript;
import androidx.media3.exoplayer.text.SubtitleTranscriptParserFactory;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SubtitleParser.Factory} that feeds ASS and SSA subtitle samples to a {@link
 * LibassMediaSession} so that they can be rendered by libass, in addition to indexing their
 * content into a {@link SubtitleTranscript}.
 */
@UnstableApi
public final class LibassSubtitleParserFactory implements SubtitleParser.Factory {

  private final LibassMediaSession mediaSession;
  private final SubtitleParser.Factory delegateFactory;
  private final boolean forMatroska;

  /**
   * Creates a factory.
   *
   * @param mediaSession The media session the samples are fed to.
   * @param delegateFactory The factory used for formats that are not rendered by libass.
   * @param forMatroska Whether the factory is used for extractors of Matroska like containers.
   */
  public LibassSubtitleParserFactory(
      LibassMediaSession mediaSession, SubtitleParser.Factory delegateFactory, boolean forMatroska) {
    this.mediaSession = mediaSession;
    this.delegateFactory = delegateFactory;
    this.forMatroska = forMatroska;
  }

  /** Returns whether the format is an ASS or SSA subtitle format. */
  public static boolean isAssFormat(Format format) {
    return MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
        || MimeTypes.TEXT_SSA.equals(format.codecs);
  }

  @Override
  public boolean supportsFormat(Format format) {
    boolean supported = isAssFormat(format) || delegateFactory.supportsFormat(format);
    if (!supported && mediaSession.transcript != null) {
      mediaSession.transcript.setState(
          format, androidx.media3.common.text.SubtitleContent.State.UNSUPPORTED);
    }
    return supported;
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior(Format format) {
    if (isAssFormat(format)) {
      return delegateFactory.getCueReplacementBehavior(format);
    }
    return delegateFactory.getCueReplacementBehavior(format);
  }

  @Override
  public SubtitleParser create(Format format) {
    if (!isAssFormat(format)) {
      @Nullable SubtitleTranscript transcript = mediaSession.transcript;
      if (transcript == null) {
        return delegateFactory.create(format);
      }
      return new SubtitleTranscriptParserFactory(transcript, delegateFactory).create(format);
    }
    SubtitleParser delegateParser = delegateFactory.create(format);
    return new LibassSubtitleParser(mediaSession, delegateParser, format);
  }

  private static final class LibassSubtitleParser implements SubtitleParser {

    private final LibassMediaSession mediaSession;
    private final SubtitleParser delegateParser;
    private final Format format;

    public LibassSubtitleParser(
        LibassMediaSession mediaSession, SubtitleParser delegateParser, Format format) {
      this.mediaSession = mediaSession;
      this.delegateParser = delegateParser;
      this.format = format;
    }

    @Override
    public void parse(
        byte[] data,
        int offset,
        int length,
        OutputOptions outputOptions,
        androidx.media3.common.util.Consumer<CuesWithTiming> output) {
      feedLibass(data, offset, length);
      SubtitleTranscript transcript = mediaSession.transcript;
      if (transcript != null) {
        List<CuesWithTiming> cuesWithTimingList = new ArrayList<>();
        delegateParser.parse(
            data,
            offset,
            length,
            SubtitleParser.OutputOptions.allCues(),
            cuesWithTimingList::add);
        for (int i = 0; i < cuesWithTimingList.size(); i++) {
          transcript.addCues(format, cuesWithTimingList.get(i), C.TIME_UNSET);
        }
      }
      // Cues are not emitted: ASS subtitles are rendered by libass instead of a SubtitleView.
    }

    private void feedLibass(byte[] data, int offset, int length) {
      Assertions.checkArgument(offset >= 0 && length >= 0 && offset <= data.length - length);
      LibassTrackSession trackSession = mediaSession.getOrCreateTrackSession(format);
      if (trackSession == null) {
        return;
      }
      synchronized (trackSession) {
        trackSession.processData(data, offset, length);
      }
    }

    @Override
    public @CueReplacementBehavior int getCueReplacementBehavior() {
      return delegateParser.getCueReplacementBehavior();
    }

    @Override
    public void reset() {
      delegateParser.reset();
    }
  }
}
