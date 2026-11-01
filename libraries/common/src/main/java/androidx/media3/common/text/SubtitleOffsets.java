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

import androidx.media3.common.util.UnstableApi;

/**
 * The text offsets applied to the primary and secondary subtitle outputs, in milliseconds.
 */
@UnstableApi
public final class SubtitleOffsets {

  /** Offsets that leave both subtitle outputs unchanged. */
  public static final SubtitleOffsets ZERO = new SubtitleOffsets(0, 0);

  /** The offset applied to the primary subtitle output, in milliseconds. */
  public final long primaryMs;
  /** The offset applied to the secondary subtitle output, in milliseconds. */
  public final long secondaryMs;

  /**
   * Creates offsets.
   *
   * @param primaryMs The offset for the primary subtitle output, in milliseconds.
   * @param secondaryMs The offset for the secondary subtitle output, in milliseconds.
   */
  public SubtitleOffsets(long primaryMs, long secondaryMs) {
    this.primaryMs = primaryMs;
    this.secondaryMs = secondaryMs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SubtitleOffsets)) {
      return false;
    }
    SubtitleOffsets that = (SubtitleOffsets) o;
    return primaryMs == that.primaryMs && secondaryMs == that.secondaryMs;
  }

  @Override
  public int hashCode() {
    int result = (int) (primaryMs ^ (primaryMs >>> 32));
    result = 31 * result + (int) (secondaryMs ^ (secondaryMs >>> 32));
    return result;
  }
}
