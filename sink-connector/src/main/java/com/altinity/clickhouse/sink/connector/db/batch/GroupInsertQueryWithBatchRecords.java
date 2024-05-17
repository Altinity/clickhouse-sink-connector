package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation.getCdcSectionBasedOnOperation;

public class GroupInsertQueryWithBatchRecords {

    private static final Logger log = LogManager.getLogger(GroupInsertQueryWithBatchRecords.class);



    /**
     * Function to group the Query with records.
     * Also this slices a chunk of records for processing
     * from the shared data structure(ConcurrentLinkedQueue<ClickHouseStruct>)
     * @param records
     * @return
     */
    public boolean groupQueryWithRecords(List<ClickHouseStruct> records,
                                         Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>
                                                 queryToRecordsMap,
                                         Map<TopicPartition, Long> partitionToOffsetMap,
                                         ClickHouseSinkConnectorConfig config,
                                         String tableName, String databaseName, ClickHouseConnection connection,
                                         Map<String, String> columnNameToDataTypeMap) {
        boolean result = false;

        // Co4 = {ClickHouseStruct@9220} de block to create a Map of Query -> list of records
        // so that all records belonging to the same  query
        // can be inserted as a batch.
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();
            updatePartitionOffsetMap(partitionToOffsetMap, record.getKafkaPartition(), record.getTopic(), record.getKafkaOffset());
            boolean enableSchemaEvolution = config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString());

            if(CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                result = updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap, tableName, config, columnNameToDataTypeMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                if(enableSchemaEvolution) {
                    try {
                        new ClickHouseAlterTable().alterTable(record.getAfterStruct().schema().fields(), tableName, connection, columnNameToDataTypeMap);
                        columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(tableName, connection, databaseName);

                    } catch(Exception e) {
                        log.error("**** ERROR ALTER TABLE: " + tableName, e);
                    }
                }

                result = updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap, tableName, config, columnNameToDataTypeMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation()))  {
                if(record.getBeforeModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap, tableName, config, columnNameToDataTypeMap);
                }
                if(record.getAfterModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap, tableName, config, columnNameToDataTypeMap);
                }
            } else {
                log.error("************ RECORD DROPPED: INVALID CDC RECORD STATE *****************" + record.getSourceRecord());
            }

        }
        return result;
    }

    /**
     * This function updates the map of Query to Records with the insert Query.
     * @param record
     * @param modifiedFields
     * @param queryToRecordsMap
     * @return
     */
    public boolean updateQueryToRecordsMap(ClickHouseStruct record, List<Field> modifiedFields,
                                           Map<MutablePair<String, Map<String, Integer>>,
                                                   List<ClickHouseStruct>> queryToRecordsMap, String tableName,
                                           ClickHouseSinkConnectorConfig config,
                                           Map<String, String> columnNameToDataTypeMap) {

        // Step 1: If its a TRUNCATE OPERATION, add a TRUNCATE TABLE command.
        if(record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
            MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
            mp.setLeft(String.format("TRUNCATE TABLE %s", tableName));
            mp.setRight(new HashMap<String, Integer>());
            ArrayList<ClickHouseStruct> records = new ArrayList<>();
            records.add(record);
            queryToRecordsMap.put(mp, records);
            return true;
        }

        // Step 2: Create the Prepared Statement Query.
        MutablePair<String, Map<String, Integer>>  response= new QueryFormatter().getInsertQueryUsingInputFunction
                (tableName, modifiedFields, columnNameToDataTypeMap,
                        config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString()),
                        config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString()),
                        config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString()), record.getDatabase() );

        String insertQueryTemplate = response.getKey();
        if(response.getKey() == null || response.getValue() == null) {
            log.error("********* QUERY or COLUMN TO INDEX MAP EMPTY");
            return false;
            //  this.columnNametoIndexMap = response.right;
        }

        MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
        mp.setLeft(insertQueryTemplate);
        mp.setRight(response.getValue());

        if (!queryToRecordsMap.containsKey(mp)) {
            List<ClickHouseStruct> newList = new ArrayList<>();
            newList.add(record);

            queryToRecordsMap.put(mp, newList);
        } else {
            List<ClickHouseStruct> recordsList = queryToRecordsMap.get(mp);
            recordsList.add(record);

            queryToRecordsMap.put(mp, recordsList);
        }

        return true;
    }

    /**
     * Function to update the map of topic/partition to offset(max)
     * @param offsetToPartitionMap
     * @param partition
     * @param topic
     * @param offset
     */
    private void updatePartitionOffsetMap(Map<TopicPartition, Long> offsetToPartitionMap, int partition, String topic,
                                          long offset) {

        TopicPartition tp = new TopicPartition(topic, partition);

        // Check if record exists.
        if(!offsetToPartitionMap.containsKey(tp)) {
            // Record does not exist;
            offsetToPartitionMap.put(tp, offset);
        } else {
            // Record exists.
            // Update only if the current offset
            // is greater than the offset stored.
            long storedOffset = offsetToPartitionMap.get(tp);
            if(offset > storedOffset) {
                offsetToPartitionMap.put(tp, offset);
            }
        }
    }
}
