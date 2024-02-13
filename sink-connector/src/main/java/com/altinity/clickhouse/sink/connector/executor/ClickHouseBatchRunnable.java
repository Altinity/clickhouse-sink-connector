package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.DbKafkaOffsetWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private final ConcurrentLinkedQueue<List<ClickHouseStruct>> records;

    private final ClickHouseSinkConnectorConfig config;

    //ToDo: Later this can be moved to one connection per application.
    private ClickHouseConnection conn;

    /**
     * Data structures with state
     */
    // Map of topic names to table names.
    private final Map<String, String> topic2TableMap;

    // Map of topic name to CLickHouseConnection instance(DbWriter)
    private Map<String, DbWriter> topicToDbWriterMap;


    private DBCredentials dbCredentials;


    public ClickHouseBatchRunnable(ConcurrentLinkedQueue<List<ClickHouseStruct>> records,
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
        //this.topicToRecordsMap = new HashMap<>();

        this.dbCredentials = parseDBConfiguration();
        createConnection();
    }

    private void createConnection() {
        String jdbcUrl = BaseDbWriter.getConnectionString(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), this.dbCredentials.getDatabase());

        this.conn = BaseDbWriter.createConnection(jdbcUrl, "Sink Connector Lightweight", this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), config);
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

            // Poll from Queue until its empty.
            while(records.size() > 0) {
                List<ClickHouseStruct> batch = records.poll();

                ///// ***** START PROCESSING BATCH **************************
                // Step 1: Add to Inflight batches.
                DebeziumOffsetManagement.addToBatchTimestamps(batch);

                log.info("****** Thread: " + Thread.currentThread().getName() + " Batch Size: " + batch.size() + " ******");
                // Group records by topic name.
                // Create a new map of topic name to list of records.
                Map<String, List<ClickHouseStruct>> topicToRecordsMap = new ConcurrentHashMap<>();
                batch.forEach(record -> {
                    String topicName = record.getTopic();
                    // If the topic name is not present, create a new list and add the record.
                    if (topicToRecordsMap.containsKey(topicName) == false) {
                        List<ClickHouseStruct> recordsList = new ArrayList<>();
                        recordsList.add(record);
                        topicToRecordsMap.put(topicName, recordsList);
                    } else {
                        // If the topic name is present, add the record to the list.
                        List<ClickHouseStruct> recordsList = topicToRecordsMap.get(topicName);
                        recordsList.add(record);
                        topicToRecordsMap.put(topicName, recordsList);
                    }
                });
                boolean result = true;
                // For each topic, process the records.
                for (Map.Entry<String, List<ClickHouseStruct>> entry : topicToRecordsMap.entrySet()) {
                    result = processRecordsByTopic(entry.getKey(), entry.getValue());
                    if(result == false) {
                        log.error("Error processing records for topic: " + entry.getKey());
                        break;
                    }
                }

                if(result) {
                    // Step 2: Check if the batch can be committed.
                    DebeziumOffsetManagement.checkIfBatchCanBeCommitted(batch);
                }
                    //acknowledgeRecords(batch);
                ///// ***** END PROCESSING BATCH **************************

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

    public DbWriter getDbWriterForTable(String topicName, String tableName,
                                        ClickHouseStruct record, ClickHouseConnection connection) {
        DbWriter writer = null;

        if (this.topicToDbWriterMap.containsKey(topicName)) {
            writer = this.topicToDbWriterMap.get(topicName);
            return writer;
        }

        writer = new DbWriter(this.dbCredentials.getHostName(), this.dbCredentials.getPort(),
                    this.dbCredentials.getDatabase(), tableName, this.dbCredentials.getUserName(),
                    this.dbCredentials.getPassword(), this.config, record, connection);
        this.topicToDbWriterMap.put(topicName, writer);
        return writer;
    }

    /**
     * Function to return ClickHouse server timezone.
     * @return
     */
    public ZoneId getServerTimeZone(ClickHouseSinkConnectorConfig config) {

        String userProvidedTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables
                .CLICKHOUSE_DATETIME_TIMEZONE.toString());
        // Validate if timezone string is valid.
        ZoneId userProvidedTimeZoneId = null;
        try {
            if(!userProvidedTimeZone.isEmpty()) {
                userProvidedTimeZoneId = ZoneId.of(userProvidedTimeZone);
                //log.info("**** OVERRIDE TIMEZONE for DateTime:" + userProvidedTimeZone);
            }
        } catch (Exception e){
            log.error("**** Error parsing user provided timezone:"+ userProvidedTimeZone + e.toString());
        }

        if(userProvidedTimeZoneId != null) {
            return userProvidedTimeZoneId;
        }
        return new DBMetadata().getServerTimeZone(this.conn);
    }
    /**
     * Function to process records
     *
     * @param topicName
     * @param records
     */
    private boolean processRecordsByTopic(String topicName, List<ClickHouseStruct> records) throws Exception {

        boolean result = false;
        //The user parameter will override the topic mapping to table.
        String tableName = getTableFromTopic(topicName);
        if(this.conn == null) {
            createConnection();
        }
        DbWriter writer = getDbWriterForTable(topicName, tableName, records.get(0), this.conn);
        PreparedStatementExecutor preparedStatementExecutor = new
                PreparedStatementExecutor(writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(), writer.getSignColumn(), writer.getVersionColumn(),
                writer.getConnection(), getServerTimeZone(this.config));


        if(writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error("*** TABLE METADATA not retrieved, retry next time");
            return false;
        }
        // Step 1: The Batch Insert with preparedStatement in JDBC
        // works by forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records(List of ClickHouseStruct)
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        result = new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(records, queryToRecordsMap,
                partitionToOffsetMap, this.config,tableName, writer.getDatabaseName(), writer.getConnection(),
                writer.getColumnNameToDataTypeMap());

        BlockMetaData bmd = new BlockMetaData();
        long maxBufferSize = this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString());

        // Step 2: Create a PreparedStatement and add the records to the batch.
        // In DBWriter, the queryToRecordsMap is converted to PreparedStatement and added to the batch.
        // The batch is then executed and the records are flushed to ClickHouse.
        result = flushRecordsToClickHouse(topicName, writer, queryToRecordsMap, bmd, maxBufferSize, preparedStatementExecutor);

        if(result) {
            // Remove the entry.
            queryToRecordsMap.remove(topicName);
        }

        if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET.toString())) {
            log.info("***** KAFKA OFFSET MANAGEMENT ENABLED *****");
            DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbCredentials.getHostName(), dbCredentials.getPort(), dbCredentials.getDatabase(),
                    "topic_offset_metadata", dbCredentials.getUserName(), dbCredentials.getPassword(), this.config, this.conn);
            try {
                dbKafkaOffsetWriter.insertTopicOffsetMetadata(partitionToOffsetMap);
            } catch (SQLException e) {
                log.error("Error persisting offsets to CH", e);
            }
        }

        return result;
    }

    /**
     * Function that flushes records to ClickHouse if
     * there are minimum records or if the flush timeout has reached.
     * @param writer
     * @param queryToRecordsMap
     * @return
     */
    private boolean flushRecordsToClickHouse(String topicName, DbWriter writer, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd, long maxBufferSize, PreparedStatementExecutor preparedStatementExecutor) throws Exception {

        boolean result = false;

        synchronized (queryToRecordsMap) {
            result = preparedStatementExecutor.addToPreparedStatementBatch(topicName, queryToRecordsMap, bmd, config, writer.getConnection(),
                    writer.getTableName(), writer.getColumnNameToDataTypeMap(), writer.getEngine());

        }
        try {
            Metrics.updateMetrics(bmd);
        } catch(Exception e) {
            log.error("****** Error updating Metrics ******");
        }
        //result = true;

        return result;
    }
}
