package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.document.DocumentReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Makes the ClickHouse table behind Debezium's {@code JdbcSchemaHistory} safe for
 * the record-part format Debezium 3.3 writes, before the engine starts
 * (spec 09.05).
 *
 * <p><b>Why.</b> A schema-history record longer than 65000 characters is stored
 * as several rows ({@code history_data_seq} 0, 1, 2, ...). Debezium 3.1.3 gave
 * each part its own random {@code id} and recovered each row as its own JSON
 * document, so the first restart after a ~200-column table was captured failed
 * with {@code JsonEOFException} (issue #1450). Debezium 3.3 (DBZ-8979) gives
 * every part of one record the SAME {@code id}, {@code record_insert_ts} and
 * {@code record_insert_seq} and concatenates them on recovery. On the layout
 * every deployment of this connector was created with --
 * {@code ReplacingMergeTree(record_insert_seq) ORDER BY id} -- those parts share
 * the sorting key AND the version, so ClickHouse keeps one of them (inside the
 * insert block or at the next merge) and the record is destroyed. The sorting
 * key must therefore include {@code history_data_seq}; ClickHouse cannot add an
 * existing column to a sorting key, so an existing table is copied and swapped.
 * Rows written by 3.1.3 for an oversized record (continuation parts whose
 * {@code id} has no part 0) cannot be recovered by 3.3 either and are re-keyed
 * onto their first part during the same copy.</p>
 *
 * <p><b>What it does.</b> For a ClickHouse-backed {@code JdbcSchemaHistory}:
 * creates the table from the configured DDL when it is missing (exactly what
 * Debezium would do), reads its engine, key and database engine, and decides:
 * leave it alone, migrate it (non-replicated MergeTree family in an Atomic
 * database: copy into a new table keyed {@code (<key>, history_data_seq)},
 * verify, {@code EXCHANGE TABLES}, keep the old copy under a backup name), or
 * refuse to start with the manual procedure. It never drops or truncates
 * anything: the pre-migration table is kept as
 * {@code <table>_pre_dbz33_<timestamp>} for the operator.</p>
 *
 * <p>A ClickHouse it cannot reach is waited for (every {@value #RETRY_INTERVAL_MS}
 * ms, up to {@value #DEFAULT_WAIT_MS} ms by default) so a slow ClickHouse start
 * still works; if it stays unreachable the start is refused -- the engine never
 * starts against a store whose layout was not verified.</p>
 */
public final class SchemaHistoryStorePreflight {

    private static final Logger log = LogManager.getLogger(SchemaHistoryStorePreflight.class);

    static final String HISTORY_CLASS = "schema.history.internal";
    static final String JDBC_HISTORY = "io.debezium.storage.jdbc.history.JdbcSchemaHistory";
    private static final String PREFIX = "schema.history.internal.jdbc.";
    static final String[] URL_KEYS = {PREFIX + "connection.url", PREFIX + "url"};
    static final String[] USER_KEYS = {PREFIX + "connection.user", PREFIX + "user"};
    static final String[] PASSWORD_KEYS = {PREFIX + "connection.password", PREFIX + "password"};
    static final String[] TABLE_NAME_KEYS = {PREFIX + "table.name", PREFIX + "schema.history.table.name"};
    static final String[] TABLE_DDL_KEYS = {PREFIX + "table.ddl", PREFIX + "schema.history.table.ddl"};
    /** Debezium's JdbcSchemaHistoryConfig default table name. */
    static final String DEFAULT_TABLE_NAME = "debezium_database_history";
    /**
     * Debezium 3.7.0 JdbcSchemaHistoryConfig default DDL (the table it would create
     * when no DDL is configured); 3.7 itself added the (id, history_data_seq) key.
     */
    static final String DEFAULT_TABLE_DDL = "CREATE TABLE %s(id VARCHAR(36) NOT NULL,history_data VARCHAR(65000),"
            + "history_data_seq INTEGER,record_insert_ts TIMESTAMP NOT NULL,record_insert_seq INTEGER NOT NULL,"
            + "PRIMARY KEY (id, history_data_seq))";

    static final String SEQ = "history_data_seq";
    static final String ID = "id";

    /** Engines that merge rows with an equal sorting key into one. */
    static final Set<String> COLLAPSING_ENGINES = Set.of(
            "ReplacingMergeTree", "CollapsingMergeTree", "VersionedCollapsingMergeTree",
            "AggregatingMergeTree", "SummingMergeTree", "CoalescingMergeTree", "GraphiteMergeTree");
    /** Key-value engines: one row per primary-key value. */
    static final Set<String> KEY_VALUE_ENGINES = Set.of("KeeperMap", "EmbeddedRocksDB", "Redis");

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private SchemaHistoryStorePreflight() {
    }

    /** What the preflight decided for the table it found. */
    enum Action { NONE, MIGRATE, REFUSE }

    /** The live facts the decision is made from (read from system tables). */
    static final class TableState {
        String database;
        String table;
        String databaseEngine;
        String engine;
        String engineFull;
        String sortingKey;
        String primaryKey;
        /** Ids that have continuation parts but no part 0 (3.1.3-format oversized records). */
        long legacyRecordIds;
    }

    /** The decision: the action plus, for MIGRATE, the storage clause of the new table. */
    static final class Plan {
        final Action action;
        final String reason;
        final String newEngineFull;
        final boolean rekey;

        Plan(Action action, String reason, String newEngineFull, boolean rekey) {
            this.action = action;
            this.reason = reason;
            this.newEngineFull = newEngineFull;
            this.rekey = rekey;
        }
    }

    /**
     * Runs the preflight against the configured schema-history store.
     *
     * @param props connector properties (read only).
     * @throws IllegalStateException when the store cannot hold Debezium 3.3
     *         records and the connector cannot correct it safely; the message
     *         names the manual procedure.
     */
    public static void apply(Properties props) {
        if (!JDBC_HISTORY.equals(trim(props.getProperty(HISTORY_CLASS)))) {
            return;
        }
        String url = first(props, URL_KEYS);
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("jdbc:clickhouse")) {
            return;
        }
        String qualified = first(props, TABLE_NAME_KEYS);
        if (qualified == null) {
            qualified = DEFAULT_TABLE_NAME;
        }
        String[] parts = qualified.split("\\.");
        // Debezium formats the DDL, SELECT and INSERT with the bare table name and
        // runs them in the connection's database (JdbcSchemaHistoryConfig).
        String bareName = parts.length == 2 ? parts[1] : qualified;
        String ddl = first(props, TABLE_DDL_KEYS);
        if (ddl == null) {
            ddl = DEFAULT_TABLE_DDL;
        }
        // ClickHouse may still be starting (the connector tolerates a slow
        // ClickHouse at start), but the engine must never touch an unverified
        // store: an unreachable ClickHouse is waited for, and if it stays
        // unreachable the start is refused rather than skipped -- a check skipped
        // on a transient outage would let the collapsing layout through.
        long waitMs = waitMs(props);
        long deadline = System.currentTimeMillis() + waitMs;
        String user = nullToEmpty(first(props, USER_KEYS));
        String password = nullToEmpty(first(props, PASSWORD_KEYS));
        Connection conn = null;
        String database = null;
        SQLException lastFailure = null;
        boolean databaseCreated = false;
        while (database == null) {
            try {
                conn = DriverManager.getConnection(url, user, password);
                // The ClickHouse JDBC driver connects lazily: an unreachable
                // server only fails on the first statement.
                database = scalar(conn, "SELECT currentDatabase()");
            } catch (SQLException e) {
                lastFailure = e;
                if (conn != null) {
                    closeQuietly(conn);
                    conn = null;
                }
                // On a first start the URL's database (by convention the one the
                // offset store lives in) does not exist yet: the connector creates
                // it later in setup() (createDatabaseForDebeziumStorage), after this
                // preflight. Create it here, the same way, instead of waiting for
                // it; ClickHouse is reachable, it answered UNKNOWN_DATABASE.
                String urlDatabase = urlDatabase(url);
                if (!databaseCreated && urlDatabase != null && isUnknownDatabase(e)) {
                    databaseCreated = true;
                    try {
                        createDatabase(serverUrl(url), user, password, urlDatabase);
                        continue;
                    } catch (SQLException ce) {
                        // Not swallowed: it becomes the failure the wait below
                        // reports, and the start is refused if it persists.
                        lastFailure = ce;
                        e = ce;
                        log.warn("Schema-history store preflight (spec 09.05) could not create database {} "
                                + "named in {}: {}", urlDatabase, url, ce.getMessage());
                    }
                }
                if (System.currentTimeMillis() + RETRY_INTERVAL_MS > deadline) {
                    throw new IllegalStateException(String.format(
                            "Schema-history store preflight (spec 09.05) could not reach %s within %d ms; "
                                    + "refusing to start with the layout of %s unverified. Cause: %s",
                            url, waitMs, qualified, e.getMessage()), e);
                }
                log.warn("Schema-history store preflight (spec 09.05) cannot reach {} yet ({}); retrying "
                        + "in {} ms, giving up {} ms after start.", url, e.getMessage(), RETRY_INTERVAL_MS, waitMs);
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Schema-history store preflight interrupted while waiting "
                            + "for " + url, lastFailure);
                }
            }
        }
        try {
            run(conn, database, bareName, ddl);
        } catch (SQLException e) {
            throw new IllegalStateException(String.format(
                    "Schema-history store preflight (spec 09.05) failed on %s for table %s: %s. "
                            + "Refusing to start: Debezium 3.3 stores every part of an oversized "
                            + "schema-history record under one id, and the layout of this table "
                            + "could not be verified or corrected. %s",
                    url, qualified, e.getMessage(), manualProcedure(qualified)), e);
        } finally {
            closeQuietly(conn);
        }
    }

    /** Pause between attempts to reach an unreachable ClickHouse. */
    static final long RETRY_INTERVAL_MS = 5_000L;
    /** Connector-internal: how long to wait for an unreachable ClickHouse (default 5 minutes). */
    static final String WAIT_MS_PROPERTY = "clickhouse.sink.internal.schema.history.preflight.wait.ms";
    static final long DEFAULT_WAIT_MS = 300_000L;

    static long waitMs(Properties props) {
        String v = trim(props.getProperty(WAIT_MS_PROPERTY));
        if (v == null || v.isEmpty()) {
            return DEFAULT_WAIT_MS;
        }
        try {
            return Math.max(0L, Long.parseLong(v));
        } catch (NumberFormatException e) {
            return DEFAULT_WAIT_MS;
        }
    }

    /** ClickHouse error code for a database that does not exist. */
    static final int UNKNOWN_DATABASE_CODE = 81;

    /**
     * Whether a failure (or any of its causes) is ClickHouse's UNKNOWN_DATABASE.
     */
    static boolean isUnknownDatabase(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException && ((SQLException) t).getErrorCode() == UNKNOWN_DATABASE_CODE) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("UNKNOWN_DATABASE") || m.contains("Code: 81."))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The database a {@code jdbc:clickhouse} URL selects in its path
     * ({@code jdbc:clickhouse://host:8123/db?x=y} gives {@code db}), or null.
     */
    static String urlDatabase(String url) {
        int path = pathStart(url);
        if (path < 0) {
            return null;
        }
        int end = url.length();
        for (char c : new char[] {'?', ';', '#'}) {
            int i = url.indexOf(c, path);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String db = url.substring(path + 1, end);
        return db.isEmpty() || db.contains("/") ? null : db;
    }

    /** The same URL without the database path, for a connection that can create it. */
    static String serverUrl(String url) {
        int path = pathStart(url);
        if (path < 0) {
            return url;
        }
        String db = urlDatabase(url);
        return db == null ? url : url.substring(0, path) + url.substring(path + 1 + db.length());
    }

    /** Index of the '/' that starts the path after {@code ://host[:port]}, or -1. */
    private static int pathStart(String url) {
        if (url == null) {
            return -1;
        }
        int scheme = url.indexOf("://");
        return scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
    }

    /**
     * Creates the URL's database the way {@code createDatabaseForDebeziumStorage}
     * later would ({@code CREATE DATABASE IF NOT EXISTS}, idempotent).
     */
    private static void createDatabase(String serverUrl, String user, String password, String database)
            throws SQLException {
        String sql = "CREATE DATABASE IF NOT EXISTS `" + database.replace("`", "``") + "`";
        log.info("Schema-history store preflight (spec 09.05): database {} does not exist yet (first start); "
                + "running [{}] before verifying the schema-history table.", database, sql);
        try (Connection c = DriverManager.getConnection(serverUrl, user, password)) {
            execute(c, sql);
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // nothing to do: the connection is discarded either way
        }
    }

    static void run(Connection conn, String database, String bareName, String ddl) throws SQLException {
        TableState state = readState(conn, database, bareName);
        if (state == null) {
            // Exactly what Debezium's initializeStorage() would execute.
            log.info("Schema-history table {}.{} does not exist; creating it from the configured DDL "
                    + "so its layout can be verified before the first record is written (spec 09.05).",
                    database, bareName);
            execute(conn, String.format(ddl, bareName));
            state = readState(conn, database, bareName);
            if (state == null) {
                throw new SQLException("table " + database + "." + bareName
                        + " still does not exist after executing the configured DDL");
            }
        }
        state.legacyRecordIds = countLegacyRecordIds(conn, state);
        Plan plan = plan(state);
        switch (plan.action) {
            case NONE:
                log.info("Schema-history table {}.{} ({}) can hold Debezium 3.3 record parts: {} (spec 09.05).",
                        state.database, state.table, state.engineFull, plan.reason);
                return;
            case REFUSE:
                throw new IllegalStateException(String.format(
                        "Refusing to start: schema-history table %s.%s (%s) %s (spec 09.05). %s",
                        state.database, state.table, state.engineFull, plan.reason,
                        manualProcedure(state.database + "." + state.table)));
            case MIGRATE:
            default:
                migrate(conn, state, plan);
        }
    }

    /**
     * Pure decision over the live facts (unit-tested).
     */
    static Plan plan(TableState s) {
        String base = baseEngine(s.engine);
        List<String> sortingKey = keyColumns(s.sortingKey);
        List<String> primaryKey = keyColumns(s.primaryKey);
        boolean rekey = s.legacyRecordIds > 0;
        if (KEY_VALUE_ENGINES.contains(base)) {
            if (primaryKey.contains(SEQ) && primaryKey.contains(ID)) {
                return rekey
                        ? new Plan(Action.REFUSE, "holds " + s.legacyRecordIds + " Debezium 3.1.3 "
                        + "oversized record(s) that Debezium 3.3 cannot recover, and the connector "
                        + "migrates only MergeTree-family tables", null, false)
                        : new Plan(Action.NONE, "primary key includes id and history_data_seq", null, false);
            }
            return new Plan(Action.REFUSE, "is a key-value table keyed by (" + s.primaryKey + "): the parts "
                    + "of one record share their id and would overwrite each other", null, false);
        }
        boolean collapsing = COLLAPSING_ENGINES.contains(base);
        boolean mergeTreeFamily = base.endsWith("MergeTree");
        boolean keySafe = !collapsing || (sortingKey.contains(ID) && sortingKey.contains(SEQ));
        if (keySafe && !rekey) {
            return new Plan(Action.NONE, collapsing ? "sorting key includes id and history_data_seq"
                    : "engine " + s.engine + " does not merge rows by key", null, false);
        }
        String problem = !keySafe
                ? "merges rows with an equal sorting key (" + s.sortingKey + ") and the parts of one "
                + "record share their id and version"
                : "holds " + s.legacyRecordIds + " Debezium 3.1.3 oversized record(s) (continuation "
                + "parts without a part 0) that Debezium 3.3 cannot recover";
        if (!mergeTreeFamily) {
            return new Plan(Action.REFUSE, problem + "; the connector migrates only MergeTree-family "
                    + "tables", null, false);
        }
        if (!base.equals(s.engine)) {
            return new Plan(Action.REFUSE, problem + "; engine " + s.engine + " is replicated or "
                    + "shared, which the connector does not migrate automatically", null, false);
        }
        if (!"Atomic".equals(s.databaseEngine)) {
            return new Plan(Action.REFUSE, problem + "; database " + s.database + " uses the "
                    + s.databaseEngine + " engine, which has no atomic EXCHANGE TABLES", null, false);
        }
        String newEngineFull = s.engineFull;
        if (!keySafe) {
            newEngineFull = withSeqInSortingKey(s.engineFull, s.sortingKey, sortingKey);
            if (newEngineFull == null) {
                return new Plan(Action.REFUSE, problem + "; its ORDER BY clause could not be located "
                        + "in the engine definition", null, false);
            }
        }
        return new Plan(Action.MIGRATE, problem, newEngineFull, rekey);
    }

    /**
     * Returns the storage clause with {@code history_data_seq} appended to the
     * sorting key, or null when the {@code ORDER BY} text is not found exactly once.
     */
    static String withSeqInSortingKey(String engineFull, String sortingKeyText, List<String> sortingKey) {
        if (engineFull == null || sortingKeyText == null) {
            return null;
        }
        List<String> newKey = new ArrayList<>(sortingKey.isEmpty() ? List.of() : sortingKey);
        if (!newKey.contains(ID)) {
            newKey.add(0, ID);
        }
        newKey.add(SEQ);
        String replacement = "ORDER BY (" + String.join(", ", newKey) + ")";
        String[] candidates = sortingKey.size() > 1
                ? new String[] {"ORDER BY (" + sortingKeyText + ")"}
                : new String[] {"ORDER BY " + sortingKeyText, "ORDER BY (" + sortingKeyText + ")",
                "ORDER BY tuple()"};
        for (String candidate : candidates) {
            int at = indexOfClause(engineFull, candidate);
            if (at >= 0 && indexOfClause(engineFull.substring(at + candidate.length()), candidate) < 0) {
                return engineFull.substring(0, at) + replacement + engineFull.substring(at + candidate.length());
            }
        }
        return null;
    }

    /** Index of {@code clause} followed by end of text or a space, else -1. */
    private static int indexOfClause(String text, String clause) {
        int from = 0;
        while (true) {
            int at = text.indexOf(clause, from);
            if (at < 0) {
                return -1;
            }
            int end = at + clause.length();
            if (end == text.length() || text.charAt(end) == ' ') {
                return at;
            }
            from = at + 1;
        }
    }

    static String baseEngine(String engine) {
        if (engine == null) {
            return "";
        }
        if (engine.startsWith("Replicated")) {
            return engine.substring("Replicated".length());
        }
        if (engine.startsWith("Shared")) {
            return engine.substring("Shared".length());
        }
        return engine;
    }

    static List<String> keyColumns(String key) {
        List<String> cols = new ArrayList<>();
        if (key == null || key.isBlank()) {
            return cols;
        }
        for (String c : key.split(",")) {
            String t = c.trim().replace("`", "");
            if (!t.isEmpty()) {
                cols.add(t);
            }
        }
        return cols;
    }

    private static TableState readState(Connection conn, String database, String table) throws SQLException {
        String sql = "SELECT t.engine, t.engine_full, t.sorting_key, t.primary_key, d.engine "
                + "FROM system.tables AS t INNER JOIN system.databases AS d ON d.name = t.database "
                + "WHERE t.database = " + literal(database) + " AND t.name = " + literal(table);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            TableState s = new TableState();
            s.database = database;
            s.table = table;
            s.engine = rs.getString(1);
            s.engineFull = rs.getString(2);
            s.sortingKey = rs.getString(3);
            s.primaryKey = rs.getString(4);
            s.databaseEngine = rs.getString(5);
            return s;
        }
    }

    /** Ids with continuation parts but no part 0: 3.1.3-format oversized records. */
    static long countLegacyRecordIds(Connection conn, TableState s) throws SQLException {
        String t = qualified(s.database, s.table);
        // I14-scan-allowed: spec 09.05 section 3.2 -- one-time read of the
        // connector's own schema-history store at start-up, not replicated data;
        // the first count reads only the history_data_seq column.
        long continuation = longScalar(conn, "SELECT count() FROM " + t + " WHERE history_data_seq > 0");
        if (continuation == 0) {
            return 0;
        }
        // I14-scan-allowed: spec 09.05 section 3.2 -- bounded by the ids that
        // have continuation parts.
        return longScalar(conn, "SELECT countIf(has_first = 0) FROM (SELECT id, max(history_data_seq = 0) "
                + "AS has_first FROM " + t + " WHERE id IN (SELECT id FROM " + t
                + " WHERE history_data_seq > 0) GROUP BY id)");
    }

    private static void migrate(Connection conn, TableState s, Plan plan) throws SQLException {
        String stamp = LocalDateTime.now().format(SUFFIX);
        String oldT = qualified(s.database, s.table);
        String newName = s.table + "_dbz33_new_" + stamp;
        String backupName = s.table + "_pre_dbz33_" + stamp;
        String newT = qualified(s.database, newName);
        String backupT = qualified(s.database, backupName);
        log.warn("Schema-history table {} ({}) {}. Migrating it before the engine starts (spec 09.05): "
                        + "copy into {} ({}), verify, EXCHANGE, keep the old copy as {}.",
                oldT, s.engineFull, plan.reason, newT, plan.newEngineFull, backupT);
        execute(conn, "CREATE TABLE " + newT + " AS " + oldT + " ENGINE = " + plan.newEngineFull);
        String cols = "id, history_data, history_data_seq, record_insert_ts, record_insert_seq";
        if (plan.rekey) {
            execute(conn, rekeyInsert(newT, oldT));
        } else {
            execute(conn, "INSERT INTO " + newT + " (" + cols + ") SELECT " + cols + " FROM " + oldT);
        }
        verifyCopy(conn, oldT, newT);
        long records = verifyRecordsParse(conn, newT);
        execute(conn, "EXCHANGE TABLES " + oldT + " AND " + newT);
        // The swap is done and atomic: the configured name now holds the verified
        // copy. Renaming the previous table to its backup name is cosmetic, so a
        // failure here is reported, not fatal.
        try {
            execute(conn, "RENAME TABLE " + newT + " TO " + backupT);
        } catch (SQLException e) {
            backupT = newT;
            log.warn("Schema-history migration: the PRE-MIGRATION table could not be renamed to its backup "
                    + "name {} and stays under {} ({}). It holds the pre-migration rows after the EXCHANGE; "
                    + "do not mistake it for a scratch copy -- keep it until the connector has restarted "
                    + "cleanly.", qualified(s.database, backupName), newT, e.getMessage());
        }
        TableState after = readState(conn, s.database, s.table);
        log.warn("Schema-history table {} migrated (spec 09.05): now {} with {} record(s), every one "
                        + "parsed as Debezium 3.3 recovers it{}. The previous table is kept as {}; drop "
                        + "it once the connector has restarted cleanly.",
                oldT, after == null ? "?" : after.engineFull, records,
                plan.rekey ? "; Debezium 3.1.3 oversized records were re-keyed onto their first part" : "",
                backupT);
    }

    /**
     * Copy that re-keys every part of a 3.1.3-format record onto the id,
     * record_insert_ts and record_insert_seq of its first part. A record starts at
     * each part 0 in Debezium's recovery order.
     */
    static String rekeyInsert(String newT, String oldT) {
        String order = "record_insert_ts, record_insert_seq, id, history_data_seq";
        return "INSERT INTO " + newT + " (id, history_data, history_data_seq, record_insert_ts, record_insert_seq) "
                + "SELECT m.new_id, o.history_data, o.history_data_seq, m.new_ts, m.new_seq FROM " + oldT + " AS o "
                + "INNER JOIN (SELECT id, history_data_seq, first_value(id) OVER w AS new_id, "
                + "first_value(record_insert_ts) OVER w AS new_ts, first_value(record_insert_seq) OVER w AS new_seq "
                + "FROM (SELECT id, history_data_seq, record_insert_ts, record_insert_seq, "
                + "sum(history_data_seq = 0) OVER (ORDER BY " + order
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS grp FROM " + oldT + ") "
                + "WINDOW w AS (PARTITION BY grp ORDER BY " + order
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)) AS m "
                + "ON o.id = m.id AND o.history_data_seq = m.history_data_seq";
    }

    private static void verifyCopy(Connection conn, String oldT, String newT) throws SQLException {
        // I14-scan-allowed: spec 09.05 section 3.3 -- one-time comparison of the
        // connector's own schema-history store with its copy during migration.
        String sql = "SELECT uniqExact(id, history_data_seq), countIf(history_data_seq = 0), "
                + "sum(length(history_data)) FROM %s";
        long[] before = longs(conn, String.format(sql, oldT));
        long[] after = longs(conn, String.format(sql, newT));
        if (!Arrays.equals(before, after)) {
            throw new SQLException(String.format("copy verification failed: %s has (parts=%d, records=%d, "
                    + "bytes=%d) but the copy %s has (parts=%d, records=%d, bytes=%d); the copy is left in "
                    + "place for inspection and the original is untouched", oldT, before[0], before[1],
                    before[2], newT, after[0], after[1], after[2]));
        }
    }

    /**
     * Reads the copy exactly as Debezium 3.3 recovers it (default table.select
     * order, consecutive rows of one id concatenated) and parses every record.
     *
     * @return the number of records parsed.
     */
    static long verifyRecordsParse(Connection conn, String table) throws SQLException {
        DocumentReader reader = DocumentReader.defaultReader();
        long records = 0;
        String sql = "SELECT id, history_data FROM " + table
                + " ORDER BY record_insert_ts, record_insert_seq, id, history_data_seq";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            String currentId = null;
            while (rs.next()) {
                String id = rs.getString(1);
                String data = rs.getString(2);
                if (currentId != null && !currentId.equals(id) && sb.length() > 0) {
                    parseOrThrow(reader, sb, currentId, table);
                    records++;
                    sb.setLength(0);
                }
                sb.append(data);
                currentId = id;
            }
            if (sb.length() > 0) {
                parseOrThrow(reader, sb, currentId, table);
                records++;
            }
        }
        return records;
    }

    private static void parseOrThrow(DocumentReader reader, StringBuilder sb, String id, String table)
            throws SQLException {
        try {
            reader.read(sb.toString());
        } catch (Exception e) {
            throw new SQLException("schema-history record " + id + " in " + table + " (" + sb.length()
                    + " chars) does not parse as Debezium 3.3 would recover it: " + e.getMessage()
                    + "; the copy is left in place for inspection and the original is untouched", e);
        }
    }

    static String manualProcedure(String table) {
        return "Manual procedure: stop the connector; CREATE TABLE <db>.<new> AS " + table
                + " ENGINE = <same engine> ORDER BY (id, history_data_seq) (ON CLUSTER and a new "
                + "replication path for a Replicated engine); INSERT INTO <db>.<new> SELECT * FROM "
                + table + "; compare uniqExact(id, history_data_seq) and sum(length(history_data)) on "
                + "both; EXCHANGE TABLES " + table + " AND <db>.<new>; start the connector. Rows "
                + "written by Debezium 3.1.3 for an oversized record (history_data_seq > 0 under an "
                + "id that has no part 0) must also be re-keyed onto the id of their part 0; see "
                + "spec 09.05 section 3.3 for the statement.";
    }

    private static String qualified(String db, String table) {
        return "`" + db.replace("`", "``") + "`.`" + table.replace("`", "``") + "`";
    }

    private static String literal(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("no row from: " + sql);
            }
            return rs.getString(1);
        }
    }

    private static long longScalar(Connection conn, String sql) throws SQLException {
        return Long.parseLong(scalar(conn, sql));
    }

    private static long[] longs(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("no row from: " + sql);
            }
            int n = rs.getMetaData().getColumnCount();
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = rs.getLong(i + 1);
            }
            return out;
        }
    }

    private static String first(Properties props, String[] keys) {
        for (String k : keys) {
            String v = trim(props.getProperty(k));
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
