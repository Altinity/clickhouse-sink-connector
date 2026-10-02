package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.05 section 3.1.1, engine selection: a failure to read the server
 * version must propagate. Returning {@code false} (the previous behaviour)
 * created the table with the legacy {@code ReplacingMergeTree(_version)} +
 * {@code _sign} layout, permanently, after any transient metadata failure.
 */
public class DbWriterEngineSelectionTest {

    private static DBMetadata reporting(String version) {
        return new DBMetadata(new ClickHouseSinkConnectorConfig(new HashMap<>())) {
            @Override
            public String getClickHouseVersion(Connection connection) {
                return version;
            }
        };
    }

    @Test
    @DisplayName("A failed SELECT VERSION() propagates instead of selecting the legacy engine")
    public void versionQueryFailurePropagates() throws Exception {
        DBMetadata failing = new DBMetadata(new ClickHouseSinkConnectorConfig(new HashMap<>())) {
            @Override
            public String getClickHouseVersion(Connection connection) throws SQLException {
                throw new SQLException("connection reset by peer");
            }
        };
        assertThrows(SQLException.class, () -> DbWriter.isNewReplacingMergeTreeEngine(failing, null),
                "a transient metadata failure must not decide the table engine");

        assertFalse(DbWriter.isNewReplacingMergeTreeEngine(reporting("22.8.5.29"), null));
        assertTrue(DbWriter.isNewReplacingMergeTreeEngine(reporting("24.8.14.10545"), null));
    }
}
