package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.DbKafkaOffsetWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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


    /**
     * Data structures with state
     */
    // Map of topic names to table names.
    private final Map<String, String> topic2TableMap;

    // Map of topic name to CLickHouseConnection instance(DbWriter)
    private Map<String, DbWriter> topicToDbWriterMap;


    // Map of topic name to buffered records.
    Map<String, Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> topicToRecordsMap;

    private DBCredentials dbCredentials;

    public ClickHouseBatchRunnable(ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> topic2TableMap) {
        this.records = records;
        this.config = config;
        if (topic2TableMap == null) {
            this.topic2TableMap = new HashMap();
        } else {
            this.topic2TableMap = topic2TableMap;
        }

        //this.queryToRecordsMap = new HashMap<>();
        this.topicToDbWriterMap = new HashMap<>();
        this.topicToRecordsMap = new HashMap<>();

        this.dbCredentials = parseDBConfiguration();

    }

    private DBCredentials parseDBConfiguration() {
        DBCredentials dbCredentials = new DBCredentials();

        dbCredentials.setHostName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString()));
        dbCredentials.setDatabase(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE.toString()));
        dbCredentials.setPort(config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString()));
        dbCredentials.setUserName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString()));
        dbCredentials.setPassword(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString()));

        return dbCredentials;
    }

    /**
     * Main run loop of the thread
     * which is called based on the schedule
     * Default: 100 msecs
     */
    @Override
    public void run() {

        Long taskId = config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
        try {
            int numRecords = records.size();
            if (numRecords <= 0) {
                //log.debug(String.format("No records to process ThreadId(%s), TaskId(%s)", Thread.currentThread().getId(), taskId));
                return;
            } else {
                log.debug("**** Processing Batch of Records ****" + numRecords);
            }

            // Topic Name -> List of records
            for (Map.Entry<String, ConcurrentLinkedQueue<ClickHouseStruct>> entry : this.records.entrySet()) {
                if (entry.getValue().size() > 0) {
                    processRecordsByTopic(entry.getKey(), entry.getValue());
                }
            }
        } catch(Exception e) {
            log.error(String.format("ClickHouseBatchRunnable exception - Task(%s)", taskId), e);
        }
    }

    /**
     * Function to retrieve table name from topic name
     *
     * @param topicName
     * @return Table Name
     */
    public String getTableFromTopic(String topicName) {
        String tableName = null;

        if (this.topic2TableMap.containsKey(topicName) == false) {
            tableName = Utils.getTableNameFromTopic(topicName);
            this.topic2TableMap.put(topicName, tableName);
        } else {
            tableName = this.topic2TableMap.get(topicName);
        }

        return tableName;
    }

    public DbWriter getDbWriterForTable(String topicName, String tableName, ClickHouseStruct record) {
        DbWriter writer = null;

        writer = new DbWriter(this.dbCredentials.getHostName(), this.dbCredentials.getPort(),
                    this.dbCredentials.getDatabase(), tableName, this.dbCredentials.getUserName(),
                    this.dbCredentials.getPassword(), this.config, record);
        this.topicToDbWriterMap.put(topicName, writer);
        return writer;
    }

    /**
     * Function to process records
     *
     * @param topicName
     * @param records
     */
    private void processRecordsByTopic(String topicName, ConcurrentLinkedQueue<ClickHouseStruct> records) throws Exception {

        //The user parameter will override the topic mapping to table.
        String tableName = getTableFromTopic(topicName);
        DbWriter writer = getDbWriterForTable(topicName, tableName, records.peek());

        if(writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error("*** TABLE METADATA not retrieved, retry next time");
            return;
        }
        // Step 1: The Batch Insert with preparedStatement in JDBC
        // works by forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records(List of ClickHouseStruct)
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap;

        if(topicToRecordsMap.containsKey(topicName)) {
            queryToRecordsMap = topicToRecordsMap.get(topicName);
        } else {
            queryToRecordsMap = new HashMap<>();
            topicToRecordsMap.put(topicName, queryToRecordsMap);
        }

        Map<TopicPartition, Long> partitionToOffsetMap = writer.groupQueryWithRecords(records, queryToRecordsMap);
        BlockMetaData bmd = new BlockMetaData();

        long maxBufferSize = this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString());
        if(flushRecordsToClickHouse(topicName, writer, queryToRecordsMap, bmd, maxBufferSize)) {
            // Remove the entry.
            queryToRecordsMap.remove(topicName);
        }

        if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET.toString())) {
            log.info("***** KAFKA OFFSET MANAGEMENT ENABLED *****");
            DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbCredentials.getHostName(), dbCredentials.getPort(), dbCredentials.getDatabase(),
                    "topic_offset_metadata", dbCredentials.getUserName(), dbCredentials.getPassword(), this.config);
            try {
                dbKafkaOffsetWriter.insertTopicOffsetMetadata(partitionToOffsetMap);
            } catch (SQLException e) {
                log.error("Error persisting offsets to CH", e);
            }
        }
    }

    /**
     * Function that flushes records to ClickHouse if
     * there are minimum records or if the flush timeout has reached.
     * @param writer
     * @param queryToRecordsMap
     * @return
     */
    private boolean flushRecordsToClickHouse(String topicName, DbWriter writer, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd, long maxBufferSize) throws Exception {

        boolean result = false;

        synchronized (queryToRecordsMap) {
            writer.addToPreparedStatementBatch(topicName, queryToRecordsMap, bmd);
        }
        try {
            Metrics.updateMetrics(bmd);
        } catch(Exception e) {
            log.error("****** Error updating Metrics ******");
        }
        result = true;

        return result;
    }
}
