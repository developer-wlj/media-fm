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
package androidx.media3.common;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.function.Consumer;

/** Controls the interactive menu of an optical disc (DVD or Blu-ray) playback. */
@UnstableApi
public interface DiscMenuController {

  /** The outcome of a disc menu open request. */
  enum OpenResult {
    /** The disc menu was opened. */
    OPENED,
    /** The disc menu cannot be opened for the current media item. */
    UNAVAILABLE,
    /** Opening the disc menu timed out. */
    TIMED_OUT,
    /** The open request was cancelled, e.g. by a subsequent request. */
    CANCELLED,
    /** The runtime required for disc menus is not installed. */
    RUNTIME_MISSING
  }

  /**
   * Opens the disc menu.
   *
   * @param action The menu to open. One of {@code "menu"}, {@code "title-menu"} or {@code "popup"}.
   * @param callback The consumer invoked exactly once with the {@link OpenResult} of the request.
   * @return Whether the request was accepted and the callback will be invoked.
   */
  boolean open(String action, Consumer<OpenResult> callback);

  /**
   * Sends a navigation action to the active disc menu.
   *
   * @param action One of {@code "up"}, {@code "down"}, {@code "left"}, {@code "right"}, {@code
   *     "select"}, {@code "prev"}, {@code "menu"}, {@code "title-menu"} or {@code "popup"}.
   * @return Whether the action was delivered to the disc menu.
   */
  boolean sendAction(String action);

  /** Returns whether a disc menu is currently active and receives navigation input. */
  boolean isActive();

  /** Returns whether the current playback is driven by the disc navigation engine. */
  boolean isNavigationPlayback();

  /** Returns whether the active disc menu can be navigated with pointer input. */
  default boolean supportsPointer() {
    return false;
  }

  /**
   * Dispatches a pointer/touch event to the active disc menu.
   *
   * @param event The pointer event, in the player view coordinate space.
   * @return Whether the disc menu handled the event.
   */
  default boolean dispatchTouchEvent(android.view.MotionEvent event) {
    return false;
  }

  /** Returns the highlight of the selected button of a DVD menu, or {@code null}. */
  @Nullable
  default DiscMenuHighlight getMenuHighlight() {
    return null;
  }

  /** Returns the overlay frame of an interactive Blu-ray menu domain, or {@code null}. */
  @Nullable
  default DiscMenuOverlay getMenuOverlay(int domain) {
    return null;
  }
}
