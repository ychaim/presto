/*
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
package com.facebook.presto.accumulo.index;

import com.facebook.presto.accumulo.conf.AccumuloConfig;
import com.facebook.presto.accumulo.index.metrics.MetricCacheKey;
import com.facebook.presto.accumulo.index.metrics.MetricsStorage;
import com.facebook.presto.accumulo.model.AccumuloColumnConstraint;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.io.Text;
import org.apache.htrace.Sampler;
import org.apache.htrace.TraceScope;

import javax.annotation.Nonnull;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static com.facebook.presto.accumulo.AccumuloErrorCode.UNEXPECTED_ACCUMULO_ERROR;
import static com.facebook.presto.accumulo.conf.AccumuloSessionProperties.getIndexCardinalityCachePollingDuration;
import static com.facebook.presto.accumulo.conf.AccumuloSessionProperties.isIndexShortCircuitEnabled;
import static com.facebook.presto.accumulo.conf.AccumuloSessionProperties.isTracingEnabled;
import static com.facebook.presto.accumulo.index.Indexer.getIndexTableName;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.htrace.Trace.startSpan;

/**
 * This class is an indexing utility to cache the cardinality of a column value for every table.
 * Each table has its own cache that is independent of every other, and every column also has its
 * own Guava cache. Use of this utility can have a significant impact for retrieving the cardinality
 * of many columns, preventing unnecessary accesses to the metrics table in Accumulo for a
 * cardinality that won't change much.
 */
public class ColumnCardinalityCache
{
    private static final Logger LOG = Logger.get(ColumnCardinalityCache.class);
    private final ExecutorService coreExecutor;
    private final BoundedExecutor executorService;
    private final CardinalityCacheLoader cacheLoader;
    private final LoadingCache<MetricCacheKey, Long> cache;

    @Inject
    public ColumnCardinalityCache(AccumuloConfig config)
    {
        int size = requireNonNull(config, "config is null").getCardinalityCacheSize();
        Duration expireDuration = config.getCardinalityCacheExpiration();

        // Create a bounded executor with a pool size at 4x number of processors
        this.coreExecutor = newCachedThreadPool(daemonThreadsNamed("cardinality-lookup-%s"));
        this.executorService = new BoundedExecutor(coreExecutor, 4 * Runtime.getRuntime().availableProcessors());

        this.cacheLoader = new CardinalityCacheLoader();
        LOG.debug("Created new cache size %d expiry %s", size, expireDuration);
        this.cache = CacheBuilder.newBuilder().maximumSize(size).expireAfterWrite(expireDuration.toMillis(), MILLISECONDS).build(cacheLoader);
    }

    @PreDestroy
    public void shutdown()
    {
        coreExecutor.shutdownNow();
    }

    /**
     * Gets the cardinality for each {@link AccumuloColumnConstraint}.
     * Given constraints are expected to be indexed! Who knows what would happen if they weren't!
     *
     * @param session Connector session
     * @param schema Schema name
     * @param table Table name
     * @param queryParameters List of index query parameters
     * @param auths Scan-time authorizations for loading any cardinalities from Accumulo
     * @param earlyReturnThreshold Smallest acceptable cardinality to return early while other tasks complete. Use a negative value to disable early return.
     * @param metricsStorage Metrics storage for looking up the cardinality
     * @return An immutable multimap of cardinality to column constraint, sorted by cardinality from smallest to largest
     * @throws TableNotFoundException If the metrics table does not exist
     * @throws ExecutionException If another error occurs; I really don't even know anymore.
     */
    public Multimap<Long, IndexQueryParameters> getCardinalities(
            ConnectorSession session,
            String schema,
            String table,
            Authorizations auths,
            List<IndexQueryParameters> queryParameters,
            long earlyReturnThreshold,
            MetricsStorage metricsStorage)
            throws ExecutionException, TableNotFoundException
    {
        requireNonNull(schema, "schema is null");
        requireNonNull(table, "table is null");
        requireNonNull(queryParameters, "queryParameters is null");
        requireNonNull(auths, "auths is null");
        requireNonNull(metricsStorage, "metricsStorage is null");

        Duration pollingDuration;
        if (isIndexShortCircuitEnabled(session)) {
            pollingDuration = getIndexCardinalityCachePollingDuration(session);
        }
        else {
            earlyReturnThreshold = 0;
            pollingDuration = new Duration(0, MILLISECONDS);
        }

        if (queryParameters.isEmpty()) {
            return ImmutableMultimap.of();
        }

        cacheLoader.setMetricsStorage(metricsStorage);

        // Submit tasks to the executor to fetch column cardinality, adding it to the Guava cache if necessary
        CompletionService<Pair<Long, IndexQueryParameters>> executor = new ExecutorCompletionService<>(executorService);
        queryParameters.forEach(queryParameter ->
                executor.submit(() -> {
                            long start = System.currentTimeMillis();
                            long cardinality = getColumnCardinality(session, metricsStorage, schema, table, auths, queryParameter);
                            LOG.debug("Cardinality for column %s is %s, took %s ms", queryParameter.getIndexColumn(), cardinality, System.currentTimeMillis() - start);
                            queryParameter.setCardinality(cardinality);
                            return Pair.of(cardinality, queryParameter);
                        }
                ));

        long pollingMillis = pollingDuration.toMillis();

        // Create a multi map sorted by cardinality
        ListMultimap<Long, IndexQueryParameters> cardinalityToConstraints = MultimapBuilder.treeKeys().arrayListValues().build();
        try {
            boolean earlyReturn = false;
            int numTasks = queryParameters.size();
            do {
                // Sleep for the polling duration to allow concurrent tasks to run for this time
                if (pollingMillis > 0) {
                    Thread.sleep(pollingMillis);
                }

                // Poll each task, retrieving the result if it is done
                for (int i = 0; i < numTasks; ++i) {
                    Future<Pair<Long, IndexQueryParameters>> futureCardinality = executor.poll();
                    if (futureCardinality != null) {
                        Pair<Long, IndexQueryParameters> columnCardinality = futureCardinality.get();
                        cardinalityToConstraints.put(columnCardinality.getLeft(), columnCardinality.getRight());
                    }
                }

                // If the smallest cardinality is present and below the threshold, set the earlyReturn flag
                Optional<Entry<Long, IndexQueryParameters>> smallestCardinality = cardinalityToConstraints.entries().stream().findFirst();
                if (smallestCardinality.isPresent()) {
                    if (smallestCardinality.get().getKey() <= earlyReturnThreshold) {
                        LOG.debug("Cardinality for column %s is below threshold of %s. Returning early while other tasks finish", smallestCardinality.get().getValue().getIndexColumn(), earlyReturnThreshold);
                        earlyReturn = true;
                    }
                }
            }
            while (!earlyReturn && cardinalityToConstraints.entries().size() < numTasks);
        }
        catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PrestoException(UNEXPECTED_ACCUMULO_ERROR, "Exception when getting cardinality", e);
        }

        // Create a copy of the cardinalities
        return ImmutableMultimap.copyOf(cardinalityToConstraints);
    }

    /**
     * Gets the column cardinality for all of the given range values. May reach out to the
     * metrics table in Accumulo to retrieve new cache elements.
     *
     * @param session Connector session
     * @param metricsStorage Metrics storage
     * @param schema Table schema
     * @param table Table name
     * @param auths Scan-time authorizations for loading any cardinalities from Accumulo
     * @param queryParameters Parameters to use for the column cardinality
     * @return The cardinality of the column
     */
    private long getColumnCardinality(ConnectorSession session, MetricsStorage metricsStorage, String schema, String table, Authorizations auths, IndexQueryParameters queryParameters)
            throws ExecutionException
    {
        LOG.debug("Getting cardinality for " + queryParameters.getIndexColumn());

        List<Future<Long>> tasks = new ArrayList<>();
        CompletionService<Long> executor = new ExecutorCompletionService<>(executorService);
        for (Entry<Text, Collection<Range>> metricParamEntry : queryParameters.getMetricParameters().asMap().entrySet()) {
            tasks.add(executor.submit(() -> {
                Optional<TraceScope> cardinalityTrace = Optional.empty();
                try {
                    if (isTracingEnabled(session)) {
                        String traceName = String.format("%s:%s_metrics:ColumnCardinalityCache:%s", session.getQueryId(), getIndexTableName(schema, table), metricParamEntry.getKey());
                        cardinalityTrace = Optional.of(startSpan(traceName, Sampler.ALWAYS));
                    }

                    // Collect all exact Accumulo Ranges, i.e. single value entries vs. a full scan
                    List<MetricCacheKey> exactRanges = new ArrayList<>();
                    List<MetricCacheKey> nonExactRanges = new ArrayList<>();
                    metricParamEntry.getValue()
                            .forEach(range -> {
                                MetricCacheKey key = new MetricCacheKey(schema, table, metricParamEntry.getKey(), auths, range);
                                if (isExact(range)) {
                                    exactRanges.add(key);
                                }
                                else {
                                    nonExactRanges.add(key);
                                }
                            });

                    // Sum the cardinalities for the exact-value Ranges
                    // This is where the reach-out to Accumulo occurs for all Ranges that have not
                    // previously been fetched
                    long sum = 0;
                    if (exactRanges.size() == 1) {
                        sum = cache.get(exactRanges.get(0));
                    }
                    else {
                        for (Long value : cache.getAll(exactRanges).values()) {
                            sum += value;
                        }
                    }

                    // For the non-exact ranges, call out directly to the metrics storage (no cache)
                    sum += metricsStorage.newReader().getCardinality(nonExactRanges);

                    return sum;
                }
                finally {
                    cardinalityTrace.ifPresent(TraceScope::close);
                }
            }));
        }

        return tasks.stream().mapToLong(x -> {
            try {
                return x.get();
            }
            catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                throw new PrestoException(UNEXPECTED_ACCUMULO_ERROR, "Exception when getting cardinality", e);
            }
        }).sum();
    }

    private static boolean isExact(Range range)
    {
        return !range.isInfiniteStartKey() && !range.isInfiniteStopKey() &&
                range.getStartKey().followingKey(PartialKey.ROW).equals(range.getEndKey());
    }

    private class CardinalityCacheLoader
            extends CacheLoader<MetricCacheKey, Long>
    {
        private MetricsStorage metricsStorage;

        public void setMetricsStorage(MetricsStorage metricsStorage)
        {
            this.metricsStorage = metricsStorage;
        }

        /**
         * Loads the cardinality for the given Range. Uses a BatchScanner and sums the cardinality for all values that encapsulate the Range.
         *
         * @param key Range to get the cardinality for
         * @return The cardinality of the column, which would be zero if the value does not exist
         */
        @Override
        public Long load(@Nonnull MetricCacheKey key)
                throws Exception
        {
            return metricsStorage.newReader().getCardinality(key);
        }

        @Override
        public Map<MetricCacheKey, Long> loadAll(@Nonnull Iterable<? extends MetricCacheKey> keys)
                throws Exception
        {
            @SuppressWarnings("unchecked")
            Collection<MetricCacheKey> cacheKeys = (Collection<MetricCacheKey>) keys;
            return metricsStorage.newReader().getCardinalities(cacheKeys);
        }
    }
}
