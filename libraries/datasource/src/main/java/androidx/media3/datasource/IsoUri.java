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
package androidx.media3.datasource;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;

/** Utility for building URIs that address ISO images with edition selection. */
public final class IsoUri {

  /** Fragment prefix used to carry the selected ISO edition index. */
  private static final String EDITION_FRAGMENT_PREFIX = "edition=";

  private IsoUri() {}

  /**
   * Returns {@code uri} extended with the given ISO edition index as a fragment, e.g. {@code
   * image.iso#edition=1}.
   *
   * @param uri The ISO image URI to extend.
   * @param editionIndex The zero-based ISO edition index.
   * @return The URI addressing the given ISO edition.
   */
  public static Uri withEditionIndex(Uri uri, int editionIndex) {
    checkNotNull(uri);
    return uri.buildUpon().encodedFragment(EDITION_FRAGMENT_PREFIX + editionIndex).build();
  }
}
