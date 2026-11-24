package com.altinity.clickhouse.sink.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SC-1: {@code default.error.table} defaults to the table the error logger
 * writes to ({@code replica_source_error}). Its default used to be the
 * option's own KEY, so an unset option resolved to the literal string
 * {@code default.error.table} -- a different table from the one the logger
 * inserts into.
 */
public class ErrorTableDefaultConfigTest {

    private static ClickHouseSinkConnectorConfig config(Map<String, String> extra) {
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>(extra)));
    }

    @Test
    @DisplayName("An unset default.error.table resolves to replica_source_error, not to its own key")
    public void unsetErrorTableDefaultsToReplicaSourceError() {
        String resolved = config(new HashMap<>())
                .getString(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());

        assertEquals("replica_source_error", resolved);
        assertNotEquals("default.error.table", resolved,
                "the default must not be the option's own key");
    }

    @Test
    @DisplayName("A configured default.error.table is honoured")
    public void configuredErrorTableIsHonoured() {
        Map<String, String> extra = new HashMap<>();
        extra.put(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString(), "my_errors");

        assertEquals("my_errors", config(extra)
                .getString(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString()));
    }

    @Test
    @DisplayName("The config default equals the error logger's fallback table")
    public void configDefaultMatchesDeclaredConstant() {
        assertEquals("replica_source_error", ClickHouseSinkConnectorConfig.DEFAULT_ERROR_TABLE);
    }
}
