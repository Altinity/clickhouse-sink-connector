package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseBatchProcessingThread extends ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchRunnable.class);

    private List<ClickHouseStruct> chStructs;


    private Map<String, Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> topicToRecordsMap = null;


    public ClickHouseBatchProcessingThread(List<ClickHouseStruct> chStructs, ClickHouseSinkConnectorConfig config) {
        super(null, config, null);
        this.chStructs = chStructs;
        topicToRecordsMap = new HashMap<>();
    }
    @Override
    public void run() {

        if (chStructs == null || chStructs.isEmpty()) {
            return;
        }
        ClickHouseStruct topRecord = chStructs.get(0);
        if (topRecord == null) {
            return;
        }

        String topicName = topRecord.getTopic();

        if (this.chStructs != null) {
            //The user parameter will override the topic mapping to table.
            String tableName = getTableFromTopic(topicName);
            DbWriter writer = getDbWriterForTable(topicName, tableName, topRecord);

            if (writer == null || writer.wasTableMetaDataRetrieved() == false) {
                log.error("*** TABLE METADATA not retrieved, retry next time");
                return;
            }
            // Step 1: The Batch Insert with preparedStatement in JDBC
            // works by forming the Query and then adding records to the Batch.
            // This step creates a Map of Query -> Records(List of ClickHouseStruct)
            Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap;

            if (topicToRecordsMap.containsKey(topicName)) {
                queryToRecordsMap = topicToRecordsMap.get(topicName);
            } else {
                queryToRecordsMap = new HashMap<>();
                topicToRecordsMap.put(topicName, queryToRecordsMap);
            }

            try {
                Map<TopicPartition, Long> partitionToOffsetMap = writer.groupQueryWithRecords(chStructs, queryToRecordsMap);
                BlockMetaData bmd = new BlockMetaData();

                try {
                    if (flushRecordsToClickHouse(topicName, writer, queryToRecordsMap, bmd)) {
                        // Remove the entry.
                        queryToRecordsMap.remove(topicName);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

            } catch (Exception e) {
                log.error("Error processing records in Thread" + Thread.currentThread().getName(), e);
            }
        }
    }
}
