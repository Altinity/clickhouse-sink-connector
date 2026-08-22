package com.altinity.clickhouse.sink.connector.db;

import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A record buffered before an {@code ALTER TABLE ... ADD COLUMN} /
 * {@code CHANGE COLUMN} does not carry the columns the ALTER introduced, but
 * the ClickHouse column map reflects the table AFTER the ALTER.
 *
 * <p>Previously every ClickHouse column was placed in the INSERT column list
 * regardless of whether the record carried it, so the missing columns were
 * bound to NULL -- silently overwriting the real source values. Row counts
 * still matched, so count-based checksum jobs reported the table clean.</p>
 *
 * <p>Measured on the connector before this fix (MySQL 8.0.36 ROW/FULL/GTID ->
 * ClickHouse 24.8.14), deterministic across 12 independent trials: of three
 * rows written around two ALTERs, two came back with NULLs where MySQL held
 * real values.</p>
 *
 * <p>Omitting the absent column from the column list instead lets ClickHouse
 * apply the column's DEFAULT, which is what the ALTER established.</p>
 */
public class QueryFormatterPreAlterRecordTest {

    /** Columns of the ClickHouse table AFTER the ALTER added c_int_u. */
    private static Map<String, String> postAlterColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("a_int", "Nullable(Int64)");
        m.put("b_str", "Nullable(String)");
        m.put("c_int_u", "Nullable(UInt32)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    /** Fields of a record captured BEFORE the ALTER: no c_int_u. */
    private static List<Field> preAlterFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", 0, Schema.INT32_SCHEMA));
        fields.add(new Field("a_int", 1, Schema.INT64_SCHEMA));
        fields.add(new Field("b_str", 2, Schema.STRING_SCHEMA));
        return fields;
    }

    @Test
    public void testColumnAbsentFromPreAlterRecordIsOmittedNotNullFilled() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "ddl_churn", preAlterFields(), postAlterColumns(),
                        false, false, null, "repro_db");

        String query = response.left;
        Assert.assertFalse(
                "c_int_u is absent from this record; including it binds NULL over the real "
                        + "MySQL value. Query was: " + query,
                query.contains("c_int_u"));
        Assert.assertFalse("c_int_u must not be bound",
                response.right.containsKey("c_int_u"));
    }

    /**
     * The connector populates _version and is_deleted itself; they are never in
     * the source record and must survive the filter.
     */
    @Test
    public void testConnectorManagedColumnsAlwaysRetained() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "ddl_churn", preAlterFields(), postAlterColumns(),
                        false, false, null, "repro_db");

        Assert.assertTrue("_version must remain in the INSERT",
                response.right.containsKey("_version"));
        Assert.assertTrue("is_deleted must remain in the INSERT",
                response.right.containsKey("is_deleted"));
    }

    /**
     * The ReplacingMergeTree delete column is configurable, so it cannot be
     * recognised from a constant. If it were filtered out, deletes would stop
     * being marked.
     */
    @Test
    public void testConfiguredDeleteColumnRetained() {
        Map<String, String> columns = postAlterColumns();
        columns.put("sign", "Int8");

        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "ddl_churn", preAlterFields(), columns,
                        false, false, null, "repro_db", "sign");

        Assert.assertTrue("configured delete column must remain in the INSERT",
                response.right.containsKey("sign"));
    }

    /**
     * Regression guard: when the record carries every column, nothing is
     * filtered and the query is unchanged.
     */
    @Test
    public void testRecordCarryingAllColumnsIsUnaffected() {
        List<Field> fields = preAlterFields();
        fields.add(new Field("c_int_u", 3, Schema.INT32_SCHEMA));

        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "ddl_churn", fields, postAlterColumns(),
                        false, false, null, "repro_db");

        Assert.assertTrue("c_int_u is present in the record and must be inserted",
                response.right.containsKey("c_int_u"));
        Assert.assertEquals("all five columns plus the connector's two",
                6, response.right.size());
    }

    /**
     * Regression guard: the placeholder count must match the column count,
     * otherwise the prepared statement is malformed.
     */
    @Test
    public void testPlaceholderCountMatchesColumnCount() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "ddl_churn", preAlterFields(), postAlterColumns(),
                        false, false, null, "repro_db");

        String query = response.left;
        long placeholders = query.substring(query.indexOf("VALUES")).chars()
                .filter(c -> c == '?').count();
        Assert.assertEquals("placeholder count must equal bound column count, query: " + query,
                response.right.size(), (int) placeholders);
    }
}
