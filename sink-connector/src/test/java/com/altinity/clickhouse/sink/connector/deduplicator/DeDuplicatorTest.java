package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkTaskTest;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Spec 10.05: de-duplication keys on EVENT identity
 * {@code (topic, partition, offset)}, never on the row key.
 *
 * <p>Keying on {@code SinkRecord.key()} -- the source primary key -- made every
 * later UPDATE and DELETE of a row look like a duplicate of its INSERT, so with
 * {@code deduplication.policy=OLD|NEW} the first image of every row was frozen
 * in ClickHouse forever.</p>
 */
public class DeDuplicatorTest {

    private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
            .field("productId", Schema.STRING_SCHEMA).build();
    private static final Schema VALUE_SCHEMA = SchemaBuilder.struct()
            .field("amount", Schema.STRING_SCHEMA).build();

    private static ClickHouseSinkConnectorConfig config(String policy, String bufferCount) {
        Map<String, String> properties = new HashMap<>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), policy);
        if (bufferCount != null) {
            properties.put(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(), bufferCount);
        }
        return new ClickHouseSinkConnectorConfig(properties);
    }

    /** A record for row {@code productId} at an explicit stream position. */
    private static SinkRecord recordAt(String topic, int partition, long offset,
                                       String productId, String amount) {
        Struct key = new Struct(KEY_SCHEMA).put("productId", productId);
        Struct value = new Struct(VALUE_SCHEMA).put("amount", amount);
        return new SinkRecord(topic, partition, KEY_SCHEMA, key, VALUE_SCHEMA, value, offset);
    }

    /**
     * Two DIFFERENT events for the same row (an INSERT then an UPDATE, at
     * consecutive offsets) are both new. This is the defect: the old
     * implementation returned false for the second one and the UPDATE was
     * dropped before it reached the writers.
     */
    @Test
    public void testSamePrimaryKeyDifferentOffsetsAreBothNew() {
        DeDuplicator dedupe = new DeDuplicator(config("new", null));

        SinkRecord insert = recordAt("products", 0, 10L, "11", "2000");
        SinkRecord update = recordAt("products", 0, 11L, "11", "2500");

        Assert.assertTrue(dedupe.isNew("products", insert));
        Assert.assertTrue(
                "an UPDATE of a row is a different event from its INSERT (offset 11 vs 10); "
                        + "keying de-duplication on the row key drops every later change to the row",
                dedupe.isNew("products", update));
    }

    /** The same event redelivered (same topic, partition and offset) is a duplicate, under both policies. */
    @Test
    public void testRedeliveredEventIsDuplicate() {
        for (String policy : new String[]{"old", "new"}) {
            DeDuplicator dedupe = new DeDuplicator(config(policy, null));

            SinkRecord first = recordAt("products", 0, 10L, "11", "2000");
            SinkRecord redelivered = recordAt("products", 0, 10L, "11", "2000");

            Assert.assertTrue(policy, dedupe.isNew("products", first));
            Assert.assertFalse(policy + ": a redelivery of offset 10 must be recognised",
                    dedupe.isNew("products", redelivered));

            // A different partition at the same offset is a different event.
            Assert.assertTrue(policy, dedupe.isNew("products", recordAt("products", 1, 10L, "11", "2000")));
        }
    }

    /** The pool consulted by isNew() is bounded by buffer.count. */
    @Test
    public void testPoolIsBoundedByBufferCount() {
        DeDuplicator dedupe = new DeDuplicator(config("new", "5"));

        for (long offset = 0; offset < 25; offset++) {
            Assert.assertTrue(dedupe.isNew("products", recordAt("products", 0, offset, "k" + offset, "v")));
        }

        Assert.assertTrue(
                "the de-duplication pool must be pruned to buffer.count (5); it holds "
                        + dedupe.poolSize("products") + " identities",
                dedupe.poolSize("products") <= 5);
        // The most recent identity is still remembered ...
        Assert.assertFalse(dedupe.isNew("products", recordAt("products", 0, 24L, "k24", "v")));
        // ... while an evicted one is treated as new again (bounded memory, by design).
        Assert.assertTrue(dedupe.isNew("products", recordAt("products", 0, 0L, "k0", "v")));
    }

    @Test
    public void testIsNew() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), "new");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        String topic = "products";
        String keyField = "productId";
        String key = "11";
        String valueField = "amount";
        String value = "2000";
        Long timestamp1 = System.currentTimeMillis();

        // Two spoofed records share the row key but sit at consecutive offsets:
        // both are distinct events and both are new.
        SinkRecord recordOne = ClickHouseSinkTaskTest.spoofSinkRecord(topic, keyField, key, valueField, value,
        TimestampType.NO_TIMESTAMP_TYPE, timestamp1);

        boolean result1 = dedupe.isNew(topic, recordOne);
        Assert.assertTrue(result1 == true);

        long timestamp2 = System.currentTimeMillis();

        SinkRecord recordTwo = ClickHouseSinkTaskTest.spoofSinkRecord(topic, keyField, key, valueField, value,
        TimestampType.NO_TIMESTAMP_TYPE, timestamp2);

        boolean result2 = dedupe.isNew(topic, recordTwo);
        Assert.assertTrue("same row key, later offset: a different event", result2 == true);

        // The identical event again is the duplicate.
        Assert.assertTrue(dedupe.isNew(topic, recordTwo) == false);

        //  Different key at yet another offset.
        SinkRecord recordDifferentKey = ClickHouseSinkTaskTest.spoofSinkRecord(topic, keyField, "22", valueField, value,
                TimestampType.NO_TIMESTAMP_TYPE, timestamp2);
        boolean resultDifferentKey = dedupe.isNew(topic, recordDifferentKey);
        Assert.assertTrue(resultDifferentKey == true);

        // Test Different Topic: pools are per topic.
        boolean resultDifferentTopic = dedupe.isNew("employees", recordTwo);
        Assert.assertTrue(resultDifferentTopic == true);

        // Policy OFF accepts everything, including exact redeliveries.
        DeDuplicator off = new DeDuplicator(config("off", null));
        Assert.assertTrue(off.isNew(topic, recordOne));
        Assert.assertTrue(off.isNew(topic, recordOne));
    }
}
