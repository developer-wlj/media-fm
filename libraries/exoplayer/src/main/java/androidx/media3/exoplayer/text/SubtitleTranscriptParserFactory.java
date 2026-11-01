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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SubtitleParser.Factory} that indexes the cues parsed for a subtitle track into a {@link
 * SubtitleTranscript} before delegating the output to the caller.
 */
@UnstableApi
public final class SubtitleTranscriptParserFactory implements SubtitleParser.Factory {

  private final SubtitleTranscript transcript;
  private final SubtitleParser.Factory delegateFactory;

  /**
   * Creates an instance that indexes parsed cues into the given transcript using a {@link
   * DefaultSubtitleParserFactory} for parsing.
   */
  public SubtitleTranscriptParserFactory(SubtitleTranscript transcript) {
    this(transcript, new DefaultSubtitleParserFactory());
  }

  /**
   * Creates an instance that indexes parsed cues into the given transcript.
   *
   * @param transcript The transcript the parsed cues are indexed into.
   * @param delegateFactory The factory used to create the actual parsers.
   */
  public SubtitleTranscriptParserFactory(
      SubtitleTranscript transcript, SubtitleParser.Factory delegateFactory) {
    this.transcript = transcript;
    this.delegateFactory = delegateFactory;
  }

  @Override
  public boolean supportsFormat(Format format) {
    boolean supported = delegateFactory.supportsFormat(format);
    if (!supported) {
      transcript.setState(format, androidx.media3.common.text.SubtitleContent.State.UNSUPPORTED);
    }
    return supported;
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior(Format format) {
    return delegateFactory.getCueReplacementBehavior(format);
  }

  @Override
  public SubtitleParser create(Format format) {
    return new TranscriptParser(
        transcript, delegateFactory.create(format), format, delegateFactory.getCueReplacementBehavior(format));
  }

  private static final class TranscriptParser implements SubtitleParser {

    private final SubtitleTranscript transcript;
    private final SubtitleParser delegate;
    private final Format format;
    private final @CueReplacementBehavior int cueReplacementBehavior;

    public TranscriptParser(
        SubtitleTranscript transcript,
        SubtitleParser delegate,
        Format format,
        @CueReplacementBehavior int cueReplacementBehavior) {
      this.transcript = transcript;
      this.delegate = delegate;
      this.format = format;
      this.cueReplacementBehavior = cueReplacementBehavior;
    }

    @Override
    public void parse(
        byte[] data,
        int offset,
        int length,
        OutputOptions outputOptions,
        Consumer<CuesWithTiming> output) {
      List<CuesWithTiming> cuesWithTimingList = new ArrayList<>();
      delegate.parse(
          data,
          offset,
          length,
          outputOptions,
          cuesWithTiming -> {
            cuesWithTimingList.add(cuesWithTiming);
            output.accept(cuesWithTiming);
          });
      for (int i = 0; i < cuesWithTimingList.size(); i++) {
        transcript.addCues(format, cuesWithTimingList.get(i), C.TIME_UNSET);
      }
    }

    @Override
    public @CueReplacementBehavior int getCueReplacementBehavior() {
      return cueReplacementBehavior;
    }

    @Override
    public void reset() {
      delegate.reset();
    }
  }
}
