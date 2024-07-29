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
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation.getCdcSectionBasedOnOperation;

public class PreparedStatementExecutor {
    private static final Logger log = LogManager.getLogger(PreparedStatementExecutor.class);

    private String replacingMergeTreeDeleteColumn;
    private boolean replacingMergeTreeWithIsDeletedColumn;

    private String signColumn;
    private String versionColumn;


    private ZoneId serverTimeZone;

    private String databaseName;

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
     * Function to iterate through records and add it to JDBC prepared statement
     * batch
     *
     * @param queryToRecordsMap
     */
    public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd,
                                                     ClickHouseSinkConnectorConfig config,
                                                     ClickHouseConnection conn,
                                                     String tableName,
                                                     Map<String, String> columnToDataTypeMap,
                                                     DBMetadata.TABLE_ENGINE engine) throws RuntimeException {

        boolean result = false;
        Iterator<Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> iter = queryToRecordsMap.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry = iter.next();
            String insertQuery = entry.getKey().getKey();
            log.info(String.format("*** INSERT QUERY for Database(%s) ***: %s", databaseName, insertQuery));
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)

            if(false == executePreparedStatement(insertQuery, topicName, entry, bmd, config,
                    conn, tableName, columnToDataTypeMap, engine)) {
                log.error(String.format("**** ERROR: executing prepared statement for Database(%s), " +
                        "table(%s), Query(%s) ****", databaseName, tableName, insertQuery));
                result = false;
                break;
            } else {
                result = true;
            }
            if(entry.getValue().isEmpty()) {
                // All records were processed.
                iter.remove();
            }
            Metrics.updateCounters(topicName, entry.getValue().size());

        }

        return result;
    }

    private boolean executePreparedStatement(String insertQuery, String topicName,
                                          Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry,
                                          BlockMetaData bmd, ClickHouseSinkConnectorConfig config,
                                          ClickHouseConnection conn, String tableName, Map<String, String> columnToDataTypeMap,
                                          DBMetadata.TABLE_ENGINE engine) throws RuntimeException {

        AtomicBoolean result = new AtomicBoolean(false);
        long maxRecordsInBatch = config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString());
        List<ClickHouseStruct> failedRecords = new ArrayList<>();

        // Split the records into batches.
        Lists.partition(entry.getValue(), (int)maxRecordsInBatch).forEach(batch -> {

            String databaseName = null;
            ArrayList<ClickHouseStruct> truncatedRecords = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(insertQuery)) {

                //List<ClickHouseStruct> recordsList = entry.getValue();
                for (ClickHouseStruct record : batch) {
                    if(record.getDatabase() != null)
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

                // ToDo: should we check for EXECUTE_FAILED
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
                PreparedStatement ps = null;
                try {
                    ps = conn.prepareStatement("TRUNCATE TABLE " + databaseName + "." + tableName);
                } catch (SQLException e) {
                    log.error("*** Error: Truncate table statement error ****", e);
                    throw new RuntimeException(e);
                }
                try {
                    ps.execute();
                } catch (SQLException e) {
                    log.error("*** Error: Truncate table statement execute error ****", e);
                    throw new RuntimeException(e);
                }
            }
        });

        return result.get();
    }


    /**
     * @param ps
     * @param fields
     * @param record
     */
    public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, List<Field> fields,
                                        ClickHouseStruct record, Struct struct, boolean beforeSection,
                                        ClickHouseSinkConnectorConfig config,
                                        Map<String, String> columnNameToDataTypeMap,
                                        DBMetadata.TABLE_ENGINE engine, String tableName) throws Exception {


        // int index = 1;
        // Use this map's key natural ordering as the source of truth.
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            //for (Field f : fields) {
            String colName = entry.getKey();
            //String colName = f.name();

            if(colName == null) {
                continue;
            }
            if(columnNameToIndexMap == null) {
                log.error("Column Name to Index map error");
            }

            int index = -1;
            //int index = 1;
            if(true == columnNameToIndexMap.containsKey(colName)) {
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
                if (value == null) {
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
            } catch (DataException e) {
                // Struct .get throws a DataException
                // if the field is not present.
                // If the record was not supplied, we need to set it as null.
                // Ignore version and sign columns.
                if(colName.equalsIgnoreCase(versionColumn) || colName.equalsIgnoreCase(signColumn) ||
                        colName.equalsIgnoreCase(replacingMergeTreeDeleteColumn)) {

                } else {
                    log.error(String.format("********** ERROR: Database(%s), Table(%s), ClickHouse column %s not present in source ************", databaseName, tableName, colName));
                    log.error(String.format("********** ERROR: Database(%s), Table(%s), Setting column %s to NULL might fail for non-nullable columns ************", databaseName, tableName, colName));
                }
                ps.setNull(index, Types.OTHER);
                continue;
            }

            if (!columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }
            //for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {

            //ToDo: Map the Clickhouse types as a Enum.


            Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = struct.get(f);
            if(type == Schema.Type.ARRAY) {
                schemaName = f.schema().valueSchema().type().name();
            }
            // This will throw an exception, unknown data type.
            ClickHouseDataType chDataType = getClickHouseDataType(colName, columnNameToDataTypeMap);

            if(false == ClickHouseDataTypeMapper.convert(type, schemaName, value, index, ps, config, chDataType, serverTimeZone)) {
                log.error(String.format("**** DATA TYPE NOT HANDLED type(%s), name(%s), column name(%s)", type.toString(),
                        schemaName, colName));
            }
        }

        // Kafka metadata columns.
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString())) {
                if (columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    if(columnNameToIndexMap != null && columnNameToIndexMap.containsKey(metaDataColName)) {
                        TableMetaDataWriter.addKafkaMetaData(metaDataColName, record, columnNameToIndexMap.get(metaDataColName), ps);
                    }
                }
            }
        }

        // Sign column.
        //String signColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_SIGN_COLUMN);
        if(engine != null && engine.getEngine() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine() &&
                signColumn != null)
            if (columnNameToDataTypeMap.containsKey(signColumn) && columnNameToIndexMap.containsKey(signColumn)) {
                int signColumnIndex = columnNameToIndexMap.get(signColumn);
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    ps.setInt(signColumnIndex, -1);
                } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())){
                    if(beforeSection == true) {
                        ps.setInt(signColumnIndex, - 1);
                    } else {
                        ps.setInt(signColumnIndex, 1);
                    }
                } else {
                    ps.setInt(signColumnIndex, 1);
                }

            }

        // Version column.
        //String versionColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_VERSION_COLUMN);
        if(engine != null &&
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
                        } else {
                            ps.setLong(columnNameToIndexMap.get(versionColumn),  record.getSequenceNumber());
                        }
                    }

            }
            // Sign column to mark deletes in ReplacingMergeTree
            if(this.replacingMergeTreeDeleteColumn != null && columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
                if(columnNameToIndexMap.containsKey(replacingMergeTreeDeleteColumn) &&
                        config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString()) == false) {
                    if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                        if(replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                    } else {
                        if(replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                    }
                }
            }
        }

        // Store raw data in JSON form.
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString())) {
            String userProvidedColName = config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString());
            String rawDataColumnDataType = columnNameToDataTypeMap.get(userProvidedColName);
            if (columnNameToDataTypeMap.containsKey(userProvidedColName) &&  rawDataColumnDataType.contains("String")) {
                if(columnNameToIndexMap.containsKey(userProvidedColName)) {
                    TableMetaDataWriter.addRawData(struct, columnNameToIndexMap.get(userProvidedColName), ps);
                }
            }
        }
    }

    /**
     * Case-insensitive
     *
     * @param fields
     * @param colName
     * @return
     */
    private Field getFieldByColumnName(List<Field> fields, String colName) {
        // ToDo: Change it to a map so that multiple loops are avoided
        Field matchingField = null;
        for (Field f : fields) {
            if (f.name().equalsIgnoreCase(colName)) {
                matchingField = f;
                break;
            }
        }
        return matchingField;
    }




    public ClickHouseDataType getClickHouseDataType(String columnName, Map<String, String> columnNameToDataTypeMap) {

        ClickHouseDataType chDataType = null;
        try {
            String columnDataType = columnNameToDataTypeMap.get(columnName);
            ClickHouseColumn column = ClickHouseColumn.of(columnName, columnDataType);

            if(column != null) {
                chDataType = column.getDataType();
            }
        } catch(Exception e) {
            log.debug("Unknown data type ", chDataType);
        }

        return chDataType;
    }


}
