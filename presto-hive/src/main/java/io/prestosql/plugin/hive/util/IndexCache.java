/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.hetu.core.common.heuristicindex.IndexCacheKey;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveSplit;
import io.prestosql.spi.HetuConstant;
import io.prestosql.spi.heuristicindex.IndexMetadata;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.service.PropertyService;
import org.apache.hadoop.fs.Path;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class IndexCache
{
    private static final Logger LOG = Logger.get(IndexCache.class);
    private static final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("Hive-IndexCache-pool-%d").setDaemon(true).build();

    private static ScheduledExecutorService executor;

    private Long loadDelay; // in millisecond
    private LoadingCache<IndexCacheKey, List<IndexMetadata>> cache;

    @Inject
    public IndexCache(CacheLoader loader)
    {
        // If the static variables have not been initialized
        if (PropertyService.getBooleanProperty(HetuConstant.FILTER_ENABLED)) {
            loadDelay = PropertyService.getDurationProperty(HetuConstant.FILTER_CACHE_LOADING_DELAY).toMillis();
            int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), PropertyService.getLongProperty(HetuConstant.FILTER_CACHE_LOADING_THREADS).intValue());
            executor = Executors.newScheduledThreadPool(numThreads, threadFactory);
            cache = CacheBuilder.newBuilder()
                    .expireAfterWrite(PropertyService.getDurationProperty(HetuConstant.FILTER_CACHE_TTL).toMillis(), TimeUnit.MILLISECONDS)
                    .maximumSize(PropertyService.getLongProperty(HetuConstant.FILTER_MAX_INDICES_IN_CACHE))
                    .build(loader);
        }
    }

    // Override the loadDelay, for testing
    public IndexCache(CacheLoader loader, Long loadDelay)
    {
        this(loader);
        this.loadDelay = loadDelay;
    }

    public List<IndexMetadata> getIndices(String catalog, String table, HiveSplit hiveSplit, TupleDomain<HiveColumnHandle> effectivePredicate, List<HiveColumnHandle> partitions)
    {
        if (cache == null || catalog == null || table == null || hiveSplit == null || effectivePredicate == null) {
            return Collections.emptyList();
        }

        long lastModifiedTime = hiveSplit.getLastModifiedTime();
        Path path = new Path(hiveSplit.getPath());

        URI pathUri = URI.create(path.toString());
        String tableFqn = catalog + "." + table;

        // for each split, load indexes for each predicate (if the predicate contains an indexed column)
        List<IndexMetadata> splitIndexes = new LinkedList<>();
        effectivePredicate.getDomains().get().keySet().stream()
                    // if the domain column is a partition column, skip it
                    .filter(key -> partitions == null || !partitions.contains(key))
                    .map(HiveColumnHandle::getName)
                    .map(String::toLowerCase).forEach(column -> {
                        String indexCacheKeyPath = Paths.get(tableFqn, column, pathUri.getPath()).toString();
                        IndexCacheKey indexCacheKey = new IndexCacheKey(indexCacheKeyPath, lastModifiedTime, "bitmap", "bloom");
                        // check if cache contains the key
                        List<IndexMetadata> predicateIndexes = cache.getIfPresent(indexCacheKey);

                        // if cache didn't contain the key, it has not been loaded, load it asynchronously
                        if (predicateIndexes == null) {
                            executor.schedule(() -> {
                                try {
                                    cache.get(indexCacheKey);
                                    LOG.debug("Loaded index for %s.", indexCacheKeyPath);
                                }
                                catch (ExecutionException e) {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug(e, "Unable to load index for %s. ", indexCacheKeyPath);
                                    }
                                }
                            }, loadDelay, TimeUnit.MILLISECONDS);
                        }
                        else {
                            // if key was present in cache, we still need to check if the index is validate based on the lastModifiedTime
                            // the index is only valid if the lastModifiedTime of the split matches the index's lastModifiedTime
                            for (IndexMetadata index : predicateIndexes) {
                                if (index.getLastUpdated() != lastModifiedTime) {
                                    cache.invalidate(indexCacheKey);
                                    predicateIndexes = Collections.emptyList();
                                    break;
                                }
                            }

                            // cache contained the key
                            if (predicateIndexes != null) {
                                splitIndexes.addAll(predicateIndexes);
                            }
                        }
                    });

        return splitIndexes;
    }
}
