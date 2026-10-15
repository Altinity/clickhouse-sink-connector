package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.01 section 3.2: a ReplacingMergeTree target whose engine columns the
 * connector cannot bind is refused when the writer is built, loudly, instead
 * of being written to with rows that are versioned 0 or never deleted.
 *
 * <p>Exercises the static check directly: {@code DbWriter}'s constructor opens
 * a connection, so building a writer here would spend the connection-retry
 * budget first (see {@code OffsetStorageDatabaseNameTest}).</p>
 */
public class DbWriterEngineColumnsTest {

    private static Map<String, String> columns(String... names) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : names) {
            m.put(n, "String");
        }
        return m;
    }

    @Test
    @DisplayName("A bare ReplacingMergeTree() (no version column) is refused")
    public void rmtWithoutVersionColumnIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                DbWriter.requireReplacingMergeTreeColumns("db1", "orders", "",
                        "is_deleted", columns("id", "is_deleted"), false));
        assertTrue(e.getMessage().contains("version column"), e.getMessage());
        assertTrue(e.getMessage().contains("db1.orders"), e.getMessage());
    }

    @Test
    @DisplayName("A version column named in the engine clause but absent from the table is refused")
    public void rmtWithMissingVersionColumnIsRefused() {
        assertThrows(IllegalStateException.class, () ->
                DbWriter.requireReplacingMergeTreeColumns("db1", "orders", "ver",
                        "is_deleted", columns("id", "is_deleted"), false));
    }

    /**
     * V6: a DELETE on such a table would insert its before image as a LIVE
     * row. INSERTs and UPDATEs replicate correctly, and old-style
     * {@code ReplacingMergeTree(ver)} tables without a delete column are
     * common (the connector's own test fixtures and every Postgres target
     * created before the {@code is_deleted} engine), so the table is NOT
     * refused when the writer is built; the first DELETE record for it is
     * ({@code PreparedStatementFieldMapperEngineColumnTest.testDeleteForTableWithoutDeleteColumnIsRefused}).
     */
    @Test
    @DisplayName("A ReplacingMergeTree lacking the delete column is accepted at open (only its DELETEs are refused)")
    public void rmtTargetWithoutDeleteColumnIsAcceptedAtOpen() {
        assertDoesNotThrow(() ->
                DbWriter.requireReplacingMergeTreeColumns("db1", "orders", "_version",
                        "is_deleted", columns("id", "_version"), false));
    }

    @Test
    @DisplayName("With ignore_delete=true the delete column is not required")
    public void rmtWithoutDeleteColumnIsAcceptedWhenDeletesAreIgnored() {
        DbWriter.requireReplacingMergeTreeColumns("db1", "orders", "_version",
                "is_deleted", columns("id", "_version"), true);
    }

    @Test
    @DisplayName("Resolved names are matched case-insensitively, like the rest of the column map")
    public void resolvedNamesMatchCaseInsensitively() {
        DbWriter.requireReplacingMergeTreeColumns("db1", "orders", "VER",
                "Removed", columns("id", "ver", "removed"), false);
    }
}
