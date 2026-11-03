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
package androidx.media3.common.util;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨道选择工具类。
 *
 * <p>提供按轨道 id 选中、按顺序循环切换、禁用整类轨道与恢复自动选择等能力，
 * 供播放命令层按 mpv 风格的 {@code audio} / {@code sub} 指令调用。
 *
 * @author Shiro
 */
@UnstableApi
public final class TrackSelectionUtil {

  /** 工具类禁止实例化。 */
  private TrackSelectionUtil() {}

  /**
   * 选中指定轨道 id 的单条轨道。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型，取值见 {@link C} 的 {@code TRACK_TYPE_*} 常量
   * @param trackId 轨道 id，对应 {@link Format#id}
   * @return 找到并应用返回 {@code true}，否则返回 {@code false}
   */
  public static boolean selectTrack(Player player, int trackType, String trackId) {
    List<TrackSelectionOverride> candidates = collectOverrides(player, trackType, null);
    for (int i = 0; i < candidates.size(); i++) {
      TrackSelectionOverride override = candidates.get(i);
      Format format = override.mediaTrackGroup.getFormat(override.trackIndices.get(0));
      if (trackId.equals(format.id)) {
        applyOverride(player, override);
        return true;
      }
    }
    return false;
  }

  /**
   * 按当前可用轨道顺序循环切换。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @param forward {@code true} 表示向后循环，{@code false} 表示向前
   * @param includeOff 是否把“关闭该类型轨道”的占位项纳入循环
   * @return 成功切换返回 {@code true}
   */
  public static boolean cycleTrack(Player player, int trackType, boolean forward, boolean includeOff) {
    return cycle(player, trackType, collectOverrides(player, trackType, null), forward, includeOff);
  }

  /**
   * 仅在给定轨道 id 集合内循环切换，集合顺序即循环顺序。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @param trackIds 参与循环的轨道 id 列表
   * @param forward {@code true} 表示向后循环，{@code false} 表示向前
   * @param includeOff 是否把“关闭”占位项纳入循环
   * @return 成功切换返回 {@code true}
   */
  public static boolean cycleTrack(
      Player player, int trackType, List<String> trackIds, boolean forward, boolean includeOff) {
    return cycle(player, trackType, collectOverrides(player, trackType, trackIds), forward, includeOff);
  }

  /**
   * 启用或禁用某一类轨道。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @param disabled {@code true} 表示禁用该类轨道
   * @return 参数已提交返回 {@code true}
   */
  public static boolean setTrackTypeDisabled(Player player, int trackType, boolean disabled) {
    TrackSelectionParameters parameters =
        player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(trackType, disabled)
            .build();
    player.setTrackSelectionParameters(parameters);
    return true;
  }

  /**
   * 清除某一类轨道的覆盖并恢复自动选择。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @return 参数已提交返回 {@code true}
   */
  public static boolean resetTrackSelection(Player player, int trackType) {
    TrackSelectionParameters parameters =
        player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .clearOverridesOfType(trackType)
            .build();
    player.setTrackSelectionParameters(parameters);
    return true;
  }

  /**
   * 收集指定类型、可选 id 过滤后的轨道覆盖项，顺序与 {@link Tracks} 一致。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @param trackIds 允许参与选择的轨道 id 集合，{@code null} 表示不过滤
   * @return 候选覆盖项列表，可能为空
   */
  private static List<TrackSelectionOverride> collectOverrides(
      Player player, int trackType, @Nullable List<String> trackIds) {
    List<TrackSelectionOverride> overrides = new ArrayList<>();
    for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
      if (group.getType() != trackType) continue;
      for (int index = 0; index < group.length; index++) {
        if (!group.isTrackSupported(index)) continue;
        Format format = group.getTrackFormat(index);
        if (trackIds != null && (format.id == null || !trackIds.contains(format.id))) continue;
        overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), index));
      }
    }
    return overrides;
  }

  /**
   * 计算当前选中项的下一个目标并应用。
   *
   * <p>未选中任何轨道（“关闭”状态）时，向后循环取第一项，向前循环取最后一项；
   * 已选中时按 {@code forward} 取相邻项，越界后回绕。{@code includeOff} 为 {@code true}
   * 时，越界切换到“关闭”状态而不是回绕到另一端。
   *
   * @param player 目标播放器
   * @param trackType 轨道类型
   * @param candidates 候选覆盖项
   * @param forward 循环方向
   * @param includeOff 是否包含“关闭”占位项
   * @return 无候选项时返回 {@code false}，其余情况返回 {@code true}
   */
  private static boolean cycle(
      Player player,
      int trackType,
      List<TrackSelectionOverride> candidates,
      boolean forward,
      boolean includeOff) {
    if (candidates.isEmpty()) return false;
    int size = candidates.size();
    int current = indexOfSelected(player, candidates);
    if (current == C.INDEX_UNSET) {
      applyOverride(player, candidates.get(forward ? 0 : size - 1));
      return true;
    }
    int next = forward ? current + 1 : current - 1;
    if (next >= 0 && next < size) {
      applyOverride(player, candidates.get(next));
      return true;
    }
    if (includeOff) {
      setTrackTypeDisabled(player, trackType, true);
      return true;
    }
    applyOverride(player, candidates.get(forward ? 0 : size - 1));
    return true;
  }

  /**
   * 返回当前选中候选的下标，未选中返回 {@link C#INDEX_UNSET}。
   *
   * @param player 目标播放器
   * @param candidates 候选覆盖项
   * @return 选中候选下标或无选中标记
   */
  private static int indexOfSelected(Player player, List<TrackSelectionOverride> candidates) {
    Tracks tracks = player.getCurrentTracks();
    for (int i = 0; i < candidates.size(); i++) {
      TrackSelectionOverride override = candidates.get(i);
      for (Tracks.Group group : tracks.getGroups()) {
        if (!group.getMediaTrackGroup().equals(override.mediaTrackGroup)) continue;
        if (group.isTrackSelected(override.trackIndices.get(0))) return i;
      }
    }
    return C.INDEX_UNSET;
  }

  /**
   * 以“仅保留目标类型覆盖并启用该类型”的方式应用一条轨道覆盖。
   *
   * @param player 目标播放器
   * @param override 待应用的轨道覆盖
   */
  private static void applyOverride(Player player, TrackSelectionOverride override) {
    TrackSelectionParameters parameters =
        player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(override.mediaTrackGroup.type, false)
            .setOverrideForType(override)
            .build();
    player.setTrackSelectionParameters(parameters);
  }
}
