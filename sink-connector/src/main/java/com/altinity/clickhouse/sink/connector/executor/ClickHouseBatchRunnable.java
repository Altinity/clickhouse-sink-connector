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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LogManager.getLogger(ClickHouseBatchRunnable.class);
    private final LinkedBlockingQueue<List<ClickHouseStruct>> records;

    private final ClickHouseSinkConnectorConfig config;

    // Connection that will be used to create
    // the debezium storage database.
    private ClickHouseConnection systemConnection;

    // For insert batch the database connection has to be the same.
    // Create a map of database name to ClickHouseConnection.
    private Map<String, ClickHouseConnection> databaseToConnectionMap = new HashMap<>();
    /**
     * Data structures with state
     */
    // Map of topic names to table names.
    private final Map<String, String> topic2TableMap;

    // Map of topic name to CLickHouseConnection instance(DbWriter)
    private Map<String, DbWriter> topicToDbWriterMap;


    private DBCredentials dbCredentials;


    private List<ClickHouseStruct> currentBatch = null;


    private Map<String, String> databaseOverrideMap = new HashMap<>();

    public ClickHouseBatchRunnable(LinkedBlockingQueue<List<ClickHouseStruct>> records,
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
        this.systemConnection = createConnection();


        try {
            this.databaseOverrideMap = Utils.parseSourceToDestinationDatabaseMap(this.config.
                    getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString()));
        } catch (Exception e) {
            log.error("Error parsing database override map" + e);
        }
    }

    private ClickHouseConnection createConnection() {
        String jdbcUrl = BaseDbWriter.getConnectionString(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), "system");

        return BaseDbWriter.createConnection(jdbcUrl, "Sink Connector Lightweight", this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), config);
    }

    // Function to check if we have already stored a ClickHouseConnection
    // in the databaseToConnectionMap.
    private ClickHouseConnection getClickHouseConnection(String databaseName) {
        if (this.databaseToConnectionMap.containsKey(databaseName)) {
            return this.databaseToConnectionMap.get(databaseName);
        }

        String jdbcUrl = BaseDbWriter.getConnectionString(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName);

        ClickHouseConnection conn = BaseDbWriter.createConnection(jdbcUrl, "Sink Connector Lightweight",
                this.dbCredentials.getUserName(), this.dbCredentials.getPassword(), config);

        this.databaseToConnectionMap.put(databaseName, conn);
        return conn;
    }

    private DBCredentials parseDBConfiguration() {
        DBCredentials dbCredentials = new DBCredentials();

        dbCredentials.setHostName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString()));
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

            // Poll from Queue until its empty.
            while(records.size() > 0 || currentBatch != null) {
                // If the thread is interrupted, the exit.
                if(Thread.currentThread().isInterrupted()) {
                    log.info("Thread is interrupted, exiting - Thread ID: " + Thread.currentThread().getId());
                    return;
                }

                if(currentBatch == null) {
                    currentBatch = records.poll();
                    if(currentBatch == null) {
                        // No records in the queue.
                        continue;
                    }
                } else {
                    log.debug("***** RETRYING the same batch again");
                }



                ///// ***** START PROCESSING BATCH **************************
                // Step 1: Add to Inflight batches.
                DebeziumOffsetManagement.addToBatchTimestamps(currentBatch);

                log.info("****** Thread: " + Thread.currentThread().getName() + " Batch Size: " + currentBatch.size() + " ******");
                // Group records by topic name.
                // Create a new map of topic name to list of records.
                Map<String, List<ClickHouseStruct>> topicToRecordsMap = new ConcurrentHashMap<>();
                currentBatch.forEach(record -> {
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
                // topic name syntax is server.database.table
                for (Map.Entry<String, List<ClickHouseStruct>> entry : topicToRecordsMap.entrySet()) {
                    result = processRecordsByTopic(entry.getKey(), entry.getValue());
                    if(result == false) {
                        log.error("Error processing records for topic: " + entry.getKey());
                        break;
                    }
                }

                if(result) {
                    // Step 2: Check if the batch can be committed.
                    if(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(currentBatch)) {
                        currentBatch = null;
                    }
                }
                    //acknowledgeRecords(batch);
                ///// ***** END PROCESSING BATCH **************************

            }

        } catch(Exception e) {
            log.error(String.format("ClickHouseBatchRunnable exception - Task(%s)", taskId), e);
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ex) {
                log.error("******* ERROR **** Thread interrupted *********", ex);
                throw new RuntimeException(ex);
            }
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

    public DbWriter getDbWriterForTable(String topicName, String tableName, String databaseName,
                                        ClickHouseStruct record, ClickHouseConnection connection) {
        DbWriter writer = null;

        if (this.topicToDbWriterMap.containsKey(topicName)) {
            writer = this.topicToDbWriterMap.get(topicName);
            return writer;
        }

        writer = new DbWriter(this.dbCredentials.getHostName(), this.dbCredentials.getPort(),
                databaseName, tableName, this.dbCredentials.getUserName(),
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
            }
        } catch (Exception e){
            log.error("**** Error parsing user provided timezone:"+ userProvidedTimeZone + e.toString());
        }

        if(userProvidedTimeZoneId != null) {
            return userProvidedTimeZoneId;
        }
        return new DBMetadata().getServerTimeZone(this.systemConnection);
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
        // Note: getting records.get(0) is safe as the topic name is same for all records.
        ClickHouseStruct firstRecord =  records.get(0);

        String databaseName = firstRecord.getDatabase();

        // Check if user has overridden the database name.
        if(this.databaseOverrideMap.containsKey(firstRecord.getDatabase()))
            databaseName = this.databaseOverrideMap.get(firstRecord.getDatabase());

        ClickHouseConnection databaseConn = getClickHouseConnection(databaseName);

        DbWriter writer = getDbWriterForTable(topicName, tableName, databaseName, firstRecord, databaseConn);
        PreparedStatementExecutor preparedStatementExecutor = new
                PreparedStatementExecutor(writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(), writer.getSignColumn(), writer.getVersionColumn(),
                writer.getDatabaseName(), getServerTimeZone(this.config));


        if(writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error(String.format("*** TABLE METADATA not retrieved for Database(%s), table(%s) retrying",
                    writer.getDatabaseName(), writer.getTableName()));
            if(writer == null) {
                writer = getDbWriterForTable(topicName, tableName, databaseName, firstRecord, databaseConn);
            }
            if(writer.wasTableMetaDataRetrieved() == false)
                writer.updateColumnNameToDataTypeMap();

            if(writer == null || writer.wasTableMetaDataRetrieved() == false ) {
                log.error(String.format("*** TABLE METADATA not retrieved for Database(%s), table(%s), " +
                        "retrying on next attempt", writer.getDatabaseName(), writer.getTableName()));
                return false;
            }
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
            DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbCredentials.getHostName(),
                    dbCredentials.getPort(), dbCredentials.getDatabase(),
                    "topic_offset_metadata", dbCredentials.getUserName(), dbCredentials.getPassword(),
                    this.config, databaseConn);
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
    private boolean flushRecordsToClickHouse(String topicName, DbWriter writer,
                Map<MutablePair<String, Map<String, Integer>>,
                List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd,
        long maxBufferSize, PreparedStatementExecutor preparedStatementExecutor) throws Exception {

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
