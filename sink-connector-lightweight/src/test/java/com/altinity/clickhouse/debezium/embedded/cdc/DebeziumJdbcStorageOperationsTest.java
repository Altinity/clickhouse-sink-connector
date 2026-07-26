package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
