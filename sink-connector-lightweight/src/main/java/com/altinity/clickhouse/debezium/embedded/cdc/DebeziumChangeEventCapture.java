package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.ErrorLogger;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchWriter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.history.BinLogHistory;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import com.altinity.clickhouse.sink.connector.model.SinkRecordColumns;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.spi.OffsetCommitPolicy;
import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.transform.Source;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

/**
 * Sets up Debezium engine with the configuration passed by the user,
 * and creates a separate thread pool to read the records that are
 * inserted from the setup function.
 */
public class DebeziumChangeEventCapture {

    /**
     * Logger for DebeziumChangeEventCapture class.
     */
    private static final Logger log = LogManager.getLogger(
            DebeziumChangeEventCapture.class);

    /**
     * Executor for scheduling batch tasks.
     */
    private ClickHouseBatchExecutor executor;

    /**
     * Queue to hold records grouped by topic.
     * Records grouped by Topic Name (used in legacy mode)
     */
    private LinkedBlockingQueue<List<ClickHouseStruct>> records;

    /**
     * Queue to hold routed batches with thread assignment.
     * Used for hash-based routing to ensure all records for the same table
     * are processed by the same thread.
     */
    private LinkedBlockingQueue<RoutedBatch> routedRecords;

    /**
     * Number of threads in the thread pool (for hash-based routing).
     */
    private int threadPoolSize;

    /**
     * Flag indicating if a new replacing merge tree engine is used.
     */
    static public boolean isNewReplacingMergeTreeEngine = true;

    /**
     * Executor service for single-thread Debezium event processing.
     */
    final ExecutorService singleThreadDebeziumEventExecutor;

    /**
     * Debezium engine for capturing change events.
     */
    DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;

    /**
     * Writer for single-threaded batch processing.
     */
    ClickHouseBatchWriter singleThreadedWriter;

    /**
     * JDBC storage operations for Debezium.
     */
    DebeziumJdbcStorageOperations debeziumJdbcStorageOperations;

    /**
     * Database writer instance.
     */
    BaseDbWriter writer;

    /**
     * Connection to the system database.
     */
    Connection systemDbConnection;

    /**
     * Connection to the replication history database.
     */
    Connection replicationHistoryDbConnection;

    /**
     * Last ignored DDL statement.
     */
    @Getter
    @Setter
    private String lastIgnoredDDL;

    /**
     * Constructor. Initializes the DebeziumChangeEventCapture by creating
     * a single thread executor and initializing JDBC storage operations.
     */
    public DebeziumChangeEventCapture() {
        singleThreadDebeziumEventExecutor = Executors.newFixedThreadPool(1);
        this.debeziumJdbcStorageOperations = new DebeziumJdbcStorageOperations();
    }

    /**
     * Maximum number of retries for Debezium setup and other operations.
     * Default value, can be overridden by errors.max.retries configuration.
     */
    public static int MAX_RETRIES = 10;

    /**
     * Sleep time (in milliseconds) between retries.
     */
    public static int SLEEP_TIME = 10000;

    /**
     * This field tracks how many times an operation has been retried.
     */
    public int numRetries = 0;

    /**
     * Starting sequence number for versioning.
     */
    public static final long SEQUENCE_START = 1000000000;

    /**
     * Initial starting sequence number, used ONLY for the first batch after the
     * connector starts/resumes (500 million, half of {@link #SEQUENCE_START}).
     *
     * <p>On a resume, Debezium re-publishes every event after the last committed
     * offset — including events that were already delivered and written before the
     * shutdown/crash with counters in the {@code SEQUENCE_START} (1&nbsp;billion) range.
     * Seeding the post-resume counter at 500 million guarantees every re-published
     * event in the same source second receives a strictly LOWER {@code _version} than
     * any pre-restart write of that second, so a re-published duplicate can never
     * supersede a row that was already correctly written. The first source-clock
     * advance of more than one second resets the counter to {@code SEQUENCE_START},
     * returning to the normal domain. Values stay inside the 2.8.0 numeric domain
     * ({@code ts_ms * 1_000_000 + counter}) in both phases.</p>
     */
    public static final long SEQUENCE_START_INITIAL = 500000000;
    
    /**
         * Global sequence number.
    */
    public static long sequenceNumber = SEQUENCE_START;

    /**
     * Source-timestamp anchor for the intra-second sequence counter.
     *
     * <p>The counter resets only when the SOURCE commit clock advances by more than one
     * second past this anchor, and the anchor never moves backward. Because the counter is
     * keyed exclusively on the source commit time - never on the binlog file name or
     * position - it is preserved across binary log rotations: two commits in the same
     * second on either side of a rotation keep incrementing the same counter and can never
     * receive an identical or inverted {@code _version} (see issue #1346).</p>
     */
    public static long sequenceAnchorTs = 0L;


    /**
     * Sets up the Debezium event capture engine using the provided properties,
     * Debezium record parser service, and connector configuration.
     *
     * @param props                       The connector properties.
     * @param debeziumRecordParserService The service to parse change records.
     * @param config                      The ClickHouse sink connector config.
     * @throws IOException            If an I/O error occurs.
     * @throws ClassNotFoundException If a required class is not found.
     */
    public void setupDebeziumEventCapture(Properties props,
                                          DebeziumRecordParserService debeziumRecordParserService,
                                          ClickHouseSinkConnectorConfig config)
            throws IOException, ClassNotFoundException {

        DBCredentials dbCredentials = parseDBConfiguration(config);
        systemDbConnection = setSystemDbConnection(dbCredentials, config);
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
            replicationHistoryDbConnection = setReplicationHistoryDbConnection(dbCredentials, config);
        }

        try {
            this.debeziumJdbcStorageOperations.createDatabaseForDebeziumStorage(systemDbConnection, props);
        } catch (SQLException e) {
            log.error("Error creating Debezium storage database", e);
        }
        try {
            DBMetadata dbMetadata = new DBMetadata(config);
            String clickHouseVersion = dbMetadata.getClickHouseVersion(systemDbConnection);
            isNewReplacingMergeTreeEngine = new DBMetadata(config).checkIfNewReplacingMergeTree(clickHouseVersion);
        } catch (Exception e) {
            log.error("Error retrieving version", e);
        }

        // This is required for Debezium JDBC storage to identify the clickhouse driver.
        // when it's bundled as a shaded JAR.
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");

        try {
            DebeziumEngine.Builder<ChangeEvent<SourceRecord, SourceRecord>> changeEventBuilder =
                    DebeziumEngine.create(Connect.class);

            // Propagate the original MySQL column type (e.g. "INT UNSIGNED")
            // as a schema parameter (__debezium.source.column.type) so the
            // record-schema auto-create path can map unsigned integers to the
            // correct ClickHouse UInt types. Only set a default when the user
            // has not configured propagation themselves.
            if (props.getProperty("column.propagate.source.type") == null) {
                props.setProperty("column.propagate.source.type", ".*");
            }

            changeEventBuilder.using(props);
            changeEventBuilder.notifying(new DebeziumEngine.ChangeConsumer<ChangeEvent<SourceRecord, SourceRecord>>() {
                @Override
                public void handleBatch(List<ChangeEvent<SourceRecord, SourceRecord>> list,
                                        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter)
                        throws InterruptedException {

                    if(list.isEmpty()) {
                        return;
                    }

                    List<ClickHouseStruct> batch = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        ChangeEvent<SourceRecord, SourceRecord> record = list.get(i);
                        boolean lastRecordInBatch = false;
                        if (i == list.size() - 1) {
                            lastRecordInBatch = true;
                        }
                        // Anchor the version to the SOURCE commit timestamp (source.ts_ms),
                        // not the envelope/processing timestamp. The source timestamp is
                        // identical on every Debezium re-delivery, so a re-delivered DELETE
                        // keeps its original (lower) _version and can no longer out-rank a
                        // later re-INSERT (issue #1346). The emitted formula is unchanged
                        // from 2.8.0 (ts_ms * 1_000_000 + counter), so values stay in the
                        // same numeric domain: upgrades AND downgrades remain safe.
                        long recordTs = ClickHouseStruct.getSourceTsFromChangeEvent(record);

                        // The intra-second counter is keyed exclusively on the source commit
                        // clock - never on the binlog file name or position - so it is kept
                        // across binary log rotations: two commits in the same second on
                        // either side of a rotation keep incrementing the same counter and
                        // cannot collide or invert. The anchor is global (survives batch
                        // boundaries) and never moves backward, so re-delivered events with
                        // older source timestamps cannot re-arm the counter reset (the
                        // duplicate-_version race).
                        //
                        // First record after start/resume: seed the counter at
                        // SEQUENCE_START_INITIAL (500m) so events re-published from the last
                        // committed offset rank strictly below any pre-restart write of the
                        // same source second (which carried counters in the 1000m range).
                        if (sequenceAnchorTs == 0L) {
                            sequenceAnchorTs = recordTs;
                            sequenceNumber = SEQUENCE_START_INITIAL;
                        }
                        int diff = (int) ((recordTs - sequenceAnchorTs) / 1000);
                        if (diff > 1) {
                            sequenceNumber = SEQUENCE_START;
                            sequenceAnchorTs = recordTs;
                        } else
                            sequenceNumber++;

                        long recordSequenceNumber = recordTs * 1000000 + sequenceNumber;

                        ClickHouseStruct chStruct = processEveryChangeRecord(props, record,
                                debeziumRecordParserService, config, recordCommitter, lastRecordInBatch, recordSequenceNumber);
                        if (chStruct != null) {
                            batch.add(chStruct);
                        }
                    }
                    // Add sequence number.
                    //addVersion(batch);

                    if (batch.size() > 0) {
                        appendToRecords(batch, config);
                    }
                }
            });
            this.engine = changeEventBuilder
                    .using(new DebeziumConnectorCallback())
                    .using(new DebeziumEngine.CompletionCallback() {
                        @Override
                        public void handle(boolean success, String message, Throwable throwable) {
                            if (success == false) {
                                log.error("Error starting connector" + throwable + " Message:" + message);
                                if (throwable != null && throwable.getCause() != null &&
                                        throwable.getCause().getLocalizedMessage() != null)
                                    log.error("Error stating connector: Cause" +
                                            throwable.getCause().getLocalizedMessage());
                                log.error("Retrying - try number:" + numRetries);
                                if (numRetries++ <= MAX_RETRIES) {
                                    try {
                                        Thread.sleep(SLEEP_TIME);
                                    } catch (InterruptedException e) {
                                        log.error("Error sleeping", e);
                                        throw new RuntimeException(e);
                                    }
                                    try {
                                        setupDebeziumEventCapture(props, debeziumRecordParserService, config);
                                    } catch (IOException | ClassNotFoundException e) {
                                        log.error("Error setting up debezium event capture", e);
                                        throw new RuntimeException(e);
                                    }
                                }
                            }
                            log.debug("Completion callback");
                        }
                    })
                    .using(new DebeziumEngine.ConnectorCallback() {
                        @Override
                        public void connectorStarted() {
                            ReplicationStatusSingleton.getInstance().setIsReplicationRunning(true);
                            log.debug("Connector started");
                            // Create view.
                            try {
                                DebeziumJdbcStorageOperations debeziumJdbcStorageOperations = new DebeziumJdbcStorageOperations();
                                debeziumJdbcStorageOperations.createViewForShowReplicaStatus(systemDbConnection, config, props);
                            } catch (Exception e) {
                                log.error("Error creating view for replica status", e);
                            }
                            try {
                                DebeziumJdbcStorageOperations debeziumJdbcStorageOperations = new DebeziumJdbcStorageOperations();
                                debeziumJdbcStorageOperations.createSchemaHistoryTable(systemDbConnection, props);
                            } catch (Exception e) {
                                log.error("Error creating schema history table", e);
                            }
                            // If replication history is enabled, create the history table.
                            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
                                try {
                                    ClickHouseAutoCreateTable clickHouseAutoCreateTable = new ClickHouseAutoCreateTable();
                                    String binlogHistoryTable = props.getProperty(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TABLE_NAME.toString(), "history");
                                    String binlogHistoryDatabase = props.getProperty(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString(), "binlog_history");
                                    clickHouseAutoCreateTable.createHistoryDatabase(binlogHistoryDatabase, systemDbConnection, config);
                                    clickHouseAutoCreateTable.createHistoryTable(binlogHistoryTable, binlogHistoryDatabase, systemDbConnection, config);
                                } catch (Exception e) {
                                    log.error("Error creating history table", e);
                                }
                            }
                        }

                        @Override
                        public void connectorStopped() {
                            ReplicationStatusSingleton.getInstance().setIsReplicationRunning(false);
                            log.debug("Connector stopped");
                        }
                    })
                    .using(OffsetCommitPolicy.always())
                    .build();
            singleThreadDebeziumEventExecutor.submit(() -> {
                Thread.currentThread().setName("Sink connector Debezium Event Thread");
                try {
                    Class.forName("com.clickhouse.jdbc.ClickHouseDriver");

                    engine.run();
                } catch (Exception e) {
                    log.error("Debezium event capture starting Exception", e);
                }
            });
        } catch (Exception e) {
            log.error("Exception", e);
            if (this.engine != null) {
                this.engine.close();
            }
        }
    }

    /**
     * Sets up the Debezium engine and processing thread.
     *
     * @param props                       The connector properties.
     * @param debeziumRecordParserService The service to parse change events.
     * @param forceStart                  If true, forces the engine to start.
     * @throws IOException            If an I/O error occurs.
     * @throws ClassNotFoundException If a required class is not found.
     */
    public void setup(Properties props,
                      DebeziumRecordParserService debeziumRecordParserService,
                      boolean forceStart)
            throws IOException, ClassNotFoundException {

        // Check if max queue size was defined by the user.
        if (props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()) != null) {
            int maxQueueSize = Integer.parseInt(props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()));
            this.records = new LinkedBlockingQueue<>(maxQueueSize);
        } else {
            this.records = new LinkedBlockingQueue<>();
        }

        try {
            if (props.getProperty(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString()) != null) {
                Integer maxRetries = Integer.parseInt(props.getProperty(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString()));
                DBMetadata.setMaxRetries(maxRetries);
                MAX_RETRIES = maxRetries;  // Update the static MAX_RETRIES for Debezium setup and DDL operations
            }
        } catch (Exception e) {
            log.error("Error retrieving max retries", e);
        }
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props));
        
        // Log if replication history mode is enabled
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
            log.info("************** HISTORY MODE ENABLED **************");
            log.info("*************only history will be tracked ***************");
        }
        
        Metrics.initialize(props.getProperty(ClickHouseSinkConnectorConfigVariables.ENABLE_METRICS.toString()),
                props.getProperty(ClickHouseSinkConnectorConfigVariables.METRICS_ENDPOINT_PORT.toString()));

        // Start Debezium event loop if it is requested from REST API.
        if (!config.getBoolean(ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString())
                || forceStart) {
            this.setupProcessingThread(config);
            setupDebeziumEventCapture(props, debeziumRecordParserService, config);
        } else {
            log.info(ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString() +
                    " variable set to true, Replication is skipped, use sink-connector-client to start replication");
        }
    }

    /**
     * Stops the Debezium engine and shuts down all executor services.
     *
     * @throws IOException If an I/O error occurs during shutdown.
     */
    public void stop() throws IOException {
        try {
            if (this.executor != null) {
                this.executor.shutdown();
                this.executor.awaitTermination(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("Error stopping executor", e);
        }

        try {
            if (this.singleThreadDebeziumEventExecutor != null) {
                this.singleThreadDebeziumEventExecutor.shutdown();
                this.singleThreadDebeziumEventExecutor.awaitTermination(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("Error stopping debezium event executor", e);
        }

        try {
            if (this.engine != null) {
                this.engine.close();
            }
        } catch (Exception e) {
            log.error("Error stopping debezium engine", e);
        }

        Metrics.stop();
    }

    /**
     * Parses the database configuration from the connector configuration.
     *
     * @param config The ClickHouse sink connector configuration.
     * @return A DBCredentials object with the database connection details.
     */
    DBCredentials parseDBConfiguration(ClickHouseSinkConnectorConfig config) {
        DBCredentials dbCredentials = new DBCredentials();

        dbCredentials.setHostName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString()));
        dbCredentials.setPort(config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString()));
        dbCredentials.setUserName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString()));
        dbCredentials.setPassword(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString()));
        dbCredentials.setDatabase("system");

        return dbCredentials;
    }

    /**
     * Function to perform DDL operation on the main thread.
     *
     * @param DDL             The DDL statement to be executed.
     * @param props           The connector properties.
     * @param sr              The source record.
     * @param config          The connector configuration.
     * @param recordCommitter The record committer for offset management.
     * @param cdcRecord       The CDC change event record.
     * @param lastRecordInBatch True if this is the last record in the batch.
     */
    private void performDDLOperation(String DDL, Properties props, SourceRecord sr,
                                     ClickHouseSinkConnectorConfig config,
                                     DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter,
                                     ChangeEvent<SourceRecord, SourceRecord> cdcRecord,
                                     boolean lastRecordInBatch, ClickHouseStruct chStruct) {
        String databaseName = getDatabaseName(sr);
        // Get source timezone from config
        String sourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
        // Get server timezone from config
        String serverTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString());

        // if serverTimezone is empty default to UTC.
//        if(serverTimeZone.isEmpty()) {
//            serverTimeZone = "UTC";
//        }
        // If replication histry is enabled, set database name to the replication history database name
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
            databaseName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
        }

        StringBuffer clickHouseQuery = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);

        if (checkIfDDLNeedsToBeIgnored(DDL, props, sr, isDropOrTruncate)) {
            log.info("Ignored Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
            return;
        }

        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(writer, config, databaseName);
        mySQLDDLParserService.parseSql(DDL, "", clickHouseQuery, isDropOrTruncate);


        log.info("Executed Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
        // Use the configured MAX_RETRIES value for DDL operations
        int MAX_DDL_RETRIES = MAX_RETRIES;
        int SLEEP_TIME = 10000;
        int numRetries = 0;

        // Check if configuration is set to retry DDL
        String retryDDL = props.getProperty(SinkConnectorLightWeightConfig.DDL_RETRY.toString());
        String errorTableName = props.getProperty(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        boolean retryDDLProperty = false;
        if (retryDDL != null && retryDDL.equalsIgnoreCase("true")) {
            retryDDLProperty = true;
        }

        while (numRetries < MAX_DDL_RETRIES) {
            try {

                if(!config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_REPLICATION_LOG_ONLY.toString()))
                    executeDDL(clickHouseQuery.toString(), writer, config);

                // Invalidate cached DbWriter for the affected table(s) so that subsequent
                // inserts use the updated schema after DDL changes (e.g., ADD/DROP COLUMN).
                // The invalidation key must match the key the batch consumers use when
                // they look up the writer's cache version. Those consumers
                // (ClickHouseBatchRunnable/ClickHouseBatchWriter) apply
                // clickhouse.database.override.map to the source database name before
                // building the "database.table" key, so we must apply the same override
                // here. We intentionally start from the real source-mapped database
                // (getDatabaseName(sr)), not the replication-history override above.
                try {
                    String invalidationDatabaseName = getDatabaseName(sr);
                    String overrideMapConfig = config.getString(
                            ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
                    if (overrideMapConfig != null) {
                        Map<String, String> databaseOverrideMap =
                                Utils.parseSourceToDestinationDatabaseMap(overrideMapConfig);
                        if (databaseOverrideMap.containsKey(invalidationDatabaseName)) {
                            invalidationDatabaseName = databaseOverrideMap.get(invalidationDatabaseName);
                        }
                    }
                    for (String tableName : getTableNamesFromDDL(sr)) {
                        CacheInvalidationManager.getInstance()
                                .invalidateTable(invalidationDatabaseName + "." + tableName);
                    }
                } catch (Exception e) {
                    log.warn("Error invalidating cache for DDL: " + DDL, e);
                }

                try {
                    // if replication history is enabled, add to the binlog history table.
                    if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        String historyTableName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TABLE_NAME.toString());
                        String replicationHistoryDatabaseName = config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString());
                        BinLogHistory binLogHistory = new BinLogHistory();
                        // Add the chStruct to the list
                        List<ClickHouseStruct> currentBatch = new ArrayList<>();
                        currentBatch.add(chStruct);
                        binLogHistory.addRecordsToHistoryTable(config, historyTableName, replicationHistoryDbConnection, DDL, 
                        currentBatch, sourceTimeZone, serverTimeZone);
                    }
                } catch (Exception e) {
                    log.error("Error adding DDL records to history table", e);
                }

                DebeziumOffsetManagement.acknowledgeRecords(recordCommitter, cdcRecord, lastRecordInBatch);
                break;
            } catch (Exception e) {
                log.error("Error executing DDL", e);
                // insert data into the error table
                try {
                    ErrorLogger.createErrorTable(systemDbConnection, config);
                    ErrorLogger.logError(systemDbConnection, e.getMessage(),
                        sr, databaseName, clickHouseQuery.toString(), props.getProperty("name"), errorTableName);
                } catch (SQLException ex) {
                    log.error("Failed to log DDL error to ClickHouse", ex);
                }
                if (retryDDLProperty == false) {
                    break;
                }
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException ex) {
                    log.error("Error sleeping", ex);
                }
                numRetries++;
            }
            if (numRetries >= MAX_DDL_RETRIES) {
                throw new RuntimeException("Max retries exceeded for DDL");
            }
        }
        updateMetrics(DDL);
    }

    /**
     * Sets up the system database connection using the provided database
     * credentials and connector configuration.
     *
     * @param dbCredentials The database credentials.
     * @param config        The ClickHouse sink connector configuration.
     * @return The system database {@link Connection}.
     */
    private Connection setSystemDbConnection(DBCredentials dbCredentials, ClickHouseSinkConnectorConfig config) {
        String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(),
                dbCredentials.getPort(), BaseDbWriter.SYSTEM_DB);

        Connection conn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                dbCredentials.getUserName(), dbCredentials.getPassword(), BaseDbWriter.SYSTEM_DB, config);
        writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                BaseDbWriter.SYSTEM_DB, dbCredentials.getUserName(), dbCredentials.getPassword(),
                config, conn);
        return conn;
    }

    /**
     * Sets up the replication history database connection using the provided database
     * credentials and connector configuration.
     *
     * @param dbCredentials The database credentials.
     * @param config        The ClickHouse sink connector configuration.
     * @return The replication history database {@link Connection}.
     */
    private Connection setReplicationHistoryDbConnection(DBCredentials dbCredentials, ClickHouseSinkConnectorConfig config) {
        String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(),
                dbCredentials.getPort(), config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString()));
        return BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                dbCredentials.getUserName(), dbCredentials.getPassword(), 
                config.getString(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString()), config);
    }

    /**
     * Function to get the database name from the SourceRecord.
     * <p>
     * If the database name is not present in the SourceRecord, then the
     * database name is set to "system". Also, if a database is overridden
     * in the configuration, then the database name is set to the overridden
     * database name.
     * </p>
     *
     * @param sr The source record.
     * @return The database name.
     */
    private String getDatabaseName(SourceRecord sr) {
        if (sr != null && sr.key() instanceof Struct) {
            String recordDbName = (String) ((Struct) sr.key()).get("databaseName");
            if (recordDbName != null && !recordDbName.isEmpty()) {
                return recordDbName;
            }
        }
        return "system";
    }

    /**
     * Function to get the table name from the SourceRecord.
     *
     * @param sr The source record.
     * @return The table name, or null if not found.
     */
    private String getTableName(SourceRecord sr) {
        if (sr != null && sr.key() instanceof Struct) {
            try {
                String tableName = (String) ((Struct) sr.key()).get("tableName");
                if (tableName != null && !tableName.isEmpty()) {
                    return tableName;
                }
            } catch (Exception e) {
                // tableName field may not exist in the struct
                log.debug("tableName field not found in source record key");
            }
        }
        return null;
    }

    /**
     * Resolves the table name(s) affected by a DDL/schema-change event from the record
     * value. DDL events carry the affected table(s) in the value's {@code tableChanges}
     * array (each entry's {@code id} is a fully qualified name such as
     * {@code "employees"."race_test"}), with {@code source.table} as a fallback. The
     * record key only carries {@code databaseName}, which is why {@link #getTableName}
     * cannot be used here.
     *
     * @param sr The source record.
     * @return The distinct table names affected by the DDL, or an empty list.
     */
    private List<String> getTableNamesFromDDL(SourceRecord sr) {
        java.util.LinkedHashSet<String> tables = new java.util.LinkedHashSet<>();
        if (sr != null && sr.value() instanceof Struct) {
            Struct value = (Struct) sr.value();
            try {
                List<Object> tableChanges = value.getArray("tableChanges");
                if (tableChanges != null) {
                    for (Object change : tableChanges) {
                        if (change instanceof Struct) {
                            String t = extractTableFromId((String) ((Struct) change).get("id"));
                            if (t != null) {
                                tables.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // tableChanges field may not exist; fall back to source.table below
            }
            if (tables.isEmpty()) {
                try {
                    Struct source = (Struct) value.get("source");
                    String t = source != null ? (String) source.get("table") : null;
                    if (t != null && !t.isEmpty()) {
                        tables.add(t);
                    }
                } catch (Exception ignored) {
                    // source.table may not exist
                }
            }
        }
        return new ArrayList<>(tables);
    }

    /**
     * Extracts the bare table name from a fully qualified table id such as
     * {@code "employees"."race_test"} or {@code `employees`.`race_test`}.
     *
     * @param id The fully qualified table id.
     * @return The bare table name, or null if the id is empty.
     */
    private String extractTableFromId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String cleaned = id.replace("\"", "").replace("`", "");
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    /**
     * Executes the given DDL query by splitting it into individual queries
     * and executing each one.
     *
     * @param clickHouseQuery The DDL query string.
     * @param writer          The {@link BaseDbWriter} used to execute the query.
     * @throws SQLException If a database access error occurs.
     */
    private void executeDDL(String clickHouseQuery, BaseDbWriter writer, ClickHouseSinkConnectorConfig config) throws SQLException {
        ClickHouseAlterTable cat = new ClickHouseAlterTable();
        DBMetadata dbMetadata = new DBMetadata(config);
        String[] queries = clickHouseQuery.replaceAll(",$", "").split("\n");
        for (String query : queries) {
            if (!query.isEmpty()) {
                log.info("ClickHouse DDL: " + query);
                dbMetadata.executeSystemQuery(writer.getConnection(), query);
            }
        }
    }


    /**
     * Updates the DDL metrics using the Metrics class.
     *
     * @param DDL The DDL statement that was executed.
     */
    private void updateMetrics(String DDL) {
        long currentTime = System.currentTimeMillis();
        boolean ddlProcessingResult = true;
        Metrics.updateDdlMetrics(DDL, currentTime, 0, ddlProcessingResult);

        long elapsedTime = System.currentTimeMillis() - currentTime;
        Metrics.updateDdlMetrics(DDL, currentTime, elapsedTime, ddlProcessingResult);
    }

    /**
     * Processes every change event record as received from Debezium.
     * <p>
     * If the record contains a DDL field, the DDL is processed; otherwise,
     * the record is parsed into a {@link ClickHouseStruct}. Also updates replication
     * status metrics.
     * </p>
     *
     * @param props                       The connector properties.
     * @param record                      The change event record.
     * @param debeziumRecordParserService The service to parse change events.
     * @param config                      The connector configuration.
     * @param recordCommitter             The record committer for offset management.
     * @param lastRecordInBatch           True if this is the last record in the batch.
     * @return A {@link ClickHouseStruct} representing the processed record,
     *         or null if the record is invalid.
     */
    private ClickHouseStruct processEveryChangeRecord(Properties props,
                                                      ChangeEvent<SourceRecord, SourceRecord> record,
                                                      DebeziumRecordParserService debeziumRecordParserService,
                                                      ClickHouseSinkConnectorConfig config,
                                                      DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter,
                                                      boolean lastRecordInBatch,
                                                      long sequenceNumber) {
        ClickHouseStruct chStruct = null;

        try {
            SourceRecord sr = record.value();
            Struct struct = (Struct) sr.value();

            if (struct == null) {
                log.debug(String.format("STRUCT EMPTY - not a valid CDC record + Record(%s)", record.toString()));
                return null;
            }
            if (struct.schema() == null) {
                log.error("SCHEMA EMPTY");
            }

            java.util.List<Field> schemaFields = struct.schema().fields();
            if (schemaFields == null) {
                return null;
            }
            Field matchingDDLField = schemaFields.stream()
                    .filter(f -> "DDL".equalsIgnoreCase(f.name()))
                    .findAny()
                    .orElse(null);
            if (matchingDDLField != null) {
                String DDL = (String) struct.get("ddl");
                log.debug("Source DB DDL: " + DDL);

                if (DDL != null && !DDL.isEmpty()) {
                    log.info("***** DDL received, Flush all existing records");
                    this.executor.pause();

                    Map<String, Object> sourceObjStruct = new ClickHouseConverter().convertValue(sr);

                    ClickHouseStruct ddlStruct = new ClickHouseStruct();
                    ddlStruct.setAdditionalMetaData(sourceObjStruct);
                    ddlStruct.setSequenceNumber(sequenceNumber);
                    performDDLOperation(DDL, props, sr, config, recordCommitter, record, lastRecordInBatch, ddlStruct);
                    this.executor.resume();
                }
            } else {
                chStruct = debeziumRecordParserService.parse(record, recordCommitter, lastRecordInBatch);
                chStruct.setSequenceNumber(sequenceNumber);
                try {
                    if (chStruct != null) {
                        ReplicationStatusSingleton rss = ReplicationStatusSingleton.getInstance();
                        rss.setReplicationLag(chStruct.getReplicationLag());
                        rss.setLastRecordTimestamp(chStruct.getTs_ms());
                        rss.setBinLogFile(chStruct.getFile());
                        rss.setBinLogPosition(String.valueOf(chStruct.getPos()));
                        rss.setGtid(String.valueOf(chStruct.getGtid()));
                    }
                } catch (Exception e) {
                    log.error("Error retrieving status metrics: Exception" + e.toString());
                }
            }
        } catch (Exception e) {
            log.error("Exception processing record", e);
        }

        return chStruct;
    }

    /**
     * Sets the writer for testing purposes.
     *
     * @param writer The {@link BaseDbWriter} to be set.
     */
    @VisibleForTesting
    void setWriter(BaseDbWriter writer) {
        this.writer = writer;
    }

    /**
     * Determines whether the given SourceRecord represents a snapshot DDL.
     *
     * @param sr The source record.
     * @return true if the record is a snapshot DDL; false otherwise.
     */
    private boolean isSnapshotDDL(SourceRecord sr) {
        boolean snapshotDDL = false;

        if (sr.sourceOffset() != null) {
            if (sr.sourceOffset().containsKey("snapshot")) {
                String snapshotMode = (String) sr.sourceOffset().get("snapshot");
                if (snapshotMode.equalsIgnoreCase("INITIAL")) {
                    snapshotDDL = true;
                }
                // snapshotDDL = (Boolean) sr.sourceOffset().get("snapshot");
            }
        }

        return snapshotDDL;
    }

    public boolean checkDDLAgainstRegexPatterns(String DDL) {
        IgnoreDDLRegexLoader ignoreDDLRegexLoader = new IgnoreDDLRegexLoader();
        List<String> ignoreDDLRegexList = ignoreDDLRegexLoader.loadRegexPatterns();
        for (String regex : ignoreDDLRegexList) {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(DDL);
            if (m.find()) {
                return true;
            }
        }
        return false;
    }

    private boolean checkIfDDLNeedsToBeIgnored(String DDL, Properties props, SourceRecord sr, AtomicBoolean isDropOrTruncate) {
        String disableDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DDL);
        if (disableDDLProperty != null && disableDDLProperty.equalsIgnoreCase("true")) {
            log.debug("Ignoring DDL");
            return true;
        }

        boolean isSnapshotDDL = isSnapshotDDL(sr);

        String enableSnapshotDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.ENABLE_SNAPSHOT_DDL);
        boolean enableSnapshotDDLPropertyFlag = false;
        if (enableSnapshotDDLProperty != null && enableSnapshotDDLProperty.equalsIgnoreCase("true")) {
            enableSnapshotDDLPropertyFlag = true;
        }

        // Also if the DDL matches the regex, then ignore it.
        String ignoreDDLRegexProperty = props.getProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX);
        // The IGNORE_DDL_REGEX will be a list of regex separated by 2 pipe(##).
        // Example: ^ALTER TABLE .* ADD COLUMN .*##^ALTER TABLE .* DROP COLUMN .*##^ALTER TABLE .* MODIFY COLUMN .*
        // Separate the list.
        if (ignoreDDLRegexProperty != null && !ignoreDDLRegexProperty.isEmpty()) {
            String[] separatedIgnoreDDLRegexList = ignoreDDLRegexProperty.split("\\|\\|");
            for (String regex : separatedIgnoreDDLRegexList) {
                Pattern p = Pattern.compile(regex);
                Matcher m = p.matcher(DDL);
                if (m.find()) {
                    lastIgnoredDDL = DDL;
                    log.info("Ignoring DDL: " + DDL + " as it matches the regex: " + regex);
                    return true;
                }
            }
        }

        // Check DDL against regex patterns from IgnoreDDLRegexLoader
        if (checkDDLAgainstRegexPatterns(DDL)) {
            return true;
        }

        String disableDropAndTruncateProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
        if (disableDropAndTruncateProperty != null && disableDropAndTruncateProperty.equalsIgnoreCase("true") && isDropOrTruncate.get() == true) {
            log.debug("Ignoring Drop or Truncate");
            return true;
        }
        if (isSnapshotDDL == true && enableSnapshotDDLPropertyFlag == false) {
            // User wants to ignore snapshot
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sets up a separate processing thread or thread pool based on the connector configuration.
     *
     * @param config The ClickHouse sink connector configuration.
     */
    private void setupProcessingThread(ClickHouseSinkConnectorConfig config) {
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())) {
            log.info("********* Running in Single Threaded mode *********");
            singleThreadedWriter = new ClickHouseBatchWriter(config, new HashMap<>());
            return;
        }
        
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("Sink Connector thread-pool-%d").build();
        this.threadPoolSize = config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString());
        this.executor = new ClickHouseBatchExecutor(this.threadPoolSize, namedThreadFactory);
        
        // Use hash-based routing if we have multiple threads
        if (this.threadPoolSize > 1) {
            log.info("********* Using hash-based routing with {} threads *********", this.threadPoolSize);
            int maxQueueSize = config.getInt(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString());
            for (int i = 0; i < this.threadPoolSize; i++) {
                this.executor.scheduleAtFixedRate(
                        new ClickHouseBatchRunnable(this.records, config, new HashMap<>()),
                        0,
                        config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()),
                        TimeUnit.MILLISECONDS);
            }
        } else {
            // Single thread - use legacy mode
            log.info("********* Using legacy mode with single thread *********");
            for (int i = 0; i < this.threadPoolSize; i++) {
                this.executor.scheduleAtFixedRate(
                        new ClickHouseBatchRunnable(this.records, config, new HashMap<>()),
                        0, 
                        config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), 
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Appends the given records to the processing queue.
     *
     * @param convertedRecords The list of {@link ClickHouseStruct} records.
     * @param config           The connector configuration.
     */
    private void appendToRecords(List<ClickHouseStruct> convertedRecords, ClickHouseSinkConnectorConfig config) {
        // If config is set to single threaded.
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())) {
            singleThreadedWriter.persistRecords(convertedRecords);
        } else if (this.threadPoolSize > 1 && this.routedRecords != null) {
            // Hash-based routing mode: group records by table and route to specific threads
            appendToRecordsWithHashRouting(convertedRecords);
        } else {
            // Legacy mode: single queue
            synchronized (this.records) {
                try {
                    int remainingCapacity = this.records.remainingCapacity();
                    int currentSize = this.records.size();
                    int totalCapacity = remainingCapacity + currentSize;
                    if (totalCapacity > 0 && currentSize >= (0.9 * totalCapacity)) {
                        log.warn("Queue is at 90% capacity! Current size: {}, Total capacity: {}", currentSize, totalCapacity);
                    }
                    if (remainingCapacity == 0) {
                        log.warn("Queue is full! Current size: {}, Total capacity: {}", this.records.size(), totalCapacity);
                    }
                    this.records.put(convertedRecords);
                }catch(Exception e){
                    log.error("An unexpected error occurred while putting batch into records queue. Error: {}",e.getMessage(),e);
                }
            }
        }
    }

    /**
     * Appends records using hash-based routing.
     * Groups records by table and assigns each group to a specific thread.
     *
     * @param convertedRecords The list of {@link ClickHouseStruct} records.
     */
    private void appendToRecordsWithHashRouting(List<ClickHouseStruct> convertedRecords) {
        // Group records by routing key (database.table)
        Map<String, List<ClickHouseStruct>> routingGroups = new HashMap<>();
        
        for (ClickHouseStruct record : convertedRecords) {
            String routingKey = RoutedBatch.createRoutingKey(record.getTopic());
            routingGroups.computeIfAbsent(routingKey, k -> new ArrayList<>()).add(record);
        }
        
        // Create a RoutedBatch for each group and add to queue
        synchronized (this.routedRecords) {
            try {
                int remainingCapacity = this.routedRecords.remainingCapacity();
                int currentSize = this.routedRecords.size();
                int totalCapacity = remainingCapacity + currentSize;
                
                if (totalCapacity > 0 && currentSize >= (0.9 * totalCapacity)) {
                    log.warn("Routed queue is at 90% capacity! Current size: {}, Total capacity: {}", currentSize, totalCapacity);
                }
                if (remainingCapacity == 0) {
                    log.warn("Routed queue is full! Current size: {}, Total capacity: {}", currentSize, totalCapacity);
                }
                
                for (Map.Entry<String, List<ClickHouseStruct>> entry : routingGroups.entrySet()) {
                    String routingKey = entry.getKey();
                    List<ClickHouseStruct> batch = entry.getValue();
                    
                    // Calculate which thread should process this table
                    int threadId = RoutedBatch.calculateThreadId(routingKey, this.threadPoolSize);
                    String tableName = RoutedBatch.extractTableName(batch.get(0).getTopic());
                    
                    // Create routed batch and add to queue
                    RoutedBatch routedBatch = new RoutedBatch(batch, threadId, tableName);
                    this.routedRecords.put(routedBatch);
                    
                    log.debug("Routed {} records for table {} to thread {}", batch.size(), tableName, threadId);
                }
            } catch (Exception e) {
                log.error("An unexpected error occurred while putting batch into routed records queue. Error: {}", e.getMessage(), e);
            }
        }
    }



    /**
     * Adds a version (sequence number) to every record.
     * <p>
     * The sequence starts at SEQUENCE_START, increments for every record,
     * and resets if more than one second has elapsed since the first record.
     * </p>
     *
     * @param chStructs The list of {@link ClickHouseStruct} records.
     */
    public static void addVersion(List<ClickHouseStruct> chStructs) {
        // Start the sequence from SEQUENCE_START and increment for every record
        if (chStructs.isEmpty()) {
            return;
        }
        for (ClickHouseStruct chStruct : chStructs) {
            // Anchor on the SOURCE commit timestamp when available (redelivery-stable,
            // issue #1346); fall back to the processing timestamp for records without one.
            // The intra-second counter is keyed exclusively on the source clock and the
            // shared anchor survives batch boundaries and binlog rotations - it is never
            // reset by file/position changes and never moves backward.
            long recordTs = chStruct.getTs_ms() > 0
                    ? chStruct.getTs_ms() : chStruct.getDebezium_ts_ms();
            if (sequenceAnchorTs == 0L) {
                // First record after start/resume: seed at SEQUENCE_START_INITIAL
                // (500m) so re-published events of the same source second rank
                // strictly below the pre-restart writes (1000m range).
                sequenceAnchorTs = recordTs;
                sequenceNumber = SEQUENCE_START_INITIAL;
            }
            int diff = (int) ((recordTs - sequenceAnchorTs) / 1000);
            if (diff > 1) {
                sequenceNumber = SEQUENCE_START;
                sequenceAnchorTs = recordTs;
            } else {
                sequenceNumber++;
            }
            // Pad the sequence number with zeros and set the sequence number in the record.
            chStruct.setSequenceNumber(recordTs * 1000000 + sequenceNumber);
        }
    }
}
