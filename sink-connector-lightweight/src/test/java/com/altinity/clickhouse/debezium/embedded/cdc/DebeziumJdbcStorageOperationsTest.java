package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
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

        assertDoesNotThrow(() -> ops.createSchemaHistoryTable(null, ZoneFixtures.withZones(props)));
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
        long result = assertDoesNotThrow(() -> ops.getLatestRecordTimestamp(null, ZoneFixtures.withZones(props)));

        assertEquals(-1L, result);
    }

    @Test
    @DisplayName("The restart monitor's latest record is the same instant whatever the JVM zone (Spec 07.03 section 3.1.10)")
    void latestRecordEpochMillis_isTheInstantInEveryJvmZone() {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            // 2026-10-20 00:00:00 UTC, as the offset query now returns it (toUnixTimestamp).
            for (String zone : new String[]{"America/Chicago", "UTC", "Asia/Kolkata"}) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                assertEquals(1792454400000L, DebeziumJdbcStorageOperations.latestRecordEpochMillis("1792454400"),
                        "JVM zone " + zone);
            }
        } finally {
            java.util.TimeZone.setDefault(before);
        }
        assertEquals(-1L, DebeziumJdbcStorageOperations.latestRecordEpochMillis(null));
        assertEquals(-1L, DebeziumJdbcStorageOperations.latestRecordEpochMillis(""));
        assertEquals(-1L, DebeziumJdbcStorageOperations.latestRecordEpochMillis("2026-10-20 00:00:00"),
                "the wall-clock text the old query returned is refused, not parsed in the JVM zone");
    }
}
