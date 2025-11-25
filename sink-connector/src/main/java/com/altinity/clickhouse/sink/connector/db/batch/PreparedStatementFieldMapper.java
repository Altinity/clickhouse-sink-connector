package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

/**
 * Handles the mapping and insertion of ClickHouseStruct fields into PreparedStatements.
 * This class is responsible for:
 * - Mapping column names to PreparedStatement indices
 * - Converting data types
 * - Handling Kafka metadata columns
 * - Managing sign, version, and delete columns for various ClickHouse engines
 * - Handling replication history columns
 */
public class PreparedStatementFieldMapper {

    /**
     * Logger instance for logging purposes.
     */
    private static final Logger log = LogManager.getLogger(PreparedStatementFieldMapper.class);

    /**
     * The column name used for "delete" operations in the ReplacingMergeTree engine.
     */
    private final String replacingMergeTreeDeleteColumn;

    /**
     * Flag indicating whether the ReplacingMergeTree engine uses an "isDeleted" column.
     */
    private final boolean replacingMergeTreeWithIsDeletedColumn;

    /**
     * The name of the column used for the sign of a record (CollapsingMergeTree).
     */
    private final String signColumn;

    /**
     * The name of the version column (ReplacingMergeTree).
     */
    private final String versionColumn;

    /**
     * The server's time zone used for converting timestamps.
     */
    private final ZoneId serverTimeZone;

    /**
     * The name of the database being used for the operations.
     */
    private final String databaseName;

    /**
     * Constructor for PreparedStatementFieldMapper.
     *
     * @param replacingMergeTreeDeleteColumn The column used for deletion in ReplacingMergeTree.
     * @param replacingMergeTreeWithIsDeletedColumn Whether to use the "is_deleted" column for deletion.
     * @param signColumn The sign column to mark updates and deletes.
     * @param versionColumn The version column for ReplacingMergeTree.
     * @param databaseName The name of the database.
     * @param serverTimeZone The time zone for the server.
     */
    public PreparedStatementFieldMapper(String replacingMergeTreeDeleteColumn,
                                       boolean replacingMergeTreeWithIsDeletedColumn,
                                       String signColumn,
                                       String versionColumn,
                                       String databaseName,
                                       ZoneId serverTimeZone) {
        this.replacingMergeTreeDeleteColumn = replacingMergeTreeDeleteColumn;
        this.replacingMergeTreeWithIsDeletedColumn = replacingMergeTreeWithIsDeletedColumn;
        this.signColumn = signColumn;
        this.versionColumn = versionColumn;
        this.databaseName = databaseName;
        this.serverTimeZone = serverTimeZone;
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
                    if(!config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
                        log.error(String.format("********** ERROR: Database(%s), Table(%s), ClickHouse column %s not present in source ************", databaseName, tableName, colName));
                        log.error(String.format("********** ERROR: Database(%s), Table(%s), Setting column %s to NULL might fail for non-nullable columns ************", databaseName, tableName, colName));
                    }
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
        handleKafkaMetadata(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap);

        // Handle Sign column for COLLAPSING_MERGE_TREE engine.
        handleSignColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, engine, beforeSection);

        // Handle replication history columns
        handleReplicationHistoryColumns(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, beforeSection);

        // Handle Version column for REPLACING_MERGE_TREE engines.
        handleVersionColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, engine);

        // Handle Sign column to mark deletes in ReplacingMergeTree.
        handleReplacingMergeTreeDeleteColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, beforeSection);

        // Store raw data in JSON form if configured.
        handleRawDataStorage(columnNameToIndexMap, ps, struct, config, columnNameToDataTypeMap);
    }

    /**
     * Handles Kafka metadata columns.
     */
    private void handleKafkaMetadata(Map<String, Integer> columnNameToIndexMap,
                                     PreparedStatement ps,
                                     ClickHouseStruct record,
                                     ClickHouseSinkConnectorConfig config,
                                     Map<String, String> columnNameToDataTypeMap) throws Exception {
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
    }

    /**
     * Handles Sign column for COLLAPSING_MERGE_TREE engine.
     */
    private void handleSignColumn(Map<String, Integer> columnNameToIndexMap,
                                   PreparedStatement ps,
                                   ClickHouseStruct record,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> columnNameToDataTypeMap,
                                   DBMetadata.TABLE_ENGINE engine,
                                   boolean beforeSection) throws Exception {
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
    }

    /**
     * Handles replication history columns (deleted_time, deleted_from_time, operation).
     */
    private void handleReplicationHistoryColumns(Map<String, Integer> columnNameToIndexMap,
                                                  PreparedStatement ps,
                                                  ClickHouseStruct record,
                                                  ClickHouseSinkConnectorConfig config,
                                                  Map<String, String> columnNameToDataTypeMap,
                                                  boolean beforeSection) throws Exception {
        if (!config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            return;
        }

        String sourceTimeZone = "UTC";
        if(config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString()) != null){
            String configSourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
            if(configSourceTimeZone != null && !configSourceTimeZone.isEmpty()) {
                sourceTimeZone = configSourceTimeZone;
            }
        }

        // Handle deleted_time column
        if (columnNameToDataTypeMap.containsKey(DELETED_TIME_COLUMN) && columnNameToIndexMap.containsKey(DELETED_TIME_COLUMN)) {
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation()) ||
                    record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                
                if(beforeSection) {
                    ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                            DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                            ZoneId.of(sourceTimeZone), serverTimeZone));
                }
                else {
                    ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                            DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                                    ZoneId.of(sourceTimeZone), serverTimeZone));                        
                }
            } else {
                ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            }
        }

        // Handle deleted_from_time column
        if (columnNameToDataTypeMap.containsKey(DELETED_FROM_TIME_COLUMN) && columnNameToIndexMap.containsKey(DELETED_FROM_TIME_COLUMN)) {
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation()) ||
                    record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                ps.setString(columnNameToIndexMap.get(DELETED_FROM_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            } else {
                ps.setString(columnNameToIndexMap.get(DELETED_FROM_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
//                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
//                                ZoneId.of(sourceTimeZone), serverTimeZone));
            }
        }

        // Handle operation column
        if(columnNameToDataTypeMap.containsKey(OPERATION_COLUMN) && columnNameToIndexMap.containsKey(OPERATION_COLUMN)) {
            ps.setString(columnNameToIndexMap.get(OPERATION_COLUMN), record.getCdcOperation().getOperation());
        }
    }

    /**
     * Handles Version column for REPLACING_MERGE_TREE engines.
     */
    private void handleVersionColumn(Map<String, Integer> columnNameToIndexMap,
                                      PreparedStatement ps,
                                      ClickHouseStruct record,
                                      ClickHouseSinkConnectorConfig config,
                                      Map<String, String> columnNameToDataTypeMap,
                                      DBMetadata.TABLE_ENGINE engine) throws Exception {
        Long version=SnowFlakeId.generate(record.getTs_ms(), record.getGtid(), false);
        if (engine != null &&
                (engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine() ||
                        engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine())
                && versionColumn != null) {
            if (columnNameToDataTypeMap.containsKey(versionColumn)) {
                    if(columnNameToIndexMap.containsKey(versionColumn)) {
                        if (record.getGtid() != -1) {
                            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString())) {
                                ps.setLong(columnNameToIndexMap.get(versionColumn), SnowFlakeId.generate(record.getTs_ms(), record.getGtid(), false));
                            } else {
                                ps.setLong(columnNameToIndexMap.get(versionColumn), record.getGtid());
                            }
                        } else if (record.getSequenceNumber() != -1) {
                            ps.setLong(columnNameToIndexMap.get(versionColumn),  record.getSequenceNumber());
                        } else {
                            ps.setLong(columnNameToIndexMap.get(versionColumn),  record.getLsn());
                        }
                }
            }
        }
    }

    /**
     * Handles delete column for ReplacingMergeTree.
     */
    private void handleReplacingMergeTreeDeleteColumn(Map<String, Integer> columnNameToIndexMap,
                                                       PreparedStatement ps,
                                                       ClickHouseStruct record,
                                                       ClickHouseSinkConnectorConfig config,
                                                       Map<String, String> columnNameToDataTypeMap,
                                                       boolean beforeSection) throws Exception {
        if (this.replacingMergeTreeDeleteColumn != null && columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
            if (columnNameToIndexMap.containsKey(replacingMergeTreeDeleteColumn) &&
                    !config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    // if after section and REPLICATION HISTORY ENABLE is set to true in config
                    if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        if(!beforeSection){
                            if (replacingMergeTreeWithIsDeletedColumn)
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                            else
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                        } else {
                            // before section.
                            if (replacingMergeTreeWithIsDeletedColumn)
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                            else
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        }
                    } else {
                        if (replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                    }
                } else {
                    if (replacingMergeTreeWithIsDeletedColumn)
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                    else
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                }
            }
        }
    }

    /**
     * Handles raw data storage if configured.
     */
    private void handleRawDataStorage(Map<String, Integer> columnNameToIndexMap,
                                       PreparedStatement ps,
                                       Struct struct,
                                       ClickHouseSinkConnectorConfig config,
                                       Map<String, String> columnNameToDataTypeMap) throws Exception {
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

