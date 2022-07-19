package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.DbKafkaOffsetWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.codahale.metrics.Timer;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchRunnable.class);
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records;

    private final ClickHouseSinkConnectorConfig config;

    private final Map<String, String> topic2TableMap;

    private Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap;

    private long lastFlushTimeInMs = 0;


    private Map<String, DbWriter> topicToWriterMap;

    public ClickHouseBatchRunnable(ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> topic2TableMap) {
        this.records = records;
        this.config = config;
        if(topic2TableMap == null) {
            this.topic2TableMap = new HashMap();
        } else {
            this.topic2TableMap = topic2TableMap;
        }

        this.queryToRecordsMap = new HashMap<>();
        this.topicToWriterMap = new HashMap<>();
    }

    @Override
    public void run() {

        String dbHostName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL);
        String database = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE);
        Integer port = config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT);
        String userName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER);
        String password = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS);
        //String tableName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE);


        // Topic Name -> List of records
        for (Map.Entry<String, ConcurrentLinkedQueue<ClickHouseStruct>> entry : this.records.entrySet()) {

            String topicName = entry.getKey();
            DbWriter writer = null;

            //The user parameter will override the topic mapping to table.
            String tableName;

            if (this.topic2TableMap.containsKey(topicName) == false) {
                tableName = Utils.getTableNameFromTopic(topicName);
                this.topic2TableMap.put(topicName, tableName);
            } else {
                tableName = this.topic2TableMap.get(topicName);
            }

            if (entry.getValue().size() > 0) {
                UUID blockUuid = UUID.randomUUID();

                Long taskId = config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID);
                int numRecords = entry.getValue().size();
                log.debug("*************** BULK INSERT TO CLICKHOUSE - RECORDS:" + numRecords + "************** task(" + taskId + ")"  + " Thread ID: " + Thread.currentThread().getName());

                // Initialize Timer to track time taken to transform and insert to Clickhouse.
                Timer timer = Metrics.timer("Bulk Insert: " + blockUuid + " Size:" + entry.getValue().size());
                Timer.Context context = timer.time();

                // Check if DB instance exists for the current topic
                // or else create a new one.
                if(this.topicToWriterMap.containsKey(topicName)) {
                    writer = this.topicToWriterMap.get(topicName);
                } else {
                    writer = new DbWriter(dbHostName, port, database, tableName, userName, password, this.config, entry.getValue().peek());
                    this.topicToWriterMap.put(topicName, writer);
                }
                Map<TopicPartition, Long> partitionToOffsetMap;
                synchronized (this.records) {

                    partitionToOffsetMap = writer.insert(entry.getValue(), queryToRecordsMap);

                    long currentTime = System.currentTimeMillis();
                    long diffInMs = currentTime - lastFlushTimeInMs;
                    long bufferFlushTimeout = this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIMEOUT);

                    if(diffInMs > bufferFlushTimeout) {
                        // Time to flush.
                        log.info("**** TIME EXCEEDED %s to FLUSH", bufferFlushTimeout);
                        writer.addToPreparedStatementBatch(queryToRecordsMap);
                        lastFlushTimeInMs = currentTime;
                    } else {
                        long totalSize = 0;
                        for (Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> mutablePairListEntry : queryToRecordsMap.entrySet()) {
                            totalSize += mutablePairListEntry.getValue().size();
                        }
                        long minRecordsToFlush = config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS);

                        if(totalSize >= minRecordsToFlush) {
                            log.info("**** MAX RECORDS EXCEEDED to FLUSH:" + "Total Records: " + totalSize);
                            writer.addToPreparedStatementBatch(queryToRecordsMap);
                            lastFlushTimeInMs = currentTime;
                        }

                    }
                }
                if(this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET)) {
                    log.info("***** KAFKA OFFSET MANAGEMENT ENABLED *****");
                    DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password, this.config);
                    try {
                        dbKafkaOffsetWriter.insertTopicOffsetMetadata(partitionToOffsetMap);
                    } catch (SQLException e) {
                        log.error("Error persisting offsets to CH", e);
                    }
                }
                //context.stop();

//                Metrics.updateSinkRecordsCounter(blockUuid.toString(), taskId, topicName, tableName,
//                        bmd.getPartitionToOffsetMap(), numRecords, bmd.getMinSourceLag(),
//                        bmd.getMaxSourceLag(), bmd.getMinConsumerLag(), bmd.getMaxConsumerLag());

            }
        }
    }
}
