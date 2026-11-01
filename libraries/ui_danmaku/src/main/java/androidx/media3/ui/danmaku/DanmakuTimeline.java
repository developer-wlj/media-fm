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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** Owns sorted danmaku entries and the window currently mapped into a view. */
final class DanmakuTimeline {

  private final List<Danmaku[]> chunks = new ArrayList<>();
  private int itemCount;
  private long loadedFromMs = Long.MIN_VALUE;
  private long loadedToMs = Long.MIN_VALUE;
  private long aheadMs;
  private long behindMs;
  private long reloadThresholdMs;

  DanmakuTimeline(long aheadMs, long behindMs, long reloadThresholdMs) {
    setWindowParams(aheadMs, behindMs, reloadThresholdMs);
  }

  private static List<Danmaku> subList(Danmaku[] sorted, long fromMs, long toMs) {
    if (sorted.length == 0 || fromMs > toMs) {
      return Collections.emptyList();
    }
    int fromIndex = lowerBound(sorted, fromMs);
    int toIndex = upperBound(sorted, toMs);
    if (fromIndex >= toIndex) {
      return Collections.emptyList();
    }
    return Arrays.asList(sorted).subList(fromIndex, toIndex);
  }

  private static int lowerBound(Danmaku[] sorted, long target) {
    int low = 0;
    int high = sorted.length;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (sorted[middle].timeMs < target) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static int upperBound(Danmaku[] sorted, long target) {
    int low = 0;
    int high = sorted.length;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (sorted[middle].timeMs <= target) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  /** Returns a snapshot of every loaded item, ordered by time. */
  List<Danmaku> snapshot() {
    if (chunks.isEmpty() || itemCount == 0) {
      return Collections.emptyList();
    }
    Danmaku[] all = new Danmaku[itemCount];
    int index = 0;
    for (Danmaku[] chunk : chunks) {
      System.arraycopy(chunk, 0, all, index, chunk.length);
      index += chunk.length;
    }
    Arrays.sort(all, (first, second) -> Long.compare(first.timeMs, second.timeMs));
    return Arrays.asList(all);
  }

  void setWindowParams(long aheadMs, long behindMs, long reloadThresholdMs) {
    this.aheadMs = Math.max(0L, aheadMs);
    this.behindMs = Math.max(0L, behindMs);
    this.reloadThresholdMs = Math.min(this.aheadMs, Math.max(0L, reloadThresholdMs));
  }

  void setItems(List<Danmaku> items) {
    Danmaku[] sorted = items.toArray(new Danmaku[0]);
    Arrays.sort(sorted, Danmaku.BY_TIME);
    setSortedItems(sorted);
  }

  void setSortedItems(Danmaku[] items) {
    chunks.clear();
    itemCount = items.length;
    if (items.length > 0) {
      chunks.add(items);
    }
    invalidateWindow();
  }

  void clear() {
    chunks.clear();
    itemCount = 0;
    invalidateWindow();
  }

  void invalidateWindow() {
    loadedFromMs = Long.MIN_VALUE;
    loadedToMs = Long.MIN_VALUE;
  }

  boolean needsExtension(long positionMs, long timeOffsetMs) {
    long sourceMs = positionMs - timeOffsetMs;
    return isOutside(positionMs, timeOffsetMs) || loadedToMs - sourceMs < reloadThresholdMs;
  }

  boolean isOutside(long positionMs, long timeOffsetMs) {
    return isRangeOutside(positionMs, timeOffsetMs, 0L);
  }

  boolean isRangeOutside(long positionMs, long timeOffsetMs, long lookbackMs) {
    long sourceMs = positionMs - timeOffsetMs;
    return sourceMs - lookbackMs < loadedFromMs || sourceMs > loadedToMs;
  }

  Update extend(long positionMs, long timeOffsetMs, boolean forceReload) {
    long sourceMs = positionMs - timeOffsetMs;
    long desiredToMs = sourceMs + aheadMs;
    if (itemCount == 0) {
      loadedFromMs = sourceMs - behindMs;
      loadedToMs = desiredToMs;
      return forceReload ? Update.replace(Collections.emptyList()) : Update.NONE;
    }
    boolean outsideWindow = sourceMs < loadedFromMs || sourceMs > loadedToMs;
    boolean nearWindowEnd = loadedToMs - sourceMs < reloadThresholdMs;
    if (!forceReload && !outsideWindow && !nearWindowEnd) {
      return Update.NONE;
    }
    if (forceReload || outsideWindow) {
      loadedFromMs = sourceMs - behindMs;
      loadedToMs = desiredToMs;
      return Update.replace(subList(loadedFromMs, loadedToMs));
    }
    List<Danmaku> extension = subList(loadedToMs + 1, desiredToMs);
    loadedToMs = desiredToMs;
    return extension.isEmpty() ? Update.NONE : Update.append(extension);
  }

  List<Danmaku> remap(long positionMs, long timeOffsetMs) {
    long sourceMs = positionMs - timeOffsetMs;
    loadedFromMs = sourceMs - behindMs;
    loadedToMs = sourceMs + aheadMs;
    return subList(loadedFromMs, loadedToMs);
  }

  Merge merge(List<Danmaku> newItems) {
    Danmaku[] incoming = newItems.toArray(new Danmaku[0]);
    Arrays.sort(incoming, Danmaku.BY_TIME);
    return mergeSorted(incoming);
  }

  Merge mergeSorted(Danmaku[] incoming) {
    boolean needsInitialWindow = loadedFromMs == Long.MIN_VALUE;
    if (incoming.length > 0) {
      chunks.add(incoming);
      itemCount += incoming.length;
    }
    return new Merge(incoming, needsInitialWindow);
  }

  List<Danmaku> visibleItems(Danmaku[] incoming, long positionMs, long timeOffsetMs) {
    long sourceMs = positionMs - timeOffsetMs;
    loadedToMs = Math.max(loadedToMs, sourceMs + aheadMs);
    return subList(incoming, loadedFromMs, loadedToMs);
  }

  int size() {
    return itemCount;
  }

  private List<Danmaku> subList(long fromMs, long toMs) {
    if (chunks.isEmpty() || fromMs > toMs) {
      return Collections.emptyList();
    }
    List<ChunkCursor> cursors = new ArrayList<>(chunks.size());
    int resultSize = 0;
    for (int i = 0; i < chunks.size(); i++) {
      Danmaku[] chunk = chunks.get(i);
      int fromIndex = lowerBound(chunk, fromMs);
      int toIndex = upperBound(chunk, toMs);
      if (fromIndex < toIndex) {
        cursors.add(new ChunkCursor(chunk, fromIndex, toIndex, i));
        resultSize += toIndex - fromIndex;
      }
    }
    if (cursors.isEmpty()) {
      return Collections.emptyList();
    }
    if (cursors.size() == 1) {
      ChunkCursor cursor = cursors.get(0);
      return Arrays.asList(cursor.items).subList(cursor.index, cursor.endIndex);
    }
    PriorityQueue<ChunkCursor> queue = new PriorityQueue<>(cursors);
    List<Danmaku> result = new ArrayList<>(resultSize);
    while (!queue.isEmpty()) {
      ChunkCursor cursor = queue.remove();
      result.add(cursor.items[cursor.index++]);
      if (cursor.index < cursor.endIndex) {
        queue.add(cursor);
      }
    }
    return result;
  }

  private static final class ChunkCursor implements Comparable<ChunkCursor> {

    final Danmaku[] items;
    final int endIndex;
    final int chunkIndex;
    int index;

    ChunkCursor(Danmaku[] items, int index, int endIndex, int chunkIndex) {
      this.items = items;
      this.index = index;
      this.endIndex = endIndex;
      this.chunkIndex = chunkIndex;
    }

    @Override
    public int compareTo(ChunkCursor other) {
      int timeComparison = Long.compare(items[index].timeMs, other.items[other.index].timeMs);
      return timeComparison != 0 ? timeComparison : Integer.compare(chunkIndex, other.chunkIndex);
    }
  }

  static final class Update {

    static final Update NONE = new Update(false, false, Collections.emptyList());
    final boolean changed;
    final boolean replace;
    final List<Danmaku> items;

    private Update(boolean changed, boolean replace, List<Danmaku> items) {
      this.changed = changed;
      this.replace = replace;
      this.items = items;
    }

    private static Update replace(List<Danmaku> items) {
      return new Update(true, true, items);
    }

    private static Update append(List<Danmaku> items) {
      return new Update(true, false, items);
    }
  }

  static final class Merge {

    final Danmaku[] incoming;
    final boolean needsInitialWindow;

    private Merge(Danmaku[] incoming, boolean needsInitialWindow) {
      this.incoming = incoming;
      this.needsInitialWindow = needsInitialWindow;
    }
  }
}
