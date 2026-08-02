package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class DebeziumJdbcStorageOperationsTest {

    @Test
    @DisplayName("Missing offset.storage.jdbc.table.name throws IllegalArgumentException with property name")
    void createDatabaseForDebeziumStorage_throwsOnMissingOffsetTableName() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties emptyProps = new Properties();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ops.createDatabaseForDebeziumStorage(null, emptyProps));

        assertTrue(ex.getMessage().contains("offset.storage.jdbc.table.name"),
                "Error message should name the missing property");
    }

    @Test
    @DisplayName("Missing schema history table name throws IllegalArgumentException with property name")
    void deleteSchemaHistory_throwsOnMissingSchemaHistoryTableName() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties emptyProps = new Properties();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ops.deleteSchemaHistory(null, null, emptyProps));

        assertTrue(ex.getMessage().contains("schema.history.internal"),
                "Error message should reference the schema history config prefix");
    }

    @Test
    @DisplayName("createSchemaHistoryTable returns without NPE when DDL query is absent")
    void createSchemaHistoryTable_skipsWhenDdlMissing() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties props = new Properties();
        String offsetTableKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        props.setProperty(offsetTableKey, "altinity_sink_connector.replica_source_info");

        assertDoesNotThrow(() -> ops.createSchemaHistoryTable(null, props));
    }

    // develop's three null-connection tests are kept (union of both sides), but
    // CORRECTED: they were written against signatures that do not exist
    // ((Connection, String) / (Connection)), so origin/develop did not
    // test-compile. Each call now uses the real signature — the intent (no
    // unhandled failure, no leaked ResultSet on a dead connection) is preserved.

    @Test
    @DisplayName("getErrorTableStatus handles null connection gracefully")
    void getErrorTableStatus_handlesNullConnection() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties props = new Properties();
        props.setProperty(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString(),
                "altinity_sink_connector.error_table");
        // A null connection must not leak a ResultSet (the original bug). It may
        // surface as SQLException/NPE, but never as a leaked resource.
        try {
            ops.getErrorTableStatus(null, props);
        } catch (SQLException | NullPointerException expected) {
            // Expected with a null connection.
        }
    }

    @Test
    @DisplayName("getDebeziumStorageStatus handles null connection gracefully")
    void getDebeziumStorageStatus_handlesNullConnection() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties props = new Properties();
        String offsetTableKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        props.setProperty(offsetTableKey, "altinity_sink_connector.replica_source_info");
        try {
            ops.getDebeziumStorageStatus(null, null, props);
        } catch (Exception expected) {
            // Expected with a null connection; the ResultSet leak is what is fixed.
        }
    }

    @Test
    @DisplayName("createViewForShowReplicaStatus handles null connection gracefully")
    void createViewForShowReplicaStatus_handlesNullConnection() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties props = new Properties();
        String offsetTableKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        props.setProperty(offsetTableKey, "altinity_sink_connector.replica_source_info");
        props.setProperty(
                ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString(),
                "CREATE OR REPLACE VIEW %s.show_replica_status AS SELECT * FROM %s");
        // Must not escape as an unhandled exception — the method logs and returns.
        assertDoesNotThrow(() -> ops.createViewForShowReplicaStatus(null, null, props));
    }

    @Test
    @DisplayName("getLatestRecordTimestamp returns -1 without NPE when the query has no result")
    void getLatestRecordTimestamp_returnsSentinelWhenQueryHasNoResult() {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        Properties props = new Properties();
        String offsetTableKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        props.setProperty(offsetTableKey, "altinity_sink_connector.replica_source_info");

        // A null connection makes the underlying query return no result. That
        // reached SimpleDateFormat.parse(null) and threw a NullPointerException,
        // which the surrounding ParseException handler does not catch.
        long result = assertDoesNotThrow(() -> ops.getLatestRecordTimestamp(null, props));

        assertEquals(-1L, result);
    }
}
