package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DDLReplicationException;
import com.altinity.clickhouse.sink.connector.db.KeylessTableWarning;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.IS_DELETED_COLUMN;

/**
 * Derives the {@code CREATE TABLE} {@code ORDER BY} sorting key and the
 * {@code ReplacingMergeTree} engine clause from the source table's declared
 * row identity (Spec 06.05 section 3.6): a declared {@code PRIMARY KEY}
 * first, then a {@code NOT NULL UNIQUE} key, then every stored column as a
 * last resort -- never an empty {@code ORDER BY tuple()}, which would
 * collapse every row of the table into one under {@code ReplacingMergeTree}.
 *
 * <p>Stateless beyond logging; extracted from
 * {@link MySqlDDLParserListenerImpl#enterColumnCreateTable} so it is owned
 * and tested on its own. The schema-override {@code primary_key} lookup and
 * the column/engine text emission stay on {@code enterColumnCreateTable}
 * itself, since both need the listener's own {@code query}/{@code config}
 * state.</p>
 */
final class ReplacingMergeTreeKeyPolicy {

    /**
     * Logger for ReplacingMergeTreeKeyPolicy class.
     */
    private static final Logger log = LogManager.getLogger(ReplacingMergeTreeKeyPolicy.class);

    private ReplacingMergeTreeKeyPolicy() {
    }

    /**
     * Resolves the {@code ORDER BY} sorting key for a {@code CREATE TABLE}
     * from the source's declared row identity, falling back in order from a
     * {@code NOT NULL UNIQUE} key to every stored column (Spec 06.05 section
     * 3.6), and refusing a table with neither a key nor any stored column.
     *
     * @param orderByColumns      the {@code PRIMARY KEY} columns already
     *                            collected by {@code parseCreateTable}/
     *                            {@code parseColumnDefinitions}; empty when the
     *                            source declares no {@code PRIMARY KEY}.
     *                            Mutated in place to the final sorting key.
     * @param uniqueKeyColumns    the first table- or column-level
     *                            {@code UNIQUE} key declared, or empty if none.
     * @param orderedColumnNames  every stored (non-generated) column, in
     *                            declaration order, for the all-columns
     *                            fallback.
     * @param notNullColumnNames  every column the source declares (or
     *                            implies) {@code NOT NULL}, backtick-stripped.
     * @param databaseName        the destination database, for logging and
     *                            the refusal message.
     * @param tableName           the table name, for logging and the refusal
     *                            message.
     * @param overridePrimaryKey  true when the schema-override
     *                            {@code primary_key} is configured for this
     *                            table; the fallback (and its
     *                            {@code allow_nullable_key} need) must not
     *                            run, since the override wins over every
     *                            derived key.
     * @param originalSql         the source DDL text, for the refusal
     *                            message.
     * @return true when the resolved sorting key can name a nullable column
     *         (only the all-columns fallback can), so the caller must append
     *         {@code SETTINGS allow_nullable_key=1}.
     * @throws DDLReplicationException if the table declares no stored column
     *         and no override, so no sorting key can be derived at all.
     */
    static boolean resolveSortingKey(StringBuilder orderByColumns, StringBuilder uniqueKeyColumns,
                                     List<String> orderedColumnNames, Set<String> notNullColumnNames,
                                     String databaseName, String tableName, boolean overridePrimaryKey,
                                     String originalSql) {
        // True when the emitted sorting key can name NULLABLE columns, which
        // ClickHouse rejects outright unless allow_nullable_key is enabled (see
        // the settings block at the end of enterColumnCreateTable).
        //
        // Only the all-columns fallback can: a PRIMARY KEY is NOT NULL by MySQL's
        // own rule, and a UNIQUE key is adopted below only when every one of its
        // columns is NOT NULL. Emitting the setting where it is not needed would
        // silently permit nullable keys ClickHouse is right to reject.
        boolean nullableSortingKey = false;

        // A table with a UNIQUE key but no PRIMARY KEY would otherwise be created
        // with ORDER BY tuple(): every row compares equal, so ReplacingMergeTree
        // collapses the whole table into one row. The UNIQUE key is the source's
        // stable row identity, so use it as the sorting key. Only applied when no
        // PRIMARY KEY was found -- the PRIMARY KEY always wins.
        //
        // ONLY when every column of that UNIQUE key is NOT NULL. MySQL does not
        // treat NULLs as equal for uniqueness, so a nullable UNIQUE index permits
        // any number of rows whose key is NULL -- it is not a row identity at
        // all. ClickHouse compares NULLs as equal in a sorting key, so adopting
        // such a key makes ReplacingMergeTree collapse those distinct source rows
        // into one.
        //
        // Measured on MySQL 8.0.36 -> ClickHouse 24.8.14.10547 with
        // UNIQUE KEY(a) over a nullable `a`: four source rows, three of them
        // a IS NULL, arrived as TWO -- 'first' and 'second' silently lost. A
        // partially-nullable composite UNIQUE key loses rows the same way.
        //
        // Such a table has no usable declared identity, so it falls through to
        // the all-columns fallback below, which reproduces MySQL's own semantics
        // for a table without a row identity: rows are distinguished by value.
        List<String> uniqueKeyColumnNames = splitIndexColumns(uniqueKeyColumns.toString());
        boolean uniqueKeyIsNotNull = !uniqueKeyColumnNames.isEmpty()
                && notNullColumnNames.containsAll(uniqueKeyColumnNames);

        if (orderByColumns.length() == 0 && uniqueKeyColumns.length() > 0) {
            if (uniqueKeyIsNotNull) {
                log.info("Table has no PRIMARY KEY; using UNIQUE key as the ClickHouse sorting key: "
                        + uniqueKeyColumns);
                orderByColumns.append(uniqueKeyColumns);
            } else {
                log.warn("Table {}.{} has no PRIMARY KEY and its UNIQUE key ({}) spans nullable "
                                + "columns. MySQL does not treat NULLs as equal, so that index permits "
                                + "many NULL-keyed rows and is not a row identity; ClickHouse would "
                                + "collapse them. Falling back to all columns as the sorting key.",
                        databaseName, tableName, uniqueKeyColumns);
            }
        }

        // Neither a PRIMARY KEY nor a NOT NULL UNIQUE key: the table has no
        // declared row identity (MySQL's `alembic_version` is the canonical
        // example). ORDER BY tuple() would make every row compare equal, so
        // ReplacingMergeTree would keep exactly ONE row for the entire table --
        // a silent, total data loss that is invisible while the table holds a
        // single row and appears the moment it grows to two.
        //
        // The identity such a table ought to have comes from MySQL: the
        // GENERATED INVISIBLE PRIMARY KEY (8.0.30+, my_row_id), which is part
        // of the table definition and so arrives here as an ordinary keyed
        // table. Until the source has one, the sorting key is every stored
        // (non-generated) column in declaration order -- exactly what the
        // record-schema creation path builds (ClickHouseAutoCreateTable
        // .keylessSortingKey, Spec 08.05 §3.2), so both creation paths give
        // the same source table the same identity (Spec 06.05 §3.6). Rows are
        // then distinguished by value, which is MySQL's own semantics for a
        // table without an identity.
        //
        // The cost: ClickHouse forbids MODIFY/RENAME/DROP of a sorting-key
        // column, so later DDL on such a table meets the sorting-key policy
        // (Spec 06.05 §3.4) -- a suppressed clause or a loud, named rebuild.
        // That is recoverable; the row loss of an empty key is not. A hash of
        // the row's values is NOT an alternative: a column it names cannot be
        // dropped (Code: 44) and a column added later is absent from it.
        //
        // KeylessTablePreflight reports the table at startup; the banner here
        // repeats the fix at CREATE time so it cannot be missed.
        //
        // The schema-override primary_key is the operator's escape hatch and
        // wins over every derived key on both creation paths; when it is set
        // the fallback (and its allow_nullable_key) must not run.
        if (orderByColumns.length() == 0 && !overridePrimaryKey) {
            if (orderedColumnNames.isEmpty()) {
                throw new DDLReplicationException(String.format(
                        "Cannot derive a sorting key for `%s`.%s: the CREATE TABLE declares no stored "
                                + "(non-generated) column. Refusing to create a ReplacingMergeTree table "
                                + "with ORDER BY tuple(), which would collapse every row into one. "
                                + "Source DDL: [%s]",
                        databaseName, tableName, originalSql), null);
            }
            log.error(KeylessTableWarning.banner(databaseName, tableName));
            for (String column : orderedColumnNames) {
                if (!notNullColumnNames.contains(MySqlDDLParserListenerImpl.stripBackticks(column))) {
                    nullableSortingKey = true;
                }
            }
            log.warn("Table {}.{} has no PRIMARY KEY and no NOT NULL UNIQUE key; using every stored "
                            + "column as the ReplacingMergeTree sorting key so distinct rows stay "
                            + "distinct: {}. Rows identical in every column will still collapse, and a "
                            + "column added later is not part of this key.",
                    databaseName, tableName, orderedColumnNames);
            orderByColumns.append("(").append(String.join(",", orderedColumnNames)).append(")");
        }

        return nullableSortingKey;
    }

    /**
     * Splits a MySQL index column list into its individual column names.
     *
     * <p>The parse tree hands back the list already flattened, e.g.
     * {@code (a,b)} or {@code (a(10),b)}. Any prefix length is dropped: it
     * narrows the index, not the column, and plays no part in nullability.</p>
     *
     * @param indexColumns the raw index column list text.
     * @return the bare column names in declaration order.
     */
    private static List<String> splitIndexColumns(String indexColumns) {
        List<String> columns = new ArrayList<>();
        if (indexColumns == null || indexColumns.isEmpty()) {
            return columns;
        }
        String stripped = indexColumns.trim();
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        for (String part : stripped.split(",")) {
            String column = MySqlDDLParserListenerImpl.stripBackticks(part).trim().replaceAll("\\(\\d+\\)$", "");
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * The {@code is_deleted} column name for the {@code ReplacingMergeTree}
     * engine clause, renamed {@code _is_deleted} when the source table
     * already declares a column named {@code is_deleted} (case-insensitive),
     * so the engine clause never names the same column twice.
     *
     * @param columnNames every column name declared by the CREATE TABLE.
     * @return {@code is_deleted}, or {@code _is_deleted} on a collision.
     */
    static String resolveIsDeletedColumnName(Set<String> columnNames) {
        String isDeletedColumn = IS_DELETED_COLUMN;

        // Iterate through columnNames and match isDeletedColumn with elements in columnNames.
        for (String columnName : columnNames) {
            if (columnName.contains("`")) {
                columnName = columnName.replace("`", "");
            }
            if (columnName.equalsIgnoreCase(isDeletedColumn)) {
                isDeletedColumn = "_" + IS_DELETED_COLUMN;
                break;
            }
        }
        return isDeletedColumn;
    }

    /**
     * The {@code ENGINE=...} clause for a {@code CREATE TABLE}, choosing
     * between {@code ReplicatedReplacingMergeTree} and
     * {@code ReplacingMergeTree}, and between the new engine's
     * {@code (version, is_deleted)} arguments and the old engine's single
     * {@code (version)} argument.
     *
     * @param isNewReplacingMergeTreeEngine  true when the destination
     *                                       ClickHouse version supports the
     *                                       {@code is_deleted} engine
     *                                       argument.
     * @param isReplicatedReplacingMergeTree true when the destination is a
     *                                       replicated engine.
     * @param versionColumn                  the version column name.
     * @param isDeletedColumn                the (possibly renamed)
     *                                       is-deleted column name.
     * @return the clause text, with a leading space, ready to append to the
     *         {@code CREATE TABLE} statement.
     */
    static String engineClause(boolean isNewReplacingMergeTreeEngine, boolean isReplicatedReplacingMergeTree,
                               String versionColumn, String isDeletedColumn) {
        if (isNewReplacingMergeTreeEngine) {
            if (isReplicatedReplacingMergeTree) {
                return String.format(" Engine=ReplicatedReplacingMergeTree(%s, %s)", versionColumn, isDeletedColumn);
            }
            return " Engine=ReplacingMergeTree(" + versionColumn + "," + isDeletedColumn + ")";
        } else {
            if (isReplicatedReplacingMergeTree) {
                return String.format(" Engine=ReplicatedReplacingMergeTree(%s)", versionColumn);
            }
            return " Engine=ReplacingMergeTree(" + versionColumn + ")";
        }
    }

    /**
     * Cleans up an invalid column-prefix-length suffix (e.g.
     * {@code id_registro(10)}) that an index column list may carry into the
     * {@code ORDER BY} clause text.
     *
     * @param orderByColumns the sorting-key column list.
     * @return the sanitized column list, or the input unchanged if no such
     *         suffix is present.
     */
    static String sanitizeOrderBy(String orderByColumns) {
        // Regex pattern to detect invalid column suffix like id_registro(10)
        String regex = "\\b(\\w+)\\(\\d+\\)";

        if (orderByColumns.matches(".*" + regex + ".*")) {
            // If pattern is matched: clean up suffix
            return orderByColumns.replaceAll(regex, "$1");
        }
        // Otherwise, return the orderByColumns unchanged
        return orderByColumns;
    }
}
