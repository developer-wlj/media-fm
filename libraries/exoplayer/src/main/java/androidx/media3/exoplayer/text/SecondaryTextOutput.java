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

import androidx.annotation.Nullable;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * The output of the secondary text renderer.
 *
 * <p>A {@link TextRenderer} created with this output renders the cues of the track selected as the
 * secondary subtitle track. The latest cues can be consumed through {@link #setCuesListener}, for
 * example to feed a secondary {@link androidx.media3.ui.SubtitleView}.
 */
@UnstableApi
public final class SecondaryTextOutput implements TextOutput {

  private final Object lock;

  @Nullable private ImmutableList<Cue> cues;
  @Nullable private Consumer<List<Cue>> cuesListener;

  /** Creates a secondary text output. */
  public SecondaryTextOutput() {
    this.lock = new Object();
    this.cues = ImmutableList.of();
  }

  /**
   * Sets the listener notified whenever the cues of the secondary text renderer change.
   *
   * @param cuesListener The listener, or null to remove a previously set listener.
   */
  public void setCuesListener(@Nullable Consumer<List<Cue>> cuesListener) {
    ImmutableList<Cue> cues;
    synchronized (lock) {
      this.cuesListener = cuesListener;
      cues = this.cues;
    }
    if (cuesListener != null && !cues.isEmpty()) {
      cuesListener.accept(cues);
    }
  }

  /** Returns the latest cues of the secondary text renderer. */
  public ImmutableList<Cue> getCues() {
    synchronized (lock) {
      return cues;
    }
  }

  /** Clears the cues of the secondary text output. */
  public void clearCues() {
    setCuesInternal(ImmutableList.of());
  }

  @Override
  public void onCues(CueGroup cueGroup) {
    setCuesInternal(ImmutableList.copyOf(cueGroup.cues));
  }

  private void setCuesInternal(ImmutableList<Cue> cues) {
    @Nullable Consumer<List<Cue>> cuesListener;
    synchronized (lock) {
      if (this.cues.equals(cues)) {
        return;
      }
      this.cues = cues;
      cuesListener = this.cuesListener;
    }
    if (cuesListener != null) {
      cuesListener.accept(cues);
    }
  }

}
