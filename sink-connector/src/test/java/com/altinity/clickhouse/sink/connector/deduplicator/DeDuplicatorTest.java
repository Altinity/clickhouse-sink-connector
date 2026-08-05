package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkTaskTest;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive tests for DeDuplicator — Phase 9 edge cases.
 * <p>
 * Validates:
 * - First record on a topic is always new
 * - Duplicate record (same key) is detected
 * - Different key on the same topic is recognized as new (Phase 1 fix)
 * - Different topic is independent
 * - OFF policy disables de-duplication entirely
 * - Null key falls back to value-based de-duplication
 * </p>
 */
public class DeDuplicatorTest {

    private static final String TOPIC = "products";
    private static final String KEY_FIELD = "productId";
    private static final String VALUE_FIELD = "amount";

    private DeDuplicator createDeDuplicator(String policy) {
        Map<String, String> properties = new HashMap<>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), policy);
        return new DeDuplicator(new ClickHouseSinkConnectorConfig(properties));
    }

    private SinkRecord createRecord(String topic, String key, String value) {
        return ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, KEY_FIELD, key, VALUE_FIELD, value,
                TimestampType.NO_TIMESTAMP_TYPE, System.currentTimeMillis());
    }

    @Nested
    @DisplayName("De-duplication with NEW policy")
    class NewPolicyTests {

        @Test
        @DisplayName("First record on a topic should always be new")
        public void testFirstRecordIsNew() {
            DeDuplicator dedupe = createDeDuplicator("new");
            Assert.assertTrue("First record should be new",
                    dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100")));
        }

        @Test
        @DisplayName("Duplicate record (same key) should be detected")
        public void testDuplicateDetected() {
            DeDuplicator dedupe = createDeDuplicator("new");
            dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100"));
            Assert.assertFalse("Same key should be detected as duplicate",
                    dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "200")));
        }

        @Test
        @DisplayName("Different key on same topic should be new (Phase 1 fix)")
        public void testDifferentKeyIsNew() {
            DeDuplicator dedupe = createDeDuplicator("new");
            dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100"));
            // This was the Phase 1 bug — different keys on the same topic
            // were incorrectly treated as duplicates
            Assert.assertTrue("Different key '22' should be new, not a duplicate",
                    dedupe.isNew(TOPIC, createRecord(TOPIC, "22", "200")));
        }

        @Test
        @DisplayName("Same key on different topic should be new")
        public void testDifferentTopicIsIndependent() {
            DeDuplicator dedupe = createDeDuplicator("new");
            dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100"));
            Assert.assertTrue("Same key on different topic should be new",
                    dedupe.isNew("employees", createRecord("employees", "1", "100")));
        }

        @Test
        @DisplayName("Multiple distinct keys should all be tracked")
        public void testMultipleDistinctKeys() {
            DeDuplicator dedupe = createDeDuplicator("new");
            Assert.assertTrue(dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100")));
            Assert.assertTrue(dedupe.isNew(TOPIC, createRecord(TOPIC, "2", "200")));
            Assert.assertTrue(dedupe.isNew(TOPIC, createRecord(TOPIC, "3", "300")));
            // Now check duplicates
            Assert.assertFalse(dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100")));
            Assert.assertFalse(dedupe.isNew(TOPIC, createRecord(TOPIC, "2", "200")));
            Assert.assertFalse(dedupe.isNew(TOPIC, createRecord(TOPIC, "3", "300")));
        }
    }

    @Nested
    @DisplayName("De-duplication with OFF policy")
    class OffPolicyTests {

        @Test
        @DisplayName("OFF policy should treat all records as new")
        public void testOffPolicyAllRecordsNew() {
            DeDuplicator dedupe = createDeDuplicator("off");
            SinkRecord record = createRecord(TOPIC, "1", "100");
            Assert.assertTrue("First record with OFF policy should be new",
                    dedupe.isNew(TOPIC, record));
            Assert.assertTrue("Duplicate with OFF policy should still be new",
                    dedupe.isNew(TOPIC, record));
            Assert.assertTrue("Third time with OFF policy should still be new",
                    dedupe.isNew(TOPIC, record));
        }
    }

    @Nested
    @DisplayName("De-duplication with OLD policy")
    class OldPolicyTests {

        @Test
        @DisplayName("OLD policy should keep original record (not replace)")
        public void testOldPolicyKeepsOriginal() {
            DeDuplicator dedupe = createDeDuplicator("old");
            Assert.assertTrue("First record should be new",
                    dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "100")));
            Assert.assertFalse("Duplicate should be detected",
                    dedupe.isNew(TOPIC, createRecord(TOPIC, "1", "200")));
        }
    }

    // ========================================================================
    // Legacy test — preserved for backward compatibility
    // ========================================================================

    @Test
    @DisplayName("Legacy: comprehensive isNew test flow")
    public void testIsNew() {
        Map<String, String> properties = new HashMap<>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "new");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String topic = "products";
        String key = "11";
        Long timestamp1 = System.currentTimeMillis();

        // First record — should be new
        SinkRecord recordOne = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, KEY_FIELD, key, VALUE_FIELD, "2000",
                TimestampType.NO_TIMESTAMP_TYPE, timestamp1);
        Assert.assertTrue("First record should be new", dedupe.isNew(topic, recordOne));

        // Same key — should be duplicate
        long timestamp2 = System.currentTimeMillis();

        // NOTE: the phase merge interleaved two copies of these assertions here.
        // The earlier copy referenced undeclared locals (keyField/valueField/
        // value) and redeclared recordTwo/recordDifferentKey, so it could not
        // compile. The KEY_FIELD/VALUE_FIELD copy below asserts exactly the same
        // behaviour (same key => duplicate, different key => new), so removing
        // the broken copy loses no coverage.
        SinkRecord recordTwo = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, KEY_FIELD, key, VALUE_FIELD, "2000",
                TimestampType.NO_TIMESTAMP_TYPE, timestamp2);
        Assert.assertFalse("Same key should be duplicate", dedupe.isNew(topic, recordTwo));

        // Different key — should be new (Phase 1 fix)
        SinkRecord recordDifferentKey = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, KEY_FIELD, "22", VALUE_FIELD, "2000",
                TimestampType.NO_TIMESTAMP_TYPE, timestamp2);
        Assert.assertTrue("Different key '22' should be new, not a duplicate",
                dedupe.isNew(topic, recordDifferentKey));

        // Different topic — should be new
        Assert.assertTrue("Different topic should be new",
                dedupe.isNew("employees", recordTwo));
    }

    @Test
    public void testMultipleNewKeysForSameTopic() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "new");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String topic = "orders";
        String keyField = "orderId";
        String valueField = "amount";
        Long ts = System.currentTimeMillis();

        // First key for topic — should be new
        SinkRecord r1 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "100", valueField, "500",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue("First key should be new", dedupe.isNew(topic, r1));

        // Second DIFFERENT key for same topic — should also be new
        SinkRecord r2 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "200", valueField, "600",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue("Second different key should be new",
                dedupe.isNew(topic, r2));

        // Third DIFFERENT key for same topic — should also be new
        SinkRecord r3 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "300", valueField, "700",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue("Third different key should be new",
                dedupe.isNew(topic, r3));

        // Duplicate of first key — should NOT be new
        SinkRecord r1dup = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "100", valueField, "500",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertFalse("Duplicate of first key should not be new",
                dedupe.isNew(topic, r1dup));
    }

    @Test
    public void testEvictionWhenPoolExceedsMaxSize() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "new");
        // Set max pool size to 2
        properties.put(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(), "2");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String topic = "events";
        String keyField = "eventId";
        String valueField = "data";
        Long ts = System.currentTimeMillis();

        // Add 3 records to a pool of size 2 — oldest should be evicted
        SinkRecord r1 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "A", valueField, "x",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue(dedupe.isNew(topic, r1));

        SinkRecord r2 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "B", valueField, "y",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue(dedupe.isNew(topic, r2));

        SinkRecord r3 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "C", valueField, "z",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue(dedupe.isNew(topic, r3));

        // "A" should have been evicted — sending it again should be new
        SinkRecord r1again = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "A", valueField, "x",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertTrue("Evicted key A should be considered new again",
                dedupe.isNew(topic, r1again));

        // "C" should still be in the pool — sending it again should be dup
        SinkRecord r3dup = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "C", valueField, "z",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        Assert.assertFalse("Key C should still be in pool (not evicted)",
                dedupe.isNew(topic, r3dup));
    }

    @Test
    public void testDeDuplicationOff() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "off");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String topic = "products";
        String keyField = "productId";
        String valueField = "amount";
        Long ts = System.currentTimeMillis();

        SinkRecord r1 = ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, keyField, "11", valueField, "2000",
                TimestampType.NO_TIMESTAMP_TYPE, ts);

        // When dedup is off, ALL records are new (no dedup check)
        Assert.assertTrue(dedupe.isNew(topic, r1));
        Assert.assertTrue(dedupe.isNew(topic, r1));
        Assert.assertTrue(dedupe.isNew(topic, r1));
    }

    @Test
    public void testMultiTopicIndependence() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "new");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String keyField = "id";
        String valueField = "data";
        Long ts = System.currentTimeMillis();

        // Same key across different topics should be independent
        SinkRecord rTopic1 = ClickHouseSinkTaskTest.spoofSinkRecord(
                "topic_a", keyField, "1", valueField, "x",
                TimestampType.NO_TIMESTAMP_TYPE, ts);
        SinkRecord rTopic2 = ClickHouseSinkTaskTest.spoofSinkRecord(
                "topic_b", keyField, "1", valueField, "x",
                TimestampType.NO_TIMESTAMP_TYPE, ts);

        Assert.assertTrue("Key 1 in topic_a: new",
                dedupe.isNew("topic_a", rTopic1));
        Assert.assertTrue("Same key 1 in topic_b: new (independent)",
                dedupe.isNew("topic_b", rTopic2));
        Assert.assertFalse("Key 1 in topic_a again: duplicate",
                dedupe.isNew("topic_a", rTopic1));
        Assert.assertFalse("Key 1 in topic_b again: duplicate",
                dedupe.isNew("topic_b", rTopic2));
    }
}
