package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects schema drift between PostgreSQL (via Debezium {@link SourceRecord} schema
 * metadata) and ClickHouse (via {@code system.columns}).
 *
 * <h2>Design overview</h2>
 * <ul>
 *   <li>Maintains a per-table cache ({@code Map<String, CacheEntry>}) of
 *       column names known to exist in ClickHouse, with TTL-based expiry.</li>
 *   <li>On every DML event the Debezium field list is compared against the cached
 *       ClickHouse schema.  Missing columns trigger
 *       {@link PostgresSchemaReconciler#addMissingColumns(String, String, Map)}.</li>
 *   <li>After reconciliation the cache is invalidated so the next event re-reads the
 *       fresh ClickHouse schema.</li>
 *   <li>A per-table cooldown of {@value #RECONCILE_COOLDOWN_MS} ms prevents hammering
 *       ClickHouse with DDL queries during a burst of events.</li>
 *   <li>The map is a {@link ConcurrentHashMap} for thread-safety when the batch
 *       executor dispatches events concurrently.</li>
 * </ul>
 *
 * <h2>Cache TTL policy</h2>
 * <ul>
 *   <li>Entries recorded when the table was <em>absent</em> from ClickHouse expire
 *       after {@value #TABLE_ABSENT_TTL_MS} ms so that the detector retries quickly
 *       once the connector's auto-create mechanism has created the table.</li>
 *   <li>Entries with a populated schema expire after {@value #SCHEMA_TTL_MS} ms to
 *       pick up columns added externally (e.g. via a manual ALTER TABLE).</li>
 * </ul>
 *
 * <h2>CDC-internal columns ignored during comparison</h2>
 * The following columns are added by the connector itself and must not be treated
 * as "missing from ClickHouse" when they appear in Debezium schema:
 * {@code _sign, _version, _topic, _offset, _partition}.
 */
public class PostgresSchemaChangeDetector {

    private static final Logger log = LogManager.getLogger(PostgresSchemaChangeDetector.class);

    /**
     * Minimum gap (ms) between two reconciliation attempts for the same table.
     * Prevents a burst of inserts from triggering repeated DDL checks.
     */
    static final long RECONCILE_COOLDOWN_MS = 10_000L;

    /**
     * TTL (ms) for a cache entry that was recorded when the table did not yet exist
     * in ClickHouse.  Short so the detector retries soon after auto-create fires.
     */
    static final long TABLE_ABSENT_TTL_MS = 5_000L;

    /**
     * TTL (ms) for a cache entry that holds a populated schema.  After this period
     * the schema is re-fetched to detect columns added externally via DDL.
     */
    static final long SCHEMA_TTL_MS = 60_000L;

    /**
     * Internal CDC columns added by the connector – not sourced from PostgreSQL.
     * These must be excluded when comparing Debezium schema against ClickHouse.
     */
    private static final Set<String> CDC_INTERNAL_COLUMNS = Set.of(
            "_sign", "_version", "_topic", "_offset", "_partition");

    // -----------------------------------------------------------------------
    // TTL-aware cache entry
    // -----------------------------------------------------------------------

    /**
     * Immutable snapshot of a single ClickHouse schema fetch result, together with
     * TTL metadata so callers can decide whether to use or discard the cached value.
     */
    static final class CacheEntry {
        /** Column name → ClickHouse type map; {@code null} when the table was absent. */
        final Map<String, String> schema;
        /** {@code true} if the table did not exist at fetch time. */
        final boolean wasAbsent;
        /** Epoch-ms timestamp of when this entry was created. */
        final long cachedAtMs;

        CacheEntry(Map<String, String> schema, boolean wasAbsent) {
            this.schema = schema;
            this.wasAbsent = wasAbsent;
            this.cachedAtMs = System.currentTimeMillis();
        }

        /** Returns {@code true} if this entry has exceeded its TTL and must be re-fetched. */
        boolean isExpired() {
            long ttl = wasAbsent ? TABLE_ABSENT_TTL_MS : SCHEMA_TTL_MS;
            return (System.currentTimeMillis() - cachedAtMs) > ttl;
        }
    }

    /**
     * Cache: fully-qualified table key ({@code "database.table"}) →
     * {@link CacheEntry} holding the last-fetched ClickHouse column schema and
     * TTL metadata.
     *
     * <p>Entries are replaced (not removed) on expiry so concurrent reads remain
     * consistent: a stale entry is only discarded once a fresh fetch succeeds.
     */
    private final ConcurrentHashMap<String, CacheEntry> clickHouseSchemaCache =
            new ConcurrentHashMap<>();

    /**
     * Tracks the last time (epoch ms) a reconciliation was attempted for each table.
     * Used to enforce the {@link #RECONCILE_COOLDOWN_MS} cooldown.
     */
    private final ConcurrentHashMap<String, Long> lastReconcileAttempt =
            new ConcurrentHashMap<>();

    /** Writer whose JDBC connection is used to query {@code system.columns}. */
    private final BaseDbWriter writer;

    /** Connector configuration – passed to {@link DBMetadata} and {@link PostgresSchemaReconciler}. */
    private final ClickHouseSinkConnectorConfig config;

    /** Delegate that executes the actual DDL on ClickHouse. */
    private final PostgresSchemaReconciler reconciler;

    /**
     * Constructs a PostgresSchemaChangeDetector.
     *
     * @param writer the {@link BaseDbWriter} connected to ClickHouse
     * @param config the connector configuration
     */
    public PostgresSchemaChangeDetector(BaseDbWriter writer, ClickHouseSinkConnectorConfig config) {
        this.writer = writer;
        this.config = config;
        this.reconciler = new PostgresSchemaReconciler(writer, config);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Called on every DML {@link SourceRecord}.
     *
     * <ol>
     *   <li>Extracts the field list from the Debezium record's {@code after} (or
     *       {@code before} for DELETEs) schema envelope.</li>
     *   <li>Compares against the cached ClickHouse schema for the table.</li>
     *   <li>If a mismatch is found (and the cooldown has elapsed), invokes
     *       {@link PostgresSchemaReconciler#addMissingColumns} and then invalidates
     *       the cache so the next event fetches a fresh schema.</li>
     * </ol>
     *
     * <p>All exceptions are caught and logged – this method <em>never</em> throws
     * so that a schema-detection failure cannot halt replication.
     *
     * @param record       Debezium {@link SourceRecord} (contains full schema metadata)
     * @param tableName    target table name in ClickHouse
     * @param databaseName target database in ClickHouse
     */
    public void checkAndReconcile(SourceRecord record, String tableName, String databaseName) {
        if (record == null || tableName == null || databaseName == null) {
            return;
        }

        try {
            // 1. Extract Debezium field schema from the record envelope.
            Map<String, Schema> debeziumFields = extractDebeziumSchema(record);
            if (debeziumFields == null || debeziumFields.isEmpty()) {
                return;
            }

            String cacheKey = databaseName + "." + tableName;

            // 2. Resolve the ClickHouse schema from the TTL-aware cache.
            //    Re-fetch if: (a) no entry exists, (b) entry is expired, or
            //    (c) the entry recorded a table-absent result (wasAbsent) and has now expired.
            CacheEntry entry = clickHouseSchemaCache.get(cacheKey);
            if (entry == null || entry.isExpired()) {
                Map<String, String> fetched = fetchClickHouseSchema(databaseName, tableName);
                if (fetched == null) {
                    // Table not yet in ClickHouse – cache as absent with short TTL.
                    clickHouseSchemaCache.put(cacheKey, new CacheEntry(null, true));
                    log.debug("Table {}.{} absent from ClickHouse; cached with {}ms TTL",
                            databaseName, tableName, TABLE_ABSENT_TTL_MS);
                    return;
                }
                entry = new CacheEntry(fetched, false);
                clickHouseSchemaCache.put(cacheKey, entry);
            } else if (entry.wasAbsent) {
                // Entry is within its short TTL but table was absent – skip without re-fetching.
                log.debug("Table {}.{} still absent (cached); skipping drift detection", databaseName, tableName);
                return;
            }

            Map<String, String> chSchema = entry.schema;
            if (chSchema == null) {
                // Safety: should not happen given the logic above, but guard anyway.
                return;
            }

            // 3. Find columns present in Debezium but absent from ClickHouse.
            Map<String, Schema> missingColumns = findMissingColumns(debeziumFields, chSchema);

            if (missingColumns.isEmpty()) {
                // No drift – fast path.
                return;
            }

            // 4. Enforce cooldown before attempting reconciliation.
            long now = System.currentTimeMillis();
            Long lastAttempt = lastReconcileAttempt.get(cacheKey);
            if (lastAttempt != null && (now - lastAttempt) < RECONCILE_COOLDOWN_MS) {
                log.debug("Schema drift detected for {}.{} but cooldown active ({} ms remaining); skipping.",
                        databaseName, tableName, RECONCILE_COOLDOWN_MS - (now - lastAttempt));
                return;
            }

            // 5. Record attempt timestamp before executing DDL.
            lastReconcileAttempt.put(cacheKey, now);

            log.info("Schema drift detected for {}.{}: {} column(s) missing from ClickHouse: {}",
                    databaseName, tableName, missingColumns.size(), missingColumns.keySet());

            // 6. Reconcile (add the missing columns in ClickHouse).
            reconciler.addMissingColumns(databaseName, tableName, missingColumns);

            // 7. Invalidate cache so the next event fetches the updated schema.
            invalidateCache(cacheKey);

        } catch (Exception e) {
            log.warn("Schema drift detection failed for table {}.{} – continuing replication. Cause: {}",
                    databaseName, tableName, e.getMessage(), e);
        }
    }

    /**
     * Invalidates the cached ClickHouse schema for the specified table key.
     * The key format is {@code "database.table"}.
     *
     * @param tableKey the fully-qualified table key ({@code "database.table"})
     */
    public void invalidateCache(String tableKey) {
        clickHouseSchemaCache.remove(tableKey);
        log.debug("Schema cache invalidated for {}", tableKey);
    }

    /**
     * Returns the current cache entry for the given table key, or {@code null} if absent.
     * Intended for testing only.
     */
    CacheEntry getCacheEntry(String tableKey) {
        return clickHouseSchemaCache.get(tableKey);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts the field name → Debezium {@link Schema} mapping from a Debezium
     * SourceRecord's value schema.
     *
     * <p>In a Debezium PostgreSQL envelope the value schema looks like:
     * <pre>
     * Envelope {
     *   before: Struct { id INT, name STRING, … }
     *   after:  Struct { id INT, name STRING, new_col FLOAT64, … }   ← preferred
     *   source: Struct { … }
     *   op:     STRING
     * }
     * </pre>
     *
     * <p>For DELETE events {@code after} is {@code null}, so {@code before} is used
     * as the fallback.  For all other event types {@code after} is used.
     *
     * @param record the Debezium {@link SourceRecord}
     * @return ordered map of field name → field {@link Schema}, or {@code null}
     *         if the schema cannot be extracted
     */
    private Map<String, Schema> extractDebeziumSchema(SourceRecord record) {
        try {
            Schema valueSchema = record.valueSchema();
            if (valueSchema == null) {
                return null;
            }

            // Prefer "after" (INSERT / UPDATE); fall back to "before" (DELETE).
            Schema rowSchema = null;

            org.apache.kafka.connect.data.Field afterField = valueSchema.field("after");
            if (afterField != null && afterField.schema() != null
                    && afterField.schema().type() == Schema.Type.STRUCT) {
                // For DELETE, the "after" value in the Struct will be null, so check the value too.
                Struct valueStruct = record.value() instanceof Struct ? (Struct) record.value() : null;
                Object afterValue = (valueStruct != null) ? safeGet(valueStruct, "after") : null;
                if (afterValue != null) {
                    rowSchema = afterField.schema();
                }
            }

            if (rowSchema == null) {
                org.apache.kafka.connect.data.Field beforeField = valueSchema.field("before");
                if (beforeField != null && beforeField.schema() != null
                        && beforeField.schema().type() == Schema.Type.STRUCT) {
                    rowSchema = beforeField.schema();
                }
            }

            if (rowSchema == null) {
                return null;
            }

            List<org.apache.kafka.connect.data.Field> fields = rowSchema.fields();
            if (fields == null || fields.isEmpty()) {
                return null;
            }

            Map<String, Schema> result = new LinkedHashMap<>(fields.size());
            for (org.apache.kafka.connect.data.Field field : fields) {
                result.put(field.name(), field.schema());
            }
            return result;

        } catch (Exception e) {
            log.debug("Could not extract Debezium schema from SourceRecord: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Queries {@code system.columns} in ClickHouse to get the current set of
     * columns for the given table.
     *
     * <p><strong>Important:</strong> returns {@code null} (not an empty map) when
     * the table does not yet exist in ClickHouse.  The caller ({@link #checkAndReconcile})
     * treats a {@code null} return as "table not yet created – skip drift detection
     * so the connector's auto-create mechanism can work undisturbed".  An empty
     * (but non-{@code null}) map is only returned for genuine errors so that drift
     * detection is silently skipped without triggering reconciliation.
     *
     * @param database ClickHouse database name
     * @param table    ClickHouse table name
     * @return map of column name → ClickHouse type string; {@code null} if the table
     *         does not exist in ClickHouse yet; empty map on connection / query error
     */
    private Map<String, String> fetchClickHouseSchema(String database, String table) {
        String columnSql = String.format(
                "SELECT name, type FROM system.columns WHERE database = '%s' AND table = '%s'",
                database, table);

        try {
            Connection conn = writer.getConnection();
            if (conn == null) {
                log.warn("Cannot fetch ClickHouse schema for {}.{}: connection is null", database, table);
                return Collections.emptyMap();  // error path – skip detection
            }

            Map<String, String> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(columnSql)) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getString(2));
                }
            }

            if (!result.isEmpty()) {
                log.debug("Fetched {} columns from ClickHouse for table {}.{}", result.size(), database, table);
                return result;
            }

            // system.columns returned 0 rows.  Distinguish between:
            //   (a) table does not exist yet → return null so checkAndReconcile skips reconciliation
            //   (b) table exists but genuinely has 0 user columns (rare) → return empty map
            String existsSql = String.format(
                    "SELECT count() FROM system.tables WHERE database = '%s' AND name = '%s'",
                    database, table);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(existsSql)) {
                if (rs.next() && rs.getLong(1) == 0) {
                    // Table is not present in ClickHouse yet – let auto-create handle it.
                    log.debug("Table {}.{} not yet in ClickHouse – skipping drift detection for this event",
                            database, table);
                    return null;
                }
            }

            // Table exists but has 0 matching columns – return empty map.
            return result;

        } catch (Exception e) {
            log.warn("Failed to fetch ClickHouse schema for {}.{}: {}", database, table, e.getMessage());
            // Return empty map – caller will skip drift detection silently.
            return Collections.emptyMap();
        }
    }

    /**
     * Computes the set of columns present in {@code debeziumFields} but absent from
     * {@code chSchema}. CDC-internal columns ({@link #CDC_INTERNAL_COLUMNS}) are
     * excluded from the comparison.
     *
     * @param debeziumFields map of field name → Schema from the Debezium record
     * @param chSchema       map of column name → ClickHouse type from the cache
     * @return map of column name → Schema for columns that need to be added to
     *         ClickHouse; never {@code null}, may be empty
     */
    private Map<String, Schema> findMissingColumns(Map<String, Schema> debeziumFields,
                                                    Map<String, String> chSchema) {
        Map<String, Schema> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> entry : debeziumFields.entrySet()) {
            String fieldName = entry.getKey();
            if (CDC_INTERNAL_COLUMNS.contains(fieldName)) {
                continue;
            }
            if (!chSchema.containsKey(fieldName)) {
                missing.put(fieldName, entry.getValue());
            }
        }
        return missing;
    }

    /**
     * Safely retrieves a field value from a {@link Struct}, returning {@code null}
     * if the field does not exist or the access throws.
     */
    private static Object safeGet(Struct struct, String fieldName) {
        try {
            return struct.get(fieldName);
        } catch (Exception e) {
            return null;
        }
    }
}
