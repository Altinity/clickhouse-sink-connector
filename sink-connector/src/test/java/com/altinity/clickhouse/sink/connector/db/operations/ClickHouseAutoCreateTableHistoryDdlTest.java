package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Record-path CREATE TABLE in history mode (Spec 12.02 section 3.2 and 3.4).
 *
 * <p>Pins the DDL string {@code ClickHouseAutoCreateTable.createTableSyntax}
 * emits for an SCD2 table, so the record path cannot drift from the DDL
 * translator again: {@code _valid_from} carries the sentinel DEFAULT (D-1),
 * both validity bounds carry the configured timezone (D-2) and the TTL is
 * {@code replication.history.ttl}, not a hardcoded 30 (D-3). No database is
 * contacted: the pure-string entry point is called exactly as
 * {@code ClickHouseAutoCreateTableTest} calls it.</p>
 */
public class ClickHouseAutoCreateTableHistoryDdlTest {

    private static Field[] fields() {
        return new Field[]{
                new Field("id", 0, Schema.INT32_SCHEMA),
                new Field("name", 1, Schema.STRING_SCHEMA),
        };
    }

    private static Map<String, String> columnMap() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("name", "String");
        return columns;
    }

    private static String createTable(Map<String, String> props) {
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");
        return new ClickHouseAutoCreateTable().createTableSyntax(primaryKey, "t", "binlog_history",
                fields(), columnMap(), true, false, null, new ClickHouseSinkConnectorConfig(props));
    }

    @Test
    public void historyModeEmitsDefaultsTimezoneAndConfiguredTtl() {
        Map<String, String> props = new HashMap<>();
        props.put("replication.history.enable", "true");
        props.put("replication.history.ttl", "7");
        props.put("clickhouse.datetime.timezone", "UTC");

        String ddl = createTable(props);

        Assert.assertTrue(ddl, ddl.contains("`_valid_from` DateTime('UTC') DEFAULT '2100-01-01 00:00:00'"));
        Assert.assertTrue(ddl, ddl.contains("`_valid_to` DateTime('UTC') DEFAULT '2100-01-01 00:00:00'"));
        Assert.assertTrue(ddl, ddl.contains("`_operation` LowCardinality(String)"));
        Assert.assertTrue(ddl, ddl.contains("PARTITION BY toDate(`_valid_to`)"));
        // The sorting key is (k, _valid_to): what makes the table an SCD2 table.
        Assert.assertTrue(ddl, ddl.contains("ORDER BY(id,`_valid_to`)"));
        Assert.assertTrue(ddl, ddl.contains("TTL `_valid_to` + toIntervalDay(7)"));
        Assert.assertFalse(ddl, ddl.contains("toIntervalDay(30)"));
    }

    @Test
    public void historyModeWithoutTimezoneEmitsBareDateTimeWithDefault() {
        Map<String, String> props = new HashMap<>();
        props.put("replication.history.enable", "true");

        String ddl = createTable(props);

        Assert.assertTrue(ddl, ddl.contains("`_valid_from` DateTime DEFAULT '2100-01-01 00:00:00'"));
        Assert.assertTrue(ddl, ddl.contains("`_valid_to` DateTime DEFAULT '2100-01-01 00:00:00'"));
        // Default replication.history.ttl.
        Assert.assertTrue(ddl, ddl.contains("TTL `_valid_to` + toIntervalDay(30)"));
    }

    @Test
    public void standardModeEmitsNoHistoryColumns() {
        String ddl = createTable(new HashMap<>());

        Assert.assertFalse(ddl, ddl.contains("_valid_from"));
        Assert.assertFalse(ddl, ddl.contains("_valid_to"));
        Assert.assertFalse(ddl, ddl.contains("_operation"));
        Assert.assertFalse(ddl, ddl.contains("PARTITION BY"));
        Assert.assertFalse(ddl, ddl.contains(" TTL "));
        Assert.assertTrue(ddl, ddl.contains("ORDER BY(id)"));
    }
}
