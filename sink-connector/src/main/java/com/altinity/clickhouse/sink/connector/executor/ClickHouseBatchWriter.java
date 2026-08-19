package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.DDLSchemaChangeWaiter;
import com.altinity.clickhouse.sink.connector.db.DbKafkaOffsetWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.SourceSchemaIntegrityValidator;
import com.altinity.clickhouse.sink.connector.db.TableReplicationFreezeManager;
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
public class ClickHouseBatchWriter {

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
     * DDL generation each cached DbWriter in {@link #topicToDbWriterMap} was built at,
     * keyed by topic name. Used to detect schema changes applied since caching.
     */
    private Map<String, Long> topicToDbWriterGeneration;

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
     * @param records a list of ClickHouseStruct records to persist
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
                    log.error("Error processing records for topic: " +
                            entry.getKey());
                    break;
                }
            }
            // acknowledge the records.
            if (result) {
                log.info("****** Acknowledging records ******");
                // Route through DebeziumOffsetManagement so this path uses the SAME
                // OFFSET_COMMIT_LOCK as the batch-runnable path. Calling
                // markProcessed()/markBatchFinished() directly here bypassed the
                // serialization entirely and could drive a concurrent beginFlush()
                // into the non-thread-safe OffsetStorageWriter.
                for (ClickHouseStruct record : records) {
                    if (record.getCommitter() == null || record.getSourceRecord() == null) {
                        continue;
                    }
                    try {
                        DebeziumOffsetManagement.acknowledgeRecord(
                                record.getCommitter(),
                                record.getSourceRecord(),
                                record.isLastRecordInBatch());
                    } catch (InterruptedException e) {
                        // Preserve the interrupt and stop acknowledging: silently
                        // continuing would advance offsets for records whose commit
                        // never completed.
                        Thread.currentThread().interrupt();
                        log.error("Interrupted while acknowledging records", e);
                        throw new RuntimeException(e);
                    }
                    // NOTE: markBatchFinished() is deliberately NOT re-invoked
                    // here. acknowledgeRecord() above already performs it, under
                    // the shared OFFSET_COMMIT_LOCK, when isLastRecordInBatch()
                    // is true. The PR-28 variant called it again outside that
                    // lock, which is precisely the unserialized second flush
                    // path that produced "OffsetStorageWriter is already
                    // flushing" (fixed in PR #31). It must stay removed.
                }
            }
        } catch (Exception e) {
            // A poisoned OffsetStorageWriter must NOT be absorbed here. Once its
            // flush semaphore is leaked, offsets can never be committed again in
            // this JVM, so logging and returning normally would let ClickHouse
            // writes continue against a frozen binlog position -- silent
            // divergence. Propagate so the task stops, matching the handling in
            // ClickHouseBatchRunnable.
            if (isOffsetWriterPoisoned(e)) {
                log.error("FATAL: the Debezium OffsetStorageWriter is stuck in the "
                        + "'already flushing' state. Offsets can no longer be "
                        + "committed, so replication would keep writing rows while "
                        + "the binlog position stays frozen. Propagating to stop "
                        + "processing and prevent silent data divergence.");
                throw new RuntimeException(
                        "OffsetStorageWriter is permanently stuck flushing; "
                                + "stopping to prevent silent data divergence", e);
            }
            log.error("Error persisting records to ClickHouse", e);
        }
    }

    /**
     * Detects the unrecoverable "OffsetStorageWriter is already flushing" condition.
     *
     * @param e the exception thrown while persisting or acknowledging records.
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
        DbWriter writer = null;
        String fullyQualifiedTableName = databaseName + "." + tableName;
        // Block while a DDL schema change is being applied + reconciled for
        // this table, so inserts never run against a stale column cache that
        // would silently drop newly added source columns.
        // The return value MUST be honoured — see the matching comment in
        // ClickHouseBatchRunnable.getDbWriterForTable. false means a DDL
        // reconciliation is stuck; inserting anyway would use the stale cache
        // this freeze exists to prevent. Return null so the batch is deferred.
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
            // Rebuild when EITHER signal fires — see the matching comment in
            // ClickHouseBatchRunnable.getDbWriterForTable. Generation change
            // means a DDL landed since this writer was cached; TTL expiry is
            // the self-heal for a schema change that was missed entirely.
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
        // Record the generation this writer was built at AND stamp the TTL
        // clock, then verify the rebuilt cache covers every source column.
        this.topicToDbWriterGeneration.put(topicName, generation);
        CacheInvalidationManager.getInstance().markCacheBuilt(fullyQualifiedTableName);
        if (!verifySourceSchemaIntegrity(fullyQualifiedTableName, record, writer)) {
            // Column map does not cover every source column — inserting with
            // this writer would silently DROP them. Evict and return null so
            // the caller defers the batch; see the matching comment in
            // ClickHouseBatchRunnable.getDbWriterForTable.
            this.topicToDbWriterMap.remove(topicName);
            this.topicToDbWriterGeneration.remove(topicName);
            return null;
        }
        return writer;
    }

    /**
     * Verifies that every source-event column exists in the rebuilt destination
     * cache; if not, logs loudly and re-marks the table for invalidation so the
     * next batch rebuilds again rather than inserting lossy rows. Safety net on
     * top of the per-table replication freeze. See {@link SourceSchemaIntegrityValidator}.
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
                    com.altinity.clickhouse.sink.connector.db.SourceSchemaColumns.fromRecord(record);
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
                // inserts into them. MySQL generated columns land here on
                // EVERY batch; without this filter the gate invalidated and
                // rebuilt the writer forever, livelocking replication for the
                // table. See the matching comment in ClickHouseBatchRunnable.
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
                // Generalized visibility gate: WAIT for the destination to gain
                // the missing columns rather than only logging. This covers
                // RENAME/MODIFY COLUMN, which the ADD/DROP text parser in
                // waitForSchemaVisibility cannot see.
                java.util.Collection<String> stillMissing = genuinelyMissing;
                if (parts.length == 2 && writer.getConnection() != null) {
                    stillMissing = new DDLSchemaChangeWaiter()
                            .waitForExpectedColumns(writer.getConnection(), parts[0],
                                    parts[1], genuinelyMissing);
                }
                if (stillMissing.isEmpty()) {
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
            // generation gates upstream are the primary protections.
            return true;
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
     * <p>This also subsumes the lazy-open behaviour this branch introduced: a
     * null handle is unusable, so the connection is still opened on first use
     * rather than in the constructor, and constructing the writer does not
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
        if (writer == null) {
            log.error(String.format(
                    "*** DbWriter is null for Database(%s), table(%s) -- retrying",
                    databaseName, tableName));
            writer = getDbWriterForTable(topicName, tableName,
                    databaseName, firstRecord, databaseConn);
            if (writer == null) {
                log.error(String.format(
                        "*** DbWriter still null for Database(%s), table(%s) -- giving up",
                        databaseName, tableName));
                return false;
            }
        }
        if (!writer.wasTableMetaDataRetrieved()) {
            log.warn(String.format(
                    "*** TABLE METADATA not retrieved for Database(%s), table(%s) -- retrying",
                    writer.getDatabaseName(), writer.getTableName()));
            writer.updateColumnNameToDataTypeMap();
            if (!writer.wasTableMetaDataRetrieved()) {
                log.error(String.format(
                        "*** TABLE METADATA not retrieved for Database(%s), table(%s) -- giving up",
                        writer.getDatabaseName(), writer.getTableName()));
                return false;
            }
        }
        PreparedStatementExecutor preparedStatementExecutor =
                new PreparedStatementExecutor(writer.
                        getReplacingMergeTreeDeleteColumn(),
                        writer.isReplacingMergeTreeWithIsDeletedColumn(),
                        writer.getSignColumn(), writer.getVersionColumn(),
                        writer.getDatabaseName(),
                        getServerTimeZone(this.config));
        // Step 1: The Batch Insert with preparedStatement in JDBC works by
        // forming the Query and then adding records to the Batch.
        // This step creates a Map of Query -> Records (List of
        // ClickHouseStruct).
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
