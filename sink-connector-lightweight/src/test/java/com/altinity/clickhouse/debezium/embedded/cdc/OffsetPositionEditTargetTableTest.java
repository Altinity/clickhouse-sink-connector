package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Failure mode FM-09.03-4 (spec 09.03 section 6): the REST position edit
 * ({@code POST /binlog}, {@code POST /lsn}, used by the {@code sink-connector-client}
 * {@code change_replication_source} and {@code lsn} commands) rewrites the offset
 * row as a DELETE on the configured, database-qualified offset table followed by
 * an INSERT built from the table name WITHOUT its database, on a connection whose
 * default database is {@code system} ({@code DebeziumEmbeddedRestApi.getDatabaseConnection}).
 * With every shipped configuration ({@code altinity_sink_connector.replica_source_info})
 * the INSERT goes to {@code system.replica_source_info} and fails after the DELETE
 * succeeded: the connector's offset row is gone, and the next start takes the
 * no-offset path ({@code snapshot.mode}).
 *
 * <p>JDBC is the scripted proxy of {@link VersionHighWaterMarkTest}; no ClickHouse
 * is needed.</p>
 */
public class OffsetPositionEditTargetTableTest {

    private static final String OFFSET_TABLE = "altinity_sink_connector.replica_source_info";

    private static Properties props() {
        Properties props = new Properties();
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), OFFSET_TABLE);
        props.setProperty("name", "company-1");
        return props;
    }

    private static List<String> writes(VersionHighWaterMarkTest.FakeClickHouse fake) {
        List<String> out = new ArrayList<>();
        for (String sql : fake.executed) {
            String lower = sql.trim().toLowerCase();
            if (lower.startsWith("delete") || lower.startsWith("insert")) {
                out.add(sql);
            }
        }
        return out;
    }

    @Test
    @DisplayName("the position edit deletes the key's row from the configured, qualified offset table")
    public void binlogEditDeletesFromTheQualifiedOffsetTable() throws Exception {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();

        new DebeziumJdbcStorageOperations().updateDebeziumStorageStatus(fake.connection(), null, props(),
                "mysql-bin.000010", "4", null);

        List<String> writes = writes(fake);
        assertEquals(2, writes.size(), "one DELETE and one INSERT: " + writes);
        // DESTRUCTIVE: asserts on the text of the DELETE that the production edit path issues;
        // the statement runs only against the recording JDBC proxy, nothing is deleted anywhere.
        assertTrue(writes.get(0).startsWith("delete from " + OFFSET_TABLE + " where offset_key="),
                writes.get(0));
    }

    @Test
    @Disabled("DEFECT FM-09.03-4: the INSERT of the edited offset names the table without its database "
            + "on a connection whose default database is system, after the DELETE on the qualified table")
    @DisplayName("the position edit inserts the new offset into the table it deleted the old one from")
    public void binlogEditInsertsIntoTheTableItDeletedFrom() throws Exception {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();

        new DebeziumJdbcStorageOperations().updateDebeziumStorageStatus(fake.connection(), null, props(),
                "mysql-bin.000010", "4", null);

        List<String> writes = writes(fake);
        assertTrue(writes.get(1).startsWith("INSERT INTO " + OFFSET_TABLE + "("),
                "the edited offset must be written to " + OFFSET_TABLE + ", but the statement was: "
                        + writes.get(1));
    }

    @Test
    @Disabled("DEFECT FM-09.03-4: the LSN edit has the same unqualified INSERT")
    @DisplayName("the LSN edit inserts the new offset into the table it deleted the old one from")
    public void lsnEditInsertsIntoTheTableItDeletedFrom() throws Exception {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();

        new DebeziumJdbcStorageOperations().updateDebeziumStorageStatus(fake.connection(), null, props(),
                "0/16B3748");

        List<String> writes = writes(fake);
        assertTrue(writes.get(1).startsWith("INSERT INTO " + OFFSET_TABLE + "("),
                "the edited offset must be written to " + OFFSET_TABLE + ", but the statement was: "
                        + writes.get(1));
    }

    @Test
    @Disabled("DEFECT FM-09.03-4: the edit deletes the stored offset before it writes the new one, so a "
            + "failed INSERT leaves the connector without any offset")
    @DisplayName("a failed position edit leaves the stored offset in place")
    public void failedEditLeavesTheStoredOffsetInPlace() {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        fake.failingInsertsRemaining = 1;

        assertThrows(SQLException.class, () -> new DebeziumJdbcStorageOperations().updateDebeziumStorageStatus(
                fake.connection(), null, props(), "mysql-bin.000010", "4", null));

        List<String> writes = writes(fake);
        assertTrue(writes.stream().noneMatch(sql -> sql.toLowerCase().startsWith("delete")),
                "the INSERT failed, yet the stored offset had already been deleted: " + writes);
    }
}
