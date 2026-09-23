package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds a ClickHouse table under a new sorting key when the source changed
 * the table's row identity (Spec 06.09) -- the same clustered-index rebuild
 * MySQL performs for {@code ADD PRIMARY KEY} / {@code DROP PRIMARY KEY}. This
 * class is the <b>swap phase</b> (§3.3.1): inside the DDL barrier it creates
 * the rebuilt definition empty and exchanges it into place with metadata-only
 * statements, then hands the copy of the rows to {@link PrimaryKeyBackfill},
 * which runs it online afterwards (§3.3.2) while replication of this and every
 * other table continues.
 *
 * <p>{@link #swap} is invoked by {@code DebeziumChangeEventCapture.performDDLOperation}
 * right after the statement's other clauses have been applied
 * ({@code executeDDL}) and before the cache invalidation, with the plan
 * {@code MySqlDDLParserListenerImpl.enforcePrimaryKeyPolicy} produced. Every
 * statement of the swap runs on the writer's connection inside the existing
 * barrier (Invariant I5); every failure of the swap is a
 * {@link DDLReplicationException} (Invariant I9) so no offset is committed
 * past the DDL. The swap never runs an {@code INSERT} and never opens the
 * source.</p>
 *
 * <p>Values only the source knows -- an {@code AUTO_INCREMENT} column the
 * statement added -- are read by the backfill from MySQL with a single
 * read-only {@code SELECT} keyed by the old identity
 * ({@link PrimaryKeyBackfill#loadSourceKeyMap}, Spec 06.09 §3.4); the connector
 * never re-derives them (Invariant I6) and never writes to its source
 * ({@link KeylessTablePreflight#assertReadOnlySql}).</p>
 */
public final class PrimaryKeyRebuild {

    private static final Logger log = LogManager.getLogger(PrimaryKeyRebuild.class);

    /** Header of a rendered {@code SHOW CREATE TABLE}: {@code CREATE TABLE db.t} with or without backticks. */
    private static final Pattern CREATE_HEADER = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(`(?:[^`\\\\]|\\\\.)*`|[^\\s.(`]+)\\.(`(?:[^`\\\\]|\\\\.)*`|[^\\s(`]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The {@code UUID 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'} token an Atomic
     * database may render after the table name; the rebuilt table must not
     * reuse it (duplicate UUID).
     */
    private static final Pattern TABLE_UUID = Pattern.compile(
            "^\\s+UUID\\s+'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'",
            Pattern.CASE_INSENSITIVE);

    /** Clause keywords ClickHouse renders on their own line after the column list. */
    private static final Pattern CLAUSE_START = Pattern.compile(
            "^(ENGINE|PARTITION BY|PRIMARY KEY|ORDER BY|SAMPLE BY|TTL|SETTINGS|COMMENT)\\b");

    /** ClickHouse types the source key map binds with {@code setObject} (Spec 06.09 §3.2 item 4). */
    private static final Pattern KEY_MAP_TYPE = Pattern.compile(
            "^(U?Int(8|16|32|64|128|256)|String|FixedString\\(\\d+\\))$");

    private static final Pattern ALLOW_NULLABLE_KEY = Pattern.compile("allow_nullable_key\\s*=\\s*\\d+");

    private PrimaryKeyRebuild() {
    }

    /** One row of {@code system.columns}. */
    static final class ColumnInfo {
        final String name;
        final String type;
        final String defaultKind;

        ColumnInfo(String name, String type, String defaultKind) {
            this.name = name;
            this.type = type;
            this.defaultKind = defaultKind == null ? "" : defaultKind;
        }
    }

    /**
     * The swap phase (Spec 06.09 §3.2 preconditions, §3.3.1): metadata-only
     * statements on the writer's connection inside the DDL barrier. The
     * rebuilt definition {@code S} is created empty and exchanged into place;
     * the copy of the rows from the retired table into it is returned as a
     * {@link PrimaryKeyBackfill.Task} for the caller to schedule. No
     * {@code INSERT} runs here and the source is never opened.
     *
     * <p>Before the exchange the task is recorded as a marker line in the
     * comment of the table about to be retired, so a restart resumes the
     * backfill from that table alone ({@link PrimaryKeyBackfill#resumePending}).</p>
     *
     * @param plan           the plan the translator produced.
     * @param ch             the writer's ClickHouse connection.
     * @param props          the connector properties ({@code disable.drop.truncate}).
     * @param config         the connector configuration ({@code replication.history.enable}).
     * @param sourceDatabase the RAW source database of the DDL event (for the backfill's source read).
     * @param epochMs        names the scratch tables of this attempt.
     * @return the backfill of the retired table into the rebuilt one.
     * @throws DDLReplicationException on any precondition failure or failed statement (terminal).
     */
    public static PrimaryKeyBackfill.Task swap(PrimaryKeyRebuildPlan plan, Connection ch, Properties props,
                                               ClickHouseSinkConnectorConfig config, String sourceDatabase,
                                               long epochMs) {
        final String db = plan.database();
        final String table = plan.table();
        final String scratch = table + "__pk_rebuild_" + epochMs;
        final String keyMapTable = table + "__pk_rebuild_keys_" + epochMs;
        log.info("Primary-key rebuild of {}.{} at the DDL barrier (swap phase): {} (Spec 06.09 §3.3.1)", db, table,
                plan);

        // ---- §3.2 preconditions: nothing has been executed yet. ----
        if (config != null && config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            throw refuse(plan, "replication.history.enable=true: SCD2 history tables key on _deleted_time and "
                    + "are not rebuilt (§3.2 item 1)");
        }
        if (!DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine) {
            throw refuse(plan, "the target uses the legacy sign-based engine, which the rebuild does not "
                    + "cover (§3.2 item 2)");
        }
        List<List<String>> tableRow = rows(ch, "SELECT engine_full, comment FROM system.tables WHERE database = '"
                + lit(db) + "' AND name = '" + lit(table) + "'", 2, "precondition (engine)");
        String engineFull = tableRow.isEmpty() ? null : tableRow.get(0).get(0);
        String comment = tableRow.isEmpty() || tableRow.get(0).get(1) == null ? "" : tableRow.get(0).get(1);
        if (engineFull == null) {
            throw refuse(plan, "the table was not found in system.tables");
        }
        if (!isReplacingMergeTree(engineFull)) {
            throw refuse(plan, "the target engine is [" + engineFull + "], not ReplacingMergeTree / "
                    + "ReplicatedReplacingMergeTree (§3.2 item 2)");
        }
        if (isReplicatedWithLiteralPath(engineFull)) {
            throw refuse(plan, "the target is Replicated* with a literal ZooKeeper path that uses neither the "
                    + "{table} nor the {uuid} macro, so the rebuilt table would collide with the old one's path "
                    + "(§3.2 item 3): [" + engineFull + "]");
        }
        List<ColumnInfo> targetColumns = columns(ch, db, table, "precondition (columns)");
        Map<String, String> targetTypes = new LinkedHashMap<>();
        for (ColumnInfo c : targetColumns) {
            targetTypes.put(c.name, c.type);
        }
        List<String> oldKey = resolveAll(plan.oldKey(), targetColumns, plan, "old-key");
        // Deferred renames of old-key columns (Spec 06.09 §3.1.1): the old
        // table's spelling -> the name the column carries on the rebuilt
        // table. The new key names renamed columns by their NEW name, so it is
        // resolved against the old table under the old names.
        Map<String, String> renames = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : plan.renamedColumns().entrySet()) {
            renames.put(resolveAll(Collections.singletonList(e.getKey()), targetColumns, plan, "renamed").get(0),
                    e.getValue());
        }
        List<String> newKeyInOldNames = new ArrayList<>();
        for (String column : plan.newKey()) {
            newKeyInOldNames.add(oldNameOf(column, renames));
        }
        List<String> newKeyOldNames = resolveAll(newKeyInOldNames, targetColumns, plan, "new-key");
        if (plan.keylessFallback()) {
            // The keyless identity is every stored column in POSITION order
            // (Spec 06.05 §3.6); the translator's column map is unordered.
            newKeyOldNames = inPositionOrder(newKeyOldNames, targetColumns);
        }
        List<String> newKey = new ArrayList<>();
        for (String column : newKeyOldNames) {
            String renamed = renames.get(column);
            newKey.add(renamed != null ? renamed : column);
        }
        List<String> sourceValued = resolveAll(plan.sourceValuedColumns(), targetColumns, plan, "source-valued");
        boolean needKeyMap = !sourceValued.isEmpty();
        if (needKeyMap) {
            for (String column : oldKey) {
                String type = targetTypes.get(column);
                if (type.startsWith("Nullable(")) {
                    throw refuse(plan, "old-key column " + column + " is " + type + " and a source key map is "
                            + "required: NULL never equals NULL in the JOIN, so such rows could not be matched "
                            + "(§3.2 item 5)");
                }
                if (!isKeyMapType(type)) {
                    throw refuse(plan, "old-key column " + column + " has ClickHouse type " + type + ", which the "
                            + "source key map cannot bind (integers and strings only, §3.2 item 4)");
                }
            }
            for (String column : sourceValued) {
                String type = targetTypes.get(column);
                if (!isKeyMapType(type)) {
                    throw refuse(plan, "source-valued column " + column + " has ClickHouse type " + type
                            + ", which the source key map cannot bind (integers and strings only, §3.2 item 4)");
                }
            }
        }

        String deleteFlag = deleteFlagColumn(engineFull);

        // ---- Step 1: clean a previous attempt's scratch tables. ----
        cleanPreviousAttempt(ch, db, table, plan, oldKey, deleteFlag, props);

        // ---- Step 2 (the statement's other clauses) was applied by the caller. ----

        // ---- Step 3: derive and create the rebuilt definition. ----
        String showCreate = scalar(ch, "SHOW CREATE TABLE " + q(db) + "." + q(table), "step 3 (SHOW CREATE TABLE)");
        if (showCreate == null) {
            throw new DDLReplicationException(failure(plan, "step 3 (SHOW CREATE TABLE)",
                    "SHOW CREATE TABLE returned nothing"), null);
        }
        String createScratch;
        try {
            createScratch = rewriteCreateStatement(showCreate, db, table, scratch, newKeyOldNames,
                    plan.keylessFallback(), targetTypes, renames, plan.retypedColumns());
        } catch (RuntimeException e) {
            throw new DDLReplicationException(failure(plan, "step 3 (rewrite CREATE TABLE)",
                    e.getMessage() + " -- statement: [" + showCreate + "]"), e);
        }
        exec(ch, createScratch, "step 3 (create the rebuilt table)", plan);

        // ---- Step 3b: deferred clauses on old-key columns (Spec 06.09 §3.1.1). ----
        // A DROP is applied to the empty rebuilt table as its own ALTER (the
        // column is not part of the rebuilt table's key). A RENAME or MODIFY
        // of a key column is NOT: ClickHouse rejects both on a sorting-key
        // column even when the table is empty (Code: 524 "Trying to ALTER
        // RENAME key ... column" / "ALTER of key column ... is not safe",
        // measured on 24.8.14), so they were folded into the CREATE TABLE
        // above (the column is declared under its new name and type) and the
        // backfill's copy converts the values.
        for (String clause : plan.deferredClauses()) {
            // DESTRUCTIVE: statement text is only translated/logged here; nothing is executed against any database.
            if (clause.regionMatches(true, 0, "DROP COLUMN", 0, "DROP COLUMN".length())) {
                // DESTRUCTIVE: drops from the EMPTY rebuilt copy of this one
                // table the column the SOURCE statement dropped; the old table
                // and its rows are untouched until the swap at step 4.
                exec(ch, "ALTER TABLE " + q(db) + "." + q(scratch) + " " + clause,
                        "step 3b (deferred clause on the rebuilt table)", plan);
            } else {
                log.info("Primary-key rebuild of {}.{} step 3b: deferred clause [{}] is applied through the CREATE "
                                + "TABLE of {}.{} (ClickHouse rejects RENAME/MODIFY of a sorting-key column with "
                                + "Code: 524 even on an empty table); the backfill's copy converts the values",
                        db, table, clause, db, scratch);
            }
        }
        // A FIRST/AFTER position on a deferred clause: restated on the empty
        // rebuilt table with the type it is declared with there. A MODIFY that
        // restates the IDENTICAL type with a position is accepted on a
        // sorting-key column (metadata-only reorder, measured on 24.8.14); any
        // other type would be Code: 524, so the type is read from the rebuilt
        // table, never taken from the plan (Spec 06.09 §3.1.1 / §3.3 step 3b).
        if (!plan.positionedColumns().isEmpty()) {
            Map<String, String> rebuiltTypes = new LinkedHashMap<>();
            for (ColumnInfo c : columns(ch, db, scratch, "step 3b (rebuilt table columns)")) {
                rebuiltTypes.put(c.name, c.type);
            }
            for (Map.Entry<String, String> e : plan.positionedColumns().entrySet()) {
                String type = getIgnoreCase(rebuiltTypes, e.getKey());
                if (type == null) {
                    throw new DDLReplicationException(failure(plan, "step 3b (column position on the rebuilt table)",
                            "column " + e.getKey() + " is not declared on " + db + "." + scratch), null);
                }
                exec(ch, "ALTER TABLE " + q(db) + "." + q(scratch) + " MODIFY COLUMN " + q(e.getKey()) + " " + type
                        + " " + e.getValue(), "step 3b (column position on the rebuilt table)", plan);
            }
        }

        // ---- Step 4: swap. ----
        String dbEngine = scalar(ch, "SELECT engine FROM system.databases WHERE name = '" + lit(db) + "'",
                "step 4 (database engine)");
        boolean exchange = dbEngine != null
                && (dbEngine.equalsIgnoreCase("Atomic") || dbEngine.equalsIgnoreCase("Replicated"));
        String retired = exchange ? scratch : table + "__pk_retired_" + epochMs;
        PrimaryKeyBackfill.Task task = new PrimaryKeyBackfill.Task(db, table, retired, keyMapTable, plan,
                sourceDatabase, deleteFlag, oldKey, newKey, renames, sourceValued, plan.keylessFallback(), epochMs);
        // The pending backfill is recorded on the table about to be retired,
        // BEFORE the swap, so a restart resumes it from that table alone
        // (PrimaryKeyBackfill.resumePending) and never has to guess renames or
        // source-valued columns from the column sets.
        exec(ch, "ALTER TABLE " + q(db) + "." + q(table) + " MODIFY COMMENT '"
                        + lit(PrimaryKeyBackfill.withMarker(comment, task.markerJson())) + "'",
                "step 4 (record the pending backfill on the table to be retired)", plan);
        if (exchange) {
            // DESTRUCTIVE: atomically swaps the mirrored table with its empty
            // rebuilt copy; no rows are lost -- the pre-rebuild table lives on
            // under the scratch name until the backfill has copied and verified it.
            exec(ch, "EXCHANGE TABLES " + q(db) + "." + q(table) + " AND " + q(db) + "." + q(scratch),
                    "step 4 (EXCHANGE TABLES)", plan);
        } else {
            // DESTRUCTIVE: renames the mirrored table aside and the empty rebuilt
            // copy into its place in one statement; no rows are lost -- the
            // pre-rebuild table lives on under the retired name until the backfill
            // has copied and verified it.
            exec(ch, "RENAME TABLE " + q(db) + "." + q(table) + " TO " + q(db) + "." + q(retired) + ", "
                    + q(db) + "." + q(scratch) + " TO " + q(db) + "." + q(table), "step 4 (RENAME TABLE)", plan);
        }
        log.info("Primary-key rebuild of {}.{} swapped: {}.{} is now the empty table keyed by ({}); {}.{} holds the "
                        + "pre-DDL rows and is backfilled online (Spec 06.09 §3.3.2). A value-level comparison of "
                        + "{}.{} differs until the backfill logs its completion and the retired table is gone.",
                db, table, db, table, String.join(", ", newKey), db, retired, db, table);
        return task;
    }

    /**
     * Step 1 of the swap (Spec 06.09 §3.3.1): removes the scratch tables of an
     * earlier attempt whose swap never completed. A scratch or retired table
     * that carries the pending-backfill marker is a swap that DID complete and
     * is never touched here, nor is the key map of the same attempt; nothing
     * is dropped unless {@code T} is still keyed by the old identity, and
     * nothing at all when {@code disable.drop.truncate=true}.
     */
    private static void cleanPreviousAttempt(Connection ch, String db, String table, PrimaryKeyRebuildPlan plan,
                                             List<String> oldKey, String deleteFlag, Properties props) {
        String leftoverQuery = "SELECT name, comment FROM system.tables WHERE database = '" + lit(db)
                + "' AND (name LIKE '" + likeLit(table + "__pk_rebuild_") + "%' OR name LIKE '"
                + likeLit(table + "__pk_retired_") + "%') ORDER BY name";
        List<List<String>> leftovers = rows(ch, leftoverQuery, 2, "step 1 (find scratch tables)");
        if (leftovers.isEmpty()) {
            return;
        }
        List<String> currentKey = withoutConnectorColumns(column(ch, sortingKeyQuery(db, table),
                "step 1 (current sorting key)"), deleteFlag);
        boolean keyedByOld = PrimaryKeyBackfill.sameNames(currentKey, oldKey);
        Set<String> pendingEpochs = new HashSet<>();
        for (List<String> row : leftovers) {
            if (PrimaryKeyBackfill.Marker.parse(row.get(1)) != null) {
                pendingEpochs.add(epochSuffix(row.get(0)));
            }
        }
        for (List<String> row : leftovers) {
            String leftover = row.get(0);
            if (PrimaryKeyBackfill.Marker.parse(row.get(1)) != null) {
                log.info("Primary-key rebuild of {}.{}: {}.{} holds the pre-DDL rows of a rebuild whose backfill is "
                        + "still pending; left in place", db, table, db, leftover);
                continue;
            }
            if (pendingEpochs.contains(epochSuffix(leftover))) {
                log.info("Primary-key rebuild of {}.{}: {}.{} belongs to the attempt whose backfill is still pending; "
                        + "left in place", db, table, db, leftover);
                continue;
            }
            if (!keyedByOld) {
                log.warn("Primary-key rebuild of {}.{}: scratch table {}.{} left in place because the table is keyed "
                        + "by {} rather than the old identity {}", db, table, db, leftover, currentKey, oldKey);
                continue;
            }
            if (dropTruncateDisabled(props)) {
                // DESTRUCTIVE: nothing is dropped on this branch; the setting name is only logged.
                log.warn("Primary-key rebuild of {}.{}: scratch table {}.{} of an earlier attempt is kept because {}=true",
                        db, table, db, leftover, SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
                continue;
            }
            log.info("Primary-key rebuild of {}.{}: dropping scratch table {}.{} left by an earlier attempt whose "
                    + "swap never completed", db, table, db, leftover);
            // DESTRUCTIVE: drops a connector-owned scratch table of a previous
            // rebuild attempt for this same table (<table>__pk_rebuild_% /
            // <table>__pk_retired_% in the destination database) that carries
            // no pending-backfill marker, never a mirrored source table.
            exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(leftover), "step 1 (clean previous attempt)", plan);
        }
    }

    /** The digits after the last underscore of a scratch table name (its attempt's epoch). */
    private static String epochSuffix(String scratchName) {
        int at = scratchName.lastIndexOf('_');
        return at < 0 ? scratchName : scratchName.substring(at + 1);
    }

    /** {@code columns} without the connector's own columns ({@code _version}, the delete flag, {@code _sign}). */
    static List<String> withoutConnectorColumns(List<String> columns, String deleteFlag) {
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            if (c.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)
                    || c.equalsIgnoreCase(ClickHouseDbConstants.SIGN_COLUMN)
                    || c.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)
                    || (deleteFlag != null && c.equalsIgnoreCase(deleteFlag))) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /** The {@code system.columns} query for a table's sorting-key columns in position order. */
    static String sortingKeyQuery(String db, String table) {
        return "SELECT name FROM system.columns WHERE database = '" + lit(db) + "' AND table = '" + lit(table)
                + "' AND is_in_sorting_key = 1 ORDER BY position";
    }

    /** The engine's version column when the table has it non-Nullable, else {@code null}. */
    static String versionColumn(Map<String, String> columnTypes) {
        String type = columnTypes == null ? null : columnTypes.get(ClickHouseDbConstants.VERSION_COLUMN);
        return type != null && !type.contains("Nullable(") ? ClickHouseDbConstants.VERSION_COLUMN : null;
    }

    /**
     * The production source connection: {@code database.hostname} /
     * {@code database.port} / {@code database.user} / {@code database.password}
     * through {@link KeylessTablePreflight#jdbcUrl}, read-only (Spec 06.09 §3.4).
     */
    static Supplier<Connection> sourceConnectionSupplier(final Properties props) {
        return () -> {
            String host = props.getProperty("database.hostname");
            String port = props.getProperty("database.port", "3306");
            String user = props.getProperty("database.user");
            String password = props.getProperty("database.password");
            if (host == null || user == null) {
                throw new IllegalStateException("database.hostname / database.user are not configured, so the "
                        + "source key values cannot be read");
            }
            try {
                Connection conn = DriverManager.getConnection(KeylessTablePreflight.jdbcUrl(host, port, props),
                        user, password);
                try {
                    conn.setReadOnly(true);
                    if (!conn.isReadOnly()) {
                        log.warn("The MySQL driver did not honour the read-only request on the primary-key "
                                + "rebuild's source connection; only SELECT is issued (assertReadOnlySql).");
                    }
                } catch (Exception e) {
                    log.warn("Could not set the primary-key rebuild's source connection read-only ({}); only "
                            + "SELECT is issued, enforced client-side.", e.toString());
                }
                return conn;
            } catch (SQLException e) {
                throw new IllegalStateException("could not connect to the source at " + host + ":" + port + ": "
                        + e.getMessage(), e);
            }
        };
    }

    /**
     * Rewrites a rendered {@code SHOW CREATE TABLE} into the definition of the
     * rebuilt table (Spec 06.09 §3.3 step 3): new name, {@code ORDER BY} the
     * new key, no separate {@code PRIMARY KEY} clause, new-key columns
     * re-declared non-{@code Nullable} -- except for the keyless all-columns
     * fallback, which keeps them {@code Nullable} and adds
     * {@code allow_nullable_key = 1} to {@code SETTINGS}. Engine arguments,
     * {@code PARTITION BY}, {@code SAMPLE BY}, {@code TTL} and every other
     * setting are kept verbatim.
     *
     * @param showCreate      the rendered statement (clauses on their own lines).
     * @param database        destination database (clean).
     * @param table           the table it renders (clean).
     * @param newTable        the rebuilt table's name (clean).
     * @param newKey          the new key, ClickHouse column spellings in order.
     * @param keylessFallback whether the new key is the keyless all-columns identity.
     * @param columnTypes     column name -> ClickHouse type of the table (system.columns).
     * @return the CREATE TABLE statement of the rebuilt table.
     * @throws IllegalArgumentException when the statement is not in the rendered shape.
     */
    static String rewriteCreateStatement(String showCreate, String database, String table, String newTable,
                                         List<String> newKey, boolean keylessFallback,
                                         Map<String, String> columnTypes) {
        return rewriteCreateStatement(showCreate, database, table, newTable, newKey, keylessFallback, columnTypes,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * As {@link #rewriteCreateStatement(String, String, String, String, List, boolean, Map)},
     * with the deferred renames and re-typings of old-key columns (Spec 06.09
     * §3.1.1) folded into the definition: a renamed column is declared under
     * its new name (and the {@code ORDER BY}, {@code PARTITION BY},
     * {@code SAMPLE BY} and {@code TTL} clauses name it so), a re-typed column
     * under its translated type. ClickHouse rejects {@code RENAME COLUMN} and
     * {@code MODIFY COLUMN} of a sorting-key column with {@code Code: 524}
     * even on an empty table, so the rebuilt table must be created in its
     * final shape and the copy converts the values.
     *
     * @param newKey  the new key, in the OLD table's column spellings (a renamed
     *                column by its old name).
     * @param renames old column spelling -> new name.
     * @param retypes column name AS ON THE REBUILT TABLE (the new name when
     *                renamed) -> translated ClickHouse type.
     */
    static String rewriteCreateStatement(String showCreate, String database, String table, String newTable,
                                         List<String> newKey, boolean keylessFallback,
                                         Map<String, String> columnTypes, Map<String, String> renames,
                                         Map<String, String> retypes) {
        String create = showCreate.replace("\r\n", "\n");
        Matcher header = CREATE_HEADER.matcher(create);
        if (!header.find()) {
            throw new IllegalArgumentException("not a rendered CREATE TABLE <db>.<table> statement");
        }
        String rest = create.substring(header.end());
        Matcher uuid = TABLE_UUID.matcher(rest);
        if (uuid.find()) {
            rest = rest.substring(uuid.end());
        }
        int engineAt = rest.indexOf("\nENGINE");
        if (engineAt < 0) {
            throw new IllegalArgumentException("no ENGINE clause on its own line");
        }
        String columnBlock = rest.substring(0, engineAt);
        String clauseText = rest.substring(engineAt + 1);

        // Re-typings arrive keyed by the rebuilt table's names; the rendered
        // definition still carries the old ones.
        Map<String, String> retypesByOldName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : retypes.entrySet()) {
            retypesByOldName.put(oldNameOf(e.getKey(), renames), e.getValue());
        }

        boolean nullableKey = false;
        for (String column : newKey) {
            String retype = getIgnoreCase(retypesByOldName, column);
            if (retype != null) {
                // The translator already applied the nullability rules (Spec
                // 06.05 §3.2): a Nullable translated type means the SOURCE
                // column is now nullable (`MODIFY x ... NULL` on a keyless
                // table's key column -- a declared PRIMARY KEY column can
                // never be made nullable on the source, error 1171), so the
                // rebuilt column keeps it and the key allows NULL
                // (allow_nullable_key = 1, Spec 06.09 §3.3.1 step 3). Stripping
                // it would reject the NULLs the source sends.
                String type = retype;
                if (type.contains("Nullable(")) {
                    nullableKey = true;
                }
                columnBlock = replaceType(columnBlock, column, type);
                continue;
            }
            String type = columnTypes == null ? null : columnTypes.get(column);
            boolean nullable = type != null ? type.contains("Nullable(") : columnLineHasNullable(columnBlock, column);
            if (!nullable) {
                continue;
            }
            if (keylessFallback) {
                nullableKey = true;
            } else {
                columnBlock = stripNullable(columnBlock, column);
            }
        }
        // A re-typed old-key column that is not part of the new key.
        for (Map.Entry<String, String> e : retypesByOldName.entrySet()) {
            if (!containsIgnoreCase(newKey, e.getKey())) {
                columnBlock = replaceType(columnBlock, e.getKey(), e.getValue());
            }
        }
        List<String> orderKey = new ArrayList<>();
        for (String column : newKey) {
            String renamed = getIgnoreCase(renames, column);
            orderKey.add(renamed != null ? renamed : column);
        }
        for (Map.Entry<String, String> e : renames.entrySet()) {
            columnBlock = renameColumnLine(columnBlock, e.getKey(), e.getValue());
        }

        List<String> clauses = splitClauses(clauseText);
        List<String> out = new ArrayList<>();
        boolean sawOrderBy = false;
        boolean sawSettings = false;
        String orderBy = "ORDER BY (" + qList(orderKey) + ")";
        for (String clause : clauses) {
            String keyword = clauseKeyword(clause);
            if (keyword.equals("PRIMARY KEY")) {
                continue;
            }
            if (RENAMED_CLAUSES.contains(keyword)) {
                for (Map.Entry<String, String> e : renames.entrySet()) {
                    clause = renameIdentifier(clause, e.getKey(), e.getValue());
                }
            }
            if (keyword.equals("ORDER BY")) {
                out.add(orderBy);
                sawOrderBy = true;
            } else if (keyword.equals("SETTINGS")) {
                sawSettings = true;
                out.add(nullableKey ? mergeAllowNullableKey(clause) : clause);
            } else {
                out.add(clause);
            }
        }
        if (!sawOrderBy) {
            throw new IllegalArgumentException("no ORDER BY clause on its own line");
        }
        if (nullableKey && !sawSettings) {
            int commentAt = -1;
            for (int i = 0; i < out.size(); i++) {
                if (clauseKeyword(out.get(i)).equals("COMMENT")) {
                    commentAt = i;
                    break;
                }
            }
            String settings = "SETTINGS allow_nullable_key = 1";
            if (commentAt >= 0) {
                out.add(commentAt, settings);
            } else {
                out.add(settings);
            }
        }
        return "CREATE TABLE " + q(database) + "." + q(newTable) + columnBlock + "\n" + String.join("\n", out);
    }

    /**
     * The live-row predicate of the copy (Spec 06.09 §3.3 step 5):
     * {@code `is_deleted` = 0} with the delete-flag column the engine names as
     * its second (non-path) argument, or {@code null} when the engine has only
     * {@code _version}.
     */
    static String liveRowPredicate(String engineFull) {
        String flag = deleteFlagColumn(engineFull);
        return flag == null ? null : q(flag) + " = 0";
    }

    /**
     * Whether the engine is {@code Replicated*} with a literal ZooKeeper path
     * that uses neither {@code {table}} nor {@code {uuid}} (Spec 06.09 §3.2
     * item 3): the rebuilt table would claim the same path.
     */
    static boolean isReplicatedWithLiteralPath(String engineFull) {
        if (engineFull == null || !engineName(engineFull).startsWith("Replicated")) {
            return false;
        }
        List<String> args = engineArguments(engineFull);
        if (args.isEmpty()) {
            return false;
        }
        String first = args.get(0);
        if (!first.startsWith("'")) {
            return false;
        }
        return !first.contains("{table}") && !first.contains("{uuid}");
    }

    // ------------------------------------------------------------------
    // Name helpers shared with PrimaryKeyBackfill
    // ------------------------------------------------------------------

    /** The old table's name of {@code column}: the key of {@code renames} whose value is it, else itself. */
    static String oldNameOf(String column, Map<String, String> renames) {
        for (Map.Entry<String, String> e : renames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(column)) {
                return e.getKey();
            }
        }
        return column;
    }

    private static String getIgnoreCase(Map<String, String> map, String key) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SHOW CREATE TABLE parsing
    // ------------------------------------------------------------------

    private static List<String> splitClauses(String clauseText) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = null;
        for (String line : clauseText.split("\n", -1)) {
            if (CLAUSE_START.matcher(line).find()) {
                if (current != null) {
                    clauses.add(current.toString());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                if (!line.trim().isEmpty()) {
                    current.append('\n').append(line);
                }
            } else if (!line.trim().isEmpty()) {
                throw new IllegalArgumentException("unexpected text before the ENGINE clause: [" + line + "]");
            }
        }
        if (current != null) {
            clauses.add(current.toString());
        }
        return clauses;
    }

    private static String clauseKeyword(String clause) {
        Matcher m = CLAUSE_START.matcher(clause);
        return m.find() ? m.group(1) : "";
    }

    private static String mergeAllowNullableKey(String settingsClause) {
        Matcher m = ALLOW_NULLABLE_KEY.matcher(settingsClause);
        if (m.find()) {
            return m.replaceFirst("allow_nullable_key = 1");
        }
        return settingsClause + ", allow_nullable_key = 1";
    }

    private static Pattern columnLine(String column) {
        return Pattern.compile("(?m)^([ \\t]*)(`" + Pattern.quote(column) + "`|" + Pattern.quote(column)
                + ")([ \\t]+)(.*)$");
    }

    private static boolean columnLineHasNullable(String columnBlock, String column) {
        Matcher m = columnLine(column).matcher(columnBlock);
        return m.find() && m.group(4).contains("Nullable(");
    }

    /** Re-declares {@code column} as {@code newType}, keeping DEFAULT/CODEC/COMMENT and the rest of its line. */
    private static String replaceType(String columnBlock, String column, String newType) {
        Matcher m = columnLine(column).matcher(columnBlock);
        if (!m.find()) {
            throw new IllegalArgumentException("re-typed column " + column + " is not declared in the column list");
        }
        String declaration = m.group(4);
        int end = leadingTypeLength(declaration);
        String rewritten = newType + declaration.substring(end);
        return columnBlock.substring(0, m.start(4)) + rewritten + columnBlock.substring(m.end(4));
    }

    /** Length of the type expression a rendered column declaration starts with (balanced parentheses, quoted args). */
    private static int leadingTypeLength(String declaration) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < declaration.length()) {
                    i++;
                } else if (c == '\'') {
                    quoted = false;
                }
            } else if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (Character.isWhitespace(c) || c == ',')) {
                return i;
            }
        }
        return declaration.length();
    }

    /** Declares {@code column} under {@code newName}; every other part of its line is kept. */
    private static String renameColumnLine(String columnBlock, String column, String newName) {
        Matcher m = columnLine(column).matcher(columnBlock);
        if (!m.find()) {
            throw new IllegalArgumentException("renamed column " + column + " is not declared in the column list");
        }
        return columnBlock.substring(0, m.start(2)) + q(newName) + columnBlock.substring(m.end(2));
    }

    /** Key-expression clauses in which a deferred rename of a column is applied (not ENGINE, SETTINGS, COMMENT). */
    private static final List<String> RENAMED_CLAUSES =
            Arrays.asList("PARTITION BY", "PRIMARY KEY", "ORDER BY", "SAMPLE BY", "TTL");

    /**
     * Renames every reference to {@code column} in a key-expression clause:
     * the backticked form and the bare form as a whole word. References
     * inside another identifier ({@code id_extra}, {@code `x id`}) and inside
     * single-quoted string literals ({@code 'id'}) are left alone.
     */
    private static String renameIdentifier(String clause, String column, String newName) {
        Pattern p = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`|(?<![A-Za-z0-9_])"
                + Pattern.quote(column) + "(?![A-Za-z0-9_])");
        Matcher m = p.matcher(clause);
        StringBuffer sb = new StringBuffer();
        String quoted = q(column);
        while (m.find()) {
            String token = m.group();
            boolean reference = token.equals(column) || token.equals(quoted);
            m.appendReplacement(sb, Matcher.quoteReplacement(reference ? q(newName) : token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Re-declares {@code column}'s {@code Nullable(X)} (also inside {@code LowCardinality}) as {@code X}. */
    private static String stripNullable(String columnBlock, String column) {
        Matcher m = columnLine(column).matcher(columnBlock);
        if (!m.find()) {
            throw new IllegalArgumentException("key column " + column + " is not declared in the column list");
        }
        String declaration = m.group(4);
        int open = declaration.indexOf("Nullable(");
        if (open < 0) {
            return columnBlock;
        }
        int depth = 0;
        int close = -1;
        for (int i = open + "Nullable".length(); i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            throw new IllegalArgumentException("unbalanced Nullable(...) in the declaration of " + column);
        }
        String rewritten = declaration.substring(0, open) + declaration.substring(open + "Nullable(".length(), close)
                + declaration.substring(close + 1);
        return columnBlock.substring(0, m.start(4)) + rewritten + columnBlock.substring(m.end(4));
    }

    // ------------------------------------------------------------------
    // engine_full parsing
    // ------------------------------------------------------------------

    private static boolean isReplacingMergeTree(String engineFull) {
        String name = engineName(engineFull);
        return name.equals("ReplacingMergeTree") || name.equals("ReplicatedReplacingMergeTree");
    }

    private static String engineName(String engineFull) {
        String s = engineFull.trim();
        int end = 0;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) {
            end++;
        }
        return s.substring(0, end);
    }

    /** Top-level arguments of the engine's parenthesised list, trimmed; empty when there is none. */
    private static List<String> engineArguments(String engineFull) {
        String s = engineFull.trim();
        int nameEnd = engineName(s).length();
        int i = nameEnd;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '(') {
            return Collections.emptyList();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quoted) {
                current.append(c);
                if (c == '\\' && j + 1 < s.length()) {
                    current.append(s.charAt(++j));
                } else if (c == '\'') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'') {
                quoted = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                if (depth > 1) {
                    current.append(c);
                }
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
                current.append(c);
            } else if (c == ',' && depth == 1) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.toString().trim().length() > 0) {
            args.add(current.toString().trim());
        }
        return args;
    }

    /** The delete-flag column: the second engine argument after any leading string literals, or null. */
    static String deleteFlagColumn(String engineFull) {
        if (engineFull == null) {
            return null;
        }
        List<String> columnsArgs = new ArrayList<>();
        for (String arg : engineArguments(engineFull)) {
            if (!arg.startsWith("'")) {
                columnsArgs.add(arg);
            }
        }
        if (columnsArgs.size() < 2) {
            return null;
        }
        return columnsArgs.get(1).replace("`", "");
    }

    // ------------------------------------------------------------------
    // Type checks (Spec 06.09 §3.2 item 4)
    // ------------------------------------------------------------------

    static boolean isKeyMapType(String clickHouseType) {
        if (clickHouseType == null) {
            return false;
        }
        String type = clickHouseType.trim();
        if (type.startsWith("LowCardinality(") && type.endsWith(")")) {
            type = type.substring("LowCardinality(".length(), type.length() - 1).trim();
        }
        return KEY_MAP_TYPE.matcher(type).matches();
    }

    static boolean isKeyMapJdbcType(int jdbcType) {
        switch (jdbcType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                return true;
            default:
                return false;
        }
    }

    static String withoutNullable(String type) {
        String t = type.trim();
        if (t.startsWith("Nullable(") && t.endsWith(")")) {
            return t.substring("Nullable(".length(), t.length() - 1);
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Column resolution
    // ------------------------------------------------------------------

    /** Resolves clean (possibly lower-cased) names to the ClickHouse spellings of {@code columns}. */
    private static List<String> resolveAll(List<String> names, List<ColumnInfo> columns, PrimaryKeyRebuildPlan plan,
                                           String role) {
        List<String> resolved = new ArrayList<>();
        for (String name : names) {
            String actual = null;
            for (ColumnInfo c : columns) {
                if (c.name.equals(name)) {
                    actual = c.name;
                    break;
                }
            }
            if (actual == null) {
                for (ColumnInfo c : columns) {
                    if (c.name.equalsIgnoreCase(name)) {
                        actual = c.name;
                        break;
                    }
                }
            }
            if (actual == null) {
                throw refuse(plan, role + " column " + name + " does not exist in " + plan.database() + "."
                        + plan.table() + " after the statement's other clauses were applied");
            }
            resolved.add(actual);
        }
        return resolved;
    }

    private static List<String> inPositionOrder(List<String> names, List<ColumnInfo> columns) {
        List<String> ordered = new ArrayList<>();
        for (ColumnInfo c : columns) {
            if (names.contains(c.name)) {
                ordered.add(c.name);
            }
        }
        return ordered;
    }

    // ------------------------------------------------------------------
    // JDBC helpers -- plain Statement, no retry: a swap statement is never
    // retried blindly; the DDL is redelivered and the swap restarts at step 1.
    // ------------------------------------------------------------------

    private static void exec(Connection ch, String sql, String step, PrimaryKeyRebuildPlan plan) {
        log.info("Primary-key rebuild of {}.{} {}: {}", plan.database(), plan.table(), step, sql);
        try (Statement st = ch.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new DDLReplicationException(failure(plan, step, "[" + sql + "]: " + e.getMessage()), e);
        }
    }

    private static String scalar(Connection ch, String sql, String step) {
        log.info("Primary-key rebuild {}: {}", step, sql);
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new DDLReplicationException("Primary-key rebuild failed at " + step + " executing [" + sql + "]: "
                    + e.getMessage(), e);
        }
    }

    /** The first {@code width} columns of every row of {@code sql}, as strings. */
    private static List<List<String>> rows(Connection ch, String sql, int width, String step) {
        log.info("Primary-key rebuild {}: {}", step, sql);
        List<List<String>> out = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>(width);
                for (int i = 1; i <= width; i++) {
                    row.add(rs.getString(i));
                }
                out.add(row);
            }
        } catch (Exception e) {
            throw new DDLReplicationException("Primary-key rebuild failed at " + step + " executing [" + sql + "]: "
                    + e.getMessage(), e);
        }
        return out;
    }

    private static List<String> column(Connection ch, String sql, String step) {
        log.info("Primary-key rebuild {}: {}", step, sql);
        List<String> values = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new DDLReplicationException("Primary-key rebuild failed at " + step + " executing [" + sql + "]: "
                    + e.getMessage(), e);
        }
        return values;
    }

    private static List<ColumnInfo> columns(Connection ch, String db, String table, String step) {
        String sql = "SELECT name, type, default_kind FROM system.columns WHERE database = '" + lit(db)
                + "' AND table = '" + lit(table) + "' ORDER BY position";
        log.info("Primary-key rebuild {}: {}", step, sql);
        List<ColumnInfo> columns = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(new ColumnInfo(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (Exception e) {
            throw new DDLReplicationException("Primary-key rebuild failed at " + step + " executing [" + sql + "]: "
                    + e.getMessage(), e);
        }
        return columns;
    }

    static boolean dropTruncateDisabled(Properties props) {
        String value = props == null ? null : props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    /** The Spec 06.07 §3.1 rule-3 refusal, naming why the rebuild cannot run. */
    private static DDLReplicationException refuse(PrimaryKeyRebuildPlan plan, String reason) {
        String message = String.format(
                "Table %s.%s: the source changes its PRIMARY KEY to (%s) but the ClickHouse sorting key is (%s). "
                        + "ClickHouse fixes the sorting key at CREATE TABLE (Code: 524), and keeping the old key "
                        + "would collapse rows the source keeps distinct, so this cannot be applied by ALTER and is "
                        + "not retried. The automatic rebuild (Spec 06.09) cannot run: %s. Manual rebuild required: "
                        + "re-create `%s`.%s with ORDER BY matching the new key and re-snapshot the table. "
                        + "Source DDL: [%s]",
                plan.database(), plan.table(), String.join(",", plan.newKey()), String.join(",", plan.oldKey()),
                reason, plan.database(), plan.table(), plan.sourceSql());
        log.error(message);
        return new DDLReplicationException(message, null);
    }

    private static String failure(PrimaryKeyRebuildPlan plan, String step, String detail) {
        return "Primary-key rebuild of " + plan.database() + "." + plan.table() + " failed at " + step + ": " + detail
                + ". The pipeline stops here (Invariant I9); the DDL is re-delivered on restart and the rebuild "
                + "retried from step 1. Source DDL: [" + plan.sourceSql() + "]";
    }

    // ------------------------------------------------------------------
    // Quoting
    // ------------------------------------------------------------------

    /** Backtick-quotes an identifier. */
    static String q(String identifier) {
        return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }

    static String qList(List<String> identifiers) {
        StringBuilder sb = new StringBuilder();
        for (String id : identifiers) {
            sb.append(sb.length() == 0 ? "" : ", ").append(q(id));
        }
        return sb.toString();
    }

    /** Escapes a value for a single-quoted ClickHouse string literal. */
    static String lit(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** As {@link #lit}, with LIKE's {@code _} and {@code %} escaped so they match literally. */
    static String likeLit(String value) {
        return lit(value).replace("_", "\\_").replace("%", "\\%");
    }
}
