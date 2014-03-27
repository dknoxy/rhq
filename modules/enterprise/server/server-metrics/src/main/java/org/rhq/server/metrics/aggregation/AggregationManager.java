package org.rhq.server.metrics.aggregation;

import static java.util.Arrays.asList;
import static org.rhq.server.metrics.domain.AggregateType.AVG;
import static org.rhq.server.metrics.domain.AggregateType.MAX;
import static org.rhq.server.metrics.domain.AggregateType.MIN;
import static org.rhq.server.metrics.domain.MetricsTable.ONE_HOUR;
import static org.rhq.server.metrics.domain.MetricsTable.SIX_HOUR;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;

import org.rhq.server.metrics.AbortedException;
import org.rhq.server.metrics.ArithmeticMeanCalculator;
import org.rhq.server.metrics.DateTimeService;
import org.rhq.server.metrics.MetricsConfiguration;
import org.rhq.server.metrics.MetricsDAO;
import org.rhq.server.metrics.StorageResultSetFuture;
import org.rhq.server.metrics.domain.AggregateNumericMetric;

/**
 * This class is the driver for metrics aggregation.
 *
 * @author John Sanda
 */
public class AggregationManager {

    public static final int INDEX_PARTITION = 0;
    private static final Comparator<AggregateNumericMetric> AGGREGATE_COMPARATOR = new Comparator<AggregateNumericMetric>() {
        @Override
        public int compare(AggregateNumericMetric left, AggregateNumericMetric right) {
            return (left.getScheduleId() < right.getScheduleId()) ? -1 : ((left.getScheduleId() == right.getScheduleId()) ? 0 : 1);
        }
    };

    private final Log log = LogFactory.getLog(AggregationManager.class);

    private MetricsDAO dao;

    private MetricsConfiguration configuration;

    private DateTimeService dtService;

    private DateTime startTime;

    private ListeningExecutorService aggregationTasks;

    private Set<AggregateNumericMetric> oneHourData;

    private int cacheBatchSize;

    private Semaphore permits;

    public AggregationManager(ListeningExecutorService aggregationTasks, MetricsDAO dao,
        MetricsConfiguration configuration,
        DateTimeService dtService, DateTime startTime, int batchSize, int parallelism, int minScheduleId,
        int maxScheduleId, int cacheBatchSize) {
        this.dao = dao;
        this.configuration = configuration;
        this.dtService = dtService;
        this.startTime = startTime;
        oneHourData = new ConcurrentSkipListSet<AggregateNumericMetric>(AGGREGATE_COMPARATOR);
        this.cacheBatchSize = cacheBatchSize;
        permits = new Semaphore(batchSize * parallelism);
        this.aggregationTasks = aggregationTasks;
    }

    private boolean is6HourTimeSliceFinished() {
        return dtService.is6HourTimeSliceFinished(startTime);
    }

    private boolean is24HourTimeSliceFinished() {
        return dtService.is24HourTimeSliceFinished(startTime);
    }

    private DateTime get6HourTimeSlice() {
        return dtService.get6HourTimeSlice(startTime);
    }

    private DateTime get24HourTimeSlice() {
        return dtService.get24HourTimeSlice(startTime);
    }

    public Set<AggregateNumericMetric> run() {
        log.info("Starting aggregation for time slice " + startTime);
        Stopwatch stopwatch = new Stopwatch().start();
        int numRaw = 0;
        int num1Hour = 0;
        int num6Hour = 0;
        try {
            createPastDataAggregator().execute();
            numRaw = createRawAggregator().execute();
            if (is6HourTimeSliceFinished()) {
                num1Hour = create1HourAggregator().execute();
            }
            if (is24HourTimeSliceFinished()) {
                num6Hour = create6HourAggregator().execute();
            }

            return oneHourData;
        } catch (InterruptedException e) {
            log.info("There was an interrupt while waiting for aggregation to finish. Aggregation will be aborted.");
            return Collections.emptySet();
        }
        catch (AbortedException e) {
            log.warn("Aggregation has been aborted: " + e.getMessage());
            return Collections.emptySet();
        } finally {
            stopwatch.stop();
            log.info("Finished aggregation of {\"raw schedules\": " + numRaw + ", \"1 hour schedules\": " + num1Hour +
                ", \"6 hour schedules\": " + num6Hour + "} in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }
    }

    private PastDataAggregator createPastDataAggregator() {
        PastDataAggregator aggregator = new PastDataAggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setCurrentDay(get24HourTimeSlice());
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setStartingDay(get24HourTimeSlice().minusDays(1));
        aggregator.setStartTime(startTime);
        aggregator.setDateTimeService(dtService);

        return aggregator;
    }

    private Aggregator createRawAggregator() {
        ComputeMetric compute1HourMetric = new ComputeMetric() {
            @Override
            public List<StorageResultSetFuture> execute(int startScheduleId, int scheduleId, Double min, Double max,
                ArithmeticMeanCalculator mean) {
                oneHourData.add(new AggregateNumericMetric(scheduleId, mean.getArithmeticMean(), min, max,
                    startTime.getMillis()));
                return asList(
                    dao.insertOneHourDataAsync(scheduleId, startTime.getMillis(), AVG, mean.getArithmeticMean()),
                    dao.insertOneHourDataAsync(scheduleId, startTime.getMillis(), MAX, max),
                    dao.insertOneHourDataAsync(scheduleId, startTime.getMillis(), MIN, min),
                    dao.updateMetricsCache(ONE_HOUR, get6HourTimeSlice().getMillis(), startScheduleId,
                        scheduleId, startTime.getMillis(), map(min, max,  mean.getArithmeticMean())),
                    dao.updateCacheIndex(ONE_HOUR, get24HourTimeSlice().getMillis(), INDEX_PARTITION,
                        get6HourTimeSlice().getMillis(), startScheduleId)
                );
            }
        };

        Aggregator aggregator = new Aggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setAggregationType(AggregationType.RAW);
        aggregator.setCacheBatchSize(cacheBatchSize);
        aggregator.setComputeMetric(compute1HourMetric);
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setStartTime(startTime);
        aggregator.setCurrentDay(get24HourTimeSlice());
        aggregator.setStartingDay(get24HourTimeSlice().minusDays(1));

        return aggregator;
    }

    private Aggregator create1HourAggregator() {
        ComputeMetric compute6HourMetric = new ComputeMetric() {
            @Override
            public List<StorageResultSetFuture> execute(int startScheduleId, int scheduleId, Double min,
                Double max, ArithmeticMeanCalculator mean) {
                return asList(
                    dao.insertSixHourDataAsync(scheduleId, get6HourTimeSlice().getMillis(), AVG,
                        mean.getArithmeticMean()),
                    dao.insertSixHourDataAsync(scheduleId, get6HourTimeSlice().getMillis(), MAX, max),
                    dao.insertSixHourDataAsync(scheduleId, get6HourTimeSlice().getMillis(), MIN, min),
                    dao.updateMetricsCache(SIX_HOUR, get24HourTimeSlice().getMillis(),
                        startScheduleId, scheduleId, get6HourTimeSlice().getMillis(), map(min, max,
                        mean.getArithmeticMean())),
                    dao.updateCacheIndex(SIX_HOUR, get24HourTimeSlice().getMillis(), INDEX_PARTITION,
                        get24HourTimeSlice().getMillis(), startScheduleId)
                );
            }
        };

        Aggregator aggregator = new Aggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setAggregationType(AggregationType.ONE_HOUR);
        aggregator.setCacheBatchSize(cacheBatchSize);
        aggregator.setComputeMetric(compute6HourMetric);
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setStartTime(get6HourTimeSlice());
        aggregator.setCurrentDay(get24HourTimeSlice());
        aggregator.setStartingDay(get24HourTimeSlice().minusDays(1));

        return aggregator;
    }

    private Aggregator create6HourAggregator() {
        ComputeMetric compute24HourMetric = new ComputeMetric() {
            @Override
            public List<StorageResultSetFuture> execute(int startScheduleId, int scheduleId, Double min,
                Double max, ArithmeticMeanCalculator mean) {
                return asList(
                    dao.insertTwentyFourHourDataAsync(scheduleId, get24HourTimeSlice().getMillis(),
                        AVG, mean.getArithmeticMean()),
                    dao.insertTwentyFourHourDataAsync(scheduleId, get24HourTimeSlice().getMillis(),
                        MAX, max),
                    dao.insertTwentyFourHourDataAsync(scheduleId, get24HourTimeSlice().getMillis(),
                        MIN, min)
                );
            }
        };

        Aggregator aggregator = new Aggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setAggregationType(AggregationType.SIX_HOUR);
        aggregator.setCacheBatchSize(cacheBatchSize);
        aggregator.setComputeMetric(compute24HourMetric);
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setStartTime(get24HourTimeSlice());
        aggregator.setCurrentDay(get24HourTimeSlice());
        aggregator.setStartingDay(get24HourTimeSlice().minusDays(1));

        return aggregator;
    }

    private Map<Integer, Double> map(Double min, Double max, Double avg) {
        Map<Integer, Double> values = new TreeMap<Integer, Double>();
        values.put(MIN.ordinal(), min);
        values.put(MAX.ordinal(), max);
        values.put(AVG.ordinal(), avg);

        return values;
    }

}
