/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.mediacodec;

import android.media.MediaCodec;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderMode;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Selector of {@link MediaCodec} instances. */
@UnstableApi
public interface MediaCodecSelector {

  /**
   * Default implementation of {@link MediaCodecSelector}, which returns the preferred decoder for
   * the given format.
   */
  MediaCodecSelector DEFAULT = MediaCodecUtil::getDecoderInfos;

  /**
   * Implementation of {@link MediaCodecSelector}, which returns the {@link #DEFAULT} list of
   * decoders for the given format, giving higher priority to software decoders.
   */
  MediaCodecSelector PREFER_SOFTWARE =
      (String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) ->
          MediaCodecUtil.getDecoderInfosSortedBySoftwareOnly(
              DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder));

  /**
   * Returns a list of decoders that can decode media in the specified MIME type, in priority order.
   *
   * @param mimeType The MIME type for which a decoder is required.
   * @param requiresSecureDecoder Whether a secure decoder is required.
   * @param requiresTunnelingDecoder Whether a tunneling decoder is required.
   * @return An unmodifiable list of {@link MediaCodecInfo}s corresponding to decoders. May be
   *     empty.
   * @throws DecoderQueryException Thrown if there was an error querying decoders.
   */
  List<MediaCodecInfo> getDecoderInfos(
      String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
      throws DecoderQueryException;

  /**
   * Returns a list of decoders that can decode media in the specified MIME type, in priority order,
   * restricted to the codecs matching the given decoder mode.
   *
   * @param mimeType The MIME type for which a decoder is required.
   * @param requiresSecureDecoder Whether a secure decoder is required.
   * @param requiresTunnelingDecoder Whether a tunneling decoder is required.
   * @param decoderMode The {@link DecoderMode} the returned codecs must belong to. {@link
   *     DecoderMode#AUTO} returns the unfiltered list.
   * @return An unmodifiable list of {@link MediaCodecInfo}s corresponding to decoders. May be
   *     empty.
   * @throws DecoderQueryException Thrown if there was an error querying decoders.
   */
  default List<MediaCodecInfo> getDecoderInfos(
      String mimeType,
      boolean requiresSecureDecoder,
      boolean requiresTunnelingDecoder,
      DecoderMode decoderMode)
      throws DecoderQueryException {
    List<MediaCodecInfo> decoderInfos =
        getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
    if (decoderMode == DecoderMode.AUTO) {
      return decoderInfos;
    }
    ImmutableList.Builder<MediaCodecInfo> filteredDecoderInfos = new ImmutableList.Builder<>();
    for (int i = 0; i < decoderInfos.size(); i++) {
      MediaCodecInfo codecInfo = decoderInfos.get(i);
      if (DecoderMode.of(codecInfo) == decoderMode) {
        filteredDecoderInfos.add(codecInfo);
      }
    }
    return filteredDecoderInfos.build();
  }
}
