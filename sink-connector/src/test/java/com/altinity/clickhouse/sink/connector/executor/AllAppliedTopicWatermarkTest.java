package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.06 section 3.5: a topic whose whole retained list is already marked
 * {@code appliedToClickHouse} short-circuits {@code processRecordsByTopic} to a
 * successful result without grouping. The durable-offset watermark must still
 * advance over those records, exactly as the grouping path folds the offset of
 * an already-applied record into {@code partitionToOffsetMap}. No ClickHouse
 * server: every connection is null and the short-circuit never touches one.
 */
public class AllAppliedTopicWatermarkTest {

    private static final String TOPIC = "srv.db.t1";

    private static ClickHouseStruct applied(int partition, long offset) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic(TOPIC);
        s.setKafkaPartition(partition);
        s.setKafkaOffset(offset);
        s.setAppliedToClickHouse(true);
        return s;
    }

    @Test
    public void anAllAppliedTopicAdvancesTheDurableWatermark() throws Exception {
        Map<TopicPartition, Long> watermark = new ConcurrentHashMap<>();
        watermark.put(new TopicPartition(TOPIC, 0), 5L);
        ClickHouseBatchRunnable worker = new ClickHouseBatchRunnable(
                new LinkedBlockingQueue<>(),
                new ClickHouseSinkConnectorConfig(new HashMap<>()),
                new HashMap<>(),
                watermark) {
            @Override
            Connection openConnection(String jdbcUrl, String databaseName) {
                return null;
            }
        };

        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(applied(0, 7L));
        records.add(applied(0, 9L));
        records.add(applied(1, 3L));

        assertTrue(worker.processRecordsByTopic(TOPIC, records),
                "every record is already in ClickHouse: nothing to send, the topic succeeded");
        assertEquals(9L, watermark.get(new TopicPartition(TOPIC, 0)),
                "partition 0 must advance from 5 to the highest durable offset, 9");
        assertEquals(3L, watermark.get(new TopicPartition(TOPIC, 1)),
                "partition 1 must be recorded at its durable offset, 3");
    }

    @Test
    public void anAllAppliedTopicNeverMovesTheWatermarkBackwards() throws Exception {
        Map<TopicPartition, Long> watermark = new ConcurrentHashMap<>();
        watermark.put(new TopicPartition(TOPIC, 0), 50L);
        ClickHouseBatchRunnable worker = new ClickHouseBatchRunnable(
                new LinkedBlockingQueue<>(),
                new ClickHouseSinkConnectorConfig(new HashMap<>()),
                new HashMap<>(),
                watermark) {
            @Override
            Connection openConnection(String jdbcUrl, String databaseName) {
                return null;
            }
        };

        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(applied(0, 7L));

        assertTrue(worker.processRecordsByTopic(TOPIC, records));
        assertEquals(50L, watermark.get(new TopicPartition(TOPIC, 0)),
                "a lower offset must never lower the watermark");
    }
}
