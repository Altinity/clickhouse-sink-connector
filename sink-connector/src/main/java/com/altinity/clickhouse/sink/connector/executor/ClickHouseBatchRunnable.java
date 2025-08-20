package com.altinity.clickhouse.sink.connector.executor;

import com.alibaba.fastjson.JSONObject;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.*;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static com.altinity.clickhouse.sink.connector.config.ReplicationHistoryConfig.loadReplicationHistoryEnable;

/**
 * Runnable object that will be called on a schedule to perform the
 * batch insert of records to ClickHouse.
 */
public class ClickHouseBatchRunnable implements Runnable {

    /**
     * Logger instance for the ClickHouseBatchRunnable class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseBatchRunnable.class);

    /**
     * Queue containing batches of ClickHouseStruct records.
     */
    private final LinkedBlockingQueue<List<ClickHouseStruct>> records;

    /**
     * Connector configuration.
     */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Connection used to create the Debezium storage database.
     */
    private Connection systemConnection;

    /**
     * Map of database name to ClickHouse Connection.
     */
    private Map<String, Connection> databaseToConnectionMap =
            new HashMap<>();

    /**
     * Map of topic names to table names.
     */
    private final Map<String, String> topic2TableMap;

    /**
     * Map of topic name to DbWriter instance.
     */
    private Map<String, DbWriter> topicToDbWriterMap;

    /**
     * Database credentials.
     */
    private DBCredentials dbCredentials;

    /**
     * Current batch of records being processed.
     */
    private List<ClickHouseStruct> currentBatch = null;

    /**
     * Map for overriding database names from source to destination.
     */
    private Map<String, String> databaseOverrideMap = new HashMap<>();

    /**
     * Sleep time in milliseconds after an exception occurs.
     */
    private static final long ERROR_SLEEP_TIME_MS = 10000;

    /**
     * Constructs a ClickHouseBatchRunnable.
     *
     * @param records        the queue of record batches
     * @param config         the connector configuration
     * @param topic2TableMap a map of topic names to table names
     */
    public ClickHouseBatchRunnable(
            LinkedBlockingQueue<List<ClickHouseStruct>> records,
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
        this.systemConnection = createConnection(BaseDbWriter.SYSTEM_DB);
        try {
            this.databaseOverrideMap = Utils.parseSourceToDestinationDatabaseMap(
                    this.config.getString(
                            ClickHouseSinkConnectorConfigVariables.
                                    CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString()));
        } catch (Exception e) {
            log.error("Error parsing database override map" + e);
        }
    }

    /**
     * Creates a connection to the specified database.
     *
     * @param databaseName the database name
     * @return a Connection object to the given database
     */
    private Connection createConnection(String databaseName) {
        String jdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), "system");
        return BaseDbWriter.createConnection(jdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), databaseName, config);
    }

    /**
     * Retrieves the ClickHouse connection for the specified database.
     *
     * <p>If no connection exists, this method creates the database (if
     * needed) and returns a new connection.
     *
     * @param databaseName the target database name
     * @return a Connection to the specified database
     */
    private Connection getClickHouseConnection(String databaseName) {
        if (this.databaseToConnectionMap.containsKey(databaseName)) {
            return this.databaseToConnectionMap.get(databaseName);
        }
        // Create database if it doesnt exist.
        String systemJdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), "system");
        Connection systemConn = BaseDbWriter.createConnection(systemJdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), "system", config);
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(systemConn,
                    "CREATE DATABASE IF NOT EXISTS " + databaseName);
        } catch (Exception e) {
            log.error("Error creating database " + e);
        } finally {
            try {
                systemConn.close();
            } catch (SQLException e) {
                log.error("Error closing connection when creating database" + e);
            }
        }
        String jdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName);
        Connection conn = BaseDbWriter.createConnection(jdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), databaseName, config);
        this.databaseToConnectionMap.put(databaseName, conn);
        return conn;
    }

    /**
     * Parses the database configuration from the connector config.
     *
     * @return a DBCredentials object with the parsed settings
     */
    private DBCredentials parseDBConfiguration() {
        DBCredentials dbCredentials = new DBCredentials();
        dbCredentials.setHostName(config.getString(
                ClickHouseSinkConnectorConfigVariables.
                        CLICKHOUSE_URL.toString()));
        dbCredentials.setPort(config.getInt(
                ClickHouseSinkConnectorConfigVariables.
                        CLICKHOUSE_PORT.toString()));
        dbCredentials.setUserName(config.getString(
                ClickHouseSinkConnectorConfigVariables.
                        CLICKHOUSE_USER.toString()));
        dbCredentials.setPassword(config.getString(
                ClickHouseSinkConnectorConfigVariables.
                        CLICKHOUSE_PASS.toString()));
        return dbCredentials;
    }

    /**
     * Gets the database name from the topic name.
     * Topic name format is expected to be: server.database.table
     *
     * @param topicName The topic name
     * @return The database name, or null if not found
     */
    private String getDatabaseNameFromTopic(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return null;
        }

        String[] parts = topicName.split("\\.");
        if (parts.length >= 3) {
            return parts[parts.length - 2]; // Second to last part is database name
        }
        return null;
    }

    /**
     * Gets the server name from the topic name.
     * Topic name format is expected to be: server.database.table
     *
     * @param topicName The topic name
     * @return The server name, or null if not found
     */
    private String getServerNameFromTopic(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return null;
        }

        String[] parts = topicName.split("\\.");
        if (parts.length >= 3) {
            return parts[0]; // First part is server name
        }
        return null;
    }

    /**
     * Main run loop of the thread, called on a schedule.
     * Default: 100 msecs
     */
    @Override
    public void run() {
        Long taskId = config.getLong(
                ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
        String errorTableName = config.getString(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        try {
            // Poll from Queue until its empty.
            while (records.size() > 0 || currentBatch != null) {
                // If the thread is interrupted, the exit.
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Thread is interrupted, exiting - Thread ID: " +
                            Thread.currentThread().getId());
                    return;
                }
                if (currentBatch == null) {
                    currentBatch = records.poll();
                    if (currentBatch == null) {
                        // No records in the queue.
                        continue;
                    }
                } else {
                    log.debug("***** RETRYING the same batch again");
                }



                ///// ***** START PROCESSING BATCH **************************
                // Step 1: Add to Inflight batches.
                DebeziumOffsetManagement.addToBatchTimestamps(currentBatch);
                log.info("****** Thread: " +
                        Thread.currentThread().getName() +
                        " Batch Size: " + currentBatch.size() +
                        " ******");
                // Group records by topic name.
                // Create a new map of topic name to list of records.
                Map<String, List<ClickHouseStruct>> topicToRecordsMap =
                        new ConcurrentHashMap<>();
                currentBatch.forEach(record -> {
                    String topicName = record.getTopic();
                    // If the topic name is not present, create a new list and
                    // add the record.
                    if (topicToRecordsMap.containsKey(topicName) == false) {
                        List<ClickHouseStruct> recordsList = new ArrayList<>();
                        recordsList.add(record);
                        topicToRecordsMap.put(topicName, recordsList);
                    } else {
                        // If the topic name is present, add the record to the list.
                        List<ClickHouseStruct> recordsList =
                                topicToRecordsMap.get(topicName);
                        recordsList.add(record);
                        topicToRecordsMap.put(topicName, recordsList);
                    }
                });
                boolean result = true;
                // For each topic, process the records.
                // topic name syntax is server.database.table
                for (Map.Entry<String, List<ClickHouseStruct>> entry :
                        topicToRecordsMap.entrySet()) {

                    result = processRecordsByTopic(entry.getKey(),
                            entry.getValue());

                    // insert history data
                    if(loadReplicationHistoryEnable()){
                        processRecordsByTopic(entry.getKey()+"_history",
                                entry.getValue());
                    }

                    if (result == false) {
                        log.error("Error processing records for topic: " +
                                entry.getKey());
                        break;
                    }
                }
                if (result) {
                    // Step 2: Check if the batch can be committed.
                    if(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(currentBatch)) {
                        currentBatch = null;
                    }
                }
                Thread.sleep(config.getLong(
                        ClickHouseSinkConnectorConfigVariables.
                                BUFFER_FLUSH_TIME.toString()));
                ///// ***** END PROCESSING BATCH **************************
            }
        } catch (Exception e) {
            // insert data into error table
            log.error(String.format(
                            "ClickHouseBatchRunnable exception - Task(%s)", taskId),
                    e);
            try {
                Connection dbCon = getClickHouseConnection(DbWriter.SYSTEM_DB);
                // Create error table if it doesn't exist
                ErrorLogger.createErrorTable(dbCon, config);
                
                // Log the error with the first record from current batch if available
                if (currentBatch != null && !currentBatch.isEmpty()) {
                    ClickHouseStruct firstRecord = currentBatch.get(0);
                    SourceRecord sourceRecord = firstRecord.getSourceRecord().value();
                    String topicName = firstRecord.getTopic();
                    String databaseName = firstRecord.getDatabase();
                    String serverName = getServerNameFromTopic(topicName);
                    
                    // Get the failure entry index
                    int failureIndex = currentBatch.indexOf(firstRecord);
                    
                    ErrorLogger.logError(dbCon, 
                        String.format("Error processing batch. Task: %s, Server: %s, Database: %s, Failure Index: %d, Error: %s", 
                            taskId, serverName, databaseName, failureIndex, e.getMessage()),
                        sourceRecord,
                        databaseName,
                        "", // No query field available
                        "", // No offset key field available
                        errorTableName);
                } else {
                    ErrorLogger.logError(dbCon, 
                        String.format("Error processing batch. Task: %s, Error: %s", taskId, e.getMessage()),
                        null,
                        "",
                        "", "",
                        errorTableName);
                }
                
                Thread.sleep(ERROR_SLEEP_TIME_MS);
            } catch (InterruptedException ex) {
                log.error("******* ERROR **** Thread interrupted *********",
                        ex);
                throw new RuntimeException(ex);
            } catch (SQLException ex) {
                log.error("******* ERROR **** Failed to log error to ClickHouse *********",
                        ex);
            }
        }
    }

    /**
     * Retrieves the table name from the given topic name.
     *
     * @param topicName the topic name
     * @return the corresponding table name
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

    /**
     * Returns a DbWriter for the specified topic, table, and database.
     *
     * @param topicName    the topic name
     * @param tableName    the table name
     * @param databaseName the database name
     * @param record       a ClickHouseStruct record for metadata
     * @param connection   the JDBC Connection to the database
     * @return a DbWriter instance for the given parameters
     */
    public DbWriter getDbWriterForTable(String topicName, String tableName,
                                        String databaseName,
                                        ClickHouseStruct record,
                                        Connection connection) {
        DbWriter writer = null;
        if (this.topicToDbWriterMap.containsKey(topicName)) {
            writer = this.topicToDbWriterMap.get(topicName);
            return writer;
        }
        writer = new DbWriter(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName, tableName,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), this.config, record,
                connection);
        this.topicToDbWriterMap.put(topicName, writer);
        return writer;
    }

    /**
     * Returns the ClickHouse server timezone.
     *
     * @param config the connector configuration
     * @return a ZoneId representing the server timezone
     */
    public ZoneId getServerTimeZone(ClickHouseSinkConnectorConfig config) {
        String userProvidedTimeZone = config.getString(
                ClickHouseSinkConnectorConfigVariables.
                        CLICKHOUSE_DATETIME_TIMEZONE.toString());
        // Validate if timezone string is valid.
        ZoneId userProvidedTimeZoneId = null;
        try {
            if (!userProvidedTimeZone.isEmpty()) {
                userProvidedTimeZoneId = ZoneId.of(userProvidedTimeZone);
            }
        } catch (Exception e) {
            log.error("**** Error parsing user provided timezone:" +
                    userProvidedTimeZone + e.toString());
        }
        if (userProvidedTimeZoneId != null) {
            return userProvidedTimeZoneId;
        }
        return new DBMetadata(config).getServerTimeZone(this.systemConnection);
    }

    /**
     * Processes records for the specified topic.
     *
     * <p>This function groups records by topic, retrieves the corresponding
     * table name and DbWriter, and processes the batch by grouping records
     * into insert queries and flushing them to ClickHouse.
     *
     * @param topicName the topic name
     * @param records   a list of ClickHouseStruct records for the topic
     * @return true if processing succeeds; false otherwise
     * @throws Exception if an error occurs during processing
     */
    private boolean processRecordsByTopic(String topicName,
                                          List<ClickHouseStruct> records)
            throws Exception {
        boolean result = false;
        //The user parameter will override the topic mapping to table.
        String tableName = getTableFromTopic(topicName);
        // Note: getting records.get(0) is safe as the topic name is same
        // for all records.
        ClickHouseStruct firstRecord = records.get(0);
        String databaseName = firstRecord.getDatabase();
        // Check if user has overridden the database name.
        if (this.databaseOverrideMap.containsKey(firstRecord.getDatabase()))
            databaseName = this.databaseOverrideMap.get(
                    firstRecord.getDatabase());
        Connection databaseConn = getClickHouseConnection(databaseName);
        DbWriter writer = getDbWriterForTable(topicName, tableName, databaseName,
                firstRecord, databaseConn);
        PreparedStatementExecutor preparedStatementExecutor = new
                PreparedStatementExecutor(writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(), writer.getSignColumn(),
                writer.getVersionColumn(), writer.getDatabaseName(),
                getServerTimeZone(this.config));
        if (writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error(String.format("*** TABLE METADATA not retrieved for " +
                            "Database(%s), table(%s) retrying",
                    writer.getDatabaseName(), writer.getTableName()));
            if (writer == null) {
                writer = getDbWriterForTable(topicName, tableName, databaseName,
                        firstRecord, databaseConn);
            }
            if (writer.wasTableMetaDataRetrieved() == false)
                writer.updateColumnNameToDataTypeMap();
            if (writer == null ||
                    writer.wasTableMetaDataRetrieved() == false) {
                log.error(String.format("*** TABLE METADATA not retrieved for " +
                                "Database(%s), table(%s), retrying on next attempt",
                        writer.getDatabaseName(), writer.getTableName()));
                return false;
            }
        }
        // Step 1: The Batch Insert with preparedStatement in JDBC works by
        // forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records (List of ClickHouseStruct).
        Map<MutablePair<String, Map<String, Integer>>,
                List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        result = new GroupInsertQueryWithBatchRecords()
                .groupQueryWithRecords(records, queryToRecordsMap,
                        partitionToOffsetMap, this.config, tableName,
                        writer.getDatabaseName(), writer.getConnection(),
                        writer.getColumnNameToDataTypeMap());
        BlockMetaData bmd = new BlockMetaData();
        long maxBufferSize = this.config.getLong(
                ClickHouseSinkConnectorConfigVariables.
                        BUFFER_MAX_RECORDS.toString());
        // Step 2: Create a PreparedStatement and add the records to the
        // batch. In DbWriter, the queryToRecordsMap is converted to
        // PreparedStatement and added to the batch. The batch is then executed
        // and the records are flushed to ClickHouse.
        result = flushRecordsToClickHouse(topicName, writer, queryToRecordsMap,
                bmd, maxBufferSize, preparedStatementExecutor);
        if (result) {
            // Remove the entry.
            queryToRecordsMap.remove(topicName);
        }
        if (this.config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.
                        ENABLE_KAFKA_OFFSET.toString())) {
            log.info("***** KAFKA OFFSET MANAGEMENT ENABLED *****");
            DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(
                    dbCredentials.getHostName(), dbCredentials.getPort(),
                    dbCredentials.getDatabase(), "topic_offset_metadata",
                    dbCredentials.getUserName(), dbCredentials.getPassword(),
                    this.config, databaseConn);
            try {
                dbKafkaOffsetWriter.insertTopicOffsetMetadata(
                        partitionToOffsetMap);
            } catch (SQLException e) {
                log.error("Error persisting offsets to CH", e);
            }
        }
        return result;
    }

    /**
     * Flushes records to ClickHouse if there are minimum records or if the
     * flush timeout has reached.
     *
     * <p>This method creates a PreparedStatement batch from the grouped
     * queries and executes it, then updates metrics.
     *
     * @param topicName the topic name
     * @param writer the DbWriter for the table
     * @param queryToRecordsMap a map of insert queries to records
     * @param bmd block metadata used for metrics
     * @param maxBufferSize the maximum buffer size before flushing
     * @param preparedStatementExecutor the executor to add batches
     * @return true if the flush succeeds; false otherwise
     * @throws Exception if an error occurs during batch execution
     */
    private boolean flushRecordsToClickHouse(String topicName, DbWriter writer,
                                             Map<MutablePair<String, Map<String, Integer>>,
                                                     List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd,
                                             long maxBufferSize,
                                             PreparedStatementExecutor preparedStatementExecutor)
            throws Exception {
        boolean result = false;
        synchronized (queryToRecordsMap) {
            result = preparedStatementExecutor.addToPreparedStatementBatch(
                    topicName, queryToRecordsMap, bmd, config,
                    writer.getConnection(), writer.getTableName(),
                    writer.getColumnNameToDataTypeMap(), writer.getEngine());
        }
        try {
            Metrics.updateMetrics(bmd);
        } catch (Exception e) {
            log.error("****** Error updating Metrics ******");
        }
        return result;
    }
}
