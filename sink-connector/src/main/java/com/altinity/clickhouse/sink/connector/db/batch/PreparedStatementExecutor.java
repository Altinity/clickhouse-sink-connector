package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation.getCdcSectionBasedOnOperation;

/**
 * The PreparedStatementExecutor class is responsible for executing prepared
 * SQL statements in batches for inserting data into ClickHouse. It handles the
 * logic for processing CDC (Change Data Capture) operations, including handling
 * truncations, updates, and inserts with the appropriate data mapping and conversions.
 */
public class PreparedStatementExecutor {
    /**
     * Logger instance for logging purposes.
     * This logger is used throughout the class to log messages related to database operations.
     */
    private static final Logger log = LogManager.getLogger(PreparedStatementExecutor.class);

    /**
     * The column name used for "delete" operations in the ReplacingMergeTree engine.
     * This column is used to track deleted records in ClickHouse when using
     * ReplacingMergeTree with deletion support.
     */
    private String replacingMergeTreeDeleteColumn;

    /**
     * Flag indicating whether the ReplacingMergeTree engine uses an "isDeleted" column.
     * If true, the engine uses an "isDeleted" column to mark records as deleted instead
     * of directly using the "delete" column.
     */
    private boolean replacingMergeTreeWithIsDeletedColumn;

    /**
     * The name of the column used for the sign of a record, typically used in the
     * context of ReplacingMergeTree engines to track changes (e.g., a flag for changes).
     */
    private String signColumn;

    /**
     * The name of the version column, used in ReplacingMergeTree engines to track the
     * version of records for conflict resolution or replacement logic.
     */
    private String versionColumn;

    /**
     * The server's time zone used for converting timestamps and handling date/time
     * related operations.
     */
    private ZoneId serverTimeZone;

    /**
     * The name of the database being used for the operations in this class.
     * This is typically set when connecting to the ClickHouse instance.
     */
    private String databaseName;

    /**
     * Constructor for PreparedStatementExecutor.
     * Initializes the instance with the provided configuration values.
     *
     * @param replacingMergeTreeDeleteColumn The column used for deletion in ReplacingMergeTree.
     * @param replacingMergeTreeWithIsDeletedColumn Whether to use the "is_deleted" column for deletion.
     * @param signColumn The sign column to mark updates and deletes.
     * @param versionColumn The version column for ReplacingMergeTree.
     * @param databaseName The name of the database.
     * @param serverTimeZone The time zone for the server.
     */
    public PreparedStatementExecutor(String replacingMergeTreeDeleteColumn,
                                     boolean replacingMergeTreeWithIsDeletedColumn,
                                     String signColumn, String versionColumn,
                                     String databaseName, ZoneId serverTimeZone) {
        this.replacingMergeTreeDeleteColumn = replacingMergeTreeDeleteColumn;
        this.replacingMergeTreeWithIsDeletedColumn = replacingMergeTreeWithIsDeletedColumn;
        this.signColumn = signColumn;
        this.versionColumn = versionColumn;
        this.serverTimeZone = serverTimeZone;
        this.databaseName = databaseName;
    }

    /**
     * Iterates through records and adds them to a JDBC prepared statement batch
     * for execution. It processes each query, logging the insert query and
     * managing any errors that occur during execution.
     *
     * @param topicName The Kafka topic name.
     * @param queryToRecordsMap The map of queries to records.
     * @param bmd Block metadata.
     * @param config Connector configuration.
     * @param conn The database connection.
     * @param tableName The name of the target table.
     * @param columnToDataTypeMap A map of column names to their data types.
     * @param engine The table engine to use.
     * @return true if all queries are successfully executed; false otherwise.
     * @throws Exception if an error occurs during execution.
     */
    public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd,
                                               ClickHouseSinkConnectorConfig config,
                                               Connection conn,
                                               String tableName,
                                               Map<String, String> columnToDataTypeMap,
                                               DBMetadata.TABLE_ENGINE engine) throws Exception {

        boolean result = false;
        Iterator<Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> iter = queryToRecordsMap.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry = iter.next();
            String insertQuery = entry.getKey().getKey();
            log.info(String.format("*** INSERT QUERY for Database(%s) ***: %s", databaseName, insertQuery));
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)

            if (false == executePreparedStatement(insertQuery, topicName, entry, bmd, config,
                    conn, tableName, columnToDataTypeMap, engine)) {
                log.error(String.format("**** ERROR: executing prepared statement for Database(%s), " +
                        "table(%s), Query(%s) ****", databaseName, tableName, insertQuery));
                result = false;
                break;
            } else {
                result = true;
            }
            if (entry.getValue().isEmpty()) {
                // All records were processed.
                iter.remove();
            }
            Metrics.updateCounters(topicName, entry.getValue().size());
        }

        return result;
    }

    /**
     * Executes the prepared statement in batches, processing CDC operations
     * such as insert, update, delete, and truncate.
     *
     * @param insertQuery The SQL insert query.
     * @param topicName The Kafka topic name.
     * @param entry The entry from the query-to-record map.
     * @param bmd Block metadata.
     * @param config Connector configuration.
     * @param conn The database connection.
     * @param tableName The name of the table.
     * @param columnToDataTypeMap A map of column names to data types.
     * @param engine The table engine to use.
     * @return true if the batch is successfully executed; false otherwise.
     * @throws Exception if an error occurs during batch execution.
     */
    private boolean executePreparedStatement(String insertQuery, String topicName,
                                             Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry,
                                             BlockMetaData bmd, ClickHouseSinkConnectorConfig config,
                                             Connection conn, String tableName, Map<String, String> columnToDataTypeMap,
                                             DBMetadata.TABLE_ENGINE engine) throws Exception {

        AtomicBoolean result = new AtomicBoolean(false);
        long maxRecordsInBatch = config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString());
        List<ClickHouseStruct> failedRecords = new ArrayList<>();

        Lists.partition(entry.getValue(), (int)maxRecordsInBatch).forEach(batch -> {

            String databaseName = null;
            ArrayList<ClickHouseStruct> truncatedRecords = new ArrayList<>();

            DBMetadata metadata = new DBMetadata();
            try (PreparedStatement ps = metadata.getPreparedStatement(conn, insertQuery)) {

                for (ClickHouseStruct record : batch) {
                    if (record.getDatabase() != null)
                        databaseName = record.getDatabase();

                    try {
                        bmd.update(record);
                    } catch (Exception e) {
                        log.error("**** ERROR: updating Prometheus", e);
                    }

                    if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
                        truncatedRecords.add(record);
                        continue;
                    }

                    if (CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                true, config, columnToDataTypeMap, engine, tableName);
                    } else if (CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(),
                                false, config, columnToDataTypeMap, engine, tableName);
                    } else if (CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (engine != null && engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                            insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                        }
                        insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(),
                                false, config, columnToDataTypeMap, engine, tableName);
                    } else {
                        log.error("INVALID CDC RECORD STATE");
                    }

                    ps.addBatch();
                }

                int[] batchResult = ps.executeBatch();

                long taskId = config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
                log.info("*************** EXECUTED BATCH Successfully " + "Records: " + batch.size() + "************** " +
                        "task(" + taskId + ")" + " Thread ID: " +
                        Thread.currentThread().getName() + " Result: " +
                        batchResult.toString() + " Database: "
                        + databaseName + " Table: " + tableName);
                result.set(true);

            } catch (Exception e) {
                Metrics.updateErrorCounters(topicName, entry.getValue().size());
                log.error(String.format("******* ERROR inserting Batch Database(%s), Table(%s) *****************",
                        databaseName, tableName), e);
                failedRecords.addAll(batch);
                throw new RuntimeException(e);
            }

            if (!truncatedRecords.isEmpty()) {
                try {
                    metadata.truncateTable(conn, databaseName, tableName);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return result.get();
    }

    /**
     * Inserts the fields of a ClickHouseStruct into the prepared statement for execution.
     * This method maps the column names from the record to the corresponding prepared
     * statement indices and handles various data types, including Kafka metadata, sign,
     * and version columns. It also handles special operations like handling deletes in
     * ReplacingMergeTree engines.
     *
     * @param columnNameToIndexMap A map of column names to their respective index positions
     *                             in the prepared statement.
     * @param ps The prepared statement where the values will be set.
     * @param fields The list of fields from the Kafka record schema.
     * @param record The ClickHouse struct containing the actual data.
     * @param struct The Kafka struct representing the record data.
     * @param beforeSection Flag indicating whether the operation is before or after the change.
     * @param config The configuration for the ClickHouse Sink connector.
     * @param columnNameToDataTypeMap A map of column names to their corresponding data types.
     * @param engine The table engine being used (e.g., COLLAPSING_MERGE_TREE, REPLACING_MERGE_TREE).
     * @param tableName The name of the target ClickHouse table.
     * @throws Exception if an error occurs while setting values or executing the prepared statement.
     */
    public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap,
                                        PreparedStatement ps, List<Field> fields,
                                        ClickHouseStruct record, Struct struct, boolean beforeSection,
                                        ClickHouseSinkConnectorConfig config,
                                        Map<String, String> columnNameToDataTypeMap,
                                        DBMetadata.TABLE_ENGINE engine, String tableName) throws Exception {

        // Iterate through the column names and map the values to their indices in the prepared statement.
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String colName = entry.getKey();

            // Skip processing if the column name is null.
            if (colName == null) {
                continue;
            }

            // Log error if columnNameToIndexMap is null.
            if (columnNameToIndexMap == null) {
                log.error("Column Name to Index map error");
            }

            // Get the index position of the column in the prepared statement.
            int index = -1;
            if (columnNameToIndexMap.containsKey(colName)) {
                index = columnNameToIndexMap.get(colName);
            } else {
                log.error("***** Column index missing for column ****" + colName);
                continue;
            }

            //String colName = entry.getKey();

            //ToDO: Setting null to a non-nullable field)
            // will throw an error.
            // If the Received column is not a clickhouse column
            try {
                Object value = struct.get(colName);

                boolean nonDefault = config.getBoolean(ClickHouseSinkConnectorConfigVariables.NON_DEFAULT_VALUE.toString());
                // if config non.default.value is set, use it.
                if (nonDefault) {
                    value = struct.getWithoutDefault(colName);
                }
                if (value == null) {
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
            } catch (DataException e) {
                // Struct .get throws a DataException
                // if the field is not present.
                // If the record was not supplied, we need to set it as null.
                // Ignore version and sign columns.
                if (colName.equalsIgnoreCase(versionColumn) || colName.equalsIgnoreCase(signColumn) ||
                        colName.equalsIgnoreCase(replacingMergeTreeDeleteColumn)) {
                    // Ignore version and sign columns
                } else {
                    log.error(String.format("********** ERROR: Database(%s), Table(%s), ClickHouse column %s not present in source ************", databaseName, tableName, colName));
                    log.error(String.format("********** ERROR: Database(%s), Table(%s), Setting column %s to NULL might fail for non-nullable columns ************", databaseName, tableName, colName));
                }
                ps.setNull(index, Types.OTHER);
                continue;
            }

            // If the column is not in the column data type map, log an error.
            if (!columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }

            // Get the field information for the column and handle its data type.
            Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = struct.get(f);
            if (type == Schema.Type.ARRAY) {
                schemaName = f.schema().valueSchema().type().name();
            }
            // This will throw an exception, unknown data type.
            ClickHouseDataType chDataType = getClickHouseDataType(colName, columnNameToDataTypeMap);
            if (!ClickHouseDataTypeMapper.convert(type, schemaName, value, index, ps, config, chDataType, serverTimeZone)) {
                log.error(String.format("**** DATA TYPE NOT HANDLED type(%s), name(%s), column name(%s)", type.toString(),
                        schemaName, colName));
            }
        }

        // Handle Kafka metadata columns if configured to store Kafka metadata.
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString())) {
                if (columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    if (columnNameToIndexMap != null && columnNameToIndexMap.containsKey(metaDataColName)) {
                        TableMetaDataWriter.addKafkaMetaData(metaDataColName, record, columnNameToIndexMap.get(metaDataColName), ps);
                    }
                }
            }
        }

        // Handle Sign column for COLLAPSING_MERGE_TREE engine.
        if (engine != null && engine.getEngine() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine() && signColumn != null) {
            if (columnNameToDataTypeMap.containsKey(signColumn) && columnNameToIndexMap.containsKey(signColumn)) {
                int signColumnIndex = columnNameToIndexMap.get(signColumn);
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    ps.setInt(signColumnIndex, -1);
                } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                    if (beforeSection) {
                        ps.setInt(signColumnIndex, -1);
                    } else {
                        ps.setInt(signColumnIndex, 1);
                    }
                } else {
                    ps.setInt(signColumnIndex, 1);
                }
            }
        }

        // Handle Version column for REPLACING_MERGE_TREE and REPLICATED_REPLACING_MERGE_TREE engines.
        if (engine != null &&
                (engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine() ||
                        engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine())
                && versionColumn != null) {
            if (columnNameToDataTypeMap.containsKey(versionColumn)) {
                if (columnNameToIndexMap.containsKey(versionColumn)) {
                    if (record.getGtid() != -1) {
                        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString())) {
                            ps.setLong(columnNameToIndexMap.get(versionColumn), SnowFlakeId.generate(record.getTs_ms(), record.getGtid(), false));
                        } else {
                            ps.setLong(columnNameToIndexMap.get(versionColumn), record.getGtid());
                        }
                    } else {
                        ps.setLong(columnNameToIndexMap.get(versionColumn), record.getSequenceNumber());
                    }
                }
            }
        }

        // Handle Sign column to mark deletes in ReplacingMergeTree.
        if (this.replacingMergeTreeDeleteColumn != null && columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
            if (columnNameToIndexMap.containsKey(replacingMergeTreeDeleteColumn) &&
                    !config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    if (replacingMergeTreeWithIsDeletedColumn)
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                    else
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                } else {
                    if (replacingMergeTreeWithIsDeletedColumn)
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                    else
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                }
            }
        }

        // Store raw data in JSON form if configured.
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString())) {
            String userProvidedColName = config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString());
            String rawDataColumnDataType = columnNameToDataTypeMap.get(userProvidedColName);
            if (columnNameToDataTypeMap.containsKey(userProvidedColName) && rawDataColumnDataType.contains("String")) {
                if (columnNameToIndexMap.containsKey(userProvidedColName)) {
                    TableMetaDataWriter.addRawData(struct, columnNameToIndexMap.get(userProvidedColName), ps);
                }
            }
        }
    }

    /**
     * Retrieves a field from a list of fields based on the column name. The search
     * is case-insensitive.
     *
     * @param fields The list of fields to search through.
     * @param colName The column name to search for.
     * @return The matching field, or null if no field matches the column name.
     */
    private Field getFieldByColumnName(List<Field> fields, String colName) {
        // ToDo: Change it to a map so that multiple loops are avoided
        Field matchingField = null;
        for (Field f : fields) {
            // Case-insensitive comparison of field name with column name
            if (f.name().equalsIgnoreCase(colName)) {
                matchingField = f;
                break;
            }
        }
        return matchingField;
    }

    /**
     * Retrieves the ClickHouse data type for a given column by looking up the
     * column's data type in the provided map.
     *
     * @param columnName The name of the column.
     * @param columnNameToDataTypeMap A map that contains column names as keys
     *                                and their corresponding data types as values.
     * @return The ClickHouse data type for the column, or null if the type is unknown.
     */
    public ClickHouseDataType getClickHouseDataType(String columnName,
                                                    Map<String, String> columnNameToDataTypeMap) {

        ClickHouseDataType chDataType = null;
        try {
            // Retrieve the column data type from the map
            String columnDataType = columnNameToDataTypeMap.get(columnName);
            // Create a ClickHouse column object based on the column name and type
            ClickHouseColumn column = ClickHouseColumn.of(columnName, columnDataType);

            // Retrieve the data type from the ClickHouse column if available
            if (column != null) {
                chDataType = column.getDataType();
            }
        } catch (Exception e) {
            // Log any error related to unknown data types
            log.debug("Unknown data type for column: " + columnName, e);
        }

        return chDataType;
    }
}
