package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the execution of replication history (SCD Type 2) updates.
 * This class encapsulates the logic for generating and executing the UNION ALL
 * query pattern used for temporal tracking of record changes.
 * 
 * The query pattern:
 * 1. First SELECT: Closes the existing record by updating _valid_to to current timestamp
 * 2. Second SELECT: Inserts the new "after" image with open-ended _valid_to
 * 3. Third SELECT: Inserts the "before" image for PK change tracking
 */
public class ReplicationHistoryHandler {

    private static final Logger log = LogManager.getLogger(ReplicationHistoryHandler.class);

    private final QueryFormatter queryFormatter;
    private final DBMetadata dbMetadata;
    private final ZoneId sourceTimeZone;
    private final ZoneId serverTimeZone;
    /**
     * Creates a new ReplicationHistoryHandler with default dependencies.
     *
     * @param config The connector configuration
     */
    public ReplicationHistoryHandler(ClickHouseSinkConnectorConfig config, ZoneId serverTimeZone) {
        this.queryFormatter = new QueryFormatter();
        this.dbMetadata = new DBMetadata(config);


        String sourceTimeZone = "UTC";
        if(config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString()) != null){
            String configSourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
            if(configSourceTimeZone != null && !configSourceTimeZone.isEmpty()) {
                sourceTimeZone = configSourceTimeZone;
            }
        }
        this.sourceTimeZone = ZoneId.of(sourceTimeZone);
        this.serverTimeZone = serverTimeZone;
    }

    /**
     * Creates a new ReplicationHistoryHandler with injectable dependencies for testing.
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
    }

    /**
     * Generates the parameters needed for the replication history update query.
     *
     * @param record The CDC record containing the change data
     * @return UpdateQueryParams containing all parameters needed for the query
     */
    public UpdateQueryParams buildUpdateQueryParams(ClickHouseStruct record) {
        // Convert epoch seconds to date strings
        String validToMax = DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                sourceTimeZone, serverTimeZone);

        String binlogRecordTimestamp = DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                sourceTimeZone, serverTimeZone);

        // Generate unique version using snowflake algorithm
        long version = SnowFlakeId.generate(record.getTs_ms(), record.getGtid(), false);

        // Get the primary key column name and its value from the record
        String primaryKeyColumnName = record.getPrimaryKey().get(0);
        Object primaryKeyValue = record.getAfterStruct().get(primaryKeyColumnName);

        return new UpdateQueryParams(
                validToMax,
                binlogRecordTimestamp,
                version,
                primaryKeyColumnName,
                primaryKeyValue,
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
                fields,
                columnToDataTypeMap,
                params.getPrimaryKeyColumnName(),
                params.getPrimaryKeyValue(),
                params.getValidToMax(),
                params.getBinlogRecordTimestamp(),
                params.getVersion(),
                params.getCdcOperation(),
                serverTimeZone.getId()
        );
    }

    /**
     * Executes the replication history update for a single record.
     * This creates and executes a prepared statement with the UNION ALL query pattern.
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
            DBMetadata.TABLE_ENGINE engine ) throws Exception {

        // Build query parameters from the record
        UpdateQueryParams params = buildUpdateQueryParams(record);

        // Generate the UNION ALL query
        MutablePair<String, Map<String, Integer>> queryResult = generateUpdateQuery(
                tableName,
                record.getAfterModifiedFields(),
                columnToDataTypeMap,
                params
        );

        String insertQuery = queryResult.left;
        Map<String, Integer> queryColumnIndexMap = queryResult.right;

        log.debug("Executing replication history update query: {}", insertQuery);

        try (PreparedStatement ps = dbMetadata.getPreparedStatement(conn, insertQuery)) {
            // Populate the prepared statement with after values (second SELECT)
            // Filter the column index map to only include non-prefixed keys (after image)
            Map<String, Integer> afterColumnIndexMap = filterAfterImageColumns(queryColumnIndexMap);
            fieldMapper.insertPreparedStatement(
                    afterColumnIndexMap,
                    ps,
                    record.getAfterModifiedFields(),
                    record,
                    record.getAfterStruct(),
                    false,  // isBeforeSection = false for after image
                    config,
                    columnToDataTypeMap,
                    engine,
                    tableName
            );

            // Populate the prepared statement with before values (third SELECT)
            // Translate "before_" prefixed keys to regular column names for the fieldMapper
            if (record.getBeforeStruct() != null && record.getBeforeModifiedFields() != null) {
                Map<String, Integer> beforeColumnIndexMap = translateBeforeImageColumns(queryColumnIndexMap);
                fieldMapper.insertPreparedStatement(
                        beforeColumnIndexMap,
                        ps,
                        record.getBeforeModifiedFields(),
                        record,
                        record.getBeforeStruct(),
                        true,  // isBeforeSection = true for before image
                        config,
                        columnToDataTypeMap,
                        engine,
                        tableName
                );


                // Override _valid_to for before image: should be max date, not binlog timestamp
                // The before image represents a "canceled" record that should have open-ended _valid_to
                if (beforeColumnIndexMap.containsKey(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                    //String validToMax = DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MAX_TTL);
                    //ps.setString(beforeColumnIndexMap.get(ClickHouseDbConstants.DELETED_TIME_COLUMN), validToMax);

                    ps.setString(beforeColumnIndexMap.get(ClickHouseDbConstants.DELETED_TIME_COLUMN),
                    DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                            sourceTimeZone, serverTimeZone));
                }

                // After the fieldMapper call for before image, add:
                if (beforeColumnIndexMap.containsKey(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                    ps.setInt(beforeColumnIndexMap.get(ClickHouseDbConstants.IS_DELETED_COLUMN), 1);
                }
            }

            ps.addBatch();
            int[] batchResult = ps.executeBatch();

            log.debug("Replication history update executed successfully for table: {}", tableName);
            return batchResult.length > 0;
        }
    }

    /**
     * Filters the column index map to only include after image columns (non-prefixed keys).
     *
     * @param columnIndexMap The full column-to-index map
     * @return A new map containing only after image columns
     */
    private Map<String, Integer> filterAfterImageColumns(Map<String, Integer> columnIndexMap) {
        Map<String, Integer> afterColumns = new HashMap<>();
        for (Map.Entry<String, Integer> entry : columnIndexMap.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("before_")) {
                afterColumns.put(key, entry.getValue());
            }
        }
        return afterColumns;
    }

    /**
     * Translates the before image columns from "before_columnName" to "columnName"
     * so they can be used with the standard field mapper.
     *
     * @param columnIndexMap The full column-to-index map
     * @return A new map with translated column names pointing to before image indices
     */
    private Map<String, Integer> translateBeforeImageColumns(Map<String, Integer> columnIndexMap) {
        Map<String, Integer> beforeColumns = new HashMap<>();
        for (Map.Entry<String, Integer> entry : columnIndexMap.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("before_")) {
                // Remove "before_" prefix so fieldMapper can match by column name
                String columnName = key.substring("before_".length());
                beforeColumns.put(columnName, entry.getValue());
            }
        }
        return beforeColumns;
    }

    /**
     * Container class for update query parameters.
     * Makes it easier to pass around and test the parameter generation logic.
     */
    public static class UpdateQueryParams {
        private final String validToMax;
        private final String binlogRecordTimestamp;
        private final long version;
        private final String primaryKeyColumnName;
        private final Object primaryKeyValue;
        private final ClickHouseConverter.CDC_OPERATION cdcOperation;

        public UpdateQueryParams(
                String validToMax,
                String binlogRecordTimestamp,
                long version,
                String primaryKeyColumnName,
                Object primaryKeyValue,
                ClickHouseConverter.CDC_OPERATION cdcOperation) {
            this.validToMax = validToMax;
            this.binlogRecordTimestamp = binlogRecordTimestamp;
            this.version = version;
            this.primaryKeyColumnName = primaryKeyColumnName;
            this.primaryKeyValue = primaryKeyValue;
            this.cdcOperation = cdcOperation;
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

        public String getPrimaryKeyColumnName() {
            return primaryKeyColumnName;
        }

        public Object getPrimaryKeyValue() {
            return primaryKeyValue;
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
                    ", primaryKeyColumnName='" + primaryKeyColumnName + '\'' +
                    ", primaryKeyValue=" + primaryKeyValue +
                    ", cdcOperation=" + cdcOperation +
                    '}';
        }
    }
}

