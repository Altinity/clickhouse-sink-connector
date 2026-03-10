package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.*;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;
import com.altinity.clickhouse.sink.connector.history.BinLogHistory;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
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
     * Queue containing routed batches (for hash-based routing).
     */
    private final LinkedBlockingQueue<RoutedBatch> routedRecords;

    /**
     * Thread ID for this runnable (used for hash-based routing).
     * -1 means no hash-based routing (legacy mode).
     */
    private final int threadId;

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
     * Shared watermark (owned by ClickHouseSinkTask): highest Kafka offset per
     * TopicPartition durably inserted into ClickHouse. Updated after each
     * successful flush so preCommit() only commits persisted offsets.
     */
    private final Map<TopicPartition, Long> durablyInsertedOffsets;

    /**
     * Map for overriding database names from source to destination.
     */
    private Map<String, String> databaseOverrideMap = new HashMap<>();

    /**
     * Sleep time in milliseconds after an exception occurs.
     */
    private static final long ERROR_SLEEP_TIME_MS = 10000;

    /**
     * Constructs a ClickHouseBatchRunnable (legacy mode without hash-based routing).
     *
     * <p>Backward-compatible overload for callers that do not track durable
     * offsets: a private (unshared) watermark is used, so the durable-offset
     * gating in {@code ClickHouseSinkTask.preCommit()} is a no-op for this
     * instance (pre-existing behaviour is preserved).</p>
     *
     * @param records        the queue of record batches
     * @param config         the connector configuration
     * @param topic2TableMap a map of topic names to table names
     */
    public ClickHouseBatchRunnable(
            LinkedBlockingQueue<List<ClickHouseStruct>> records,
            ClickHouseSinkConnectorConfig config,
            Map<String, String> topic2TableMap) {
        this(records, null, -1, config, topic2TableMap, new ConcurrentHashMap<>());
    }

    /**
     * Constructs a ClickHouseBatchRunnable (legacy mode without hash-based routing).
     *
     * @param records                the queue of record batches
     * @param config                 the connector configuration
     * @param topic2TableMap         a map of topic names to table names
     * @param durablyInsertedOffsets shared watermark of durably-inserted offsets
     */
    public ClickHouseBatchRunnable(
            LinkedBlockingQueue<List<ClickHouseStruct>> records,
            ClickHouseSinkConnectorConfig config,
            Map<String, String> topic2TableMap,
            Map<TopicPartition, Long> durablyInsertedOffsets) {
        this(records, null, -1, config, topic2TableMap, durablyInsertedOffsets);
    }

    /**
     * Constructs a ClickHouseBatchRunnable with hash-based routing.
     *
     * @param routedRecords  the queue of routed record batches
     * @param threadId       the thread ID for this runnable
     * @param config         the connector configuration
     * @param topic2TableMap a map of topic names to table names
     */
    public ClickHouseBatchRunnable(
            LinkedBlockingQueue<RoutedBatch> routedRecords,
            int threadId,
            ClickHouseSinkConnectorConfig config,
            Map<String, String> topic2TableMap) {
        this(null, routedRecords, threadId, config, topic2TableMap,
                new ConcurrentHashMap<>());
    }

    /**
     * Private constructor that initializes all fields.
     *
     * @param records        the queue of record batches (legacy mode)
     * @param routedRecords  the queue of routed record batches (hash-based routing)
     * @param threadId       the thread ID for this runnable (-1 for legacy mode)
     * @param config         the connector configuration
     * @param topic2TableMap a map of topic names to table names
     */
    private ClickHouseBatchRunnable(
            LinkedBlockingQueue<List<ClickHouseStruct>> records,
            LinkedBlockingQueue<RoutedBatch> routedRecords,
            int threadId,
            ClickHouseSinkConnectorConfig config,
            Map<String, String> topic2TableMap,
            Map<TopicPartition, Long> durablyInsertedOffsets) {
        this.records = records;
        this.routedRecords = routedRecords;
        this.threadId = threadId;
        this.config = config;
        this.durablyInsertedOffsets = durablyInsertedOffsets;
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
        
        if (threadId >= 0) {
            log.info("ClickHouseBatchRunnable initialized with thread ID: {}", threadId);
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
            boolean useOnCluster = this.config.
                    getBoolean(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString());
            new ClickHouseCreateDatabase().createNewDatabase(systemConn, databaseName, useOnCluster, this.config);
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(systemConn,
                    "CREATE DATABASE IF NOT EXISTS `" + databaseName + "`");
        } catch (Exception e) {
            log.error("Error creating database " + e);
        } finally {
            try {
                // createConnection() returns null when ClickHouse is
                // unreachable, closing it would throw a NullPointerException
                // that the handler below does not catch.
                if (systemConn != null) {
                    systemConn.close();
                }
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
        // Only cache a usable connection. containsKey() above returns true for
        // a key mapped to null, so caching a failed connection would keep
        // returning null for this database until the connector restarts.
        if (conn != null) {
            this.databaseToConnectionMap.put(databaseName, conn);
        }
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
        
        // Get source timezone from config
        String sourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
        // Get server timezone from config
        String serverTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString());
        String errorTableName = config.getString(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        
        // Determine which mode we're in: hash-based routing or legacy
        boolean useHashRouting = (threadId >= 0 && routedRecords != null);
        useHashRouting = false;
        try {
//            if (useHashRouting) {
//                runWithHashRouting(taskId, sourceTimeZone, serverTimeZone, errorTableName);
//            } else
           {
                runLegacyMode(taskId, sourceTimeZone, serverTimeZone, errorTableName);
            }
        } catch (Exception e) {
            log.error(String.format(
                            "ClickHouseBatchRunnable exception - Task(%s)", taskId),
                    e);
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.ERROR_LOGGING_ENABLE.toString())){
                logErrorToClickHouse(e, taskId, errorTableName);
            }

            // Classify the error to decide whether to retry or stop
            ClickHouseErrorClassifier.ErrorCategory category = ClickHouseErrorClassifier.classify(e);
            int errorCode = ClickHouseErrorClassifier.extractErrorCode(e);

            if (category == ClickHouseErrorClassifier.ErrorCategory.FATAL) {
                log.error("FATAL ClickHouse error (Code: {}) -- this batch will never succeed. " +
                          "Discarding batch and stopping task to prevent silent data loss. " +
                          "Manual intervention required.", errorCode);
                // Clear the stuck batch so it is not retried forever
                currentBatch = null;
                // Rethrow to stop the scheduled executor -- silent swallowing causes
                // binlog advancement to stall and blocks replication for ALL tables
                throw new RuntimeException("Fatal ClickHouse error, stopping task", e);
            } else {
                log.warn("Retriable ClickHouse error (Code: {}, Category: {}) -- " +
                         "batch will be retried on next scheduled run.", errorCode, category);
            }
        }
    }


    /**
     * Run loop for hash-based routing mode.
     * Only processes batches assigned to this thread.
     */
    private void runWithHashRouting(Long taskId, String sourceTimeZone, String serverTimeZone, String errorTableName) throws Exception {
        // Poll from Queue until its empty.
        while (routedRecords.size() > 0 || currentBatch != null) {
            // If the thread is interrupted, the exit.
            if (Thread.currentThread().isInterrupted()) {
                log.info("Thread {} is interrupted, exiting - Java Thread ID: {}",
                        threadId, Thread.currentThread().getId());
                return;
            }
            
            if (currentBatch == null) {
                RoutedBatch routedBatch = routedRecords.poll();
                if (routedBatch == null) {
                    // No records in the queue.
                    continue;
                }
                
                // Only process if this batch is assigned to this thread
                if (routedBatch.getAssignedThreadId() == threadId) {
                    currentBatch = routedBatch.getBatch();
                    log.debug("Thread {} picked up batch for table: {}", threadId, routedBatch.getTableName());
                } else {
                    // Put it back for another thread to pick up
                    routedRecords.put(routedBatch);
                    Thread.sleep(10); // Small sleep to avoid busy waiting
                    continue;
                }
            } else {
                log.debug("***** Thread {} RETRYING the same batch again", threadId);
            }

            // Process the batch (rest of the logic stays the same)
            processBatch(sourceTimeZone, serverTimeZone);
        }
    }

    /**
     * Run loop for legacy mode (no hash-based routing).
     */
    private void runLegacyMode(Long taskId, String sourceTimeZone, String serverTimeZone, String errorTableName) throws Exception {
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

            // Process the batch (rest of the logic stays the same)
            processBatch(sourceTimeZone, serverTimeZone);
        }
    }

    /**
     * Common batch processing logic used by both hash-based routing and legacy mode.
     * 
     * @param sourceTimeZone Source timezone configuration
     * @param serverTimeZone Server timezone configuration
     * @throws Exception if processing fails
     */
    private void processBatch(String sourceTimeZone, String serverTimeZone) throws Exception {
        // If replication history is enabled, add the records to the history table.
        addRecordsToHistoryTable(currentBatch, sourceTimeZone, serverTimeZone);

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

        boolean replicationHistoryEnabled = config.getBoolean(
            ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString());
        boolean replicationLogOnly = config.getBoolean(
            ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_REPLICATION_LOG_ONLY.toString());
        if(replicationLogOnly && replicationHistoryEnabled)  { 
            // skip the following for loop and continue to the next step
            log.debug("Replication log only mode is enabled, skipping the processing of records");
        } else {
            for (Map.Entry<String, List<ClickHouseStruct>> entry :
                    topicToRecordsMap.entrySet()) {

                result = processRecordsByTopic(entry.getKey(),
                        entry.getValue());

                if (result == false) {
                    log.error("Error processing records for topic: " +
                            entry.getKey());
                    break;
                }
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

    /**
     * Function to persist records to binlog history table when replication history mode is enabled.
     * @param records
     * @param sourceTimeZone
     * @param serverTimeZone
     * @throws SQLException
     */
    private void addRecordsToHistoryTable(List<ClickHouseStruct> records, String sourceTimeZone, String serverTimeZone) throws SQLException {
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            String databaseName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
            String tableName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TABLE_NAME.toString());
            Connection databaseConn = getClickHouseConnection(databaseName);
            DbWriter writer = getDbWriterForTable(databaseName + "." + tableName, tableName, databaseName,
                    records.get(0), databaseConn);

            BinLogHistory binLogHistory = new BinLogHistory();
            binLogHistory.addRecordsToHistoryTable(config, tableName, writer.getConnection(), "", records, sourceTimeZone, serverTimeZone);
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
            boolean schemaPrefix = this.config != null &&
                    this.config.getBoolean(
                            ClickHouseSinkConnectorConfigVariables
                                    .CLICKHOUSE_TABLE_SCHEMA_PREFIX.toString());
            tableName = Utils.getTableNameFromTopic(topicName, schemaPrefix);
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
        // Compare the cached writer's build version against the shared, monotonic
        // table version. A mismatch means a DDL invalidated this table after the
        // writer was built, so it must be rebuilt with the fresh schema.
        String fullyQualifiedTableName = databaseName + "." + tableName;
        long currentVersion = CacheInvalidationManager.getInstance()
                .getVersion(fullyQualifiedTableName);
        DbWriter writer = this.topicToDbWriterMap.get(topicName);
        boolean invalidated = false;
        if (writer != null) {
            if (writer.getCacheInvalidationVersion() == currentVersion) {
                return writer;
            }
            log.info("Invalidating cached DbWriter for {} after DDL (version {} -> {})",
                    topicName, writer.getCacheInvalidationVersion(), currentVersion);
            this.topicToDbWriterMap.remove(topicName);
            invalidated = true;
        }
        writer = new DbWriter(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName, tableName,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), this.config, record,
                connection);
        writer.setCacheInvalidationVersion(currentVersion);
        this.topicToDbWriterMap.put(topicName, writer);
        // Log the resolved schema whenever this table has seen a DDL (version > 0).
        // This covers both rebuilding a stale writer and building a fresh writer at
        // the current version after a burst of DDLs, so the post-DDL schema is always
        // observable regardless of which thread ends up owning the writer.
        if (invalidated || currentVersion > 0) {
            logRefreshedColumns(topicName, writer, invalidated);
        }
        return writer;
    }

    /**
     * Logs the refreshed column name and type map of a DbWriter that was rebuilt
     * after a DDL cache invalidation.
     *
     * @param topicName the topic whose writer was rebuilt
     * @param writer    the freshly constructed DbWriter
     */
    private void logRefreshedColumns(String topicName, DbWriter writer, boolean rebuilt) {
        Map<String, String> cols = writer.getColumnNameToDataTypeMap();
        if (cols != null) {
            log.info("{} DbWriter schema for {} at cache version {} ({} columns): {}",
                    rebuilt ? "Rebuilt" : "Built", topicName,
                    writer.getCacheInvalidationVersion(), cols.size(), cols);
        }
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
        return new DBMetadata(config).getServerTimeZone(systemConnection());
    }

    /**
     * Returns a usable connection to the system database, replacing the cached
     * one when it has been closed.
     * <p>
     * {@code systemConnection} is opened once in the constructor and then held
     * for the lifetime of the task. A pooled connection does not stay open that
     * long: it gets returned to the pool, evicted, or reaped after an idle
     * period, and every later use of the stale handle throws
     * {@code SQLException: Connection is closed}. The only caller
     * ({@link #getServerTimeZone}) runs on every batch, so a single closed
     * handle produced one full stack trace per batch for the rest of the run —
     * 1.17M log lines in CI — while the timezone silently fell back to the
     * default. Re-opening when the handle is unusable keeps the connection
     * valid for the life of the task.
     *
     * @return a usable system-database connection, or null when one cannot be
     *         obtained (callers already handle a null connection).
     */
    private Connection systemConnection() {
        if (BaseDbWriter.isUnusable(this.systemConnection)) {
            this.systemConnection = createConnection(BaseDbWriter.SYSTEM_DB);
        }
        return this.systemConnection;
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

        // If replication history is enabled, set database name to the replication history database name
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
            databaseName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
        }

        return processBatchRecords(records, topicName, tableName, databaseName, firstRecord);
    }

    private boolean processBatchRecords(List<ClickHouseStruct> records, String topicName,
                                      String tableName, String databaseName,
                                      ClickHouseStruct firstRecord) throws Exception {
        boolean result = false;


        // Check if user has overridden the database name.
        if (this.databaseOverrideMap.containsKey(databaseName))
            databaseName = this.databaseOverrideMap.get(
                    databaseName);

        Connection databaseConn = getClickHouseConnection(databaseName);

        DbWriter writer = getDbWriterForTable(topicName, tableName, databaseName,
                firstRecord, databaseConn);
        // Sorting key passed as a supplier, not a snapshot: this executor is
        // built BEFORE the metadata-retry block below, which is where a table
        // created by DDL (rather than by auto-create) first resolves its
        // sorting key. A value captured here would still be empty, and the
        // writer would then silently skip the UPDATE tombstone.
        final DbWriter sortingKeySource = writer;
        PreparedStatementExecutor preparedStatementExecutor = new
                PreparedStatementExecutor(writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(), writer.getSignColumn(),
                writer.getVersionColumn(), writer.getDatabaseName(),
                getServerTimeZone(this.config),
                sortingKeySource::getSortingKeyColumns);
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
            // Records are now DURABLY in ClickHouse: advance the shared watermark
            // (max offset per TopicPartition) so ClickHouseSinkTask.preCommit()
            // only commits offsets that were actually persisted. Without this the
            // offset advances on consume, and a crash/restart silently loses the
            // records that were consumed but never inserted.
            partitionToOffsetMap.forEach((tp, offset) ->
                    this.durablyInsertedOffsets.merge(tp, offset, Math::max));
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

    /**
     * Logs error information to ClickHouse error table.
     *
     * @param e exception that occurred
     * @param taskId task identifier
     * @param errorTableName name of the error table
     */
    private void logErrorToClickHouse(Exception e, Long taskId, String errorTableName) {
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
