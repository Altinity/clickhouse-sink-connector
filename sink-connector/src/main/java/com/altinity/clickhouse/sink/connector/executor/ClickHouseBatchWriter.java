package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.DbKafkaOffsetWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
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

/**
 * ClickHouseBatchWriter is responsible for persisting records to
 * ClickHouse in batch mode.
 *
 * <p>This class manages connections to ClickHouse, maps topics to
 * tables, and processes record batches, including grouping records
 * by topic, flushing them to ClickHouse, and handling Kafka offsets.
 */
public class ClickHouseBatchWriter implements AutoCloseable {

    /**
     * Connector configuration.
     */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Connection that will be used to create the debezium storage
     * database.
     */
    private Connection systemConnection;

    /**
     * For insert batch the database connection has to be the same.
     * Create a map of database name to ClickHouseConnection.
     */
    private Map<String, Connection> databaseToConnectionMap = new HashMap<>();

    /**
     * Logger instance for the ClickHouseBatchRunnable class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseBatchWriter.class);

    /**
     * Map of topic names to table names.
     */
    private final Map<String, String> topic2TableMap;

    /**
     * Map of topic name to CLickHouseConnection instance (DbWriter).
     */
    private Map<String, DbWriter> topicToDbWriterMap;

    /**
     * Database credentials.
     */
    private DBCredentials dbCredentials;

    /**
     * Map for overriding database names from source to destination.
     */
    private Map<String, String> databaseOverrideMap = new HashMap<>();

    /**
     * Sleep time after an error occurs (in milliseconds).
     */
    private static final long ERROR_SLEEP_TIME_MS = 10000;

    /**
     * Constructs a ClickHouseBatchWriter.
     *
     * @param config the connector configuration
     * @param topic2TableMap a map of topic names to table names
     */
    public ClickHouseBatchWriter(
            ClickHouseSinkConnectorConfig config,
            Map<String, String> topic2TableMap) {
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
                                    CLICKHOUSE_DATABASE_OVERRIDE_MAP
                                    .toString()));
        } catch (Exception e) {
            log.error("Error parsing database override map" + e);
        }
    }

    /**
     * Creates a connection to the specified database.
     *
     * @param database the database name
     * @return a Connection object to the given database
     */
    private Connection createConnection(String database) {
        String jdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), "system");
        return BaseDbWriter.createConnection(jdbcUrl,
                "Sink Connector Lightweight",
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(),
                database, config);
    }

    /**
     * Retrieves the ClickHouse connection for the specified database.
     *
     * <p>If no connection exists, a new one is created and stored.
     *
     * @param databaseName the target database name
     * @return a Connection to the specified database
     */
    private Connection getClickHouseConnection(String databaseName) {
        if (this.databaseToConnectionMap.containsKey(databaseName)) {
            return this.databaseToConnectionMap.get(databaseName);
        }
        String jdbcUrl = BaseDbWriter.getConnectionString(
                this.dbCredentials.getHostName(),
                this.dbCredentials.getPort(), databaseName);
        Connection conn = BaseDbWriter.createConnection(jdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME,
                this.dbCredentials.getUserName(),
                this.dbCredentials.getPassword(),
                databaseName, config);
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
     * Persists a batch of records to ClickHouse.
     *
     * <p>This method groups records by topic, processes each group, and
     * acknowledges the records if processing is successful.
     *
     * <p>A batch that cannot be written raises
     * {@link BatchPersistenceException} rather than returning quietly, so the
     * Debezium engine stops instead of committing an offset past records that
     * never reached ClickHouse.
     *
     * @param records a list of ClickHouseStruct records to persist
     * @throws BatchPersistenceException if any topic in the batch could not be
     *         persisted
     */
    public void persistRecords(List<ClickHouseStruct> records) {
        log.info("****** Thread: " +
                Thread.currentThread().getName() +
                " Batch Size: " + records.size() +
                " ******");
        // Group records by topic name.
        // Create a new map of topic name to list of records.
        try {
            Map<String, List<ClickHouseStruct>> topicToRecordsMap =
                    new ConcurrentHashMap<>();
            records.forEach(record -> {
                String topicName = record.getTopic();
                // If the topic name is not present, create a new list and add
                // the record.
                if (topicToRecordsMap.containsKey(topicName) == false) {
                    List<ClickHouseStruct> recordsList =
                            new ArrayList<>();
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
                if (result == false) {
                    // Do NOT break and fall out of this method normally. A
                    // normal return tells the caller the batch was handled:
                    // the acknowledgement block below is skipped, so this
                    // batch is never committed, but the engine goes straight
                    // on to the NEXT batch and commits ITS offsets -- which
                    // are higher. The unwritten records are then behind the
                    // committed offset and are never replayed. That is the
                    // silent loss in issue #1285.
                    throw new BatchPersistenceException(String.format(
                            "Failed to persist %d record(s) for topic %s to "
                            + "ClickHouse. The most common cause is that the "
                            + "target table does not exist and "
                            + "auto.create.tables is disabled (see the "
                            + "TABLE METADATA not retrieved errors above). "
                            + "Failing the batch so its offset is not "
                            + "committed; the records are replayed from the "
                            + "last committed offset once the table exists.",
                            entry.getValue().size(), entry.getKey()));
                }
            }
            // acknowledge the records.
            if (result) {
                log.info("****** Acknowledging records ******");
                records.forEach(record -> {
                    try {
                        record.getCommitter().markProcessed(
                                record.getSourceRecord());
                    } catch (InterruptedException e) {
                        //throw new RuntimeException(e);
                        log.error("Error marking records as processed" + e);
                    }
                    if (record.isLastRecordInBatch()) {
                        try {
                            record.getCommitter().markBatchFinished();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            if (isInterrupt(e)) {
                // The task is being shut down. Nothing was acknowledged, so
                // this batch is replayed from the last committed offset on the
                // next start; treating a shutdown as a persistence failure
                // would turn a clean stop into a restart loop.
                Thread.currentThread().interrupt();
                log.warn("Interrupted while persisting records to ClickHouse. "
                        + "The batch was not acknowledged and will be replayed "
                        + "from the last committed offset.", e);
                return;
            }
            // Anything else means the batch did not reach ClickHouse.
            // Swallowing it here has exactly the same effect as the break
            // above: the batch is dropped, the engine moves on, and a later
            // batch commits an offset past these records. Unlike
            // ClickHouseBatchRunnable -- which keeps currentBatch non-null and
            // re-polls the SAME batch, so a failure there is retried -- this
            // writer is called once per batch and has no retry loop. The only
            // way for it to avoid losing data is to fail loudly.
            log.error("Error persisting records to ClickHouse", e);
            throw e instanceof BatchPersistenceException
                    ? (BatchPersistenceException) e
                    : new BatchPersistenceException(
                            "Failed to persist a batch of records to ClickHouse", e);
        }
    }

    /**
     * Whether this failure is an interrupt, i.e. the task is shutting down
     * rather than failing to write.
     *
     * @param e the exception to inspect
     * @return true when an InterruptedException appears in the cause chain
     */
    private static boolean isInterrupt(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    /**
     * Signals that a batch could not be written to ClickHouse.
     *
     * <p>Thrown so the failure reaches the Debezium engine instead of being
     * logged and forgotten. The engine stops and restarts from the last
     * committed offset, which replays the records this batch failed to write.
     * The alternative -- returning normally -- leaves those records behind an
     * offset that a later batch commits, and they are lost for good.
     *
     * <p>See issue #1285.
     */
    public static class BatchPersistenceException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BatchPersistenceException(String message) {
            super(message);
        }

        public BatchPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Function to retrieve table name from topic name.
     *
     * @param topicName the topic name
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
    public ZoneId getServerTimeZone(
            ClickHouseSinkConnectorConfig config) {
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
     * Same lifetime problem as in {@code ClickHouseBatchRunnable}: the
     * connection is opened once in the constructor and held for the life of the
     * task, but a pooled connection is returned, evicted or idle-reaped long
     * before then, after which every use throws
     * {@code SQLException: Connection is closed}. Fixed in both places so the
     * two executors do not diverge.
     *
     * <p>Package-private rather than private so the reconnect behaviour can be
     * exercised directly, in the same style as
     * {@code PreparedStatementExecutor#updateRelocatesSortingKey}.
     *
     * @return a usable system-database connection, or null when one cannot be
     *         obtained (callers already handle a null connection).
     */
    Connection systemConnection() {
        if (BaseDbWriter.isUnusable(this.systemConnection)) {
            // Close before replacing. isUnusable() is also true when the
            // driver THROWS from isClosed(), in which case the handle is
            // still open; overwriting the field would orphan a live
            // connection that nothing can ever release (issue #1252).
            closeQuietly(this.systemConnection, BaseDbWriter.SYSTEM_DB);
            this.systemConnection = createConnection(BaseDbWriter.SYSTEM_DB);
        }
        return this.systemConnection;
    }

    /**
     * Releases every JDBC connection this writer holds: the per-database
     * connections cached in {@link #databaseToConnectionMap} and the
     * system-database connection opened by the constructor.
     *
     * <p>Nothing else can release them. The connections are opened here and
     * the handles are not published anywhere, so without this method every
     * connector stop leaks the whole set -- and the lightweight connector
     * restarts its event loop in-process (the REST force-start stops the
     * current {@code DebeziumChangeEventCapture} and builds a new one), so
     * the leak accumulates without the JVM ever exiting.</p>
     *
     * <p>Idempotent and non-throwing: a second call has nothing left to do,
     * and a connection that fails to close is logged and skipped so the rest
     * of the set is still released. Shutdown must not be abandoned halfway --
     * that is the same leak again.</p>
     */
    @Override
    public void close() {
        for (Map.Entry<String, Connection> entry
                : this.databaseToConnectionMap.entrySet()) {
            closeQuietly(entry.getValue(), entry.getKey());
        }
        // Cleared, not just closed: a closed handle handed back by
        // getClickHouseConnection() would fail every subsequent statement.
        this.databaseToConnectionMap.clear();
        closeQuietly(this.systemConnection, BaseDbWriter.SYSTEM_DB);
        this.systemConnection = null;
    }

    /**
     * Closes a connection, logging rather than propagating a failure.
     *
     * @param connection the connection to close; null and already-closed
     *                   handles are no-ops
     * @param databaseName the database the connection belongs to, for logging
     */
    private static void closeQuietly(Connection connection,
                                     String databaseName) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.error("Error closing the ClickHouse connection for database "
                    + databaseName, e);
        }
    }

    /**
     * Processes records for the specified topic.
     *
     * <p>This function groups records by topic, retrieves the
     * corresponding table name and DbWriter, and processes the batch
     * by grouping records into insert queries and flushing them to
     * ClickHouse.
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

        // If replication history is enabled or replication_log_only is enabled,
        // use the replication history database name
        boolean replicationHistoryEnabled = config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString());
        boolean replicationLogOnly = config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_REPLICATION_LOG_ONLY.toString());

        if (replicationHistoryEnabled || replicationLogOnly) {
            databaseName = config.getString(
                    ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
        }

        // Check if user has overridden the database name.
        if (this.databaseOverrideMap.containsKey(firstRecord.getDatabase()))
            databaseName = this.databaseOverrideMap.get(
                    firstRecord.getDatabase());
        Connection databaseConn = getClickHouseConnection(databaseName);
        DbWriter writer = getDbWriterForTable(topicName, tableName, databaseName,
                firstRecord, databaseConn);
        // Supplied as a supplier, not a snapshot: this executor is built BEFORE
        // the metadata-retry block below, which is exactly when a table created
        // by DDL (rather than by auto-create) first gets its sorting key. A
        // value captured here would still be empty, and the writer would then
        // silently skip the UPDATE tombstone.
        PreparedStatementExecutor preparedStatementExecutor =
                new PreparedStatementExecutor(writer.
                        getReplacingMergeTreeDeleteColumn(),
                        writer.isReplacingMergeTreeWithIsDeletedColumn(),
                        writer.getSignColumn(), writer.getVersionColumn(),
                        writer.getDatabaseName(),
                        getServerTimeZone(this.config),
                        writer::getSortingKeyColumns);
        if (writer == null || writer.wasTableMetaDataRetrieved() == false) {
            log.error(String.format(
                    "*** TABLE METADATA not retrieved for " +
                            "Database(%s), table(%s) retrying",
                    writer.getDatabaseName(), writer.getTableName()));
            if (writer == null) {
                writer = getDbWriterForTable(topicName, tableName,
                        databaseName, firstRecord, databaseConn);
            }
            if (writer.wasTableMetaDataRetrieved() == false)
                writer.updateColumnNameToDataTypeMap();
            if (writer == null ||
                    writer.wasTableMetaDataRetrieved() == false) {
                log.error(String.format(
                        "*** TABLE METADATA not retrieved for " +
                                "Database(%s), table(%s), retrying on next " +
                                "attempt",
                        writer.getDatabaseName(), writer.getTableName()));
                return false;
            }
        }
        // Step 1: The Batch Insert with preparedStatement in JDBC works by
        // forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records (List of
        // ClickHouseStruct).
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
        // PreparedStatement and added to the batch. The batch is then
        // executed and the records are flushed to ClickHouse.
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
     * Function that flushes records to ClickHouse if there are minimum
     * records or if the flush timeout has reached.
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
