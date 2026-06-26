package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that waits for DDL schema changes (ALTER TABLE ADD/DROP COLUMN)
 * to become visible in ClickHouse's {@code system.columns} before returning.
 *
 * <p>This class addresses a race condition (GitHub issue #1222) where the
 * connector executes an ALTER TABLE and then immediately reads column
 * metadata. If the ALTER hasn't propagated yet, the connector builds
 * INSERT statements using a stale column list, silently dropping values
 * for newly added columns.</p>
 *
 * <p>The waiter polls {@code system.columns} in a tight loop with
 * configurable timeout and interval.</p>
 */
public class DDLSchemaChangeWaiter {

    private static final Logger log = LogManager.getLogger(DDLSchemaChangeWaiter.class);

    /** Default max wait time (ms) for a schema change to become visible. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Default polling interval (ms) between visibility checks. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 100;

    /**
     * Regex to extract column names from ALTER TABLE ... ADD COLUMN statements.
     * Matches both backtick-quoted and unquoted column names.
     */
    private static final Pattern ADD_COLUMN_PATTERN = Pattern.compile(
            "ADD\\s+COLUMN\\s+`?([^`\\s]+)`?",
            Pattern.CASE_INSENSITIVE
    );

    /** Regex to extract column names from ALTER TABLE ... DROP COLUMN. */
    private static final Pattern DROP_COLUMN_PATTERN = Pattern.compile(
            "DROP\\s+COLUMN\\s+`?([^`\\s]+)`?",
            Pattern.CASE_INSENSITIVE
    );

    /** Regex to extract database.table from ALTER TABLE statements. */
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?([^`\\s.]+)`?\\.`?([^`\\s,]+)`?",
            Pattern.CASE_INSENSITIVE
    );

    private final long timeoutMs;
    private final long pollIntervalMs;

    /** Creates a waiter with default timeout and poll interval. */
    public DDLSchemaChangeWaiter() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Creates a waiter with custom timeout and poll interval.
     *
     * @param timeoutMs      Maximum time to wait for visibility (ms).
     * @param pollIntervalMs Time between polls (ms).
     */
    public DDLSchemaChangeWaiter(long timeoutMs, long pollIntervalMs) {
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Waits for all column additions in the given DDL to become visible
     * in {@code system.columns}. For DROP COLUMN, waits until the column
     * disappears. For DDL that cannot be parsed, applies a conservative
     * fixed delay.
     *
     * @param conn An active JDBC connection to the ClickHouse server.
     * @param ddl  The DDL statement that was just executed.
     */
    public void waitForSchemaVisibility(Connection conn, String ddl) {
        if (conn == null || ddl == null || ddl.isEmpty()) {
            return;
        }

        Matcher tableMatcher = ALTER_TABLE_PATTERN.matcher(ddl);
        if (!tableMatcher.find()) {
            return;
        }

        String database = tableMatcher.group(1);
        String table = tableMatcher.group(2);

        List<String> addedColumns = extractColumnNames(ddl, ADD_COLUMN_PATTERN);
        List<String> droppedColumns = extractColumnNames(ddl, DROP_COLUMN_PATTERN);

        if (!addedColumns.isEmpty()) {
            waitForColumnsToAppear(conn, database, table, addedColumns);
        }

        if (!droppedColumns.isEmpty()) {
            waitForColumnsToDisappear(conn, database, table, droppedColumns);
        }

        if (addedColumns.isEmpty() && droppedColumns.isEmpty()) {
            log.info("DDL does not contain parseable ADD/DROP COLUMN; " +
                    "applying {}ms safety delay for: {}", pollIntervalMs * 5, ddl);
            try {
                Thread.sleep(pollIntervalMs * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Polls {@code system.columns} until all specified columns appear.
     */
    private void waitForColumnsToAppear(Connection conn, String database,
                                        String table, List<String> columns) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> remaining = new HashSet<>(columns);

        log.info("Waiting for {} column(s) to appear in {}.{}: {}",
                columns.size(), database, table, columns);

        while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Set<String> currentColumns = queryColumnNames(conn, database, table);
                remaining.removeAll(currentColumns);

                if (remaining.isEmpty()) {
                    log.info("All added columns are now visible in {}.{}", database, table);
                    return;
                }

                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for column visibility");
                return;
            } catch (Exception e) {
                log.warn("Error polling system.columns for {}.{}: {}",
                        database, table, e.getMessage());
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!remaining.isEmpty()) {
            log.warn("Timeout ({}ms) waiting for columns to appear in {}.{}: {}. " +
                            "Proceeding anyway -- data loss may occur for these columns.",
                    timeoutMs, database, table, remaining);
        }
    }

    /**
     * Polls {@code system.columns} until all specified columns disappear.
     */
    private void waitForColumnsToDisappear(Connection conn, String database,
                                           String table, List<String> columns) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> remaining = new HashSet<>(columns);

        log.info("Waiting for {} column(s) to disappear from {}.{}: {}",
                columns.size(), database, table, columns);

        while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Set<String> currentColumns = queryColumnNames(conn, database, table);
                remaining.retainAll(currentColumns);

                if (remaining.isEmpty()) {
                    log.info("All dropped columns are no longer visible in {}.{}", database, table);
                    return;
                }

                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for column drop visibility");
                return;
            } catch (Exception e) {
                log.warn("Error polling system.columns for {}.{}: {}",
                        database, table, e.getMessage());
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!remaining.isEmpty()) {
            log.warn("Timeout ({}ms) waiting for columns to disappear from {}.{}: {}",
                    timeoutMs, database, table, remaining);
        }
    }

    /**
     * Queries the current column names for a table from {@code system.columns}.
     */
    private Set<String> queryColumnNames(Connection conn, String database,
                                         String table) throws Exception {
        Set<String> columns = new HashSet<>();
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s'",
                database, table);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    /**
     * Extracts column names from a DDL statement using the given pattern.
     */
    static List<String> extractColumnNames(String ddl, Pattern pattern) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = pattern.matcher(ddl);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }
}
