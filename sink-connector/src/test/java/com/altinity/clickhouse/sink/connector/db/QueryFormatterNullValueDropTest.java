package com.altinity.clickhouse.sink.connector.db;

import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The record's "modified fields" list is built by
 * {@code ClickHouseStruct#setAfterStruct} as the fields whose value is
 * {@code != null}, so a column that is genuinely NULL in the source row is
 * absent from that list even though the record's SCHEMA carries it.
 *
 * <p>{@code QueryFormatter#createColumns} then drops every column missing from
 * that list out of the INSERT column list entirely, so ClickHouse applies the
 * column's DEFAULT instead of NULL. For an ADD COLUMN pre-ALTER record that is
 * correct and intended (#1389). For a plain row that merely holds a NULL it is
 * silent corruption: the target cell takes the DEFAULT (0 / '' / 1970-01-01)
 * rather than NULL, and on a ReplacingMergeTree UPDATE it takes the DEFAULT
 * rather than the value the row previously held.</p>
 *
 * <p>The two cases are distinguishable and must be distinguished: a pre-ALTER
 * record does not carry the column in its SCHEMA at all, whereas a NULL-valued
 * column IS in the schema and only absent from the value-filtered list. The
 * schema is therefore the correct authority for which columns the INSERT must
 * bind; the value-filtered list never was.</p>
 *
 * <p>Field indices below are deliberately non-contiguous where a NULL column is
 * filtered out, mirroring what {@code setAfterStruct} actually produces.</p>
 */
public class QueryFormatterNullValueDropTest {

    /** Columns of the live ClickHouse table. */
    private static Map<String, String> tableColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("kafka_offset", "Nullable(Int64)");
        m.put("request", "Nullable(String)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    /**
     * The full record schema: the source row carries all three data columns.
     * This is what {@code afterStruct.schema().fields()} returns.
     */
    private static List<Field> fullSchemaFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", 0, Schema.INT32_SCHEMA));
        fields.add(new Field("kafka_offset", 1, Schema.OPTIONAL_INT64_SCHEMA));
        fields.add(new Field("request", 2, Schema.OPTIONAL_STRING_SCHEMA));
        return fields;
    }

    /**
     * What {@code setAfterStruct} actually hands the writer when
     * {@code kafka_offset} is NULL in this row: the field is filtered out by the
     * {@code s.get(f) != null} test, so only id and request survive.
     */
    private static List<Field> valueFilteredFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", 0, Schema.INT32_SCHEMA));
        fields.add(new Field("request", 2, Schema.OPTIONAL_STRING_SCHEMA));
        return fields;
    }

    /**
     * Reproduces the value-filtering in {@code ClickHouseStruct#setAfterStruct}
     * so the test is pinned to the real producer of this list rather than to a
     * hand-written approximation of it.
     */
    private static List<Field> modifiedFieldsOf(Struct s) {
        List<Field> modified = new ArrayList<>();
        for (Field f : s.schema().fields()) {
            if (s.get(f) != null) {
                modified.add(f);
            }
        }
        return modified;
    }

    /**
     * A NULL-valued column must still be bound, so the cell lands as NULL.
     * Dropping it from the INSERT makes ClickHouse substitute the column
     * DEFAULT, which is a different value from what the source holds.
     */
    @Test
    public void testNullValuedColumnIsStillBound() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "event", valueFilteredFields(), tableColumns(),
                        false, false, null, "txnrepo_uat", null,
                        fullSchemaFields());

        String query = response.left;
        Assert.assertTrue(
                "kafka_offset is NULL in this row but present in the source schema. "
                        + "Dropping it from the INSERT makes ClickHouse apply the column "
                        + "DEFAULT instead of NULL, so MySQL NULL and ClickHouse 0 diverge "
                        + "and the checksum job fails. Query was: " + query,
                query.contains("kafka_offset"));
        Assert.assertTrue("kafka_offset must have a placeholder to bind",
                response.right.containsKey("kafka_offset"));
    }

    /**
     * The defect as the checksum job sees it: an UPDATE that sets a previously
     * populated column to NULL must overwrite the stored value, not silently
     * retain the DEFAULT. Same binding requirement, stated from the failure the
     * job actually reports.
     */
    @Test
    public void testUpdateSettingColumnToNullBindsTheColumn() {
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("kafka_offset", Schema.OPTIONAL_INT64_SCHEMA)
                .field("request", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Struct after = new Struct(schema)
                .put("id", 7)
                .put("kafka_offset", null)
                .put("request", "payload");

        List<Field> modified = modifiedFieldsOf(after);
        Assert.assertEquals(
                "setAfterStruct filters NULL-valued fields out of the modified list; "
                        + "this test is pointless if that stops being true",
                2, modified.size());

        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "event", modified, tableColumns(),
                        false, false, null, "txnrepo_uat", null,
                        schema.fields());

        Assert.assertTrue(
                "An UPDATE nulling kafka_offset must bind it, otherwise the new row "
                        + "carries the column DEFAULT and the NULL never reaches ClickHouse. "
                        + "Query was: " + response.left,
                response.right.containsKey("kafka_offset"));
    }

    /**
     * Regression guard for #1389, which this fix must not undo: a record whose
     * SCHEMA genuinely lacks the column (buffered before ALTER TABLE ADD COLUMN)
     * must still be omitted, so ClickHouse applies the DEFAULT the ALTER
     * established rather than binding NULL over the real value.
     */
    @Test
    public void testPreAlterRecordStillOmitsColumnAbsentFromSchema() {
        List<Field> preAlter = new ArrayList<>();
        preAlter.add(new Field("id", 0, Schema.INT32_SCHEMA));
        preAlter.add(new Field("kafka_offset", 1, Schema.OPTIONAL_INT64_SCHEMA));

        Map<String, String> postAlter = tableColumns();

        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "event", preAlter, postAlter, false, false, null,
                        "txnrepo_uat", null, preAlter);

        Assert.assertFalse(
                "request is absent from the pre-ALTER record's schema; binding it would "
                        + "NULL-fill over the real value (#1389). Query was: " + response.left,
                response.right.containsKey("request"));
    }

    /**
     * Connector-managed columns are never in the source record and must always
     * survive, whichever list is used.
     */
    @Test
    public void testConnectorManagedColumnsAlwaysRetained() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "event", valueFilteredFields(), tableColumns(),
                        false, false, null, "txnrepo_uat", null,
                        fullSchemaFields());

        Assert.assertTrue("_version must be retained",
                response.right.containsKey("_version"));
        Assert.assertTrue("is_deleted must be retained",
                response.right.containsKey("is_deleted"));
    }
}
