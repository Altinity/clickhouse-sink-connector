package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates that every column present in a source change event also exists in
 * the (freshly rebuilt) destination ClickHouse column cache before INSERTs are
 * allowed to resume after a DDL change.
 *
 * <p><b>Why.</b> The connector builds INSERT statements by iterating the
 * destination column cache, not the source event. A column that exists in the
 * source event but is missing from the destination cache is therefore
 * <i>silently dropped</i> — the value never reaches ClickHouse and the column
 * is left at its DEFAULT. This is data loss. add/drop is only one way the two
 * schemas can diverge; rename, modify, and reorder all produce the same class
 * of mismatch. This validator generalizes the check: it compares the full set
 * of source columns against the destination cache and reports any source
 * column the destination cannot store.</p>
 *
 * <p>The validator is deliberately <b>asymmetric</b>: destination-only columns
 * (e.g. {@code _version}, {@code _sign}, {@code is_deleted}, Kafka metadata,
 * materialized/alias columns) are expected and ignored. Only source columns
 * absent from the destination are flagged, because those are the ones whose
 * values would be lost.</p>
 */
public final class SourceSchemaIntegrityValidator {

    private static final Logger log =
            LogManager.getLogger(SourceSchemaIntegrityValidator.class);

    private SourceSchemaIntegrityValidator() {
    }

    /** Result of an integrity check. */
    public static final class Result {
        private final boolean consistent;
        private final List<String> missingInDestination;

        Result(boolean consistent, List<String> missingInDestination) {
            this.consistent = consistent;
            this.missingInDestination = missingInDestination;
        }

        /** True if every source column can be stored in the destination. */
        public boolean isConsistent() {
            return consistent;
        }

        /** Source columns that have no matching destination column. */
        public List<String> getMissingInDestination() {
            return missingInDestination;
        }
    }

    /**
     * Compares source columns against the destination column cache.
     *
     * <p>Comparison is case-insensitive because ClickHouse JDBC metadata and
     * Debezium field names can differ in case. This is conservative: it errs
     * toward declaring a match (no false data-loss alarm) rather than toward a
     * false positive.</p>
     *
     * @param sourceColumns      column names from the source change event.
     * @param destinationColumns column names from the rebuilt destination cache.
     * @return a {@link Result} describing whether all source columns are
     *         storable and, if not, which are missing.
     */
    public static Result check(Collection<String> sourceColumns,
                               Collection<String> destinationColumns) {
        Set<String> destLower = new LinkedHashSet<>();
        if (destinationColumns != null) {
            for (String c : destinationColumns) {
                if (c != null) {
                    destLower.add(c.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> missing = new ArrayList<>();
        if (sourceColumns != null) {
            for (String c : sourceColumns) {
                if (c == null) {
                    continue;
                }
                if (!destLower.contains(c.toLowerCase(Locale.ROOT))) {
                    missing.add(c);
                }
            }
        }
        return new Result(missing.isEmpty(), missing);
    }

    /**
     * Removes from {@code missingColumns} every column that exists in the
     * destination as an ALIAS or MATERIALIZED column.
     *
     * <p>The insertable column cache deliberately EXCLUDES alias/materialized
     * columns — ClickHouse computes their values, so they cannot be inserted
     * into. A MySQL generated column therefore legitimately appears in the
     * source event but not in the insertable cache: the destination derives
     * the same value itself, so not inserting the source value is correct
     * behaviour, not data loss. Treating such a column as "missing" made the
     * integrity gate invalidate and rebuild the writer on every batch,
     * livelocking replication for any table with generated columns
     * (observed: 8,208 invalidate/rebuild cycles on one calculated-columns
     * table before the test timed out).</p>
     *
     * @param missingColumns             columns the insertable cache cannot
     *                                   store.
     * @param aliasOrMaterializedColumns destination ALIAS/MATERIALIZED column
     *                                   names (from system.columns).
     * @return the columns that are genuinely missing (not computed by the
     *         destination). Case-insensitive match, consistent with
     *         {@link #check}.
     */
    public static List<String> excludeGeneratedColumns(
            Collection<String> missingColumns,
            Collection<String> aliasOrMaterializedColumns) {
        List<String> genuine = new ArrayList<>();
        if (missingColumns == null) {
            return genuine;
        }
        Set<String> generatedLower = new LinkedHashSet<>();
        if (aliasOrMaterializedColumns != null) {
            for (String c : aliasOrMaterializedColumns) {
                if (c != null) {
                    generatedLower.add(c.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String c : missingColumns) {
            if (c != null && !generatedLower.contains(c.toLowerCase(Locale.ROOT))) {
                genuine.add(c);
            }
        }
        return genuine;
    }

    /**
     * Runs {@link #check} and, on inconsistency, logs a loud error and throws.
     * Failing loudly (rather than swallowing) is intentional: a missing source
     * column means in-flight inserts would lose data, so the DDL reconciliation
     * must not report success.
     *
     * @param fullyQualifiedTableName table in "database.table" form (for logs).
     * @param sourceColumns           source change-event columns.
     * @param destinationColumns      rebuilt destination cache columns.
     * @throws SchemaIntegrityException if any source column is missing from the
     *                                  destination.
     */
    public static void validateOrThrow(String fullyQualifiedTableName,
                                       Collection<String> sourceColumns,
                                       Collection<String> destinationColumns)
            throws SchemaIntegrityException {
        Result result = check(sourceColumns, destinationColumns);
        if (!result.isConsistent()) {
            String msg = String.format(
                    "Schema integrity violation for %s: source event has column(s) %s "
                            + "that are NOT present in the destination ClickHouse table cache. "
                            + "Inserting now would silently drop these columns (data loss). "
                            + "Source columns=%s, destination columns=%s.",
                    fullyQualifiedTableName,
                    result.getMissingInDestination(),
                    sourceColumns,
                    destinationColumns);
            log.error(msg);
            throw new SchemaIntegrityException(msg, result.getMissingInDestination());
        }
        log.info("Schema integrity OK for {}: all {} source column(s) are storable in destination.",
                fullyQualifiedTableName,
                sourceColumns == null ? 0 : sourceColumns.size());
    }

    /**
     * Returns {@code true} when the given destination table is the configured
     * replication-history (binlog history) table, which must be EXEMPT from
     * the source-schema integrity gate.
     *
     * <p><b>Why.</b> The integrity gate compares the columns of the SOURCE
     * change event against the columns of the DESTINATION table and blocks the
     * writer until they agree. That comparison is only meaningful when the
     * destination mirrors the source table. The history table does not: it has
     * its own fixed audit schema ({@code gtid}, {@code ddl}, {@code database},
     * {@code table}, {@code before}, {@code after}, ...) and the source-row
     * columns are serialized INTO the {@code before}/{@code after} payload
     * columns rather than mapped one-to-one. Comparing e.g. an
     * {@code employees} event against the history schema reports every source
     * column "missing", which is a category error — the gate then blocks each
     * batch for the full visibility-wait timeout polling {@code system.columns}
     * for columns that will never appear, skips the history write, and (with
     * many worker threads all stuck in the wait loop against the shared
     * {@code system} pool) starves the connection pool for the whole process.</p>
     *
     * @param config                  connector configuration (nullable).
     * @param fullyQualifiedTableName destination table in "database.table" form.
     * @return true when the table is the configured history table.
     */
    public static boolean isReplicationHistoryTable(
            ClickHouseSinkConnectorConfig config,
            String fullyQualifiedTableName) {
        if (config == null || fullyQualifiedTableName == null) {
            return false;
        }
        try {
            if (!config.getBoolean(ClickHouseSinkConnectorConfigVariables
                    .REPLICATION_HISTORY_ENABLE.toString())) {
                return false;
            }
            String db = config.getString(ClickHouseSinkConnectorConfigVariables
                    .REPLICATION_HISTORY_DATABASE_NAME.toString());
            String table = config.getString(ClickHouseSinkConnectorConfigVariables
                    .REPLICATION_HISTORY_TABLE_NAME.toString());
            return fullyQualifiedTableName.equalsIgnoreCase(db + "." + table);
        } catch (Exception e) {
            log.warn("Could not determine replication-history table from config: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Thrown when source columns cannot be stored in the destination table.
     */
    public static final class SchemaIntegrityException extends Exception {
        private final List<String> missingColumns;

        public SchemaIntegrityException(String message, List<String> missingColumns) {
            super(message);
            this.missingColumns = missingColumns;
        }

        public List<String> getMissingColumns() {
            return missingColumns;
        }
    }
}
