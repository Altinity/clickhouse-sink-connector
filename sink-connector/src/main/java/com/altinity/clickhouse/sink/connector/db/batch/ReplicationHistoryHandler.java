package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles the execution of replication history (SCD Type 2) updates
 * (Spec 12.03). This class encapsulates the logic for generating and executing
 * the UNION ALL query pattern used for temporal tracking of record changes.
 *
 * The UPDATE query pattern (Spec 12.03 section 3.2):
 * 1. First SELECT: Closes the open row at the BEFORE-image key (_valid_to = event time)
 * 2. Second SELECT: Inserts the new "after" image with open-ended _valid_to
 * 3. Third SELECT, only when the primary key changed: a delete marker at the old key
 *
 * The DELETE query pattern closes the open row and appends a delete marker
 * (section 3.3); a replicated truncation closes every open row the same way
 * (section 3.4, {@link #executeHistoryBulkClose}). Every row of one event carries
 * the record's ONE standard version (section 3.5).
 */
public class ReplicationHistoryHandler {

    private static final Logger log = LogManager.getLogger(ReplicationHistoryHandler.class);

    private final QueryFormatter queryFormatter;
    private final DBMetadata dbMetadata;
    private final ZoneId sourceTimeZone;
    private final ZoneId serverTimeZone;
    /**
     * {@code snowflake.id}: how {@link ClickHouseStruct#calculateVersion(boolean)}
     * derives a version that the standard INSERT path has not derived yet. The
     * same flag the field mapper binds INSERT rows with, so the history rows of
     * an event and its INSERT row live in one version domain (Spec 12.03 section 3.5).
     */
    private final boolean useSnowflakeId;
    /**
     * Creates a new ReplicationHistoryHandler with default dependencies (creates its own {@link DBMetadata}).
     *
     * @param config The connector configuration
     */
    public ReplicationHistoryHandler(ClickHouseSinkConnectorConfig config, ZoneId serverTimeZone) {
        this(config, serverTimeZone, new DBMetadata(config));
    }

    /**
     * Creates a new ReplicationHistoryHandler reusing an existing {@link DBMetadata} instance.
     * Prefer this constructor from batch executors that already hold a {@code DBMetadata} for the same connection
     * to avoid per-record allocation of metadata helpers.
     *
     * @param config The connector configuration
     * @param serverTimeZone ClickHouse server time zone
     * @param dbMetadata Shared database metadata (e.g. same instance as {@code PreparedStatementExecutor}'s batch metadata)
     */
    public ReplicationHistoryHandler(ClickHouseSinkConnectorConfig config, ZoneId serverTimeZone, DBMetadata dbMetadata) {
        this.queryFormatter = new QueryFormatter();
        this.dbMetadata = dbMetadata;

        String sourceTz = "UTC";
        if (config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString()) != null) {
            String configSourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
            if (configSourceTimeZone != null && !configSourceTimeZone.isEmpty()) {
                sourceTz = configSourceTimeZone;
            }
        }
        this.sourceTimeZone = ZoneId.of(sourceTz);
        this.serverTimeZone = serverTimeZone;
        this.useSnowflakeId = config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString());
    }

    /**
     * Creates a new ReplicationHistoryHandler with injectable dependencies for testing.
     * Versions not yet derived are derived the raw-GTID way ({@code snowflake.id=false}).
     *
     * @param queryFormatter The query formatter to use
     * @param dbMetadata The database metadata handler to use
     */
    @VisibleForTesting
    public ReplicationHistoryHandler(QueryFormatter queryFormatter, DBMetadata dbMetadata) {
        this.queryFormatter = queryFormatter;
        this.dbMetadata = dbMetadata;
        this.sourceTimeZone = ZoneId.of("UTC");
        this.serverTimeZone = ZoneId.of("UTC");
        this.useSnowflakeId = false;
    }

    /**
     * Renders an epoch-seconds instant as the {@code DateTime} literal text the
     * history statements embed: converted "without timezone adjustment" from the
     * source timezone to the server timezone (Spec 12.03 section 3.6).
     */
    private String serverDateTime(long epochSeconds) {
        return DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(epochSeconds * 1000,
                ClickHouseDataType.DateTime, sourceTimeZone, serverTimeZone);
    }

    /** The open-row sentinel {@code 2100-01-01 00:00:00} (Spec 12.02 section 3.1), rendered like {@link #serverDateTime}. */
    private String openRowSentinel() {
        return serverDateTime(DataTypeRange.DATETIME32_MAX_TTL);
    }

    /**
     * The ONE version every history row of this event carries: the record's
     * standard version (Spec 12.03 section 3.5, Gap G-12.03-4) -- the same
     * floor-clamped, monotonic value {@code ClickHouseStruct.calculateVersion}
     * gives the INSERT path -- never a separate {@code SnowFlakeId} of
     * {@code (ts_ms, gtid)}, which ignored the sequence number and the commit
     * floor and collided within a millisecond without GTIDs.
     *
     * <p>The standard path derives the version lazily at bind time
     * ({@code PreparedStatementFieldMapper.handleVersionColumn}); the history
     * statements are built BEFORE any binding, and the DELETE and TRUNCATE
     * statements bind nothing, so the same lazy derivation happens here with the
     * same {@code snowflake.id} flag. A version that cannot be derived is refused
     * loudly (Spec 02.05 section 3.2): bound as {@code -1} it would become the
     * maximum UInt64 and win every merge for the key forever.</p>
     *
     * @param record the change event
     * @return {@code record.getVersion()}, positive
     * @throws IllegalStateException when no version can be derived for the record
     */
    public long resolveVersion(ClickHouseStruct record) {
        if (record.getVersion() == -1) {
            record.calculateVersion(useSnowflakeId);
        }
        PreparedStatementFieldMapper.rejectUnderivableVersion(record);
        return record.getVersion();
    }

    /**
     * Generates the parameters needed for the replication history update query.
     *
     * <p>The close predicate (and the key-change marker) use the BEFORE-image
     * primary key: that is the row the event replaces. Taking the key from the
     * after image closed nothing when an UPDATE changed a primary-key column, so
     * the old key kept an open row forever next to the new key's open row -- two
     * current rows for one source row (Gap G-12.03-3). {@code keyChanged} is set
     * when any key column differs between the two images.</p>
     *
     * @param record The CDC record containing the change data
     * @return UpdateQueryParams containing all parameters needed for the query
     * @throws IllegalStateException if the record carries no primary key, no row
     *         image, or no derivable version
     */
    public UpdateQueryParams buildUpdateQueryParams(ClickHouseStruct record) {
        // Convert epoch seconds to date strings
        String validToMax = openRowSentinel();
        String binlogRecordTimestamp = serverDateTime(record.getTsSec());

        // The record's standard version, shared by every row this event emits.
        long version = resolveVersion(record);

        // Every primary-key column with its value, in key order: the previous history
        // row is closed by the WHOLE key. Closing on the first column alone closed
        // every row that shared it (spec 02.01 section 3.5 a).
        List<String> primaryKeyColumns = record.getPrimaryKey();
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
            throw new IllegalStateException("History mode cannot close the previous row for topic "
                    + record.getTopic() + ": the record carries no primary key (spec 02.01 section 3.5 a)");
        }
        // UPDATE and DELETE carry the before image: the row being closed. A record
        // with only an after image (never an UPDATE today) can only be keyed by it.
        Struct closeImage = record.getBeforeStruct() != null ? record.getBeforeStruct() : record.getAfterStruct();
        if (closeImage == null) {
            throw new IllegalStateException("History mode cannot close the previous row for topic "
                    + record.getTopic() + ": the " + record.getCdcOperation()
                    + " record carries neither a before nor an after image");
        }
        Struct afterImage = record.getAfterStruct() != null ? record.getAfterStruct() : closeImage;
        Map<String, Object> primaryKey = new LinkedHashMap<>();
        boolean keyChanged = false;
        for (String column : primaryKeyColumns) {
            Object beforeValue = closeImage.get(column);
            primaryKey.put(column, beforeValue);
            if (!Objects.equals(beforeValue, afterImage.get(column))) {
                keyChanged = true;
            }
        }

        return new UpdateQueryParams(
                validToMax,
                binlogRecordTimestamp,
                version,
                primaryKey,
                keyChanged,
                record.getCdcOperation()
        );
    }

    /**
     * Generates the INSERT query with UNION ALL for the replication history update.
     *
     * @param tableName The target table name
     * @param fields The list of fields to include in the query
     * @param columnToDataTypeMap Map of column names to their ClickHouse data types
     * @param params The query parameters
     * @return A pair containing the query string and the column-to-index map for PreparedStatement
     */
    public MutablePair<String, Map<String, Integer>> generateUpdateQuery(
            String tableName,
            List<Field> fields,
            Map<String, String> columnToDataTypeMap,
            UpdateQueryParams params) {

        return queryFormatter.getInsertQueryForUpdate(
                tableName,
                columnToDataTypeMap,
                params.getPrimaryKey(),
                params.getValidToMax(),
                params.getBinlogRecordTimestamp(),
                params.getVersion(),
                params.getCdcOperation(),
                serverTimeZone.getId(),
                params.isKeyChanged()
        );
    }

    /**
     * Generates the 2-SELECT UNION ALL query for replication history DELETE (SCD2 delete pattern).
     * No parameter binding is needed; both SELECTs read from the table.
     *
     * @param tableName The target table name
     * @param columnToDataTypeMap Map of column names to their ClickHouse data types
     * @param params The query parameters
     * @return A pair containing the query string and empty column-to-index map
     */
    public MutablePair<String, Map<String, Integer>> generateDeleteQuery(
            String tableName,
            Map<String, String> columnToDataTypeMap,
            UpdateQueryParams params) {

        return queryFormatter.getInsertQueryForDelete(
                tableName,
                columnToDataTypeMap,
                params.getPrimaryKey(),
                params.getValidToMax(),
                params.getBinlogRecordTimestamp(),
                params.getVersion(),
                serverTimeZone.getId()
        );
    }

    /**
     * Executes the replication history update for a single record.
     * This creates and executes a prepared statement with the UNION ALL query pattern.
     * <p>
     * Per-record {@link PreparedStatement} creation is required today because {@code QueryFormatter}
     * embeds primary key, timestamps, and version literals in the SQL; see
     * {@code doc/replication_history_prepared_statement_reuse.md} for a path to reuse statements across records.
     * </p>
     *
     * @param conn The database connection
     * @param tableName The target table name
     * @param record The CDC record to process
     * @param columnToDataTypeMap Map of column names to their ClickHouse data types
     * @param fieldMapper The field mapper for populating the prepared statement
     * @param columnIndexMap The column-to-index map for the main query (used for after values)
     * @param config The connector configuration
     * @param engine The table engine type
     * @return true if the update was executed successfully
     * @throws SQLException if a database error occurs
     */
    public boolean executeHistoryUpdate(
            Connection conn,
            String tableName,
            ClickHouseStruct record,
            Map<String, String> columnToDataTypeMap,
            PreparedStatementFieldMapper fieldMapper,
            Map<String, Integer> columnIndexMap,
            ClickHouseSinkConnectorConfig config,
            DBMetadata.TABLE_ENGINE engine, boolean isDelete ) throws Exception {

        // Build query parameters from the record
        UpdateQueryParams params = buildUpdateQueryParams(record);

        final String insertQuery;
        final Map<String, Integer> queryColumnIndexMap;

        if (isDelete) {
            MutablePair<String, Map<String, Integer>> queryResult = generateDeleteQuery(tableName, columnToDataTypeMap, params);

            insertQuery = queryResult.left;
            queryColumnIndexMap = queryResult.right;
        } else {
            MutablePair<String, Map<String, Integer>> queryResult = generateUpdateQuery(
                    tableName,
                    record.getAfterModifiedFields(),
                    columnToDataTypeMap,
                    params
            );
            insertQuery = queryResult.left;
            queryColumnIndexMap = queryResult.right;
        }

        log.debug("Executing replication history {} query: {}", isDelete ? "delete" : "update", insertQuery);

        try (PreparedStatement ps = dbMetadata.getPreparedStatement(conn, insertQuery)) {
            if (!isDelete)
            {
                // Populate the prepared statement with after values (second SELECT only)
                fieldMapper.insertPreparedStatement(
                        queryColumnIndexMap,
                        ps,
                        record.getAfterModifiedFields(),
                        record,
                        record.getAfterStruct(),
                        false,
                        config,
                        columnToDataTypeMap,
                        engine,
                        tableName
                );
            }

            ps.addBatch();
            int[] batchResult = ps.executeBatch();

            log.debug("Replication history {} executed successfully for table: {}", isDelete ? "delete" : "update", tableName);
            return batchResult.length > 0;
        }
    }

    /**
     * Applies a replicated truncation to an SCD2 table the history way (Spec 12.03
     * section 3.4, Gap G-12.03-6): ONE statement closes every visible open row at
     * the event time and appends a delete marker at each row's open sorting key.
     * Only INSERTs are issued -- the closed versions survive and the current-state
     * view becomes empty, exactly as the source table did.
     *
     * @param conn               the database connection
     * @param qualifiedTable     {@code database.table}
     * @param hasIsDeletedColumn whether the table carries {@code is_deleted}
     * @param tsSec              the event's source time in epoch seconds
     * @param version            the event's version (see {@link #resolveVersion}), shared by every row
     * @param op                 the operation stored in {@code _operation} ({@code TRUNCATE}, letter {@code 'T'})
     * @throws IllegalStateException when {@code version} is not positive
     * @throws java.sql.SQLException if ClickHouse refuses the statement
     */
    public void executeHistoryBulkClose(Connection conn, String qualifiedTable, boolean hasIsDeletedColumn,
                                        long tsSec, long version, ClickHouseConverter.CDC_OPERATION op)
            throws Exception {
        if (version <= 0) {
            throw new IllegalStateException(String.format(
                    "History bulk close of %s refused: version %d is not a derivable event version "
                            + "(Spec 02.05 section 3.2)", qualifiedTable, version));
        }
        String query = queryFormatter.getInsertQueryForBulkClose(qualifiedTable, hasIsDeletedColumn,
                openRowSentinel(), serverDateTime(tsSec), version, op.getOperation(), serverTimeZone.getId());

        log.debug("Executing replication history bulk close ({}) query: {}", op, query);

        try (PreparedStatement ps = dbMetadata.getPreparedStatement(conn, query)) {
            ps.execute();
        }
        log.debug("Replication history bulk close ({}) executed successfully for table: {}", op, qualifiedTable);
    }

    /**
     * Container class for update query parameters.
     * Makes it easier to pass around and test the parameter generation logic.
     */
    public static class UpdateQueryParams {
        private final String validToMax;
        private final String binlogRecordTimestamp;
        private final long version;
        /** Every primary-key column with its value, in key order (spec 02.01 section 3.5 a). */
        private final Map<String, Object> primaryKey;
        /** Whether the after image carries a different primary key than {@link #primaryKey} (Gap G-12.03-3). */
        private final boolean keyChanged;
        private final ClickHouseConverter.CDC_OPERATION cdcOperation;

        public UpdateQueryParams(
                String validToMax,
                String binlogRecordTimestamp,
                long version,
                String primaryKeyColumnName,
                Object primaryKeyValue,
                ClickHouseConverter.CDC_OPERATION cdcOperation) {
            this(validToMax, binlogRecordTimestamp, version,
                    singleColumn(primaryKeyColumnName, primaryKeyValue), false, cdcOperation);
        }

        public UpdateQueryParams(
                String validToMax,
                String binlogRecordTimestamp,
                long version,
                Map<String, Object> primaryKey,
                ClickHouseConverter.CDC_OPERATION cdcOperation) {
            this(validToMax, binlogRecordTimestamp, version, primaryKey, false, cdcOperation);
        }

        public UpdateQueryParams(
                String validToMax,
                String binlogRecordTimestamp,
                long version,
                Map<String, Object> primaryKey,
                boolean keyChanged,
                ClickHouseConverter.CDC_OPERATION cdcOperation) {
            this.validToMax = validToMax;
            this.binlogRecordTimestamp = binlogRecordTimestamp;
            this.version = version;
            this.primaryKey = new LinkedHashMap<>(primaryKey);
            this.keyChanged = keyChanged;
            this.cdcOperation = cdcOperation;
        }

        private static Map<String, Object> singleColumn(String columnName, Object value) {
            Map<String, Object> primaryKey = new LinkedHashMap<>();
            primaryKey.put(columnName, value);
            return primaryKey;
        }

        /** The whole primary key: column name to value, in key order. */
        public Map<String, Object> getPrimaryKey() {
            return primaryKey;
        }

        public String getValidToMax() {
            return validToMax;
        }

        public String getBinlogRecordTimestamp() {
            return binlogRecordTimestamp;
        }

        public long getVersion() {
            return version;
        }

        /** The first primary-key column (the whole key is {@link #getPrimaryKey()}). */
        public String getPrimaryKeyColumnName() {
            return primaryKey.isEmpty() ? null : primaryKey.keySet().iterator().next();
        }

        /** The first primary-key column's value (the whole key is {@link #getPrimaryKey()}). */
        public Object getPrimaryKeyValue() {
            return primaryKey.isEmpty() ? null : primaryKey.values().iterator().next();
        }

        /** Whether the UPDATE moved the row to another primary key, so the old key needs a delete marker. */
        public boolean isKeyChanged() {
            return keyChanged;
        }

        public ClickHouseConverter.CDC_OPERATION getCdcOperation() {
            return cdcOperation;
        }

        @Override
        public String toString() {
            return "UpdateQueryParams{" +
                    "validToMax='" + validToMax + '\'' +
                    ", binlogRecordTimestamp='" + binlogRecordTimestamp + '\'' +
                    ", version=" + version +
                    ", primaryKeyColumnName='" + getPrimaryKeyColumnName() + '\'' +
                    ", primaryKeyValue=" + getPrimaryKeyValue() +
                    ", primaryKey=" + primaryKey +
                    ", keyChanged=" + keyChanged +
                    ", cdcOperation=" + cdcOperation +
                    '}';
        }
    }
}

