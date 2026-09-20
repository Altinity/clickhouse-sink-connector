package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
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

    /**
     * A schema whose {@code status} column carries a Connect-schema default,
     * which is what Debezium produces for a MySQL column declared
     * {@code status VARCHAR(16) DEFAULT 'new'}.
     */
    private static Schema schemaWithDefault() {
        return SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("status", SchemaBuilder.string().optional().defaultValue("new").build())
                .build();
    }

    /**
     * A PreparedStatement stand-in recording every setNull index and every
     * setString value. A JDK proxy is used because this module has no mocking
     * framework on its test classpath.
     */
    private static PreparedStatement recordingStatement(List<Integer> nullIndices,
                                                        List<String> boundStrings) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setNull":
                    nullIndices.add((Integer) args[0]);
                    return null;
                case "setString":
                    boundStrings.add((String) args[1]);
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    /**
     * Spec 07.07 section 3.1: the Connect-schema default must never stand in for a
     * source NULL. Kafka Connect {@code Struct.get} returns
     * {@code schema.defaultValue()} for a null field, and Debezium propagates
     * the MySQL column DEFAULT into that schema, so reading the value through
     * {@code get} writes {@code 'new'} where MySQL holds NULL -- with matching
     * row counts. The bind path must read with {@code getWithoutDefault},
     * unconditionally (not only under the old {@code non.default.value=true}).
     */
    @Test
    public void testSchemaDefaultIsNotSubstitutedForNull() throws Exception {
        Struct after = new Struct(schemaWithDefault()).put("id", 1);
        // status is deliberately left null; Struct.get("status") would answer "new".
        Assert.assertEquals("precondition: Struct.get substitutes the schema default",
                "new", after.get("status"));

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<String, Integer> columnNameToIndexMap = new LinkedHashMap<>();
        columnNameToIndexMap.put("id", 1);
        columnNameToIndexMap.put("status", 2);
        Map<String, String> columnNameToDataTypeMap = new LinkedHashMap<>();
        columnNameToDataTypeMap.put("id", "Int32");
        columnNameToDataTypeMap.put("status", "Nullable(String)");

        List<Integer> nullIndices = new ArrayList<>();
        List<String> boundStrings = new ArrayList<>();

        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db",
                ZoneId.of("UTC")).insertPreparedStatement(
                columnNameToIndexMap, recordingStatement(nullIndices, boundStrings),
                after.schema().fields(), record, after, false, config(),
                columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");

        Assert.assertFalse(
                "the Connect-schema default 'new' was bound in place of the source NULL; "
                        + "MySQL holds NULL, so ClickHouse must too. Bound strings: " + boundStrings,
                boundStrings.contains("new"));
        Assert.assertTrue(
                "status is NULL at the source and must be bound with setNull(2); "
                        + "setNull indices were: " + nullIndices,
                nullIndices.contains(2));
    }

    /**
     * The modified-field list is built from the same default-free read, so a
     * NULL-with-default column is classified as NULL rather than as a field
     * "modified" to its default value.
     */
    @Test
    public void testNullWithSchemaDefaultIsNotAModifiedField() {
        Struct after = new Struct(schemaWithDefault()).put("id", 1);

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);

        List<String> modified = new ArrayList<>();
        for (Field f : record.getAfterModifiedFields()) {
            modified.add(f.name());
        }
        Assert.assertFalse(
                "status is NULL at the source; treating it as modified means its schema "
                        + "default was read in place of NULL. Modified fields: " + modified,
                modified.contains("status"));
        Assert.assertTrue(modified.contains("id"));
    }
}
