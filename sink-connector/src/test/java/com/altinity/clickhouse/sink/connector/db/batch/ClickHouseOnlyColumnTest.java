package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
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
 * Pins the parity-scope carve-out (Constitution I6; spec 11.02 section 3.3): a
 * column that exists on the ClickHouse replica alone, with no column of that
 * name on the MySQL source and not created or managed by the connector for
 * replication bookkeeping, is tolerated. Tolerated means the connector must
 * never try to supply a value for it, never warn about it, and never alter or
 * overwrite it -- it simply keeps whatever value it already has (its DEFAULT,
 * or whatever a human or another process put there).
 *
 * <p>Mechanically this is the same column-membership path as the #1389
 * pre-ALTER fix ({@link NullValueColumnDropTest#testPreAlterRecordStillOmitsUnknownColumn()}):
 * a column absent from the record's schema is excluded from the generated
 * INSERT (Spec 04.03 section 3.1 Case B) and the bind-time mapper recognises
 * the gap as "the record does not carry this column" rather than a defect
 * (Spec 04.03 section 3.1, {@code PreparedStatementFieldMapper#recordCarries}).
 * That fix was framed around a column the ALTER has not reached yet
 * (temporary absence, later resolved). This test pins the same mechanism for a
 * column that will NEVER appear in any record's schema (permanent absence,
 * never resolved) -- the parity-scope scenario -- through both production
 * entry points: {@code QueryFormatter}'s column-list membership (exercised via
 * {@link GroupInsertQueryWithBatchRecords#updateQueryToRecordsMap}) and
 * {@link PreparedStatementFieldMapper#insertPreparedStatement} at bind time.
 * See Spec 04.03 FM-04.03-4.</p>
 */
public class ClickHouseOnlyColumnTest {

    private static Map<String, String> tableColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("name", "Nullable(String)");
        // Added on the ClickHouse replica only. Plain/DEFAULT, not
        // ALIAS/MATERIALIZED -- DBMetadata.getColumnsDataTypesForTable already
        // excludes those kinds before this map is built, so a plain extra
        // column really does reach this code path. No MySQL column of this
        // name has ever existed, so no record's schema ever carries it.
        m.put("ch_added_col", "Nullable(String)");
        return m;
    }

    private static Schema rowSchema() {
        return SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /**
     * Query-formatting half, through the production entry point: a
     * ClickHouse-only column never gets a placeholder, so ClickHouse applies
     * its own DEFAULT (or keeps its existing value) instead of the connector
     * trying to supply one it does not have.
     */
    @Test
    public void testClickHouseOnlyColumnIsOmittedFromGeneratedInsert() {
        Struct after = new Struct(rowSchema())
                .put("id", 1)
                .put("name", "payload");

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db1");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().updateQueryToRecordsMap(
                record, record.getAfterModifiedFields(), queryToRecords,
                "t1", config(), tableColumns());

        Assert.assertEquals(1, queryToRecords.size());
        MutablePair<String, Map<String, Integer>> key =
                queryToRecords.keySet().iterator().next();

        Assert.assertFalse(
                "ch_added_col has no source column and is not connector-managed; "
                        + "parity scope (Constitution I6) tolerates it, so the connector "
                        + "must never try to write it. Query was: " + key.getLeft(),
                key.getRight().containsKey("ch_added_col"));
        Assert.assertFalse("the column must not appear in the statement text either",
                key.getLeft().contains("ch_added_col"));
        Assert.assertTrue("a real source column must still be written",
                key.getRight().containsKey("name"));
    }

    /**
     * A PreparedStatement stand-in recording every index touched by a set
     * call. A JDK proxy is used because this module has no mocking framework
     * on its test classpath (see {@code NullValueColumnDropTest}).
     *
     * <p>This test only cares which parameter indices were touched, not which
     * JDBC setter touched them -- {@link com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper#convert}
     * picks the setter per column type and target width (e.g. an Int32 column
     * is bound with {@code setObject}, not {@code setInt}:
     * {@code isWiderIntegerTarget} treats Int32 as a possible post-ALTER
     * widening target). So every {@code setXxx(int parameterIndex, ...)}
     * overload is recorded by matching the method name prefix and the first
     * argument's type, rather than naming each setter the converter might
     * choose -- a narrower, enumerated list is exactly what silently dropped
     * the "id" column's bind from this test the first time it was written.</p>
     */
    private static PreparedStatement recordingStatement(List<Integer> touchedIndices) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    if (method.getName().startsWith("set") && args != null && args.length > 0
                            && args[0] instanceof Integer) {
                        touchedIndices.add((Integer) args[0]);
                    }
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    /**
     * Bind-time half: a {@code columnNameToIndexMap} with no entry for
     * ch_added_col (what the query-formatting half above actually produces).
     * The mapper must recognise the gap as "the record does not carry this
     * column" (DEBUG, continue -- Spec 04.03 section 3.1 Case B) rather than
     * the genuine-defect branch that fails the batch, and the row must still
     * write: nothing bound for ch_added_col, no exception, every real column
     * still bound.
     */
    @Test
    public void testClickHouseOnlyColumnRecordWritesWithoutError() throws Exception {
        Struct after = new Struct(rowSchema())
                .put("id", 1)
                .put("name", "payload");

        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db1");

        // The index map QueryFormatter would actually produce for this
        // record: no placeholder for ch_added_col.
        Map<String, Integer> columnNameToIndexMap = new LinkedHashMap<>();
        columnNameToIndexMap.put("id", 1);
        columnNameToIndexMap.put("name", 2);

        List<Integer> touchedIndices = new ArrayList<>();

        // No ReplacingMergeTree/CollapsingMergeTree/history columns configured:
        // this test isolates the ClickHouse-only-column path from the
        // engine-column and history-mode handling that other specs/tests
        // already cover.
        new PreparedStatementFieldMapper(null, false, null, null, "db1",
                ZoneId.of("UTC")).insertPreparedStatement(
                columnNameToIndexMap, recordingStatement(touchedIndices),
                record.getAfterModifiedFields(), record, after, false, config(),
                tableColumns(), DBMetadata.TABLE_ENGINE.MERGE_TREE, "t1");

        Assert.assertFalse(
                "no index was ever reserved for ch_added_col; nothing may be bound "
                        + "at an index outside the real columns' (1, 2)",
                touchedIndices.contains(3));
        Assert.assertTrue("id must still be bound", touchedIndices.contains(1));
        Assert.assertTrue("name must still be bound", touchedIndices.contains(2));
    }
}
