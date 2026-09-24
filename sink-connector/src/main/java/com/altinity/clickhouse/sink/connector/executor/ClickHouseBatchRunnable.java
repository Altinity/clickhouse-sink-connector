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
import com.google.common.annotations.VisibleForTesting;
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
     * Paces the retries of a batch that failed to reach ClickHouse: the delay
     * doubles per consecutive failure of the same batch up to a cap, and is
     * reset by a successful write (spec 10.02). Without it the scheduled tick
     * re-ran a failing batch every buffer.flush.time.ms (30 ms) against a
     * server that had just reported backpressure.
     */
    private final RetryBackoff retryBackoff;

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
        this.retryBackoff = new RetryBackoff(
                this.config.getLong(ClickHouseSinkConnectorConfigVariables
                        .BATCH_RETRY_BACKOFF_INITIAL_MS.toString()),
                this.config.getLong(ClickHouseSinkConnectorConfigVariables
                        .BATCH_RETRY_BACKOFF_MAX_MS.toString()));
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
        return openConnection(jdbcUrl, databaseName);
    }

    /**
     * The single place this worker obtains a JDBC connection. Every
     * connection the worker uses -- system, per-database, bootstrap -- comes
     * through here, so a test can substitute recording or null connections
     * without a ClickHouse server.
     *
     * @param jdbcUrl      the server URL to connect to
     * @param databaseName the database the connection is for (pool key)
     * @return a connection, or null when one cannot be obtained
     */
    @VisibleForTesting
    Connection openConnection(String jdbcUrl, String databaseName) {
        return BaseDbWriter.createConnection(jdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(), databaseName, config);
    }

    /**
     * {@code host:port/database} keys whose {@code CREATE DATABASE IF NOT
     * EXISTS} has succeeded in this process. Shared by every worker: the
     * statement is needed once per database per process, not once per worker
     * per cache miss -- and never again on every batch while a connection to
     * that database cannot be obtained.
     */
    private static final java.util.Set<String> ENSURED_DATABASES =
            ConcurrentHashMap.newKeySet();

    /**
     * Retrieves the ClickHouse connection for the specified database.
     *
     * <p>If no connection is cached, this method ensures the database exists
     * (once per process, see {@link #ensureDatabaseExists}) and opens a new
     * connection. A connection that cannot be obtained is reported at ERROR
     * naming the database and is NOT cached, so the next lookup retries.
     *
     * @param databaseName the target database name
     * @return a Connection to the specified database, or null if none could
     *         be obtained
     */
    private Connection getClickHouseConnection(String databaseName) {
        if (this.databaseToConnectionMap.containsKey(databaseName)) {
            return this.databaseToConnectionMap.get(databaseName);
        }
        ensureDatabaseExists(databaseName);
        String jdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName);
        Connection conn = openConnection(jdbcUrl, databaseName);
        // Only cache a usable connection. containsKey() above returns true for
        // a key mapped to null, so caching a failed connection would keep
        // returning null for this database until the connector restarts.
        if (conn != null) {
            this.databaseToConnectionMap.put(databaseName, conn);
        } else {
            // Never silent: the caller retries on the next tick, and the
            // operator must be able to see WHICH database cannot be reached.
            log.error("Could not obtain a ClickHouse connection to database `{}` on {}:{}; "
                            + "the batch will be retried on the next tick.",
                    databaseName, this.dbCredentials.getHostName(), this.dbCredentials.getPort());
        }
        return conn;
    }

    /**
     * Issues {@code CREATE DATABASE IF NOT EXISTS} for {@code databaseName}
     * unless this process has already done so successfully.
     *
     * <p>Exactly one statement per database per process. A failure -- no
     * system connection, or a rejected statement -- is reported at ERROR
     * naming the database and is NOT recorded as ensured, so the next cache
     * miss tries again. The caller still attempts the database connection in
     * either case: a user that may write to an existing database but lacks
     * {@code CREATE DATABASE} must not be blocked.
     *
     * @param databaseName the destination database
     */
    private void ensureDatabaseExists(String databaseName) {
        String host = this.dbCredentials.getHostName();
        Integer port = this.dbCredentials.getPort();
        String key = host + ":" + port + "/" + databaseName;
        if (ENSURED_DATABASES.contains(key)) {
            return;
        }
        String systemJdbcUrl = BaseDbWriter.getConnectionString(host, port, "system");
        Connection systemConn = openConnection(systemJdbcUrl, "system");
        if (systemConn == null) {
            log.error("Cannot ensure database `{}` exists on {}:{}: no ClickHouse connection could "
                            + "be obtained (server unreachable or credentials rejected). Will retry "
                            + "on the next lookup.", databaseName, host, port);
            return;
        }
        try {
            boolean useOnCluster = this.config.
                    getBoolean(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString());
            new ClickHouseCreateDatabase().createNewDatabase(systemConn, databaseName, useOnCluster, this.config);
            ENSURED_DATABASES.add(key);
        } catch (Exception e) {
            log.error("Error creating database `{}` on {}:{}: {}", databaseName, host, port,
                    e.toString(), e);
        } finally {
            try {
                systemConn.close();
            } catch (SQLException e) {
                log.error("Error closing connection after ensuring database `{}`: {}",
                        databaseName, e.toString());
            }
        }
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

        try {
            // Hash-routing mode (threadId >= 0 with a dedicated routed queue)
            // vs legacy single shared queue. In routing mode this runnable owns
            // exactly one queue and drains it in FIFO order, so same-table
            // batches (all routed to this thread) are applied in source order.
            if (this.routedRecords != null && this.threadId >= 0) {
                runWithHashRouting(taskId, sourceTimeZone, serverTimeZone, errorTableName);
            } else {
                runLegacyMode(taskId, sourceTimeZone, serverTimeZone, errorTableName);
            }
        } catch (Exception e) {
            log.error(String.format(
                            "ClickHouseBatchRunnable exception - Task(%s)", taskId),
                    e);
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.ERROR_LOGGING_ENABLE.toString())){
                logErrorToClickHouse(e, taskId, errorTableName);
            }

            // A poisoned OffsetStorageWriter is NOT retriable, and must be
            // checked before the ClickHouse classifier, which sees no
            // ClickHouse error code and defaults it to UNKNOWN/retriable. With
            // errors.max.retries = -1 that means retrying forever, and because
            // the offset store writes asynchronously the ClickHouse inserts
            // keep succeeding while the committed binlog position stays
            // frozen -- replication silently diverges instead of failing.
            // Observed on txnrepo-sink-staging (2026-09-10/11): ~8h of
            // "Retriable ClickHouse error (Code: -1, Category: UNKNOWN)" while
            // the committed offset never moved past its 13:29 event.
            if (isOffsetWriterPoisoned(e)) {
                log.error("FATAL: the Debezium OffsetStorageWriter is stuck in "
                        + "the 'already flushing' state -- Task({}). Offsets "
                        + "can no longer be committed in this JVM, so "
                        + "replication would keep writing rows against a "
                        + "frozen binlog position. Stopping the task to "
                        + "prevent silent data divergence; a restart resumes "
                        + "from the last committed offset.", taskId);
                // currentBatch is deliberately NOT cleared: its handoff unit
                // must stay outstanding so no control-record offset can pass
                // it while the engine is being stopped (spec 03.01 section 3.3).
                throw new RuntimeException(
                        "OffsetStorageWriter is permanently stuck flushing; "
                                + "stopping to prevent silent data divergence",
                        e);
            }

            // Classify the error to decide whether to retry or stop
            ClickHouseErrorClassifier.ErrorCategory category = ClickHouseErrorClassifier.classify(e);
            int errorCode = ClickHouseErrorClassifier.extractErrorCode(e);

            if (category == ClickHouseErrorClassifier.ErrorCategory.FATAL) {
                log.error("FATAL ClickHouse error (Code: {}) -- this batch will never succeed. " +
                          "Stopping this worker; the engine is stopped on the next source " +
                          "batch (a dead worker is detected by the capture loop). " +
                          "Manual intervention required.", errorCode);
                // currentBatch is deliberately NOT cleared: the batch's handoff
                // unit stays outstanding, so no control-record offset can pass
                // its rows and every younger unit stays parked behind it. The
                // throw ends this scheduled task; DebeziumChangeEventCapture
                // sees the terminated future and stops the engine LOUDLY with
                // this cause (spec 03.01 section 3.3) instead of leaving a
                // silently stalled pipeline behind.
                throw new RuntimeException("Fatal ClickHouse error, stopping task", e);
            } else {
                long delayMs = retryBackoff.nextDelayMs(currentBatch);
                log.warn("Retriable ClickHouse error (Code: {}, Category: {}) -- the same "
                         + "batch will be retried in {} ms (consecutive failures: {}). Every "
                         + "table hashed to this worker waits behind it until it succeeds.",
                         errorCode, category, delayMs, retryBackoff.consecutiveFailures());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Detects the unrecoverable "OffsetStorageWriter is already flushing"
     * condition.
     * <p>
     * {@code EmbeddedEngine.commitOffsets} returns early when {@code doFlush}
     * returns null WITHOUT calling {@code cancelFlush}, leaking the
     * OffsetStorageWriter's {@code flushInProgress} semaphore permanently, so
     * every subsequent {@code beginFlush()} in this JVM throws.
     * </p>
     *
     * @param e the exception thrown while processing a batch.
     * @return true when offset commits can no longer succeed in this JVM.
     */
    private static boolean isOffsetWriterPoisoned(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null
                    && message.contains(
                            "OffsetStorageWriter is already flushing")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Run loop for hash-based routing mode.
     * <p>
     * This runnable owns a dedicated queue ({@code routedRecords}) that receives
     * ONLY the batches routed to this thread (all batches for a given table hash
     * to one thread). It therefore drains its own queue in FIFO order and never
     * needs to inspect {@code assignedThreadId} or re-enqueue a sibling's batch.
     * The old shared-queue design (one queue, every thread filtering by id and
     * putting non-matching batches back at the tail) reordered same-table batches
     * under contention; per-thread queues remove that race.
     */
    private void runWithHashRouting(Long taskId, String sourceTimeZone, String serverTimeZone, String errorTableName) throws Exception {
        // Poll from this thread's own queue until it is empty.
        while (routedRecords.size() > 0 || currentBatch != null) {
            // If the thread is interrupted, exit.
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
                currentBatch = routedBatch.getBatch();
                log.debug("Thread {} picked up batch for table: {}", threadId, routedBatch.getTableName());
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
        // The batch was registered with its handoff sequence by the producer
        // before it was enqueued (spec 09.01 section 3.1); there is nothing to
        // register on pick-up.
        // Per-batch progress line: INFO by design (spec 03.06 section 3.3) --
        // operators read the connector's progress from the log.
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
            // WRITTEN-ONCE (spec 09.01 section 3.2). The rows are durably in
            // ClickHouse, so this worker is finished with the batch whatever
            // happens to its offset: drop it BEFORE handing it to the FIFO.
            // Keeping a written batch as currentBatch while its offset waited
            // for older batches made the run loop re-execute it -- and
            // re-insert its rows -- on every tick until it became committable
            // (rows present 3x in non-FINAL reads; write amplification).
            // Whether the offset is acknowledged now (this is the oldest
            // outstanding unit) or later (parked; drained by whichever call
            // acknowledges the head) is the FIFO's concern, not the worker's.
            List<ClickHouseStruct> written = currentBatch;
            currentBatch = null;
            retryBackoff.reset();
            DebeziumOffsetManagement.checkIfBatchCanBeCommitted(written);
        } else {
            // Not written (e.g. table metadata not yet retrievable): keep the
            // batch and retry it, but not every 30 ms (spec 10.02).
            long delayMs = retryBackoff.nextDelayMs(currentBatch);
            log.warn("Batch not written to ClickHouse; retrying the same batch in {} ms "
                    + "(consecutive failures: {})", delayMs, retryBackoff.consecutiveFailures());
            Thread.sleep(delayMs);
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
            String schemaTemplate = this.config != null
                    ? this.config.getString(
                            ClickHouseSinkConnectorConfigVariables
                                    .CLICKHOUSE_COMMON_SCHEMA_TEMPLATE.toString())
                    : null;
            tableName = Utils.getTableNameFromTopic(topicName, schemaPrefix, schemaTemplate);
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
     * Resolves the target ClickHouse database name for a topic and first record.
     * Applies replication history override, database prefix, schema template suffix,
     * and database override mapping.
     *
     * @param topicName   the Kafka/Debezium topic name
     * @param firstRecord the first record in the batch
     * @return the resolved ClickHouse database name
     */
    String resolveDatabaseName(String topicName, ClickHouseStruct firstRecord) {
        String databaseName = firstRecord != null ? firstRecord.getDatabase() : null;

        // If replication history is enabled, set database name to the replication history database name
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            return config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
        }

        // Apply database prefix if configured (mirrors DebeziumChangeEventCapture.extractDatabaseNameFromRecord)
        if (databaseName != null && this.config != null) {
            String dbPrefix = this.config.getString(
                    ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_DATABASE_PREFIX.toString());
            databaseName = Utils.applyDatabasePrefix(databaseName, dbPrefix);
        }

        // Apply database schema suffix if configured (mirrors DebeziumChangeEventCapture.extractDatabaseNameFromRecord)
        if (databaseName != null && this.config != null) {
            boolean dbSchemaSuffix = this.config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_SCHEMA_SUFFIX.toString());
            String schemaTemplate = this.config.getString(
                    ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_SCHEMA_TEMPLATE.toString());
            if (dbSchemaSuffix && schemaTemplate != null && !schemaTemplate.isEmpty()) {
                String schema = Utils.extractSchemaFromTopic(topicName);
                databaseName = Utils.applyDatabaseSchemaSuffix(databaseName, schemaTemplate, schema);
            }
        }

        // Check if user has overridden the database name (check both post-transform and pre-transform raw db)
        if (this.databaseOverrideMap.containsKey(databaseName)) {
            databaseName = this.databaseOverrideMap.get(databaseName);
        } else if (firstRecord != null && firstRecord.getDatabase() != null
                && this.databaseOverrideMap.containsKey(firstRecord.getDatabase())) {
            databaseName = this.databaseOverrideMap.get(firstRecord.getDatabase());
        }

        return databaseName;
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
    boolean processRecordsByTopic(String topicName,
                                  List<ClickHouseStruct> records)
            throws Exception {

        boolean result = false;
        //The user parameter will override the topic mapping to table.
        String tableName = getTableFromTopic(topicName);
        // Note: getting records.get(0) is safe as the topic name is same
        // for all records.
        ClickHouseStruct firstRecord = records.get(0);
        String databaseName = resolveDatabaseName(topicName, firstRecord);

        return processBatchRecords(records, topicName, tableName, databaseName, firstRecord);
    }

    private boolean processBatchRecords(List<ClickHouseStruct> records, String topicName,
                                      String tableName, String databaseName,
                                      ClickHouseStruct firstRecord) throws Exception {
        boolean result = false;

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
                PreparedStatementExecutor(
                        writer != null ? writer.getReplacingMergeTreeDeleteColumn() : null,
                        writer != null && writer.isReplacingMergeTreeWithIsDeletedColumn(),
                        writer != null ? writer.getSignColumn() : null,
                        writer != null ? writer.getVersionColumn() : null,
                        writer != null ? writer.getDatabaseName() : databaseName,
                        getServerTimeZone(this.config),
                        sortingKeySource != null ? sortingKeySource::getSortingKeyColumns : null);
        if (writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error(String.format("*** TABLE METADATA not retrieved for " +
                            "Database(%s), table(%s) retrying",
                    writer != null ? writer.getDatabaseName() : databaseName,
                    writer != null ? writer.getTableName() : tableName));
            if (writer == null) {
                writer = getDbWriterForTable(topicName, tableName, databaseName,
                        firstRecord, databaseConn);
            }
            if (writer != null && writer.wasTableMetaDataRetrieved() == false)
                writer.updateColumnNameToDataTypeMap();
            if (writer == null ||
                    writer.wasTableMetaDataRetrieved() == false) {
                log.error(String.format("*** TABLE METADATA not retrieved for " +
                                "Database(%s), table(%s), retrying on next attempt",
                        writer != null ? writer.getDatabaseName() : databaseName,
                        writer != null ? writer.getTableName() : tableName));
                return false;
            }
        }
        // Step 1: The Batch Insert with preparedStatement in JDBC works by
        // forming the Query and then adding records to the Batch.
        // This step creates an ordered list of segments, each a Map of
        // Query -> Records (List of ClickHouseStruct); a replicated TRUNCATE
        // is a segment of its own (spec 04.05).
        List<Map<MutablePair<String, Map<String, Integer>>,
                List<ClickHouseStruct>>> querySegments = new ArrayList<>();
        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        // The resolved engine columns (spec 08.01) must reach query
        // construction: a version / sign / delete column with a
        // non-default name is otherwise omitted from the INSERT and stored
        // as the type default for every row (spec 04.02 section 3.1).
        new GroupInsertQueryWithBatchRecords(writer.getVersionColumn(), writer.getSignColumn(),
                writer.getReplacingMergeTreeDeleteColumn())
                .groupQueryWithRecords(records, querySegments,
                        partitionToOffsetMap, this.config, tableName,
                        writer.getDatabaseName(), writer.getConnection(),
                        writer.getColumnNameToDataTypeMap());
        BlockMetaData bmd = new BlockMetaData();
        long maxBufferSize = this.config.getLong(
                ClickHouseSinkConnectorConfigVariables.
                        BUFFER_MAX_RECORDS.toString());
        // Step 2: Create a PreparedStatement and add the records to the
        // batch. In DbWriter, the query segments are converted to
        // PreparedStatements and added to the batch. The batch is then
        // executed and the records are flushed to ClickHouse.
        result = flushRecordsToClickHouse(topicName, writer, querySegments,
                bmd, maxBufferSize, preparedStatementExecutor);
        if (result) {
            // Records are now DURABLY in ClickHouse: advance the shared watermark
            // (max offset per TopicPartition) so ClickHouseSinkTask.preCommit()
            // only commits offsets that were actually persisted. Without this the
            // offset advances on consume, and a crash/restart silently loses the
            // records that were consumed but never inserted.
            partitionToOffsetMap.forEach((tp, offset) ->
                    this.durablyInsertedOffsets.merge(tp, offset, Math::max));
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
     * @param querySegments the ordered segments of insert queries to records
     * @param bmd block metadata used for metrics
     * @param maxBufferSize the maximum buffer size before flushing
     * @param preparedStatementExecutor the executor to add batches
     * @return true if the flush succeeds; false otherwise
     * @throws Exception if an error occurs during batch execution
     */
    private boolean flushRecordsToClickHouse(String topicName, DbWriter writer,
                                             List<Map<MutablePair<String, Map<String, Integer>>,
                                                     List<ClickHouseStruct>>> querySegments, BlockMetaData bmd,
                                             long maxBufferSize,
                                             PreparedStatementExecutor preparedStatementExecutor)
            throws Exception {
        boolean result = false;
        synchronized (querySegments) {
            result = preparedStatementExecutor.addToPreparedStatementBatch(
                    topicName, querySegments, bmd, config,
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
                SourceRecord sourceRecord = sourceRecordOrNull(firstRecord);
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
        } catch (SQLException | RuntimeException ex) {
            // Best-effort diagnostics only. The exception being logged is
            // classified and handled by the caller right after this; a
            // failure INSIDE the error logger must not replace it, or a FATAL
            // ClickHouse error would surface as an unrelated logger failure
            // and its classification would never run (spec 03.01 section 3.4).
            log.error("******* ERROR **** Failed to log error to ClickHouse *********",
                    ex);
        }
    }

    /**
     * The Debezium {@code SourceRecord} behind a batch record, or null.
     *
     * <p>Only the embedded (lightweight) runtime attaches a source record; a
     * record built from a Kafka {@code SinkRecord} carries none, so reading
     * {@code getSourceRecord().value()} unguarded threw
     * {@code NullPointerException} from inside the error logger in Kafka
     * mode whenever {@code error.logging.enable} was on -- replacing the
     * ClickHouse error that was being reported.</p>
     */
    @VisibleForTesting
    static SourceRecord sourceRecordOrNull(ClickHouseStruct record) {
        if (record == null || record.getSourceRecord() == null) {
            return null;
        }
        return record.getSourceRecord().value();
    }
}
