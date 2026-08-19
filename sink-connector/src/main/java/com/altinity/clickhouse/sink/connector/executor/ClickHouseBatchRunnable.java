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
     *
     * <p>Opened LAZILY via {@link #getSystemConnection()}. It used to be opened
     * in the constructor, which meant simply constructing this object required a
     * reachable ClickHouse — even for pure-parsing calls like
     * {@link #getTableFromTopic(String)} that touch no database at all. That
     * became a hard failure once BaseDbWriter.createConnection was changed to
     * throw rather than return null.</p>
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
     * DDL generation each cached DbWriter in {@link #topicToDbWriterMap} was built at,
     * keyed by topic name. Used to detect schema changes applied since caching.
     */
    private Map<String, Long> topicToDbWriterGeneration;

    /**
     * Database credentials.
     */
    private DBCredentials dbCredentials;

    /**
     * Current batch of records being processed.
     */
    private List<ClickHouseStruct> currentBatch = null;

    /**
     * True when {@link #currentBatch} has already been flushed to ClickHouse
     * successfully and is only waiting for older in-flight batches to clear
     * before its offsets can be committed. While set, the retry loop must NOT
     * re-execute the inserts: re-flushing an already-flushed batch writes a
     * duplicate part per retry, and with no pacing sleep the loop re-inserted
     * one record 1,000 times in CI — SELECTs without FINAL then saw duplicate
     * rows until the merge caught up.
     */
    private boolean currentBatchFlushed = false;

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
        this.topicToDbWriterGeneration = new HashMap<>();
        //this.topicToRecordsMap = new HashMap<>();
        this.dbCredentials = parseDBConfiguration();
        // systemConnection is opened lazily — see getSystemConnection(). Opening
        // it here made construction require a reachable ClickHouse even for
        // operations that never touch the database.
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
                    "CREATE DATABASE IF NOT EXISTS " + databaseName);
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

            // Two INDEPENDENT fatal detectors, both retained. Detector 2 is
            // 2.10.0's error classifier; detector 1 is develop's addition and is
            // a strict superset — 2.10.0 contributes nothing that is dropped here.
            //
            // 1. A poisoned OffsetStorageWriter is unrecoverable in-process:
            //    once its flush semaphore is leaked, every future beginFlush()
            //    throws for the life of the JVM. Swallowing it turns the fault
            //    into SILENT data divergence -- ClickHouse writes keep
            //    succeeding while the binlog offset is frozen, so the connector
            //    looks healthy, replays the same batch forever, and re-delivers
            //    from a stale offset on restart. This exception is a Kafka
            //    ConnectException carrying NO ClickHouse error code, so the
            //    classifier below cannot see it -- it must be checked first.
            if (isOffsetWriterPoisoned(e)) {
                log.error("FATAL: the Debezium OffsetStorageWriter is stuck in the "
                        + "'already flushing' state. Offsets can no longer be committed, "
                        + "so replication would continue writing rows while the binlog "
                        + "position stays frozen. Stopping task Task({}) to prevent "
                        + "silent data divergence -- the connector must be restarted.",
                        taskId);
                throw new RuntimeException(
                        "OffsetStorageWriter is permanently stuck flushing; "
                                + "stopping task to prevent silent data divergence", e);
            }

            // 2. Deterministic ClickHouse errors (auth, schema, type mismatch)
            //    will never succeed on retry, so retrying forever stalls binlog
            //    advancement for ALL tables. Classify by error code and stop.
            ClickHouseErrorClassifier.ErrorCategory category =
                    ClickHouseErrorClassifier.classify(e);
            int errorCode = ClickHouseErrorClassifier.extractErrorCode(e);

            if (category == ClickHouseErrorClassifier.ErrorCategory.FATAL) {
                log.error("FATAL ClickHouse error (Code: {}) -- this batch will never succeed. "
                        + "Discarding batch and stopping task to prevent silent data loss. "
                        + "Manual intervention required.", errorCode);
                // Clear the stuck batch so it is not retried forever.
                currentBatch = null;
                // Wrapped rather than rethrown directly: run() implements
                // Runnable and cannot declare a checked exception, and the
                // upstream `throw e` does not compile here.
                throw new RuntimeException(
                        "Fatal ClickHouse error (Code: " + errorCode
                                + "), stopping task", e);
            } else {
                log.warn("Retriable ClickHouse error (Code: {}, Category: {}) -- "
                        + "batch will be retried on next scheduled run.", errorCode, category);
            }
        }
    }


    /**
     * Detects the unrecoverable "OffsetStorageWriter is already flushing" condition.
     *
     * @param e the exception thrown from the batch loop.
     * @return true when offset commits can no longer succeed in this JVM.
     */
    private boolean isOffsetWriterPoisoned(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("OffsetStorageWriter is already flushing")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        // Retry of an ALREADY-FLUSHED batch: the data is in ClickHouse and the
        // batch is only waiting for older in-flight batches to clear before
        // its offsets may be committed. Do NOT re-run the inserts (each replay
        // writes a duplicate part, visible to SELECTs without FINAL until the
        // merge collapses it — CI observed one record re-inserted 1,000 times)
        // and do NOT re-add the batch to the in-flight map (that would undo
        // its move to completedBatches and re-block every newer batch). Just
        // re-check committability, with a short pause to avoid a hot spin.
        if (currentBatchFlushed) {
            if (DebeziumOffsetManagement.checkIfBatchCanBeCommitted(currentBatch)) {
                currentBatch = null;
                currentBatchFlushed = false;
            } else {
                Thread.sleep(100);
            }
            return;
        }

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
                new HashMap<>();
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
            // The flush succeeded: remember that so a commit-blocked retry
            // does not re-execute the inserts (duplicate parts) or re-add the
            // batch to the in-flight map.
            currentBatchFlushed = true;
            // Step 2: Check if the batch can be committed.
            if(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(currentBatch)) {
                currentBatch = null;
                currentBatchFlushed = false;
            }
        }
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
        if(records == null || records.isEmpty()) {
            return;
        }
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            String databaseName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
            String tableName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TABLE_NAME.toString());
            if (records == null || records.isEmpty()) {
                log.warn("Skipping history table update — batch is empty");
                return;
            }
            Connection databaseConn = getClickHouseConnection(databaseName);
            DbWriter writer = getDbWriterForTable(databaseName + "." + tableName, tableName, databaseName,
                    records.get(0), databaseConn);
            if (writer == null) {
                // getDbWriterForTable returns null when the table is still
                // frozen for a DDL reconciliation. Skip the history write for
                // this batch rather than dereferencing null; it is retried.
                log.error("*** DbWriter is null for {}.{} (table frozen for DDL); "
                        + "skipping history write for this batch", databaseName, tableName);
                return;
            }

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
        String fullyQualifiedTableName = databaseName + "." + tableName;
        // Block while a DDL schema change is being applied + reconciled for
        // this table. This prevents inserting against a stale column cache
        // (which would silently drop newly added source columns). The DDL
        // thread freezes the table before ALTER and only unfreezes after the
        // destination schema, the source schema, and the cache all agree.
        // The return value MUST be honoured: false means the freeze did not
        // clear within the timeout, i.e. a DDL reconciliation is stuck.
        // Proceeding anyway would insert against the very stale column cache
        // this freeze exists to prevent, silently dropping newly added source
        // columns. Return null so the caller defers the batch and retries.
        boolean unfrozen = TableReplicationFreezeManager.getInstance()
                .awaitUnfrozen(fullyQualifiedTableName, DDLSchemaChangeWaiter.DEFAULT_TIMEOUT_MS);
        if (!unfrozen) {
            log.error("Table {} still frozen after {}ms; deferring this batch rather "
                            + "than inserting against a possibly stale schema cache.",
                    fullyQualifiedTableName, DDLSchemaChangeWaiter.DEFAULT_TIMEOUT_MS);
            return null;
        }

        long generation = CacheInvalidationManager.getInstance()
                .currentGeneration(fullyQualifiedTableName);

        if (this.topicToDbWriterMap.containsKey(topicName)) {
            // Rebuild when EITHER signal fires. Both sides of this merge are
            // kept deliberately:
            //  - generation change: a DDL was applied since this writer was
            //    cached. Comparing generations (rather than consuming a
            //    one-shot flag) is what lets every worker thread's private
            //    cache invalidate independently; the old remove-on-read Set
            //    let the first reader consume the signal and left the other
            //    threads serving a stale column list.
            //  - TTL expiry: defensive self-heal in case a schema change was
            //    missed entirely and never bumped the generation.
            Long cachedGeneration = this.topicToDbWriterGeneration.get(topicName);
            boolean ddlInvalidated =
                    cachedGeneration == null || cachedGeneration != generation;
            boolean ttlExpired = CacheInvalidationManager.getInstance()
                    .isCacheExpired(fullyQualifiedTableName);
            if (!ddlInvalidated && !ttlExpired) {
                return this.topicToDbWriterMap.get(topicName);
            }
            log.info("Rebuilding cached DbWriter for {} ({}; generation {} -> {})",
                    topicName,
                    ddlInvalidated ? "DDL invalidation" : "cache TTL expiry",
                    cachedGeneration, generation);
            this.topicToDbWriterMap.remove(topicName);
        }
        writer = new DbWriter(this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName, tableName,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), this.config, record,
                connection);
        this.topicToDbWriterMap.put(topicName, writer);
        // Record the generation this writer was built at (so the next call can
        // detect a later DDL) AND stamp the TTL clock, then verify the freshly
        // built column cache actually covers every source column.
        this.topicToDbWriterGeneration.put(topicName, generation);
        CacheInvalidationManager.getInstance().markCacheBuilt(fullyQualifiedTableName);
        if (!verifySourceSchemaIntegrity(fullyQualifiedTableName, record, writer)) {
            // The writer's column map does not cover every source column, so
            // inserting with it would silently DROP those columns. Evict it and
            // return null: the caller defers this batch and retries, by which
            // time the rebuild picks up the now-visible columns. Returning the
            // writer anyway was the stale-writer escape hatch that made the
            // integrity check advisory instead of protective.
            this.topicToDbWriterMap.remove(topicName);
            this.topicToDbWriterGeneration.remove(topicName);
            return null;
        }
        return writer;
    }

    /**
     * Verifies that every column present in the source change event also exists
     * in the freshly rebuilt destination column cache. If a source column is
     * missing, the INSERT path would silently drop it (data loss), so we log
     * loudly and re-mark the table for invalidation. Re-marking forces the next
     * batch to rebuild again rather than insert lossy data, which lets a
     * still-propagating schema change catch up. This is a safety net layered on
     * top of the per-table replication freeze.
     *
     * @return {@code true} when the writer may safely be used; {@code false}
     *         when its column map is missing source columns, in which case the
     *         caller MUST NOT insert with it.
     */
    private boolean verifySourceSchemaIntegrity(String fullyQualifiedTableName,
                                                ClickHouseStruct record,
                                                DbWriter writer) {
        try {
            // The replication-history table is EXEMPT: it has its own fixed
            // audit schema (gtid/ddl/before/after/...) and source-row columns
            // are serialized into its payload columns, not mapped one-to-one.
            // Comparing a source event against it reports every source column
            // "missing" — the gate then blocks each batch for the full
            // visibility-wait timeout polling system.columns for columns that
            // will never appear, skips the history write, and starves the
            // shared connection pool for the whole process.
            // See SourceSchemaIntegrityValidator.isReplicationHistoryTable.
            if (SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                    this.config, fullyQualifiedTableName)) {
                return true;
            }
            java.util.List<String> sourceColumns =
                    SourceSchemaColumns.fromRecord(record);
            if (sourceColumns.isEmpty() || writer == null
                    || writer.getColumnNameToDataTypeMap() == null) {
                return true;
            }
            SourceSchemaIntegrityValidator.Result result =
                    SourceSchemaIntegrityValidator.check(sourceColumns,
                            writer.getColumnNameToDataTypeMap().keySet());
            if (!result.isConsistent()) {
                String[] parts = fullyQualifiedTableName.split("\\.", 2);
                // Source columns that map to destination ALIAS/MATERIALIZED
                // columns are NOT missing: the insertable cache excludes them
                // by design — ClickHouse computes their values and rejects
                // inserts into them, so the source value is intentionally not
                // written. MySQL generated columns land here on EVERY batch;
                // without this filter the gate invalidated and rebuilt the
                // writer forever, livelocking replication for the table
                // (observed: 8,208 invalidate/rebuild cycles in one CI run).
                java.util.List<String> genuinelyMissing =
                        result.getMissingInDestination();
                if (parts.length == 2 && writer.getConnection() != null) {
                    try {
                        java.util.Set<String> generated = new DBMetadata(this.config)
                                .getAliasAndMaterializedColumnsForTableAndDatabase(
                                        parts[1], parts[0], writer.getConnection());
                        genuinelyMissing = SourceSchemaIntegrityValidator
                                .excludeGeneratedColumns(genuinelyMissing, generated);
                    } catch (Exception ex) {
                        log.warn("Could not fetch ALIAS/MATERIALIZED columns for {}: {}",
                                fullyQualifiedTableName, ex.getMessage());
                    }
                }
                if (genuinelyMissing.isEmpty()) {
                    // Every "missing" column is computed by the destination —
                    // the writer is correct as built. Invalidating here is the
                    // livelock; the writer must be used as-is.
                    return true;
                }
                // Generalized visibility gate: rather than only logging, WAIT for
                // the destination to actually gain the missing columns. This is
                // what covers RENAME/MODIFY COLUMN, whose net effect is "these
                // columns must exist" but which the ADD/DROP text parser in
                // waitForSchemaVisibility cannot see.
                java.util.Collection<String> stillMissing = genuinelyMissing;
                if (parts.length == 2 && writer.getConnection() != null) {
                    stillMissing = new DDLSchemaChangeWaiter()
                            .waitForExpectedColumns(writer.getConnection(), parts[0],
                                    parts[1], genuinelyMissing);
                }
                if (stillMissing.isEmpty()) {
                    // Columns landed while we waited; force a rebuild so the next
                    // call picks up a writer that actually knows about them.
                    log.warn("Schema integrity for {}: source column(s) {} were missing "
                                    + "but became visible while waiting; re-marking for "
                                    + "invalidation so the writer is rebuilt.",
                            fullyQualifiedTableName, genuinelyMissing);
                } else {
                    log.error("Schema integrity violation for {}: source column(s) {} "
                                    + "still missing from the destination after waiting; "
                                    + "inserting now would drop them. Re-marking for "
                                    + "invalidation.",
                            fullyQualifiedTableName, stillMissing);
                }
                CacheInvalidationManager.getInstance().invalidateTable(fullyQualifiedTableName);
                // Either way the CURRENT writer's column map predates those
                // columns, so it must not be used for this batch.
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Error during source schema integrity check for {}: {}",
                    fullyQualifiedTableName, e.getMessage());
            // Fail open on an unexpected checker error: the freeze and
            // generation gates upstream are the primary protections, and
            // blocking every batch on a bug in this safety net would be worse.
            return true;
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
     * <p>This also subsumes the lazy-open behaviour this branch introduced: a
     * null handle is unusable, so the connection is still opened on first use
     * rather than in the constructor, and constructing the runnable does not
     * require a reachable ClickHouse.</p>
     *
     * @return a usable system-database connection, or null when one cannot be
     *         obtained (callers already handle a null connection).
     */
    private synchronized Connection systemConnection() {
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
        // Validate writer before using it — null writer causes NPE in
        // PreparedStatementExecutor creation and error logging
        if (writer == null) {
            log.error("*** DbWriter is null for {}.{}, retrying", databaseName, tableName);
            writer = getDbWriterForTable(topicName, tableName, databaseName,
                    firstRecord, databaseConn);
        }
        if (writer == null) {
            log.error("*** DbWriter still null for {}.{}, retrying on next attempt",
                    databaseName, tableName);
            return false;
        }
        if (writer.wasTableMetaDataRetrieved() == false) {
            log.error(String.format("*** TABLE METADATA not retrieved for " +
                            "Database(%s), table(%s) retrying",
                    writer.getDatabaseName(), writer.getTableName()));
            writer.updateColumnNameToDataTypeMap();
            if (writer.wasTableMetaDataRetrieved() == false) {
                log.error(String.format("*** TABLE METADATA not retrieved for " +
                                "Database(%s), table(%s), retrying on next attempt",
                        databaseName, tableName));
                return false;
            }
        }
        PreparedStatementExecutor preparedStatementExecutor = new
                PreparedStatementExecutor(writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(), writer.getSignColumn(),
                writer.getVersionColumn(), writer.getDatabaseName(),
                getServerTimeZone(this.config));
        // Step 1: The Batch Insert with preparedStatement in JDBC works by
        // forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records (List of ClickHouseStruct).
        Map<MutablePair<String, Map<String, Integer>>,
                List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        // Pass the writer's connector-managed column names (version/sign/
        // delete columns as actually named in the table engine) so the query
        // formatter keeps them in the insert list while omitting genuine
        // destination-only data columns the source event does not carry.
        java.util.List<String> managedColumns = new ArrayList<>();
        if (writer.getVersionColumn() != null) {
            managedColumns.add(writer.getVersionColumn());
        }
        if (writer.getSignColumn() != null) {
            managedColumns.add(writer.getSignColumn());
        }
        if (writer.getReplacingMergeTreeDeleteColumn() != null) {
            managedColumns.add(writer.getReplacingMergeTreeDeleteColumn());
        }
        result = new GroupInsertQueryWithBatchRecords()
                .groupQueryWithRecords(records, queryToRecordsMap,
                        partitionToOffsetMap, this.config, tableName,
                        writer.getDatabaseName(), writer.getConnection(),
                        writer.getColumnNameToDataTypeMap(), managedColumns);
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
        // queryToRecordsMap uses MutablePair keys, not String — remove was a no-op
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
        result = preparedStatementExecutor.addToPreparedStatementBatch(
                topicName, queryToRecordsMap, bmd, config,
                writer.getConnection(), writer.getTableName(),
                writer.getColumnNameToDataTypeMap(), writer.getEngine());
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
