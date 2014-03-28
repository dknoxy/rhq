package org.rhq.server.metrics;

import static java.util.Arrays.asList;
import static org.rhq.test.AssertUtils.assertCollectionEqualsNoOrder;
import static org.testng.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import com.datastax.driver.core.ResultSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;

import org.joda.time.DateTime;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import org.rhq.core.domain.measurement.MeasurementDataNumeric;
import org.rhq.server.metrics.aggregation.AggregationManager;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.AggregateType;
import org.rhq.server.metrics.domain.RawNumericMetric;

/**
 * @author John Sanda
 */
public class AggregationTests extends MetricsTest {

    private Aggregates schedule1 = new Aggregates();
    private Aggregates schedule2 = new Aggregates();
    private Aggregates schedule3 = new Aggregates();
    private Aggregates schedule4 = new Aggregates();
    private Aggregates schedule5 = new Aggregates();

    private ListeningExecutorService aggregationTasks;

    private DateTime currentHour;

    private final int MIN_SCHEDULE_ID = 100;

    private final int MAX_SCHEDULE_ID = 200;

    private final int BATCH_SIZE = 10;

    private final int INDEX_PARTITION = 0;

    private MetricsServerStub metricsServer;

    private DateTimeServiceStub dateTimeService;

    private InMemoryMetricsDB testdb;

    @BeforeClass
    public void setUp() throws Exception {
        purgeDB();

        schedule1.id = 100;
        schedule2.id = 101;
        schedule3.id = 131;
        schedule4.id = 104;
        schedule5.id = 105;

        testdb = new InMemoryMetricsDB();
        dateTimeService = new DateTimeServiceStub();
        dateTimeService.setConfiguration(new MetricsConfiguration());
        metricsServer = new MetricsServerStub();
        metricsServer.setConfiguration(new MetricsConfiguration());
        metricsServer.setDateTimeService(dateTimeService);
        metricsServer.setDAO(dao);
        metricsServer.setCacheBatchSize(PARTITION_SIZE);
        metricsServer.init();

        aggregationTasks = metricsServer.getAggregationWorkers();
    }

    @Test
    public void insertRawDataDuringHour16() throws Exception {

        dateTimeService.setNow(hour(16).plusMinutes(41));
        insertRawData(
            newRawData(hour(16).plusMinutes(20), schedule1.id, 3.0),
            newRawData(hour(16).plusMinutes(40), schedule1.id, 5.0),
            newRawData(hour(16).plusMinutes(15), schedule2.id, 0.0032),
            newRawData(hour(16).plusMinutes(30), schedule2.id, 0.104),
            newRawData(hour(16).plusMinutes(7), schedule3.id, 3.14)
        );
    }

    @Test(dependsOnMethods = "insertRawDataDuringHour16")
    public void runAggregationForHour16() throws Exception {
        currentHour = hour(17);
        dateTimeService.setNow(hour(17).plusMinutes(1));
        testdb.aggregateRawData(hour(16), hour(17));
        AggregationManagerTestStub aggregator = new AggregationManagerTestStub(hour(16));
        Set<AggregateNumericMetric> oneHourData = aggregator.run();

        assertCollectionEqualsNoOrder(testdb.get1HourData(hour(16)), oneHourData,
            "The returned one hour aggregates are wrong");
        // verify values in the db
        assert1HourDataEquals(schedule1.id, testdb.get1HourData(hour(16), schedule1.id));
        assert1HourDataEquals(schedule2.id, testdb.get1HourData(hour(16), schedule2.id));
        assert1HourDataEquals(schedule3.id, testdb.get1HourData(hour(16), schedule3.id));

        assert6HourDataEmpty(schedule1.id, schedule2.id, schedule3.id);
        assert24HourDataEmpty(schedule1.id, schedule2.id, schedule3.id);

        assertRawCacheEmpty(hour(16), startScheduleId(schedule1.id), startScheduleId(schedule3.id));
        assertRawCacheIndexEmpty(hour(16), INDEX_PARTITION, hour(16));

        assert1HourCacheEquals(hour(12), startScheduleId(schedule1.id),
            asList(testdb.get1HourData(hour(16), schedule1.id), testdb.get1HourData(hour(16), schedule2.id)));
        assert1HourCacheIndexEquals(today(), INDEX_PARTITION, hour(12), asList(
            new1HourCacheIndexEntry(today(), startScheduleId(schedule1.id), hour(12)),
            new1HourCacheIndexEntry(today(), startScheduleId(schedule3.id), hour(12))
        ));

        assert6HourCacheEmpty(hour(0), startScheduleId(schedule1.id));
        assert6HourCacheIndexEmpty(hour(0), INDEX_PARTITION, hour(0));
    }

    @Test(dependsOnMethods = "runAggregationForHour16")
    public void insertRawDataDuringHour17() throws Exception {
        dateTimeService.setNow(hour(17).plusMinutes(50));
        insertRawData(
            newRawData(hour(17).plusMinutes(20), schedule1.id, 11.0),
            newRawData(hour(17).plusMinutes(40), schedule1.id, 16.0),
            newRawData(hour(17).plusMinutes(30), schedule2.id, 0.092),
            newRawData(hour(17).plusMinutes(45), schedule2.id, 0.0733)
        );
    }

    @Test(dependsOnMethods = "insertRawDataDuringHour17")
    public void runAggregationForHour17() throws Exception {
        dateTimeService.setNow(hour(18).plusMinutes(1));
        currentHour = hour(18);
        testdb.aggregateRawData(hour(17), hour(18));
        testdb.aggregate1HourData(hour(12), hour(18));
        AggregationManagerTestStub aggregator = new AggregationManagerTestStub(hour(17));

        Set<AggregateNumericMetric> oneHourData = aggregator.run();

        assertCollectionEqualsNoOrder(testdb.get1HourData(hour(17)), oneHourData,
            "The returned one hour data is wrong");
        // verify values in the db
        assert1HourDataEquals(schedule1.id, testdb.get1HourData(schedule1.id));
        assert1HourDataEquals(schedule2.id, testdb.get1HourData(schedule2.id));
        assert1HourDataEquals(schedule3.id, testdb.get1HourData(schedule3.id));

        assert6HourDataEquals(schedule1.id, testdb.get6HourData(hour(12), schedule1.id));
        assert6HourDataEquals(schedule2.id, testdb.get6HourData(hour(12), schedule2.id));
        assert6HourDataEquals(schedule3.id, testdb.get6HourData(hour(12), schedule3.id));

        assert24HourDataEmpty(schedule1.id, schedule2.id, schedule3.id);

        assertRawCacheEmpty(hour(17), startScheduleId(schedule1.id), startScheduleId(schedule3.id));
        assertRawCacheIndexEmpty(hour(17), INDEX_PARTITION, hour(17));

        assert1HourCacheEmpty(hour(12), startScheduleId(schedule1.id), startScheduleId(schedule3.id));
        assert1HourCacheIndexEmpty(hour(0), INDEX_PARTITION, hour(12));

        assert6HourCacheEquals(hour(0), startScheduleId(schedule1.id), testdb.get6HourData(scheduleIds(schedule1.id)));
        assert6HourCacheEquals(hour(0), startScheduleId(schedule3.id), testdb.get6HourData(scheduleIds(schedule3.id)));
        assert6HourCacheIndexEquals(hour(0), INDEX_PARTITION, hour(0), asList(
            new6HourCacheIndexEntry(today(), startScheduleId(schedule1.id), hour(0)),
            new6HourCacheIndexEntry(today(), startScheduleId(schedule3.id), hour(0))
        ));
    }


    @Test(dependsOnMethods = "runAggregationForHour17")
    public void insertRawDataDuringHour18() throws Exception {
        dateTimeService.setNow(hour(18).plusMinutes(50));
        insertRawData(
            newRawData(hour(18).plusMinutes(20), schedule1.id, 22.0),
            newRawData(hour(18).plusMinutes(40), schedule1.id, 26.0),
            newRawData(hour(18).plusMinutes(15), schedule2.id, 0.205),
            newRawData(hour(18).plusMinutes(15), schedule3.id, 2.42)
        );
    }

    @Test(dependsOnMethods = "insertRawDataDuringHour18")
    public void runAggregationForHour18() throws Exception {
        currentHour = hour(19);
        dateTimeService.setNow(hour(19).plusMinutes(1));
        testdb.aggregateRawData(hour(18), hour(19));
        AggregationManagerTestStub aggregator = new AggregationManagerTestStub(hour(18));
        Set<AggregateNumericMetric> oneHourData = aggregator.run();

        assertCollectionEqualsNoOrder(testdb.get1HourData(hour(18)), oneHourData, "The returned 1 hour data is wrong");
        // verify values in db
        assert1HourDataEquals(schedule1.id, testdb.get1HourData(schedule1.id));
        assert1HourDataEquals(schedule2.id, testdb.get1HourData(schedule2.id));
        assert1HourDataEquals(schedule3.id, testdb.get1HourData(schedule3.id));

        assert6HourDataEquals(schedule1.id, testdb.get6HourData(schedule1.id));
        assert6HourDataEquals(schedule2.id, testdb.get6HourData(schedule2.id));
        assert6HourDataEquals(schedule3.id, testdb.get6HourData(schedule3.id));

        assert24HourDataEmpty(schedule1.id, schedule2.id, schedule3.id);

        assertRawCacheEmpty(hour(18), startScheduleId(schedule1.id), startScheduleId(schedule3.id));
        assertRawCacheIndexEmpty(hour(18), INDEX_PARTITION, hour(18));

        assert1HourCacheEmpty(hour(12), startScheduleId(schedule1.id));
        assert1HourCacheEmpty(hour(12), startScheduleId(schedule3.id));
        assert1HourCacheIndexEmpty(hour(0), INDEX_PARTITION, hour(12));

        assert1HourCacheEquals(hour(18), startScheduleId(schedule1.id), asList(
            testdb.get1HourData(hour(18), schedule1.id),
            testdb.get1HourData(hour(18), schedule2.id)));
        assert1HourCacheEquals(hour(18), startScheduleId(schedule3.id),
            asList(testdb.get1HourData(hour(18), schedule3.id)));
        assert1HourCacheIndexEquals(today(), INDEX_PARTITION, hour(18), asList(
            new1HourCacheIndexEntry(today(), startScheduleId(schedule1.id), hour(18)),
            new1HourCacheIndexEntry(today(), startScheduleId(schedule3.id), hour(18))
        ));

        assert6HourCacheEquals(hour(0), startScheduleId(schedule1.id), testdb.get6HourData(schedule1.id, schedule2.id));
        assert6HourCacheEquals(hour(0), startScheduleId(schedule3.id), testdb.get6HourData(schedule3.id));
        assert6HourCacheIndexEquals(today(), INDEX_PARTITION, hour(0), asList(
            new6HourCacheIndexEntry(today(), startScheduleId(schedule1.id), hour(0)),
            new6HourCacheIndexEntry(today(), startScheduleId(schedule3.id), hour(0))
        ));
    }

    @Test(dependsOnMethods = "runAggregationForHour18")
    public void insertRawDataDuringHour23() throws Exception {
        dateTimeService.setNow(hour(23).plusMinutes(50));
        insertRawData(
            newRawData(hour(23).plusMinutes(25), schedule1.id, 34.0),
            newRawData(hour(23).plusMinutes(30), schedule2.id, 0.322)
        );
    }

    @Test(dependsOnMethods = "insertRawDataDuringHour23")
    public void runAggregationForHour24() throws Exception {
        currentHour = hour(24);
        dateTimeService.setNow(hour(24).plusMinutes(1));
        testdb.aggregateRawData(hour(23), hour(24));
        testdb.aggregate1HourData(hour(18), hour(24));
        testdb.aggregate6HourData(hour(0), hour(24));
        AggregationManagerTestStub aggregator = new AggregationManagerTestStub(hour(23));
        Set<AggregateNumericMetric> oneHourData = aggregator.run();

        assertCollectionEqualsNoOrder(testdb.get1HourData(hour(23)), oneHourData, "The returned 1 hour data is wrong");
        // verify values in db
        assert1HourDataEquals(schedule1.id, testdb.get1HourData(schedule1.id));
        assert1HourDataEquals(schedule2.id, testdb.get1HourData(schedule2.id));
        assert1HourDataEquals(schedule3.id, testdb.get1HourData(schedule3.id));

        assert6HourDataEquals(schedule1.id, testdb.get6HourData(schedule1.id));
        assert6HourDataEquals(schedule2.id, testdb.get6HourData(schedule2.id));
        assert6HourDataEquals(schedule3.id, testdb.get6HourData(schedule3.id));

        assert24HourDataEquals(schedule1.id, testdb.get24HourData(schedule1.id));
        assert24HourDataEquals(schedule2.id, testdb.get24HourData(schedule2.id));
        assert24HourDataEquals(schedule3.id, testdb.get24HourData(schedule3.id));

        assertRawCacheEmpty(hour(18), startScheduleId(schedule1.id), startScheduleId(schedule3.id));
        assertRawCacheIndexEmpty(hour(18), INDEX_PARTITION, hour(0));

        assert1HourCacheEmpty(hour(18), startScheduleId(schedule1.id));
        assert1HourCacheEmpty(hour(18), startScheduleId(schedule3.id));
        assert1HourCacheIndexEmpty(hour(0), INDEX_PARTITION, hour(18));

        assert6HourCacheEmpty(hour(18), startScheduleId(schedule1.id));
        assert6HourCacheEmpty(hour(18), startScheduleId(schedule3.id));
        assert6HourCacheIndexEmpty(hour(0), INDEX_PARTITION,  hour(18));
    }


    @Test(dependsOnMethods = "runAggregationForHour24")
    public void prepareForLateDataAggregationInSame6HourTimeSlice() throws Exception {
        purgeDB();
        currentHour = hour(3);
        testdb = new InMemoryMetricsDB();
        dateTimeService.setNow(hour(3).plusMinutes(55));
        insertRawData(
            newRawData(hour(3).plusMinutes(20), schedule1.id, 20),
            newRawData(hour(3).plusMinutes(30), schedule1.id, 22),
            newRawData(hour(3).plusMinutes(15), schedule2.id, 75),
            newRawData(hour(3).plusMinutes(20), schedule2.id, 100)
        );
        testdb.aggregateRawData(hour(3), hour(4));
        new AggregationManagerTestStub(hour(3)).run();
    }

    @Test(dependsOnMethods = "prepareForLateDataAggregationInSame6HourTimeSlice")
    public void aggregateLateDataInSame6HourTimeSlice() throws Exception {
        currentHour = hour(4);
        dateTimeService.setNow(hour(4).plusMinutes(55));
        insertRawData(
            newRawData(hour(3).plusMinutes(35), schedule1.id, 20),
            newRawData(hour(3).plusMinutes(40), schedule1.id, 30),
            newRawData(hour(4).plusMinutes(40), schedule1.id, 27),
            newRawData(hour(4).plusMinutes(40), schedule2.id, 321),
            newRawData(hour(4).plusMinutes(45), schedule2.id, 333)
        );

        testdb.aggregateRawData(hour(3), hour(4));
        testdb.aggregateRawData(hour(4), hour(5));

        AggregationManagerTestStub aggregator = new AggregationManagerTestStub(hour(4));
        Set<AggregateNumericMetric> oneHourData = aggregator.run();

        assertCollectionEqualsNoOrder(testdb.get1HourData(hour(4)), oneHourData, "The returned 1 hour data is wrong");
        // verify values in db
        assert1HourDataEquals(schedule1.id, testdb.get1HourData(schedule1.id));
        assert1HourDataEquals(schedule2.id, testdb.get1HourData(schedule2.id));

        assertRawCacheEmpty(hour(4), startScheduleId(schedule1.id));
        assertRawCacheIndexEmpty(hour(3), INDEX_PARTITION, hour(3));
        assertRawCacheIndexEmpty(hour(4), INDEX_PARTITION, hour(4));

        assert1HourCacheIndexEquals(today(), INDEX_PARTITION, hour(0), asList(
            new1HourCacheIndexEntry(today(), startScheduleId(schedule1.id), hour(0))
        ));
        assert1HourCacheEquals(hour(0), startScheduleId(schedule1.id), asList(
            testdb.get1HourData(hour(3), schedule1.id),
            testdb.get1HourData(hour(4), schedule1.id),
            testdb.get1HourData(hour(3), schedule2.id),
            testdb.get1HourData(hour(4), schedule2.id)
        ));
    }

//    @Test(dependsOnMethods = "runAggregationForHour24")
    public void resetDBForFailureScenarios() throws Exception {
        purgeDB();
    }

//    @Test(dependsOnMethods = "resetDBForFailureScenarios")
    public void doNotDeleteCachePartitionOnBatchFailure() throws Exception {
        currentHour = hour(5);
        DateTime time = hour(4).plusMinutes(20);
        insertRawData(hour(4), new MeasurementDataNumeric(time.getMillis(), schedule1.id, 3.0))
            .await("Failed to insert raw data");

        TestDAO testDAO = new TestDAO() {
            @Override
            public StorageResultSetFuture insertOneHourDataAsync(int scheduleId, long timestamp, AggregateType type,
                double value) {
                StorageResultSetFuture future = super.insertOneHourDataAsync(scheduleId, timestamp, type, value);
                future.setException(new Exception("An unexpected error occurred while inserting 1 hour data"));
                return future;
            }
        };

        AggregationManagerTestStub aggregationManager = new AggregationManagerTestStub(hour(4), testDAO);
        aggregationManager.run();

        assertRawCacheEquals(hour(4), startScheduleId(schedule1.id), asList(new RawNumericMetric(schedule1.id,
            time.getMillis(), 3.0)));
    }

    private void insertRawData(MeasurementDataNumeric... data) throws Exception {
        Set<MeasurementDataNumeric> dataSet = Sets.newHashSet(data);
        for (MeasurementDataNumeric datum : data) {
            testdb.putRawData(new RawNumericMetric(datum.getScheduleId(), datum.getTimestamp(), datum.getValue()));
        }
        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.length);
        metricsServer.addNumericData(dataSet, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");
    }

    private MeasurementDataNumeric newRawData(DateTime timestamp, int scheduleId, double value) {
        return new MeasurementDataNumeric(timestamp.getMillis(), scheduleId, value);
    }

    private int[] scheduleIds(int scheduleId) {
        int[] ids = new int[BATCH_SIZE];
        int startId = startScheduleId(scheduleId);
        for (int i = 0; i < BATCH_SIZE; ++i) {
            ids[i] = startId + i;
        }
        return ids;
    }

    private class AggregationManagerTestStub extends AggregationManager {

        public AggregationManagerTestStub(DateTime startTime) {
            super(aggregationTasks, dao, dateTimeService, startTime, BATCH_SIZE, 4, PARTITION_SIZE);
        }

        public AggregationManagerTestStub(DateTime startTime, MetricsDAO dao) {
            super(aggregationTasks, dao, dateTimeService, startTime, BATCH_SIZE, 4, PARTITION_SIZE);
        }

    }

    private class Aggregates {
        int id;  // schedule id
        Map<DateTime, AggregateNumericMetric> oneHourData = new HashMap<DateTime, AggregateNumericMetric>();
        Map<DateTime, AggregateNumericMetric> sixHourData = new HashMap<DateTime, AggregateNumericMetric>();
        Map<DateTime, AggregateNumericMetric> twentyFourHourData = new HashMap<DateTime, AggregateNumericMetric>();
    }

    private class TestDAO extends MetricsDAO {

        public TestDAO() {
            super(storageSession, configuration);
        }
    }

    private class FailedStorageResultSetFuture extends StorageResultSetFuture implements ListenableFuture<ResultSet> {

        private SettableFuture future;

        private Throwable t;

        public FailedStorageResultSetFuture(Throwable t) {
            super(null, null);
            future = SettableFuture.create();
            this.t = t;
            assertTrue(future.setException(t), "Failed to set exception for future");
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            future.addListener(listener, executor);
        }

        @Override
        public ResultSet get() {
            throw new AssertionError();
        }
    }
}
