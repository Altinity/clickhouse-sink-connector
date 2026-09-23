package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds a ClickHouse table under a new sorting key at the DDL barrier when
 * the source changed the table's row identity (Spec 06.09) -- the same
 * clustered-index rebuild MySQL performs for {@code ADD PRIMARY KEY} /
 * {@code DROP PRIMARY KEY}: copy the live rows into a table with the new key,
 * count-reconcile, swap, retire the original.
 *
 * <p>Invoked by {@code DebeziumChangeEventCapture.performDDLOperation} right
 * after the statement's other clauses have been applied ({@code executeDDL})
 * and before the cache invalidation, with the plan
 * {@code MySqlDDLParserListenerImpl.enforcePrimaryKeyPolicy} produced. Every
 * statement runs on the writer's connection inside the existing barrier
 * (Invariant I5); every failure is a {@link DDLReplicationException}
 * (Invariant I9) so no offset is committed past the DDL.</p>
 *
 * <p>Values only the source knows -- an {@code AUTO_INCREMENT} column the
 * statement added -- are read from MySQL with a single read-only
 * {@code SELECT} keyed by the old identity ({@link #loadSourceKeyMap}, Spec
 * 06.09 §3.4); the connector never re-derives them (Invariant I6) and never
 * writes to its source ({@link KeylessTablePreflight#assertReadOnlySql}).</p>
 */
public final class PrimaryKeyRebuild {

    private static final Logger log = LogManager.getLogger(PrimaryKeyRebuild.class);

    /** Rows bound per {@code executeBatch} when filling the source key map. */
    static final int SOURCE_BATCH_SIZE = 10000;

    /** Header of a rendered {@code SHOW CREATE TABLE}: {@code CREATE TABLE db.t} with or without backticks. */
    private static final Pattern CREATE_HEADER = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(`(?:[^`\\\\]|\\\\.)*`|[^\\s.(`]+)\\.(`(?:[^`\\\\]|\\\\.)*`|[^\\s(`]+)",
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
    private static final class ColumnInfo {
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
     * Executes the rebuild (Spec 06.09 §3.2 preconditions, §3.3 protocol).
     *
     * @param plan                     the plan the translator produced.
     * @param ch                       the writer's ClickHouse connection.
     * @param props                    the connector properties ({@code disable.drop.truncate}).
     * @param config                   the connector configuration ({@code replication.history.enable}).
     * @param sourceDatabase           the RAW source database of the DDL event (for the source read).
     * @param sourceConnectionSupplier opens a read-only connection to the source; only
     *                                 invoked when the plan requires a source key map.
     * @param epochMs                  names the scratch tables of this attempt.
     * @throws DDLReplicationException on any precondition failure, count mismatch or failed statement.
     */
    public static void execute(PrimaryKeyRebuildPlan plan, Connection ch, Properties props,
                               ClickHouseSinkConnectorConfig config, String sourceDatabase,
                               Supplier<Connection> sourceConnectionSupplier, long epochMs) {
        final String db = plan.database();
        final String table = plan.table();
        final String scratch = table + "__pk_rebuild_" + epochMs;
        final String keyMapTable = table + "__pk_rebuild_keys_" + epochMs;
        log.info("Primary-key rebuild of {}.{} at the DDL barrier: {} (Spec 06.09)", db, table, plan);

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
        String engineFull = scalar(ch, "SELECT engine_full FROM system.tables WHERE database = '"
                + lit(db) + "' AND name = '" + lit(table) + "'", "precondition (engine)");
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

        // ---- Step 1: clean a previous attempt's scratch tables. ----
        String leftoverQuery = "SELECT name FROM system.tables WHERE database = '" + lit(db) + "' AND (name LIKE '"
                + likeLit(table + "__pk_rebuild_") + "%' OR name LIKE '" + likeLit(table + "__pk_rebuild_keys_") + "%')";
        for (String leftover : column(ch, leftoverQuery, "step 1 (find scratch tables)")) {
            log.info("Primary-key rebuild of {}.{}: dropping scratch table {}.{} left by an earlier attempt",
                    db, table, db, leftover);
            // DESTRUCTIVE: drops a connector-owned scratch table of a previous
            // rebuild attempt for this same table (name pattern
            // <table>__pk_rebuild_% / <table>__pk_rebuild_keys_% in the
            // destination database), never a mirrored source table.
            exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(leftover), "step 1 (clean previous attempt)", plan);
        }

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
        // copy converts the values (step 5).
        for (String clause : plan.deferredClauses()) {
            // DESTRUCTIVE: statement text is only translated/logged here; nothing is executed against any database.
            if (clause.regionMatches(true, 0, "DROP COLUMN", 0, "DROP COLUMN".length())) {
                // DESTRUCTIVE: drops from the EMPTY rebuilt copy of this one
                // table the column the SOURCE statement dropped; the old table
                // and its rows are untouched until the swap at step 7.
                exec(ch, "ALTER TABLE " + q(db) + "." + q(scratch) + " " + clause,
                        "step 3b (deferred clause on the rebuilt table)", plan);
            } else {
                log.info("Primary-key rebuild of {}.{} step 3b: deferred clause [{}] is applied through the CREATE "
                                + "TABLE of {}.{} (ClickHouse rejects RENAME/MODIFY of a sorting-key column with "
                                + "Code: 524 even on an empty table); the copy converts the values",
                        db, table, clause, db, scratch);
            }
        }

        // ---- Step 4: source key map (SOURCE_VALUED columns only). ----
        if (needKeyMap) {
            StringBuilder createKeys = new StringBuilder("CREATE TABLE ").append(q(db)).append('.')
                    .append(q(keyMapTable)).append(" (");
            boolean first = true;
            for (String column : oldKey) {
                createKeys.append(first ? "" : ", ").append(q(column)).append(' ').append(targetTypes.get(column));
                first = false;
            }
            for (String column : sourceValued) {
                // Typed as in the rebuilt table: the key column is non-Nullable there.
                createKeys.append(", ").append(q(column)).append(' ').append(withoutNullable(targetTypes.get(column)));
            }
            createKeys.append(") ENGINE = MergeTree ORDER BY (").append(qList(oldKey)).append(')');
            exec(ch, createKeys.toString(), "step 4 (create the source key map)", plan);

            Connection source;
            try {
                source = sourceConnectionSupplier.get();
            } catch (RuntimeException e) {
                throw new DDLReplicationException(failure(plan, "step 4 (connect to the source)",
                        e.getMessage()), e);
            }
            try {
                long rows = loadSourceKeyMap(source, sourceDatabase, table, oldKey, sourceValued, ch, db,
                        keyMapTable, plan);
                log.info("Primary-key rebuild of {}.{}: source key map {}.{} holds {} rows read from `{}`.`{}`",
                        db, table, db, keyMapTable, rows, sourceDatabase, table);
            } finally {
                try {
                    source.close();
                } catch (Exception e) {
                    log.warn("Primary-key rebuild of {}.{}: could not close the source connection ({})",
                            db, table, e.toString());
                }
            }
        }

        // ---- Step 5: copy the live rows, versions preserved. ----
        List<String> copyColumns = new ArrayList<>();
        for (ColumnInfo c : columns(ch, db, scratch, "step 5 (columns of the rebuilt table)")) {
            if (!c.defaultKind.equalsIgnoreCase("ALIAS") && !c.defaultKind.equalsIgnoreCase("MATERIALIZED")) {
                copyColumns.add(c.name);
            }
        }
        if (copyColumns.isEmpty()) {
            throw new DDLReplicationException(failure(plan, "step 5 (columns of the rebuilt table)",
                    "system.columns lists no column for " + db + "." + scratch), null);
        }
        String deleteFlag = deleteFlagColumn(engineFull);
        String select = copySelect(db, table, keyMapTable, copyColumns, oldKey, sourceValued, deleteFlag, needKeyMap,
                renames);
        String insert = "INSERT INTO " + q(db) + "." + q(scratch) + " (" + qList(copyColumns) + ") " + select;
        exec(ch, insert, "step 5 (copy the live rows)", plan);

        // ---- Step 6: count-reconcile. ----
        long expected = count(ch, "SELECT count() FROM (" + select + ")", "step 6 (expected count)", plan);
        long actual = count(ch, "SELECT count() FROM " + q(db) + "." + q(scratch), "step 6 (actual count)", plan);
        log.info("Primary-key rebuild of {}.{}: copied {} live rows into {}.{} (the feeding SELECT counts {})",
                db, table, actual, db, scratch, expected);
        if (expected != actual) {
            throw new DDLReplicationException(String.format(
                    "Primary-key rebuild of %s.%s aborted at step 6 (count-reconcile): the feeding SELECT counts %d "
                            + "rows but %s.%s holds %d. %s.%s is untouched; %s.%s%s left in place for inspection. "
                            + "Source DDL: [%s]",
                    db, table, expected, db, scratch, actual, db, table, db, scratch,
                    needKeyMap ? " and " + db + "." + keyMapTable + " are" : " is", plan.sourceSql()), null);
        }
        if (needKeyMap) {
            String live = deleteFlag == null ? "" : " WHERE " + q(deleteFlag) + " = 0";
            long liveRows = count(ch, "SELECT count() FROM " + q(db) + "." + q(table) + " FINAL" + live,
                    "step 6 (live rows before the JOIN)", plan);
            log.info("Primary-key rebuild of {}.{}: {} live rows in {}.{}, {} matched the source key map, {} dropped "
                            + "by the JOIN (their old identity is no longer on the source; their later events re-key "
                            + "or retire them, Spec 06.09 §3.4)",
                    db, table, liveRows, db, table, expected, liveRows - expected);
        }

        // ---- Step 7: swap. ----
        String dbEngine = scalar(ch, "SELECT engine FROM system.databases WHERE name = '" + lit(db) + "'",
                "step 7 (database engine)");
        String retired;
        if (dbEngine != null && (dbEngine.equalsIgnoreCase("Atomic") || dbEngine.equalsIgnoreCase("Replicated"))) {
            // DESTRUCTIVE: atomically swaps the mirrored table with its rebuilt
            // copy; no rows are lost -- the pre-rebuild table lives on under
            // the scratch name until step 8 decides its fate.
            exec(ch, "EXCHANGE TABLES " + q(db) + "." + q(table) + " AND " + q(db) + "." + q(scratch),
                    "step 7 (EXCHANGE TABLES)", plan);
            retired = scratch;
        } else {
            retired = table + "__pk_retired_" + epochMs;
            // DESTRUCTIVE: renames the mirrored table aside and the rebuilt copy
            // into its place in one statement; no rows are lost -- the
            // pre-rebuild table lives on under the retired name until step 8.
            exec(ch, "RENAME TABLE " + q(db) + "." + q(table) + " TO " + q(db) + "." + q(retired) + ", "
                    + q(db) + "." + q(scratch) + " TO " + q(db) + "." + q(table), "step 7 (RENAME TABLE)", plan);
        }

        // ---- Step 8: retire. ----
        if (dropTruncateDisabled(props)) {
            log.warn("Primary-key rebuild of {}.{}: {}=true, so the retired pre-rebuild copy {}.{}{} kept; drop "
                            + "them once the rebuilt table is verified",
                    db, table, SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, db, retired,
                    needKeyMap ? " and the source key map " + db + "." + keyMapTable + " are" : " is");
        } else {
            // DESTRUCTIVE: drops the pre-rebuild copy of this one table, whose
            // live rows were copied and count-reconciled into the rebuilt
            // table at steps 5-6 and which was swapped out at step 7 -- as
            // MySQL drops the original after its own rebuild. Bounded to the
            // retired name of this attempt; disable.drop.truncate=true keeps it.
            exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(retired), "step 8 (drop the retired copy)", plan);
            if (needKeyMap) {
                // DESTRUCTIVE: drops the connector-owned source key map of this
                // attempt (scratch, never source data).
                exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(keyMapTable), "step 8 (drop the source key map)",
                        plan);
            }
        }
        log.info("Primary-key rebuild of {}.{} complete: sorting key is now ({}) (Spec 06.09)", db, table,
                String.join(", ", newKey));
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
     * Fills the source key map (Spec 06.09 §3.4): one streaming read-only
     * {@code SELECT <old key>, <source-valued> FROM `<source db>`.`<table>`},
     * bound into {@code K} with {@code setObject} in batches.
     *
     * @return the number of rows loaded.
     */
    static long loadSourceKeyMap(Connection source, String sourceDatabase, String table, List<String> oldKey,
                                 List<String> sourceValued, Connection ch, String db, String keyMapTable,
                                 PrimaryKeyRebuildPlan plan) {
        List<String> readColumns = new ArrayList<>(oldKey);
        readColumns.addAll(sourceValued);
        String select = "SELECT " + qList(readColumns) + " FROM " + q(sourceDatabase) + "." + q(table);
        KeylessTablePreflight.assertReadOnlySql(select);
        String insert = "INSERT INTO " + q(db) + "." + q(keyMapTable) + " (" + qList(readColumns) + ") VALUES ("
                + placeholders(readColumns.size()) + ")";
        log.info("Primary-key rebuild of {}.{}: reading the source key map with [{}] (read-only)", db, table, select);
        long rows = 0;
        try (Statement st = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = st.executeQuery(select)) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= readColumns.size(); i++) {
                    int jdbcType = md.getColumnType(i);
                    if (!isKeyMapJdbcType(jdbcType)) {
                        throw refuse(plan, "source column " + readColumns.get(i - 1) + " has MySQL type "
                                + md.getColumnTypeName(i) + " (JDBC type " + jdbcType + "), which the source key map "
                                + "cannot bind (integers and strings only, §3.2 item 4)");
                    }
                }
                log.info("Primary-key rebuild of {}.{}: filling {}.{} with [{}] in batches of {}", db, table, db,
                        keyMapTable, insert, SOURCE_BATCH_SIZE);
                try (PreparedStatement ps = ch.prepareStatement(insert)) {
                    int pending = 0;
                    while (rs.next()) {
                        for (int i = 1; i <= readColumns.size(); i++) {
                            ps.setObject(i, rs.getObject(i));
                        }
                        ps.addBatch();
                        rows++;
                        if (++pending == SOURCE_BATCH_SIZE) {
                            ps.executeBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch();
                    }
                }
            }
        } catch (DDLReplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new DDLReplicationException(failure(plan, "step 4 (fill the source key map)",
                    "[" + select + "] -> [" + insert + "]: " + e.getMessage()), e);
        }
        return rows;
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
     * its new name (and the {@code ORDER BY} names it so), a re-typed column
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
                // The translator already applied the nullability rules; a
                // declared key column is non-Nullable unless the new key is
                // the keyless fallback (Spec 06.05 §3.6).
                String type = keylessFallback ? retype : withoutNullable(retype);
                if (keylessFallback && type.contains("Nullable(")) {
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
    // Statement builders
    // ------------------------------------------------------------------

    /**
     * The SELECT feeding the copy (Spec 06.09 §3.3 step 5). {@code columns}
     * are the rebuilt table's columns; a renamed old-key column is read from
     * the old table as {@code o.<old> AS <new>} (step 3b), a source-valued one
     * from the key map.
     */
    private static String copySelect(String db, String table, String keyMapTable, List<String> columns,
                                     List<String> oldKey, List<String> sourceValued, String deleteFlag,
                                     boolean needKeyMap, Map<String, String> renames) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (!needKeyMap && renames.isEmpty()) {
            sb.append(qList(columns)).append(" FROM ").append(q(db)).append('.').append(q(table)).append(" FINAL");
            if (deleteFlag != null) {
                sb.append(" WHERE ").append(q(deleteFlag)).append(" = 0");
            }
            return sb.toString();
        }
        boolean first = true;
        for (String column : columns) {
            sb.append(first ? "" : ", ");
            String oldName = oldNameOf(column, renames);
            if (sourceValued.contains(column)) {
                sb.append("k.").append(q(column));
            } else if (!oldName.equals(column)) {
                sb.append("o.").append(q(oldName)).append(" AS ").append(q(column));
            } else {
                sb.append("o.").append(q(column));
            }
            first = false;
        }
        sb.append(" FROM ").append(q(db)).append('.').append(q(table)).append(" AS o FINAL");
        if (needKeyMap) {
            sb.append(" INNER JOIN ").append(q(db)).append('.').append(q(keyMapTable)).append(" AS k ON ");
            first = true;
            for (String column : oldKey) {
                sb.append(first ? "" : " AND ").append("o.").append(q(column)).append(" = k.").append(q(column));
                first = false;
            }
        }
        if (deleteFlag != null) {
            sb.append(" WHERE o.").append(q(deleteFlag)).append(" = 0");
        }
        return sb.toString();
    }

    /** The old table's name of {@code column}: the key of {@code renames} whose value is it, else itself. */
    private static String oldNameOf(String column, Map<String, String> renames) {
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

    private static String placeholders(int n) {
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

    private static boolean isKeyMapJdbcType(int jdbcType) {
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

    private static String withoutNullable(String type) {
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
    // JDBC helpers -- plain Statement, no retry: a rebuild step is never
    // retried blindly (a repeated INSERT ... SELECT would double the rows).
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

    private static long count(Connection ch, String sql, String step, PrimaryKeyRebuildPlan plan) {
        log.info("Primary-key rebuild of {}.{} {}: {}", plan.database(), plan.table(), step, sql);
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("count() returned no row");
            }
            return rs.getLong(1);
        } catch (Exception e) {
            throw new DDLReplicationException(failure(plan, step, "[" + sql + "]: " + e.getMessage()), e);
        }
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

    private static boolean dropTruncateDisabled(Properties props) {
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

    private static String qList(List<String> identifiers) {
        StringBuilder sb = new StringBuilder();
        for (String id : identifiers) {
            sb.append(sb.length() == 0 ? "" : ", ").append(q(id));
        }
        return sb.toString();
    }

    /** Escapes a value for a single-quoted ClickHouse string literal. */
    private static String lit(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** As {@link #lit}, with LIKE's {@code _} and {@code %} escaped so they match literally. */
    private static String likeLit(String value) {
        return lit(value).replace("_", "\\_").replace("%", "\\%");
    }
}
