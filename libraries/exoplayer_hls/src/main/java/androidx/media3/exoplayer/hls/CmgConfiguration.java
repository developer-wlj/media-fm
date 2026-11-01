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
package androidx.media3.exoplayer.hls;

import android.net.Uri;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Configuration for playing CMG protected media streams.
 *
 * <p>A configuration is only created for media URIs known to be protected with CMG, either because
 * they are explicitly flagged with a {@code cmg=1} query parameter, or because they belong to a
 * known CMG provider.
 */
public final class CmgConfiguration {

  private static final String CMG_QUERY_PARAMETER = "cmg";
  private static final String CMG_QUERY_VALUE = "1";

  private static final String PAGE_URL = "https://www.yangshipin.cn/tv/home";

  /** The page location the CMG runtime is configured against. */
  private final Uri locationUri;

  @Nullable private final String programId;

  private CmgConfiguration(Uri locationUri, @Nullable String programId) {
    this.locationUri = locationUri;
    this.programId = programId;
  }

  /**
   * Returns the CMG configuration for a media URI, or {@code null} if the URI does not describe a
   * CMG protected stream.
   *
   * @param mediaUri The media URI to inspect.
   * @return The configuration describing the CMG stream, or {@code null}.
   */
  @Nullable
  public static CmgConfiguration fromMediaUri(@Nullable Uri mediaUri) {
    if (mediaUri == null || !mediaUri.isHierarchical()) {
      return null;
    }
    if (!CMG_QUERY_VALUE.equals(mediaUri.getQueryParameter(CMG_QUERY_PARAMETER))) {
      String host = mediaUri.getHost();
      if (host == null) {
        return null;
      }
      String normalizedHost = host.toLowerCase(Locale.US);
      String path = mediaUri.getPath();
      if ((!normalizedHost.equals("ysp.cctv.cn") && !normalizedHost.endsWith(".ysp.cctv.cn"))
          || path == null
          || !path.toLowerCase(Locale.US).endsWith(".m3u8")) {
        return null;
      }
    }
    String programId = mediaUri.getQueryParameter("pid");
    Uri locationUri =
        programId == null || programId.isEmpty()
            ? Uri.parse(PAGE_URL)
            : Uri.parse(PAGE_URL + "?pid=" + Uri.encode(programId));
    return new CmgConfiguration(
        locationUri, programId == null || programId.isEmpty() ? null : programId);
  }

  /** Returns the page location the CMG runtime is configured against. */
  public Uri getLocationUri() {
    return locationUri;
  }

  /** Returns the location string the CMG runtime is configured against. */
  public String getLocation() {
    return locationUri.toString();
  }

  /** Returns the CMG program id of the stream, or {@code null} if it is not part of the URI. */
  @Nullable
  public String getProgramId() {
    return programId;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CmgConfiguration)) {
      return false;
    }
    CmgConfiguration that = (CmgConfiguration) o;
    return locationUri.equals(that.locationUri) && Objects.equals(programId, that.programId);
  }

  @Override
  public int hashCode() {
    return 31 * locationUri.hashCode() + Objects.hashCode(programId);
  }
}
