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
package io.prestosql.utils;

import com.google.common.cache.CacheLoader;
import io.airlift.log.Logger;
import io.hetu.core.common.heuristicindex.IndexCacheKey;
import io.prestosql.heuristicindex.HeuristicIndexerManager;
import io.prestosql.heuristicindex.IndexCache;
import io.prestosql.heuristicindex.IndexCacheLoader;
import io.prestosql.heuristicindex.SplitFilter;
import io.prestosql.heuristicindex.SplitFilterFactory;
import io.prestosql.metadata.Split;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.heuristicindex.IndexClient;
import io.prestosql.spi.heuristicindex.IndexMetadata;
import io.prestosql.split.SplitSource;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.InListExpression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.LogicalBinaryExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static io.prestosql.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;

public class SplitUtils
{
    private static final Logger log = Logger.get(SplitUtils.class);
    private static final AtomicLong totalSplitsProcessed = new AtomicLong();
    private static final AtomicLong splitsFiltered = new AtomicLong();
    private static SplitFilterFactory splitFilterFactory;

    private SplitUtils()
    {
    }

    private static synchronized void initCache(IndexClient indexClient)
    {
        if (splitFilterFactory == null) {
            CacheLoader<IndexCacheKey, List<IndexMetadata>> cacheLoader = new IndexCacheLoader(indexClient);
            IndexCache indexCache = new IndexCache(cacheLoader);
            splitFilterFactory = new SplitFilterFactory(indexCache);
        }
    }

    public static List<Split> getFilteredSplit(Optional<Expression> expression, Optional<String> tableName, Map<Symbol, ColumnHandle> assignments,
            SplitSource.SplitBatch nextSplits, HeuristicIndexerManager heuristicIndexerManager)
    {
        if (!expression.isPresent() || !tableName.isPresent()) {
            return nextSplits.getSplits();
        }

        if (splitFilterFactory == null) {
            initCache(heuristicIndexerManager.getIndexClient());
        }

        List<Split> allSplits = nextSplits.getSplits();
        String fullQualifiedTableName = tableName.get();
        long initialSplitsSize = allSplits.size();

        // apply filtering use heuristic indexes
        List<Split> splitsToReturn = splitFilter(expression.get(), allSplits, fullQualifiedTableName, assignments);

        if (log.isDebugEnabled()) {
            log.debug("totalSplitsProcessed: " + totalSplitsProcessed.addAndGet(initialSplitsSize));
            log.debug("splitsFiltered: " + splitsFiltered.addAndGet(initialSplitsSize - splitsToReturn.size()));
        }

        return splitsToReturn;
    }

    private static List<Split> splitFilter(Expression expression, List<Split> inputSplits, String fullQualifiedTableName, Map<Symbol, ColumnHandle> assignments)
    {
        List<Split> splitsToReturn = new ArrayList<>();
        splitsToReturn.addAll(inputSplits);
        if (expression instanceof ComparisonExpression) {
            // get filter for each predicate
            // the SplitFilterFactory will return a SplitFilter that has the applicable indexes
            // based on the predicate, but no filtering has been performed yet
            Predicate predicate = PredicateExtractor.processComparisonExpression((ComparisonExpression) expression, fullQualifiedTableName, assignments);
            if (predicate != null) {
                SplitFilter splitFilter = splitFilterFactory.getFilter(predicate, inputSplits);
                // do filter on splits to remove invalid splits from filteredSplits
                splitsToReturn = splitFilter.filter(inputSplits, predicate.getValue());
            }

            return splitsToReturn;
        }

        if (expression instanceof BetweenPredicate) {
            BetweenPredicate betweenPredicate = (BetweenPredicate) expression;
            ComparisonExpression left = new ComparisonExpression(GREATER_THAN_OR_EQUAL, betweenPredicate.getValue(), betweenPredicate.getMin());
            ComparisonExpression right = new ComparisonExpression(LESS_THAN_OR_EQUAL, betweenPredicate.getValue(), betweenPredicate.getMax());

            List<Split> splitsRemain = splitFilter(left, inputSplits, fullQualifiedTableName, assignments);
            splitsToReturn = splitFilter(right, splitsRemain, fullQualifiedTableName, assignments);

            return splitsToReturn;
        }

        if (expression instanceof LogicalBinaryExpression) {
            LogicalBinaryExpression lbExpression = (LogicalBinaryExpression) expression;
            LogicalBinaryExpression.Operator operator = lbExpression.getOperator();

            // select * from hive.tpch.nation where nationKey = 24 and name = 'CANADA';
            // if is an AND operator, the splits are first filtered using the index on nationKey,
            // the remaining splits are then filtered using the index on name column
            if ((operator == LogicalBinaryExpression.Operator.AND)) {
                List<Split> splitsRemain = splitFilter(lbExpression.getLeft(), inputSplits, fullQualifiedTableName, assignments);
                splitsToReturn = splitFilter(lbExpression.getRight(), splitsRemain, fullQualifiedTableName, assignments);
            }
            // select * from hive.tpch.nation where nationKey = 24 or name = 'CANADA';
            // if is an OR operator, apply filtering on nationKey, the splits that match are to be returned,
            // for splits that didn’t match the nationKey filter, the name column filter is applied, if any splits match, they will also be returned.
            else if ((operator == LogicalBinaryExpression.Operator.OR)) {
                List<Split> splitsNotMatched = new ArrayList<>();
                splitsNotMatched.addAll(inputSplits);
                splitsToReturn = splitFilter(lbExpression.getLeft(), inputSplits, fullQualifiedTableName, assignments);

                splitsNotMatched.removeAll(splitsToReturn);
                List<Split> splitsMatched = splitFilter(lbExpression.getRight(), splitsNotMatched, fullQualifiedTableName, assignments);

                splitsToReturn.addAll(splitsMatched);
            }
            else {
                throw new IllegalArgumentException("Unsupported logical expression type: " + operator);
            }

            return splitsToReturn;
        }

        if (expression instanceof InPredicate) {
            Expression valueList = ((InPredicate) expression).getValueList();
            if (valueList instanceof InListExpression) {
                InListExpression inListExpression = (InListExpression) valueList;
                List<Split> splitsNotMatched = new ArrayList<>();
                List<Split> splitsPrepareToReturn = new ArrayList<>();

                splitsNotMatched.addAll(inputSplits);

                for (Expression expr : inListExpression.getValues()) {
                    ComparisonExpression comparisonExpression = new ComparisonExpression(
                            ComparisonExpression.Operator.EQUAL, ((InPredicate) expression).getValue(), expr);

                    List<Split> splitsMatched = splitFilter(comparisonExpression, splitsNotMatched, fullQualifiedTableName, assignments);
                    splitsNotMatched.removeAll(splitsMatched);
                    splitsPrepareToReturn.addAll(splitsMatched);
                }

                if (inListExpression != null) {
                    splitsToReturn = splitsPrepareToReturn;
                }

                return splitsToReturn;
            }
        }

        return splitsToReturn;
    }

    public static String getSplitKey(Split split)
    {
        return split.getCatalogName() + ":" + split.getConnectorSplit().getFilePath();
    }
}
