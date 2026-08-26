package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _operation} must survive into the generated INSERT column list.
 *
 * <p>{@code createColumns} drops any ClickHouse column that the incoming change
 * event does not carry, unless that column is one the connector populates
 * itself. {@code _operation} is connector-populated -- exactly like
 * {@code _version} and {@code is_deleted} -- but was missing from
 * {@code isConnectorManagedColumn}, so it was filtered out of every
 * replication-history INSERT.</p>
 *
 * <p>The consequence is silent and permanent: the SCD Type 2 rows land with an
 * empty {@code _operation}, so a DELETE cannot be distinguished from an insert.
 * A row deleted at the source stays visible in ClickHouse forever, while row
 * counts stay plausible enough that a count-based checksum reports the table
 * clean. Observed in production as MySQL 13 rows vs ClickHouse 15.</p>
 */
public class QueryFormatterOperationColumnTest {

    /** The bitemporal target shape used by replication-history mode. */
    private Map<String, String> historyTargetColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("appkey", "String");
        m.put("comment", "Nullable(String)");
        m.put("_valid_from", "DateTime");
        m.put("_valid_to", "DateTime");
        m.put("_operation", "LowCardinality(String)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    /**
     * A change event carrying only the source columns -- never the
     * connector-managed metadata columns.
     */
    private List<Field> sourceOnlyFields() {
        List<Field> fields = new ArrayList<>();
        Schema s = SchemaBuilder.string().optional().build();
        fields.add(new Field("id", 0, Schema.INT32_SCHEMA));
        fields.add(new Field("appkey", 1, s));
        return fields;
    }

    private String insertQuery() {
        MutablePair<String, Map<String, Integer>> r =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        "process", sourceOnlyFields(), historyTargetColumns(),
                        false, false, "", "binlog_history", "is_deleted");
        return r.getLeft();
    }

    @Test
    @DisplayName("_operation is kept in the INSERT column list even though no record carries it")
    public void testOperationColumnSurvives() {
        String q = insertQuery();
        assertTrue(q.contains("`_operation`"),
                "_operation is connector-populated and must stay in the column list; "
                        + "without it a DELETE is indistinguishable from an insert. Got: " + q);
    }

    @Test
    @DisplayName("The other connector-managed columns are kept too (regression guard)")
    public void testOtherManagedColumnsSurvive() {
        String q = insertQuery();
        assertTrue(q.contains("`_version`"), q);
        assertTrue(q.contains("`is_deleted`"), q);
        assertTrue(q.contains("`_valid_from`"), q);
        assertTrue(q.contains("`_valid_to`"), q);
    }

    @Test
    @DisplayName("Source columns the record carries are kept")
    public void testCarriedSourceColumnsSurvive() {
        String q = insertQuery();
        assertTrue(q.contains("`id`"), q);
        assertTrue(q.contains("`appkey`"), q);
    }
}
