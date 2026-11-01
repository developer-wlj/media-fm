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

import androidx.media3.datasource.cache.Cache;

/**
 * A {@link DataSource.Factory} that keeps the shared disk {@link Cache} available for ISO image
 * reading.
 *
 * <p>Data sources created by the wrapped factory are used as-is. The cache is retained so that the
 * ISO reading stack can progressively store large, randomly accessed image ranges on disk instead
 * of reading them through the network for every seek.
 */
public final class ProgressiveIsoCache implements DataSource.Factory {

  private final DataSource.Factory dataSourceFactory;
  private final Cache cache;

  private ProgressiveIsoCache(DataSource.Factory dataSourceFactory, Cache cache) {
    this.dataSourceFactory = dataSourceFactory;
    this.cache = cache;
  }

  /**
   * Returns a factory that combines the given factory with a shared disk cache for ISO image
   * reading.
   *
   * @param dataSourceFactory The factory to wrap.
   * @param cache The shared disk cache backing ISO image reads.
   * @return A factory that provides ISO image reading on top of the given factory.
   */
  public static DataSource.Factory withDiskCache(
      DataSource.Factory dataSourceFactory, Cache cache) {
    return new ProgressiveIsoCache(checkNotNull(dataSourceFactory), checkNotNull(cache));
  }

  @Override
  public DataSource createDataSource() {
    return dataSourceFactory.createDataSource();
  }

  /** Returns the shared disk cache backing ISO image reads. */
  public Cache getCache() {
    return cache;
  }
}
