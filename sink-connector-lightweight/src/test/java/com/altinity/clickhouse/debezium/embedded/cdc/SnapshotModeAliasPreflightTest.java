package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spec 02.06 section 3.4: snapshot.mode values Debezium 3.3 removed keep the
 * meaning they had under 3.1.3.
 */
public class SnapshotModeAliasPreflightTest {

    private static Properties props(String connector, String mode) {
        Properties p = new Properties();
        p.setProperty("connector.class", connector);
        if (mode != null) {
            p.setProperty("snapshot.mode", mode);
        }
        return p;
    }

    private static final String MYSQL = "io.debezium.connector.mysql.MySqlConnector";
    private static final String POSTGRES = "io.debezium.connector.postgresql.PostgresConnector";

    @Test
    @DisplayName("MySQL schema_only (the shipped template default) becomes no_data")
    public void schemaOnlyBecomesNoData() {
        Properties p = props(MYSQL, "schema_only");
        assertEquals("no_data", SnapshotModeAliasPreflight.apply(p));
        assertEquals("no_data", p.getProperty("snapshot.mode"));
    }

    @Test
    @DisplayName("MySQL schema_only_recovery becomes no_data (its 3.1.3 snapshotter extended NoDataSnapshotter)")
    public void schemaOnlyRecoveryBecomesNoData() {
        Properties p = props(MYSQL, " Schema_Only_Recovery ");
        assertEquals("no_data", SnapshotModeAliasPreflight.apply(p));
    }

    @Test
    @DisplayName("Values 3.3 still knows are left alone, and so is an unset mode")
    public void validValuesUntouched() {
        for (String mode : new String[] {"initial", "recovery", "no_data", "when_needed"}) {
            Properties p = props(MYSQL, mode);
            assertNull(SnapshotModeAliasPreflight.apply(p), mode);
            assertEquals(mode, p.getProperty("snapshot.mode"));
        }
        Properties unset = props(MYSQL, null);
        assertNull(SnapshotModeAliasPreflight.apply(unset));
        assertNull(unset.getProperty("snapshot.mode"));
    }

    @Test
    @DisplayName("MySQL never (removed with NeverSnapshotter in 3.7) becomes no_data")
    public void mysqlNeverBecomesNoData() {
        Properties p = props(MYSQL, "never");
        assertEquals("no_data", SnapshotModeAliasPreflight.apply(p));
        assertEquals("no_data", p.getProperty("snapshot.mode"));
    }

    @Test
    @DisplayName("PostgreSQL never (removed in 3.3) becomes no_data")
    public void postgresNeverBecomesNoData() {
        Properties p = props(POSTGRES, "never");
        assertEquals("no_data", SnapshotModeAliasPreflight.apply(p));
        assertEquals("no_data", p.getProperty("snapshot.mode"));
    }

    @Test
    @DisplayName("PostgreSQL schema_only is not a binlog alias and is left to Debezium")
    public void postgresDoesNotGetBinlogAliases() {
        Properties p = props(POSTGRES, "initial");
        assertNull(SnapshotModeAliasPreflight.apply(p));
        assertEquals("initial", p.getProperty("snapshot.mode"));
    }
}
