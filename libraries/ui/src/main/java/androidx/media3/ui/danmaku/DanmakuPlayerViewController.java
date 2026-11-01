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
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import okhttp3.OkHttpClient;

/**
 * Binds danmaku rendering to a {@link PlayerView}: it hosts a {@link DanmakuView} inside the
 * player view, keeps it attached to the current player and exposes the playback-facing controls
 * (data source, configuration, enable state and live sending).
 */
@UnstableApi
public final class DanmakuPlayerViewController {

  @Nullable private PlayerView playerView;
  @Nullable private DanmakuView danmakuView;
  private final DanmakuController controller;

  @Nullable private String dataSource;
  private boolean enabled;
  private DanmakuConfig config = DanmakuConfig.DEFAULT;

  /** Creates a controller. Call {@link #bind(PlayerView)} to attach it to a player view. */
  public DanmakuPlayerViewController() {
    this.controller = new DanmakuController();
  }

  /** Attaches the danmaku view to {@code playerView} and follows its player. */
  public void bind(PlayerView playerView) {
    this.playerView = playerView;
    if (danmakuView == null) {
      danmakuView = new DanmakuView(playerView.getContext());
      addDanmakuView(playerView, danmakuView);
      controller.setView(danmakuView);
    }
    Player player = playerView.getPlayer();
    controller.setPlayer(player);
    controller.setEnabled(enabled);
    controller.setConfig(config);
    controller.setDataSource(dataSource == null ? null : Uri.parse(dataSource));
  }

  /** Returns the currently loaded items, ordered by time. */
  public java.util.List<Danmaku> getItems() {
    return controller.getItems();
  }

  /** Sets the HTTP client used to fetch danmaku sources. */
  public void setOkHttpClient(@Nullable OkHttpClient client) {
    controller.setOkHttpClient(client);
  }

  /** Sets whether danmaku rendering is enabled. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    controller.setEnabled(enabled);
  }

  /** Sets the rendering configuration. */
  public void setConfig(DanmakuConfig config) {
    this.config = config == null ? DanmakuConfig.DEFAULT : config;
    controller.setConfig(this.config);
  }

  /**
   * Sets the danmaku source URI, or {@code null} to detach the current source.
   *
   * @param dataSource The source URI, or {@code null}.
   */
  public void setDataSource(@Nullable String dataSource) {
    this.dataSource = dataSource;
    controller.setDataSource(dataSource == null ? null : Uri.parse(dataSource));
  }

  /**
   * Sets the danmaku source URI, or {@code null} to detach the current source.
   *
   * @param dataSource The source URI, or {@code null}.
   */
  public void setDataSource(@Nullable Uri dataSource) {
    setDataSource(dataSource == null ? null : dataSource.toString());
  }

  /** Posts a danmaku to be displayed immediately on the next playback frame. */
  public void post(Danmaku danmaku) {
    controller.sendNow(danmaku);
  }

  /** Sends a danmaku at the current playback position. */
  public void sendNow(String text) {
    controller.sendNow(text);
  }

  /** Detaches the danmaku view from the player view and releases its resources. */
  public void close() {
    controller.setEnabled(false);
    controller.setPlayer(null);
    controller.setView(null);
    if (danmakuView != null) {
      danmakuView.release();
      if (danmakuView.getParent() instanceof ViewGroup) {
        ((ViewGroup) danmakuView.getParent()).removeView(danmakuView);
      }
      danmakuView = null;
    }
    playerView = null;
  }

  private static void addDanmakuView(PlayerView playerView, DanmakuView view) {
    ViewGroup.LayoutParams params =
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    int insertIndex = 0;
    for (int i = 0; i < playerView.getChildCount(); i++) {
      if (playerView.getChildAt(i) instanceof AspectRatioFrameLayout) {
        insertIndex = i + 1;
        break;
      }
    }
    playerView.addView(view, insertIndex, params);
  }
}
