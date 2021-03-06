/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.topn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.druid.java.util.common.DateTimes;
import io.druid.java.util.common.Intervals;
import io.druid.java.util.common.granularity.Granularities;
import io.druid.java.util.common.guava.Sequence;
import io.druid.query.CacheStrategy;
import io.druid.query.QueryPlus;
import io.druid.query.QueryRunner;
import io.druid.query.QueryRunnerFactory;
import io.druid.query.QueryRunnerTestHelper;
import io.druid.query.Result;
import io.druid.query.TableDataSource;
import io.druid.query.TestQueryRunners;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.aggregation.PostAggregator;
import io.druid.query.aggregation.post.ArithmeticPostAggregator;
import io.druid.query.aggregation.post.ConstantPostAggregator;
import io.druid.query.aggregation.post.FieldAccessPostAggregator;
import io.druid.query.dimension.DefaultDimensionSpec;
import io.druid.query.spec.MultipleIntervalSegmentSpec;
import io.druid.segment.IncrementalIndexSegment;
import io.druid.segment.TestHelper;
import io.druid.segment.TestIndex;
import io.druid.segment.VirtualColumns;
import io.druid.segment.column.ValueType;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

public class TopNQueryQueryToolChestTest
{

  private static final String segmentId = "testSegment";

  @Test
  public void testCacheStrategy() throws Exception
  {
    doTestCacheStrategy(ValueType.STRING, "val1");
    doTestCacheStrategy(ValueType.FLOAT, 2.1f);
    doTestCacheStrategy(ValueType.DOUBLE, 2.1d);
    doTestCacheStrategy(ValueType.LONG, 2L);
  }

  @Test
  public void testCacheStrategyWithFloatDimension() throws Exception
  {
  }

  @Test
  public void testComputeCacheKeyWithDifferentPostAgg() throws Exception
  {
    final TopNQuery query1 = new TopNQuery(
        new TableDataSource("dummy"),
        VirtualColumns.EMPTY,
        new DefaultDimensionSpec("test", "test"),
        new NumericTopNMetricSpec("post"),
        3,
        new MultipleIntervalSegmentSpec(ImmutableList.of(Intervals.of("2015-01-01/2015-01-02"))),
        null,
        Granularities.ALL,
        ImmutableList.<AggregatorFactory>of(new CountAggregatorFactory("metric1")),
        ImmutableList.<PostAggregator>of(new ConstantPostAggregator("post", 10)),
        null
    );

    final TopNQuery query2 = new TopNQuery(
        new TableDataSource("dummy"),
        VirtualColumns.EMPTY,
        new DefaultDimensionSpec("test", "test"),
        new NumericTopNMetricSpec("post"),
        3,
        new MultipleIntervalSegmentSpec(ImmutableList.of(Intervals.of("2015-01-01/2015-01-02"))),
        null,
        Granularities.ALL,
        ImmutableList.<AggregatorFactory>of(new CountAggregatorFactory("metric1")),
        ImmutableList.<PostAggregator>of(
            new ArithmeticPostAggregator(
                "post",
                "+",
                ImmutableList.<PostAggregator>of(
                    new FieldAccessPostAggregator(
                        null,
                        "metric1"
                    ),
                    new FieldAccessPostAggregator(
                        null,
                        "metric1"
                    )
                )
            )
        ),
        null
    );

    final CacheStrategy<Result<TopNResultValue>, Object, TopNQuery> strategy1 = new TopNQueryQueryToolChest(
        null,
        null
    ).getCacheStrategy(query1);

    final CacheStrategy<Result<TopNResultValue>, Object, TopNQuery> strategy2 = new TopNQueryQueryToolChest(
        null,
        null
    ).getCacheStrategy(query2);

    Assert.assertFalse(Arrays.equals(strategy1.computeCacheKey(query1), strategy2.computeCacheKey(query2)));
  }

  @Test
  public void testMinTopNThreshold() throws Exception
  {
    TopNQueryConfig config = new TopNQueryConfig();
    final TopNQueryQueryToolChest chest = new TopNQueryQueryToolChest(
        config,
        QueryRunnerTestHelper.NoopIntervalChunkingQueryRunnerDecorator()
    );
    QueryRunnerFactory factory = new TopNQueryRunnerFactory(
        TestQueryRunners.getPool(),
        chest,
        QueryRunnerTestHelper.NOOP_QUERYWATCHER
    );
    QueryRunner<Result<TopNResultValue>> runner = QueryRunnerTestHelper.makeQueryRunner(
        factory,
        new IncrementalIndexSegment(TestIndex.getIncrementalTestIndex(), segmentId),
        null
    );

    Map<String, Object> context = Maps.newHashMap();
    context.put("minTopNThreshold", 500);

    TopNQueryBuilder builder = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.dataSource)
        .granularity(QueryRunnerTestHelper.allGran)
        .dimension(QueryRunnerTestHelper.placementishDimension)
        .metric(QueryRunnerTestHelper.indexMetric)
        .intervals(QueryRunnerTestHelper.fullOnInterval)
        .aggregators(QueryRunnerTestHelper.commonDoubleAggregators);

    TopNQuery query1 = builder.threshold(10).context(null).build();
    MockQueryRunner mockRunner = new MockQueryRunner(runner);
    new TopNQueryQueryToolChest.ThresholdAdjustingQueryRunner(mockRunner, config).run(
        QueryPlus.wrap(query1),
        ImmutableMap.<String, Object>of()
    );
    Assert.assertEquals(1000, mockRunner.query.getThreshold());

    TopNQuery query2 = builder.threshold(10).context(context).build();

    new TopNQueryQueryToolChest.ThresholdAdjustingQueryRunner(mockRunner, config)
        .run(QueryPlus.wrap(query2), ImmutableMap.<String, Object>of());
    Assert.assertEquals(500, mockRunner.query.getThreshold());

    TopNQuery query3 = builder.threshold(2000).context(context).build();
    new TopNQueryQueryToolChest.ThresholdAdjustingQueryRunner(mockRunner, config)
        .run(QueryPlus.wrap(query3), ImmutableMap.<String, Object>of());
    Assert.assertEquals(2000, mockRunner.query.getThreshold());
  }

  private void doTestCacheStrategy(final ValueType valueType, final Object dimValue) throws IOException
  {
    CacheStrategy<Result<TopNResultValue>, Object, TopNQuery> strategy =
        new TopNQueryQueryToolChest(null, null).getCacheStrategy(
            new TopNQuery(
                new TableDataSource("dummy"),
                VirtualColumns.EMPTY,
                new DefaultDimensionSpec("test", "test", valueType),
                new NumericTopNMetricSpec("metric1"),
                3,
                new MultipleIntervalSegmentSpec(ImmutableList.of(Intervals.of("2015-01-01/2015-01-02"))),
                null,
                Granularities.ALL,
                ImmutableList.<AggregatorFactory>of(new CountAggregatorFactory("metric1")),
                ImmutableList.<PostAggregator>of(new ConstantPostAggregator("post", 10)),
                null
            )
        );

    final Result<TopNResultValue> result1 = new Result<>(
        // test timestamps that result in integer size millis
        DateTimes.utc(123L),
        new TopNResultValue(
            Arrays.asList(
                ImmutableMap.<String, Object>of(
                    "test", dimValue,
                    "metric1", 2
                )
            )
        )
    );

    Object preparedValue = strategy.prepareForCache().apply(
        result1
    );

    ObjectMapper objectMapper = TestHelper.makeJsonMapper();
    Object fromCacheValue = objectMapper.readValue(
        objectMapper.writeValueAsBytes(preparedValue),
        strategy.getCacheObjectClazz()
    );

    Result<TopNResultValue> fromCacheResult = strategy.pullFromCache().apply(fromCacheValue);

    Assert.assertEquals(result1, fromCacheResult);

    final Result<TopNResultValue> result2 = new Result<>(
        // test timestamps that result in integer size millis
        DateTimes.utc(123L),
        new TopNResultValue(
            Arrays.asList(
                ImmutableMap.<String, Object>of(
                    "test", dimValue,
                    "metric1", 2
                )
            )
        )
    );

    Object preparedResultCacheValue = strategy.prepareForCache().apply(
        result2
    );

    Object fromResultCacheValue = objectMapper.readValue(
        objectMapper.writeValueAsBytes(preparedResultCacheValue),
        strategy.getCacheObjectClazz()
    );

    Result<TopNResultValue> fromResultCacheResult = strategy.pullFromCache().apply(fromResultCacheValue);
    Assert.assertEquals(result2, fromResultCacheResult);
  }

  static class MockQueryRunner implements QueryRunner<Result<TopNResultValue>>
  {
    private final QueryRunner<Result<TopNResultValue>> runner;
    TopNQuery query = null;

    MockQueryRunner(QueryRunner<Result<TopNResultValue>> runner)
    {
      this.runner = runner;
    }

    @Override
    public Sequence<Result<TopNResultValue>> run(
        QueryPlus<Result<TopNResultValue>> queryPlus,
        Map<String, Object> responseContext
    )
    {
      this.query = (TopNQuery) queryPlus.getQuery();
      return runner.run(queryPlus, responseContext);
    }
  }
}
