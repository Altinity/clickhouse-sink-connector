package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEW-J3: {@link DDLSchemaChangeWaiter} fails closed on a timeout, waits for
 * RENAME / MODIFY / unqualified ALTERs too, waits for the NET state of a
 * column that one statement drops and re-adds, and escapes the names it puts
 * in its {@code system.columns} query.
 *
 * <p>Uses {@link Proxy} JDBC stubs: the module has no mocking library.</p>
 */
class DDLSchemaChangeWaiterFailClosedTest {

    /** A system.columns stand-in: the SQL it received and the rows (name, type) a poll returns. */
    private static final class FakeClickHouse {
        final List<String> queries = new CopyOnWriteArrayList<>();
        final AtomicInteger polls = new AtomicInteger();
        final Function<Integer, Map<String, String>> rowsForPoll;

        FakeClickHouse(Function<Integer, Map<String, String>> rowsForPoll) {
            this.rowsForPoll = rowsForPoll;
        }

        Connection connection() {
            InvocationHandler statement = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        queries.add((String) args[0]);
                        return resultSet(rowsForPoll.apply(polls.incrementAndGet()));
                    case "close":
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            };
            InvocationHandler connection = (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, statement);
                }
                throw new UnsupportedOperationException(method.getName());
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, connection);
        }

        private static ResultSet resultSet(Map<String, String> rows) {
            List<Map.Entry<String, String>> entries = new ArrayList<>(rows.entrySet());
            int[] cursor = {-1};
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        return ++cursor[0] < entries.size();
                    case "getString":
                        int column = (Integer) args[0];
                        return column == 1 ? entries.get(cursor[0]).getKey() : entries.get(cursor[0]).getValue();
                    case "close":
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }
    }

    private static Map<String, String> columns(String... nameTypePairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) {
            m.put(nameTypePairs[i], nameTypePairs[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // (a) fail closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A column that never appears fails the strict wait with SchemaVisibilityTimeoutException")
    void timeoutFailsClosed() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(200, 20);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns());

        DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException e = assertThrows(
                DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class,
                () -> waiter.awaitSchemaVisibility(ch.connection(),
                        "ALTER TABLE `db`.`tbl` ADD COLUMN `never` String"));
        assertTrue(e.getMessage().contains("db.tbl"), e.getMessage());
        assertTrue(e.getMessage().contains("never"), e.getMessage());
    }

    @Test
    @DisplayName("The lenient wait reports a timeout as false instead of throwing")
    void lenientWaitReturnsFalseOnTimeout() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(150, 20);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns());

        assertFalse(waiter.waitForSchemaVisibility(ch.connection(),
                "ALTER TABLE `db`.`tbl` ADD COLUMN `never` String"));
    }

    @Test
    @DisplayName("A timeout caused by failing polls carries the last polling error as its cause")
    void timeoutCarriesPollingError() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(150, 20);
        Connection broken = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    throw new SQLException("connection reset");
                });

        DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException e = assertThrows(
                DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class,
                () -> waiter.awaitSchemaVisibility(broken, "ALTER TABLE `db`.`tbl` ADD COLUMN `c` String"));
        assertNotNull(e.getCause());
        assertEquals("connection reset", e.getCause().getMessage());
    }

    @Test
    @DisplayName("An interrupted wait fails closed and keeps the interrupt flag")
    void interruptFailsClosedAndKeepsFlag() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5_000, 20);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns());

        Thread.currentThread().interrupt();
        try {
            assertThrows(DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class,
                    () -> waiter.awaitSchemaVisibility(ch.connection(),
                            "ALTER TABLE `db`.`tbl` ADD COLUMN `c` String"));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt must not be swallowed");
        }
    }

    // ------------------------------------------------------------------
    // (b) more than ADD / DROP, unqualified tables
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RENAME COLUMN waits until the old name is gone and the new one is there")
    void renameColumnWaitsForBothNames() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5_000, 10);
        // poll 1: still old; poll 2: both (impossible but partial); poll 3: renamed
        FakeClickHouse ch = new FakeClickHouse(poll -> poll < 3 ? columns("old_c", "String") : columns("new_c", "String"));

        waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE `db`.t RENAME COLUMN IF EXISTS old_c to new_c");

        assertTrue(ch.polls.get() >= 3, "must keep polling until the rename is visible: " + ch.polls.get());
    }

    @Test
    @DisplayName("RENAME COLUMN that never becomes visible fails closed")
    void renameColumnTimeoutFailsClosed() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(150, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("old_c", "String"));

        assertThrows(DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class,
                () -> waiter.awaitSchemaVisibility(ch.connection(),
                        "ALTER TABLE `db`.t RENAME COLUMN old_c TO new_c"));
    }

    @Test
    @DisplayName("MODIFY COLUMN waits until the column carries the new type (whitespace and case ignored)")
    void modifyColumnWaitsForTheNewType() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5_000, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> poll < 3
                ? columns("c", "Nullable(Int32)")
                : columns("c", "Nullable(DateTime64(0, 'UTC'))"));

        waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE `db`.t MODIFY COLUMN c Nullable(DateTime64(0,'UTC'))");

        assertTrue(ch.polls.get() >= 3, "the type check must hold the wait until the type changed");
        assertTrue(ch.queries.get(0).startsWith("SELECT name, type FROM system.columns"), ch.queries.get(0));
    }

    @Test
    @DisplayName("MODIFY COLUMN that never takes effect fails closed")
    void modifyColumnTimeoutFailsClosed() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(150, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("c", "Nullable(Int32)"));

        assertThrows(DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class,
                () -> waiter.awaitSchemaVisibility(ch.connection(),
                        "ALTER TABLE `db`.t MODIFY COLUMN c Nullable(String)"));
    }

    @Test
    @DisplayName("MODIFY COLUMN without a type (DEFAULT / COMMENT only) waits for presence, not a type")
    void modifyWithoutTypeChecksPresenceOnly() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("c", "whatever"));

        assertDoesNotThrow(() -> waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE `db`.t MODIFY COLUMN c DEFAULT 5"));
        assertTrue(ch.queries.get(0).startsWith("SELECT name FROM system.columns"), ch.queries.get(0));
    }

    @Test
    @DisplayName("An unqualified ALTER TABLE tbl is checked in the session database")
    void unqualifiedAlterUsesTheSessionDatabase() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("c", "String"));

        waiter.awaitSchemaVisibility(ch.connection(), "ALTER TABLE tbl ADD COLUMN c String", "session_db");

        assertTrue(ch.queries.get(0).contains("database = 'session_db' AND table = 'tbl'"), ch.queries.get(0));
    }

    @Test
    @DisplayName("An unqualified ALTER with no session database is skipped, not polled")
    void unqualifiedAlterWithoutSessionDatabaseIsSkipped() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns());

        assertDoesNotThrow(() -> waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE tbl ADD COLUMN c String"));
        assertEquals(0, ch.polls.get());
    }

    @Test
    @DisplayName("Backticked database and table names (with dots) are parsed whole")
    void backtickedTargetWithDots() {
        String[] target = DDLSchemaChangeWaiter.parseAlterTarget(
                "ALTER TABLE `my.db`.`t.1` ADD COLUMN c String", null);
        assertEquals("my.db", target[0]);
        assertEquals("t.1", target[1]);
    }

    // ------------------------------------------------------------------
    // (c) net state of a drop + add of one column
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DROP then ADD of the same column expects it PRESENT and returns at once")
    void dropThenAddSameColumnExpectsPresent() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5_000, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("c", "String"));

        long start = System.currentTimeMillis();
        // DESTRUCTIVE: synthetic DDL string parsed by unit test against an in-memory fake connection.
        waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE `db`.t DROP COLUMN IF EXISTS c, ADD COLUMN IF NOT EXISTS c String");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, ch.polls.get(), "the column is present: one poll is enough");
        assertTrue(elapsed < 1_000, "must not hang on 'absent AND present': " + elapsed + "ms");
    }

    @Test
    @DisplayName("ADD then DROP of the same column expects it ABSENT")
    void addThenDropSameColumnExpectsAbsent() {
        // DESTRUCTIVE: synthetic DDL string parsed by unit test; no database is touched.
        Map<String, DDLSchemaChangeWaiter.ColumnExpectation> state = DDLSchemaChangeWaiter.expectedColumnState(
                "ALTER TABLE `db`.t ADD COLUMN c String, DROP COLUMN c");
        assertEquals(1, state.size());
        assertFalse(state.get("c").present);
    }

    @Test
    @DisplayName("The net state keeps each column's last clause, in statement order")
    void netStateIsOrderSensitive() {
        // DESTRUCTIVE: synthetic DDL string parsed by unit test; no database is touched.
        Map<String, DDLSchemaChangeWaiter.ColumnExpectation> state = DDLSchemaChangeWaiter.expectedColumnState(
                "ALTER TABLE `db`.t DROP COLUMN a, ADD COLUMN a String, RENAME COLUMN b TO c, MODIFY COLUMN d Int8");
        assertTrue(state.get("a").present);
        assertFalse(state.get("b").present);
        assertTrue(state.get("c").present);
        assertTrue(state.get("d").present);
        assertEquals("Int8", state.get("d").type);
    }

    @Test
    @DisplayName("RENAME COLUMN IF EXISTS returns immediately on replay no-op and still waits when source column exists")
    void renameColumnIfExistsHandlesReplayNoOpAndLiveRename() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);

        // Replay no-op: neither old nor new column exists (e.g. column was subsequently dropped).
        FakeClickHouse replayCh = new FakeClickHouse(poll -> columns("other", "String"));
        waiter.awaitSchemaVisibility(replayCh.connection(),
                "ALTER TABLE `db`.t RENAME COLUMN IF EXISTS old_c TO new_c");
        assertEquals(1, replayCh.polls.get(), "IF EXISTS replay no-op must not time out");

        // Live rename: old_c is present on poll 1, replaced by new_c on poll 2.
        FakeClickHouse liveCh = new FakeClickHouse(poll -> poll == 1
                ? columns("old_c", "String")
                : columns("new_c", "String"));
        waiter.awaitSchemaVisibility(liveCh.connection(),
                "ALTER TABLE `db`.t RENAME COLUMN IF EXISTS old_c TO new_c");
        assertEquals(2, liveCh.polls.get(), "must keep polling while old_c is still visible");
    }

    @Test
    @DisplayName("MODIFY COLUMN IF EXISTS returns immediately when column is absent and still checks type when present")
    void modifyColumnIfExistsHandlesReplayNoOpAndLiveTypeChange() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);

        // Replay no-op: column does not exist.
        FakeClickHouse replayCh = new FakeClickHouse(poll -> columns("other", "String"));
        waiter.awaitSchemaVisibility(replayCh.connection(),
                "ALTER TABLE `db`.t MODIFY COLUMN IF EXISTS c Int64");
        assertEquals(1, replayCh.polls.get(), "IF EXISTS replay no-op must not time out");

        // Live modify: column exists with old type on poll 1 and updated type on poll 2.
        FakeClickHouse liveCh = new FakeClickHouse(poll -> poll == 1
                ? columns("c", "Int32")
                : columns("c", "Int64"));
        waiter.awaitSchemaVisibility(liveCh.connection(),
                "ALTER TABLE `db`.t MODIFY COLUMN IF EXISTS c Int64");
        assertEquals(2, liveCh.polls.get(), "must wait until existing column's type matches");
    }

    @Test
    @DisplayName("Backticked column names with spaces, commas, and doubled backticks are parsed whole")
    void backtickedColumnsWithSpacesCommasAndEscapedBackticks() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);
        FakeClickHouse addCh = new FakeClickHouse(poll -> columns("my col", "Nullable(Int32)", "a, b", "String", "x`y", "String"));

        assertDoesNotThrow(() -> waiter.awaitSchemaVisibility(addCh.connection(),
                "ALTER TABLE `db`.`t` ADD COLUMN IF NOT EXISTS `my col` Nullable(Int32), "
                        + "ADD COLUMN `a, b` String, ADD COLUMN `x``y` String"));

        Map<String, DDLSchemaChangeWaiter.ColumnExpectation> renameModify = DDLSchemaChangeWaiter.expectedColumnState(
                "ALTER TABLE `db`.`t` RENAME COLUMN `old col` TO `new, col`, "
                        + "MODIFY COLUMN `my col` Nullable(Int64)");
        assertFalse(renameModify.get("old col").present);
        assertTrue(renameModify.get("new, col").present);
        assertTrue(renameModify.get("my col").present);
        assertEquals("Nullable(Int64)", renameModify.get("my col").type);

        // DESTRUCTIVE: synthetic DDL string parsed by unit test; no database is touched.
        assertEquals(List.of("my col", "a, b"), DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE `db`.`t` DROP COLUMN IF EXISTS `my col`, DROP COLUMN `a, b`;",
                DDLSchemaChangeWaiter.DROP_COLUMN_PATTERN));
    }

    @Test
    @DisplayName("Unquoted comma-separated names do not swallow the comma")
    void commaIsNotPartOfTheName() {
        // DESTRUCTIVE: synthetic DDL string parsed by unit test; no database is touched.
        assertEquals(List.of("a", "b"), DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE t DROP COLUMN a, DROP COLUMN b", DDLSchemaChangeWaiter.DROP_COLUMN_PATTERN));
    }

    // ------------------------------------------------------------------
    // (d) escaping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Quotes in database and table names are escaped in the system.columns query")
    void literalsAreEscaped() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1_000, 10);
        FakeClickHouse ch = new FakeClickHouse(poll -> columns("c", "String"));

        waiter.awaitSchemaVisibility(ch.connection(),
                "ALTER TABLE `o'db`.`t'bl` ADD COLUMN c String");

        String sql = ch.queries.get(0);
        assertTrue(sql.contains("database = 'o''db'"), sql);
        assertTrue(sql.contains("table = 't''bl'"), sql);
    }

    @Test
    @DisplayName("escapeSqlLiteral doubles quotes and escapes backslashes")
    void escapeSqlLiteralHandlesQuotesAndBackslashes() {
        assertEquals("o''brien", DDLSchemaChangeWaiter.escapeSqlLiteral("o'brien"));
        assertEquals("a\\\\b", DDLSchemaChangeWaiter.escapeSqlLiteral("a\\b"));
        assertEquals("x''; DROP", DDLSchemaChangeWaiter.escapeSqlLiteral("x'; DROP"));
    }
}
