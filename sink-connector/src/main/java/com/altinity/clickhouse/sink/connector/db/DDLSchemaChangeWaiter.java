package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that waits for DDL schema changes (ALTER TABLE ADD/DROP/RENAME/MODIFY COLUMN)
 * to become visible in ClickHouse's {@code system.columns} before returning.
 *
 * <p>This class addresses a race condition (GitHub issue #1222) where the
 * connector executes an ALTER TABLE and then immediately reads column
 * metadata. If the ALTER hasn't propagated yet, the connector builds
 * INSERT statements using a stale column list, silently dropping values
 * for newly added columns.</p>
 *
 * <p>The waiter polls {@code system.columns} in a tight loop with
 * configurable timeout and interval. The strict form
 * ({@link #awaitSchemaVisibility(Connection, String, String)}) FAILS when the
 * change does not become visible: proceeding would write rows against a stale
 * schema.</p>
 */
public class DDLSchemaChangeWaiter {

    private static final Logger log = LogManager.getLogger(DDLSchemaChangeWaiter.class);

    /** Default max wait time (ms) for a schema change to become visible. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Default polling interval (ms) between visibility checks. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 100;

    /** Matches a backtick-quoted identifier (doubled backtick = literal) or an unquoted column name. */
    private static final String COLUMN_IDENTIFIER_REGEX = "(`(?:[^`]|``)+`|[^`\\s,;]+)";

    /**
     * Regex to extract column names from ALTER TABLE ... ADD COLUMN statements.
     * Matches both backtick-quoted (including spaces, commas, or escaped backticks) and unquoted
     * column names.
     *
     * <p>The optional {@code IF NOT EXISTS} is consumed as part of the prefix,
     * not captured as the column name. Without that, the generated DDL
     * {@code ADD COLUMN IF NOT EXISTS ship_spec Nullable(String)} yields the
     * literal column {@code IF}, which never appears in
     * {@code system.columns} -- so the waiter polls a name that cannot exist
     * until it times out, and the real column is never waited for at all.</p>
     *
     * <p>An unquoted name stops before a comma or semicolon, so
     * {@code DROP COLUMN a, DROP COLUMN b} yields {@code a}, not {@code a,}.</p>
     */
    static final Pattern ADD_COLUMN_PATTERN = Pattern.compile(
            "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + COLUMN_IDENTIFIER_REGEX,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract column names from ALTER TABLE ... DROP COLUMN.
     *
     * <p>Consumes the optional {@code IF EXISTS} for the same reason as
     * {@link #ADD_COLUMN_PATTERN}.</p>
     */
    static final Pattern DROP_COLUMN_PATTERN = Pattern.compile(
            "DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?" + COLUMN_IDENTIFIER_REGEX,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract the old and new name of
     * {@code RENAME COLUMN [IF EXISTS] old TO new}.
     */
    static final Pattern RENAME_COLUMN_PATTERN = Pattern.compile(
            "RENAME\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?" + COLUMN_IDENTIFIER_REGEX
                    + "\\s+TO\\s+" + COLUMN_IDENTIFIER_REGEX,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract the column of {@code MODIFY COLUMN [IF EXISTS] name <type> ...}; the
     * type, when there is one, is read from the text that follows the match.
     */
    static final Pattern MODIFY_COLUMN_PATTERN = Pattern.compile(
            "MODIFY\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?" + COLUMN_IDENTIFIER_REGEX,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract the target of {@code ALTER TABLE [IF EXISTS] [db.]table}. Each identifier
     * is backtick-quoted (a doubled backtick is a literal one) or a bare word; the database is
     * optional.
     */
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(`(?:[^`]|``)+`|[^\\s`.,;]+)"
                    + "(?:\\.(`(?:[^`]|``)+`|[^\\s`.,;]+))?",
            Pattern.CASE_INSENSITIVE
    );

    /** Keywords that can follow a column name in place of a type. */
    private static final Pattern TYPE_STOP_WORD = Pattern.compile(
            "(?:DEFAULT|MATERIALIZED|ALIAS|EPHEMERAL|COMMENT|CODEC|TTL|SETTINGS|AFTER|FIRST|REMOVE|MODIFY)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches an {@code IF EXISTS} guard inside a RENAME/MODIFY COLUMN clause. */
    private static final Pattern IF_EXISTS_PATTERN = Pattern.compile(
            "\\bIF\\s+EXISTS\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final long timeoutMs;
    private final long pollIntervalMs;

    /**
     * Thrown when a DDL's schema change is not visible in {@code system.columns} within the
     * timeout (or the wait is interrupted). The connector must not go on writing rows against a
     * ClickHouse schema that does not match the source, so this is fail-closed.
     */
    public static class SchemaVisibilityTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SchemaVisibilityTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** What a DDL leaves true of one column once the whole statement has been applied. */
    static final class ColumnExpectation {
        /** True when the column must exist afterwards, false when it must be gone. */
        final boolean present;
        /** The type the column must have afterwards, or null when it is not checked. */
        final String type;
        /**
         * True when the clause carried {@code IF EXISTS}, so ClickHouse treats an absent column
         * (e.g. during replay after a subsequent drop) as a no-op rather than a missing change.
         */
        final boolean ifExists;

        ColumnExpectation(boolean present, String type) {
            this(present, type, false);
        }

        ColumnExpectation(boolean present, String type, boolean ifExists) {
            this.present = present;
            this.type = type;
            this.ifExists = ifExists;
        }

        @Override
        public String toString() {
            if (!present) {
                return "absent";
            }
            String base = "present" + (type == null ? "" : " as " + type);
            return ifExists ? base + " (if exists)" : base;
        }
    }

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
     * Lenient form of {@link #awaitSchemaVisibility(Connection, String, String)}: a timeout is
     * logged and reported as {@code false} instead of thrown. Callers that must not write
     * against a stale schema use the strict form.
     *
     * @param conn An active JDBC connection to the ClickHouse server.
     * @param ddl  The DDL statement that was just executed.
     * @return true when the change is visible (or there is nothing to wait for); false on timeout
     */
    public boolean waitForSchemaVisibility(Connection conn, String ddl) {
        return waitForSchemaVisibility(conn, ddl, null);
    }

    /**
     * Lenient form with a session database for an unqualified table; see
     * {@link #waitForSchemaVisibility(Connection, String)}.
     */
    public boolean waitForSchemaVisibility(Connection conn, String ddl, String defaultDatabase) {
        try {
            awaitSchemaVisibility(conn, ddl, defaultDatabase);
            return true;
        } catch (SchemaVisibilityTimeoutException e) {
            log.warn("{} Proceeding anyway (lenient wait) -- data loss may occur for these columns.",
                    e.getMessage());
            return false;
        }
    }

    /** Strict wait without a session database; see {@link #awaitSchemaVisibility(Connection, String, String)}. */
    public void awaitSchemaVisibility(Connection conn, String ddl) {
        awaitSchemaVisibility(conn, ddl, null);
    }

    /**
     * Waits until the column changes of an {@code ALTER TABLE} are visible in
     * {@code system.columns}, and FAILS when they are not within the timeout.
     *
     * <p>The statement's NET effect per column is awaited: a column added, dropped, renamed
     * (the old name gone, the new one there) or modified (present, with the new type when the
     * statement declares one). A column dropped and re-added by one statement is expected
     * PRESENT, not both absent and present. A DDL with no column clause waits a short fixed
     * delay.</p>
     *
     * @param conn            An active JDBC connection to the ClickHouse server.
     * @param ddl             The DDL statement that was just executed.
     * @param defaultDatabase The database of an unqualified {@code ALTER TABLE tbl}; may be null,
     *                        in which case such a statement cannot be checked and is skipped.
     * @throws SchemaVisibilityTimeoutException when the change is not visible in time, or the wait
     *                                          is interrupted
     */
    public void awaitSchemaVisibility(Connection conn, String ddl, String defaultDatabase) {
        if (conn == null || ddl == null || ddl.isEmpty()) {
            return;
        }

        String[] target = parseAlterTarget(ddl, defaultDatabase);
        if (target == null) {
            return;
        }
        String database = target[0];
        String table = target[1];

        Map<String, ColumnExpectation> expected = expectedColumnState(ddl);
        if (expected.isEmpty()) {
            log.info("DDL does not contain a parseable column change; " +
                    "applying {}ms safety delay for: {}", pollIntervalMs * 5, ddl);
            try {
                Thread.sleep(pollIntervalMs * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        awaitColumns(conn, database, table, expected);
    }

    /**
     * The database and table an {@code ALTER TABLE} names, unquoted; an unqualified table's
     * database falls back to {@code defaultDatabase}.
     *
     * @return {database, table}, or null when the statement is not an ALTER TABLE or no
     *         database can be determined
     */
    static String[] parseAlterTarget(String ddl, String defaultDatabase) {
        Matcher m = ALTER_TABLE_PATTERN.matcher(ddl);
        if (!m.find()) {
            return null;
        }
        String first = unquote(m.group(1));
        String second = m.group(2) == null ? null : unquote(m.group(2));
        String database = second == null ? defaultDatabase : first;
        String table = second == null ? first : second;
        if (database == null || database.isEmpty()) {
            log.warn("ALTER TABLE {} names no database and no session database is known; "
                    + "its schema change cannot be awaited: {}", table, ddl);
            return null;
        }
        return new String[]{database, table};
    }

    private static String unquote(String identifier) {
        if (identifier.length() >= 2 && identifier.startsWith("`") && identifier.endsWith("`")) {
            return identifier.substring(1, identifier.length() - 1).replace("``", "`");
        }
        return identifier;
    }

    /**
     * What the statement leaves true of each column it touches, applying its clauses in the
     * order they were written so that the LAST clause about a column wins.
     */
    static Map<String, ColumnExpectation> expectedColumnState(String ddl) {
        // position in the statement -> the action it performs; ordered by position
        TreeMap<Integer, Runnable> actions = new TreeMap<>();
        Map<String, ColumnExpectation> state = new LinkedHashMap<>();

        Matcher add = ADD_COLUMN_PATTERN.matcher(ddl);
        while (add.find()) {
            String name = unquote(add.group(1));
            actions.put(add.start(), () -> state.put(name, new ColumnExpectation(true, null)));
        }
        Matcher drop = DROP_COLUMN_PATTERN.matcher(ddl);
        while (drop.find()) {
            String name = unquote(drop.group(1));
            actions.put(drop.start(), () -> state.put(name, new ColumnExpectation(false, null)));
        }
        Matcher rename = RENAME_COLUMN_PATTERN.matcher(ddl);
        while (rename.find()) {
            String oldName = unquote(rename.group(1));
            String newName = unquote(rename.group(2));
            boolean ifExists = IF_EXISTS_PATTERN.matcher(rename.group(0)).find();
            actions.put(rename.start(), () -> {
                state.put(oldName, new ColumnExpectation(false, null));
                // With IF EXISTS, ClickHouse is a no-op if oldName was already renamed or dropped
                // (for example on crash replay). Waiting for oldName to be absent still blocks
                // until an actual rename propagates without timing out on a replay no-op.
                if (!ifExists) {
                    state.put(newName, new ColumnExpectation(true, null));
                }
            });
        }
        Matcher modify = MODIFY_COLUMN_PATTERN.matcher(ddl);
        while (modify.find()) {
            String name = unquote(modify.group(1));
            String type = typeAfter(ddl, modify.end());
            boolean ifExists = IF_EXISTS_PATTERN.matcher(modify.group(0)).find();
            actions.put(modify.start(), () -> state.put(name, new ColumnExpectation(true, type, ifExists)));
        }
        actions.values().forEach(Runnable::run);
        return state;
    }

    /**
     * The type token that follows a column name, or null when the clause carries none
     * ({@code MODIFY COLUMN c DEFAULT 1}, {@code REMOVE COMMENT}, ...). Parentheses and quotes are
     * kept together, so {@code Nullable(DateTime64(0, 'UTC'))} is one token.
     */
    static String typeAfter(String ddl, int from) {
        int i = from;
        while (i < ddl.length() && Character.isWhitespace(ddl.charAt(i))) {
            i++;
        }
        if (i >= ddl.length() || TYPE_STOP_WORD.matcher(ddl).region(i, ddl.length()).lookingAt()) {
            return null;
        }
        int depth = 0;
        char quote = 0;
        int start = i;
        for (; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (Character.isWhitespace(c) || c == ',' || c == ';')) {
                break;
            }
        }
        String type = ddl.substring(start, i).trim();
        return type.isEmpty() ? null : type;
    }

    /** Lower-cased with every whitespace removed: ClickHouse re-renders spacing inside a type. */
    private static String normalizeType(String type) {
        return type.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean satisfied(Map<String, ColumnExpectation> expected, Map<String, String> current) {
        for (Map.Entry<String, ColumnExpectation> e : expected.entrySet()) {
            ColumnExpectation want = e.getValue();
            boolean has = current.containsKey(e.getKey());
            if (want.present && want.ifExists && !has) {
                continue;
            }
            if (want.present != has) {
                return false;
            }
            if (want.present && want.type != null
                    && !normalizeType(want.type).equals(normalizeType(String.valueOf(current.get(e.getKey()))))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Polls {@code system.columns} until every expectation holds.
     *
     * @throws SchemaVisibilityTimeoutException on timeout or interrupt
     */
    private void awaitColumns(Connection conn, String database, String table,
                              Map<String, ColumnExpectation> expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean needTypes = expected.values().stream().anyMatch(c -> c.present && c.type != null);
        Exception lastError = null;

        log.info("Waiting for column state in {}.{}: {}", database, table, expected);

        while (System.currentTimeMillis() < deadline) {
            try {
                if (satisfied(expected, queryColumns(conn, database, table, needTypes))) {
                    log.info("Column changes are now visible in {}.{}", database, table);
                    return;
                }
                lastError = null;
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SchemaVisibilityTimeoutException(String.format(
                        "Interrupted while waiting for the schema change of %s.%s to become visible: %s",
                        database, table, expected), e);
            } catch (Exception e) {
                lastError = e;
                log.warn("Error polling system.columns for {}.{}: {}",
                        database, table, e.getMessage());
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SchemaVisibilityTimeoutException(String.format(
                            "Interrupted while waiting for the schema change of %s.%s to become visible: %s",
                            database, table, expected), ie);
                }
            }
        }

        throw new SchemaVisibilityTimeoutException(String.format(
                "Timeout (%dms) waiting for the schema change of %s.%s to become visible in "
                        + "system.columns (expected %s). Writing rows now would use a stale ClickHouse "
                        + "schema, so the DDL is failed. If a name here is a SQL keyword such as IF, "
                        + "the DDL was misparsed.",
                timeoutMs, database, table, expected), lastError);
    }

    /** Escapes a value for a single-quoted ClickHouse string literal. */
    static String escapeSqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * Reads the table's columns (name to type; the type is null unless requested).
     */
    private Map<String, String> queryColumns(Connection conn, String database, String table,
                                             boolean withTypes) throws Exception {
        Map<String, String> columns = new HashMap<>();
        String sql = String.format(
                "SELECT name%s FROM system.columns WHERE database = '%s' AND table = '%s'",
                withTypes ? ", type" : "", escapeSqlLiteral(database), escapeSqlLiteral(table));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                columns.put(rs.getString(1), withTypes ? rs.getString(2) : null);
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
            columns.add(unquote(matcher.group(1)));
        }
        return columns;
    }
}
