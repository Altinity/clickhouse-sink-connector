package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end guard, through the production entry point
 * {@link GroupInsertQueryWithBatchRecords#updateQueryToRecordsMap}, for the
 * NULL-valued column being dropped out of the generated INSERT.
 *
 * <p>{@code ClickHouseStruct#setAfterStruct} builds the "modified fields" list
 * by keeping only fields whose value is {@code != null}. When INSERT membership
 * is decided from that list, a column that is simply NULL in the source row
 * disappears from the statement and ClickHouse applies the column DEFAULT
 * (0 / '' / 1970-01-01) instead of NULL. Row counts still match, so only a
 * value-level checksum catches it.</p>
 *
 * <p>Observed in production on txnrepo-sink-uat after the 2.10.1 to 2.10.3
 * upgrade: {@code txnrepo_uat.event.kafka_offset} diverged on 3,348 rows in a
 * single day, and {@code trade_uat.enriched_trade.capped_by} on 240, with the
 * connector logging "Column index missing for column" and writing the row
 * anyway.</p>
 *
 * <p>The companion unit test
 * {@code com.altinity.clickhouse.sink.connector.db.QueryFormatterNullValueDropTest}
 * pins the same behaviour at the formatter level; this one pins the wiring, so
 * a future caller that stops passing the schema is caught.</p>
 */
public class NullValueColumnDropTest {

    private static Map<String, String> tableColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("kafka_offset", "Nullable(Int64)");
        m.put("request", "Nullable(String)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static Schema rowSchema() {
        return SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("kafka_offset", Schema.OPTIONAL_INT64_SCHEMA)
                .field("request", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /**
     * An INSERT whose kafka_offset is NULL must still bind the column, so the
     * cell lands as NULL rather than as the ClickHouse DEFAULT.
     */
    @Test
    public void testNullColumnIsBoundOnInsert() {
        Struct after = new Struct(rowSchema())
                .put("id", 1)
                .put("kafka_offset", null)
                .put("request", "payload");

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("txnrepo_uat");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords =
                new HashMap<>();

        boolean ok = new GroupInsertQueryWithBatchRecords().updateQueryToRecordsMap(
                record, record.getAfterModifiedFields(), queryToRecords,
                "event", config(), tableColumns());

        Assert.assertTrue("query generation should succeed", ok);
        Assert.assertEquals(1, queryToRecords.size());

        MutablePair<String, Map<String, Integer>> key =
                queryToRecords.keySet().iterator().next();

        Assert.assertTrue(
                "kafka_offset is NULL in this row but carried by the record's schema. "
                        + "Dropping it from the INSERT makes ClickHouse write the column "
                        + "DEFAULT instead of NULL, diverging from MySQL with matching row "
                        + "counts. Query was: " + key.getLeft(),
                key.getRight().containsKey("kafka_offset"));
        Assert.assertTrue("the column must appear in the statement itself",
                key.getLeft().contains("kafka_offset"));
    }

    /**
     * The reverse direction, which is how the divergence accumulates on a
     * ReplacingMergeTree: an UPDATE that clears a previously populated column
     * must write NULL over the stored value.
     */
    @Test
    public void testUpdateClearingColumnBindsIt() {
        Struct before = new Struct(rowSchema())
                .put("id", 1)
                .put("kafka_offset", 4242L)
                .put("request", "payload");
        Struct after = new Struct(rowSchema())
                .put("id", 1)
                .put("kafka_offset", null)
                .put("request", "payload");

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setDatabase("txnrepo_uat");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().updateQueryToRecordsMap(
                record, record.getAfterModifiedFields(), queryToRecords,
                "event", config(), tableColumns());

        MutablePair<String, Map<String, Integer>> key =
                queryToRecords.keySet().iterator().next();

        Assert.assertTrue(
                "an UPDATE clearing kafka_offset must bind it, otherwise the stored "
                        + "value survives in ClickHouse while MySQL holds NULL. Query was: "
                        + key.getLeft(),
                key.getRight().containsKey("kafka_offset"));
    }

    /**
     * The before-image of an UPDATE must be resolved against the BEFORE struct's
     * schema, not the after struct's. Both schemas are identical here, but the
     * value-filtered lists differ, so this pins that the correct image is used.
     */
    @Test
    public void testBeforeImageUsesBeforeSchema() {
        Struct before = new Struct(rowSchema())
                .put("id", 1)
                .put("kafka_offset", null)
                .put("request", "payload");
        Struct after = new Struct(rowSchema())
                .put("id", 1)
                .put("kafka_offset", 99L)
                .put("request", "payload");

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.DELETE);
        record.setDatabase("txnrepo_uat");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().updateQueryToRecordsMap(
                record, record.getBeforeModifiedFields(), queryToRecords,
                "event", config(), tableColumns());

        MutablePair<String, Map<String, Integer>> key =
                queryToRecords.keySet().iterator().next();

        Assert.assertTrue("the before-image's NULL column must still be bound",
                key.getRight().containsKey("kafka_offset"));
    }

    /**
     * Regression guard for #1389: a record whose SCHEMA genuinely lacks the
     * column (buffered before ALTER TABLE ADD COLUMN) must still be omitted, so
     * ClickHouse applies the DEFAULT the ALTER established rather than binding
     * NULL over the real value.
     */
    @Test
    public void testPreAlterRecordStillOmitsUnknownColumn() {
        Schema preAlterSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("kafka_offset", Schema.OPTIONAL_INT64_SCHEMA)
                .build();
        Struct after = new Struct(preAlterSchema)
                .put("id", 1)
                .put("kafka_offset", 7L);

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("txnrepo_uat");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().updateQueryToRecordsMap(
                record, record.getAfterModifiedFields(), queryToRecords,
                "event", config(), tableColumns());

        MutablePair<String, Map<String, Integer>> key =
                queryToRecords.keySet().iterator().next();

        Assert.assertFalse(
                "'request' is absent from this record's schema entirely (pre-ALTER); "
                        + "binding it would NULL-fill over the real value (#1389). Query was: "
                        + key.getLeft(),
                key.getRight().containsKey("request"));
        Assert.assertTrue("connector-managed _version must survive",
                key.getRight().containsKey("_version"));
    }
}
