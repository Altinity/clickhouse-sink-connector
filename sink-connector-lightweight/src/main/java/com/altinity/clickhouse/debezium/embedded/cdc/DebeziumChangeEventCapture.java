package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserFactory;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DDLSchemaChangeWaiter;
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
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
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

    /** A possibly-quoted, possibly database-qualified table identifier. */
    private static final String QUALIFIED_NAME =
            "[`\"]?[A-Za-z0-9_$]+[`\"]?(?:\\.[`\"]?[A-Za-z0-9_$]+[`\"]?)?";

    /**
     * Matches {@code RENAME TABLE a TO b, c TO d}, capturing one source and
     * destination pair per match.
     * <p>
     * The source is anchored on a word boundary and must not be a bare SQL
     * keyword, so this pattern cannot mis-fire on the
     * {@code ALTER TABLE ... RENAME TO} form -- where the token preceding
     * {@code TO} is the keyword rather than a table name -- nor match a
     * suffix of one. That form is handled by {@link #ALTER_RENAME} instead.
     */
    private static final Pattern RENAME_PAIR = Pattern.compile(
            "\\b(?!(?:RENAME|TABLE|ALTER)\\b)(" + QUALIFIED_NAME + ")\\s+TO\\s+("
                    + QUALIFIED_NAME + ")",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches {@code ALTER TABLE a RENAME [TO|AS] b}, capturing the source table
     * (which precedes the {@code RENAME} keyword) and the destination.
     */
    private static final Pattern ALTER_RENAME = Pattern.compile(
            "ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")\\s+RENAME\\s+(?:TO\\s+|AS\\s+)?("
                    + QUALIFIED_NAME + ")",
            Pattern.CASE_INSENSITIVE);

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
     * One routed queue PER worker thread (index == thread id). Hash-based
     * routing sends every batch for a given table to a single thread's queue,
     * and that thread drains only its own queue in FIFO order, so same-table
     * batches are applied in source order.
     * <p>
     * This is deliberately a per-thread queue, not one shared queue. A single
     * shared queue that workers filter by thread id (putting non-matching
     * batches back at the tail) reorders same-table batches under contention:
     * a worker that polls a sibling's batch and re-enqueues it moves it behind
     * later batches for the same table. Per-thread queues remove that race
     * entirely.
     */
    private java.util.List<LinkedBlockingQueue<RoutedBatch>> routedQueues;

    /**
     * The scheduled future of every worker task (spec 03.01 section 3.3).
     * <p>
     * ScheduledThreadPoolExecutor cancels a periodic task whose run throws and
     * tells nobody. A worker that rethrows a FATAL classification therefore
     * used to die silently: its batch stayed outstanding, every later unit
     * stayed parked, its queue filled, and the Debezium thread eventually
     * blocked in put -- a stall with no error after the first one. These
     * futures are inspected at the top of every handleChangeEventBatch so a
     * dead worker stops the engine loudly instead.
     * </p>
     */
    final java.util.List<java.util.concurrent.ScheduledFuture<?>> workerFutures =
            new java.util.concurrent.CopyOnWriteArrayList<>();

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
     * PostgreSQL-specific configuration and state (schema change detection,
     * schema prefix, database suffix, etc.).  Initialised in
     * {@link #setup} from the connector properties.
     */
    private PostgresConnectorConfig pgConfig;

    /**
     * Connection to the system database.
     */
    Connection systemDbConnection;

    /**
     * Durable high-water mark of the versions handed to the writers (spec 02.02
     * section 3.5, 09.03 section 3.4). Created in {@link #setupDebeziumEventCapture}
     * once the offset database exists; every version the dispatch loop assigns to a
     * row is covered by it BEFORE the row is handed off, and a new run seeds its
     * version floor from it. Null only in unit tests that drive
     * {@link #handleChangeEventBatch} directly, which is logged once.
     */
    VersionHighWaterMark versionHighWaterMark;

    /** One WARN per JVM when rows are versioned without a durable high-water mark. */
    private static final java.util.concurrent.atomic.AtomicBoolean UNSEEDED_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

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
     * High-water mark of the source-log position ({@link SourcePosition}) versioned in
     * this run.
     *
     * <p>Debezium delivers events in log order, and log order IS commit order. The only
     * time a lower position follows a higher one is a redelivery after an offset rewind.
     * The mark therefore separates the two cases the version sequence has to treat
     * differently:</p>
     * <ul>
     *   <li><b>first delivery</b> (position above the mark): the event committed after
     *   every event already versioned, so its {@code _version} must rank above all of
     *   them - whatever its source timestamp says (see {@link #sequenceMaxSourceTs});</li>
     *   <li><b>redelivery</b> (position at or below the mark, or no position at all):
     *   the redelivery-stable, source-timestamp anchored assignment of issue #1346 is
     *   kept unchanged.</li>
     * </ul>
     *
     * <p>The mark is comparable only within one binary log: a positioned record from a
     * differently named log ({@link SourcePosition#sameLog} false -- the basename
     * changed) is a first delivery and replaces the mark (spec 01.02 section 3.1.1).
     * It is reset by a process restart; a new run starts with an empty mark and a
     * floor seeded from the durable high-water mark (spec 02.02 section 3.5).</p>
     */
    public static SourcePosition sequenceHighWaterPosition = null;

    /**
     * Highest effective timestamp (ms) versioned in this run - the floor applied to the
     * timestamp component of every first delivery.
     *
     * <p>It is raised by every record that goes through the sequence - row or DDL,
     * positioned or not (a record that moves the anchor and resets the counter must
     * move the floor with it) - and it is applied only to first deliveries; redeliveries
     * keep their own timestamp. Control records (heartbeats, transaction metadata) do
     * NOT go through the sequence: they carry the connector's wall clock, not a source
     * time, and produce no row, so letting them in pinned the floor to the connector
     * clock and made the restart window lag-sized (spec 02.02 section 3.2).</p>
     *
     * <p>It is seeded at engine start from the durable high-water mark
     * ({@link #seedVersionFloor}), so the first deliveries of a new run rank above
     * every version the previous run assigned (spec 02.02 section 3.5).</p>
     *
     * <p>On MySQL {@code source.ts_ms} is the timestamp of the STATEMENT that produced
     * the row event, not of the commit. A transaction that stays open while others
     * commit reaches the binlog after them but with an OLDER timestamp. Without a floor
     * such an event would be versioned as {@code olderTs * 1e6 + counter} - and if the
     * counter had been reset by the newer-timestamped events in between, its version
     * ranked BELOW the earlier write of the same key (same source second, higher
     * counter). ReplacingMergeTree then kept the stale row and the later UPDATE was
     * lost, with row counts still matching on both sides. Clamping the timestamp
     * component of every first delivery to this floor keeps {@code _version}
     * monotonic in commit order, which is the only order ReplacingMergeTree needs.</p>
     */
    public static long sequenceMaxSourceTs = 0L;


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
        pgConfig.initSchemaChangeDetector(props, config, writer);
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
            replicationHistoryDbConnection = setReplicationHistoryDbConnection(dbCredentials, config);
        }

        try {
            this.debeziumJdbcStorageOperations.createDatabaseForDebeziumStorage(systemDbConnection, props);
        } catch (SQLException e) {
            log.error("Error creating Debezium storage database", e);
        }
        // The version floor must be seeded BEFORE the engine delivers its first
        // record: every first delivery of this run must rank above every version
        // the previous run handed off (spec 02.02 section 3.5).
        seedVersionFloorFromDurableMark(props, config);
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

        // Ensure ClickHouse JDBC URLs used by Debezium storage have
        // jdbc_ignore_unsupported_values=true so that setAutoCommit(false)
        // calls from Debezium's RetriableConnection don't throw
        // SQLFeatureNotSupportedException (ClickHouse has no transactions).
        // When the legacy V1 driver is requested (clickhouse.jdbc.v1=true),
        // tag the URLs with the V1 marker instead — the V1 driver ignores
        // those calls natively and does not know the V2-only flag.
        boolean useV1Driver = config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString());
        if (useV1Driver) {
            ensureUrlParam(props, "offset.storage.jdbc.url", V1_DRIVER_MARKER + "=true");
            ensureUrlParam(props, "schema.history.internal.jdbc.url", V1_DRIVER_MARKER + "=true");
        } else {
            ensureIgnoreUnsupportedValuesParam(props, "offset.storage.jdbc.url");
            ensureIgnoreUnsupportedValuesParam(props, "schema.history.internal.jdbc.url");
            // Only the V2 driver needs this. It returns a lazily-connected
            // object for an unreachable server, which keeps Debezium's
            // RetriableConnection on its UNBOUNDED statement-failure branch
            // instead of its bounded reconnect branch, spinning without a
            // delay. Route the two Debezium storage URLs through a driver
            // that validates the connection before returning it, restoring
            // the fail-fast behaviour the V1 driver has. The sink's own data
            // path is deliberately left alone.
            useValidatingDriver(props, "offset.storage.jdbc.url");
            useValidatingDriver(props, "schema.history.internal.jdbc.url");
        }

        // Guarantee that end-of-snapshot state can actually reach the offset
        // store. See ensureHeartbeatInterval for why this is not optional.
        ensureHeartbeatInterval(props);

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
                    handleChangeEventBatch(list, recordCommitter, props,
                            debeziumRecordParserService, config);
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
                    log.error("Debezium event thread: engine.run() threw exception", e);
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
        // A source table with no PRIMARY KEY and no non-null UNIQUE key has no
        // row identity in the binlog, so nothing downstream can keep its
        // ClickHouse copy correct. Name every such table here, with the ALTER
        // that fixes it, and carry on replicating: whether to accept that
        // divergence until a key is added is the operator's call, and refusing
        // would take every correctly-keyed table on the same source down with
        // it. This call never throws.
        KeylessTablePreflight.check(props);

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props));

        // snowflake.id=false versions GTID rows with the raw transaction number,
        // which ranks below every snapshot row; say so loudly before the engine
        // starts (spec 02.06 section 3.2.1). Not refused: an existing config must
        // keep starting after an upgrade (Invariant I11).
        warnIfRawGtidVersioningWithDataSnapshot(props, config);

        // Initialize PostgreSQL-specific configuration from properties.
        this.pgConfig = new PostgresConnectorConfig(props);

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
     * JDBC driver property key — tells ClickHouse JDBC 0.9.x to silently
     * ignore unsupported calls (setAutoCommit, commit, rollback).
     * Keep in sync with SinkConnectorDataSource.IGNORE_UNSUPPORTED_KEY.
     */
    private static final String IGNORE_UNSUPPORTED_KEY = "jdbc_ignore_unsupported_values";

    /**
     * URL marker understood by com.clickhouse.jdbc.ClickHouseDriver (0.7+):
     * selects the legacy V1 driver implementation for this URL.
     * Keep in sync with SinkConnectorDataSource.V1_DRIVER_MARKER.
     */
    static final String V1_DRIVER_MARKER = "clickhouse.jdbc.v1";

    /** Debezium's heartbeat interval property. Defaults to 0 (disabled). */
    static final String HEARTBEAT_INTERVAL_MS = "heartbeat.interval.ms";

    /**
     * Default heartbeat interval applied when the user has not set one.
     *
     * <p>Short enough that a snapshot of a small, idle database reaches its
     * committed end-of-snapshot state within seconds rather than never; long
     * enough to be irrelevant to a busy source, where rows are flowing and
     * the heartbeat path is not what advances the offset.</p>
     */
    static final String DEFAULT_HEARTBEAT_INTERVAL_MS = "5000";

    /**
     * Ensures Debezium emits heartbeats, because the connector's
     * end-of-snapshot state depends on them.
     *
     * <p><b>Why this is not optional (issue #1379, "Initial Snapshot never
     * finishes").</b> Debezium marks a snapshot complete only AFTER the last
     * snapshot row is emitted, so every snapshot ROW still carries
     * {@code snapshot=INITIAL, snapshot_completed=false}. The completed state
     * rides exclusively on records emitted after the snapshot. On a source
     * that is idle once the snapshot ends -- which is the normal case for the
     * small test databases people first try the connector on -- there are no
     * such records except heartbeats.</p>
     *
     * <p>Committing the offset from those control records is what
     * {@code commitControlRecordOffset} exists to do. But that machinery can
     * only act on a heartbeat that is actually emitted, and Debezium's
     * {@code heartbeat.interval.ms} defaults to 0, which disables heartbeats
     * entirely. The connector never set it. So on an idle source the fix had
     * nothing to fire on and the offset stayed at
     * {@code snapshot_completed=false} forever.</p>
     *
     * <p><b>Why that is destructive rather than cosmetic.</b> On restart
     * Debezium reads the persisted offset, sees a snapshot that never
     * completed, and re-runs the whole snapshot from the beginning
     * ({@code InitialSnapshotter#shouldSnapshotData} keys off exactly this
     * state). Every restart re-snapshots, so the connector can never make
     * forward progress past its first snapshot and the target is rewritten
     * from scratch each time.</p>
     *
     * <p>A user-supplied value always wins: this only fills in a default when
     * the property is absent or blank. Setting it to {@code 0} explicitly is
     * honoured, which keeps the escape hatch for anyone who has a reason to
     * disable heartbeats and accepts the consequence.</p>
     *
     * @param props the Debezium properties, mutated in place.
     */
    static void ensureHeartbeatInterval(Properties props) {
        if (props == null) {
            return;
        }
        String configured = props.getProperty(HEARTBEAT_INTERVAL_MS);
        if (configured != null && !configured.trim().isEmpty()) {
            log.info("Heartbeat interval is set to {}ms by configuration; leaving it "
                            + "unchanged. Note that a value of 0 disables heartbeats, and "
                            + "on a source that goes idle after the initial snapshot the "
                            + "snapshot's completed state will then never be committed "
                            + "(issue #1379).",
                    configured);
            return;
        }
        props.setProperty(HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_INTERVAL_MS);
        log.info("No {} configured; defaulting to {}ms. Heartbeats are what carry the "
                        + "end-of-snapshot state to the offset store on an idle source, so "
                        + "leaving them disabled would make the initial snapshot re-run on "
                        + "every restart (issue #1379).",
                HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    /**
     * Ensures the ClickHouse JDBC URL in the given property key has the
     * {@code jdbc_ignore_unsupported_values=true} parameter, which prevents
     * the driver from throwing SQLFeatureNotSupportedException on calls
     * like setAutoCommit(false) that ClickHouse does not support.
     */
    static void ensureIgnoreUnsupportedValuesParam(Properties props, String key) {
        ensureUrlParam(props, key, IGNORE_UNSUPPORTED_KEY + "=true");
    }

    /**
     * Ensures the ClickHouse JDBC URL stored under the given property key
     * carries the given {@code key=value} URL parameter (no-op if the
     * parameter key is already present or the URL is not a ClickHouse one).
     */
    static void ensureUrlParam(Properties props, String propKey, String param) {
        String url = props.getProperty(propKey);
        if (url == null || !url.startsWith("jdbc:clickhouse:")) {
            return;
        }
        String paramKey = param.substring(0, param.indexOf('='));
        if (url.contains(paramKey)) {
            return;
        }
        String separator = url.contains("?") ? "&" : "?";
        props.setProperty(propKey, url + separator + param);
    }

    /**
     * Routes the ClickHouse JDBC URL under the given property key through
     * {@link ValidatingClickHouseDriver}, so that a connection is only handed
     * to Debezium once the server has been proven reachable.
     * <p>
     * Without this, the V2 driver's lazily-connected object keeps Debezium's
     * {@code RetriableConnection} on its unbounded, sleepless
     * statement-failure branch: it never reaches the reconnect branch that
     * honours {@code retry.max.attempts} / {@code wait.retry.delay.ms}.
     * <p>
     * No-op when the URL is absent or is not a ClickHouse URL.
     *
     * @param props   the Debezium properties, modified in place.
     * @param propKey the property holding the JDBC URL.
     */
    static void useValidatingDriver(Properties props, String propKey) {
        String url = props.getProperty(propKey);
        String checked = ValidatingClickHouseDriver.toCheckedUrl(url);
        if (checked == null || checked.equals(url)) {
            return;
        }
        try {
            ValidatingClickHouseDriver.register();
        } catch (SQLException e) {
            // Leave the URL on the stock driver rather than producing one no
            // registered driver accepts.
            log.error("Could not register the validating ClickHouse driver; "
                    + "leaving {} on the default driver", propKey, e);
            return;
        }
        props.setProperty(propKey, checked);
    }

    /**
     * Flushes all buffered records and pauses the batch executor so that
     * no further writes reach ClickHouse until {@link #resumeAfterFlush()}
     * is called.
     *
     * <p>This is used by the checksum tool to quiesce the connector without
     * fully stopping it:
     * <ol>
     *   <li>Set the executor's pause flag — any currently executing batch
     *       finishes, but no new batch starts.</li>
     *   <li>Wait for the in-flight batch to drain (up to 30 seconds).</li>
     * </ol>
     *
     * <p>After this method returns the connector is still running (Debezium
     * still captures WAL events into the internal queue), but nothing is
     * written to ClickHouse.
     *
     * @throws IllegalStateException if the executor is not initialised.
     */
    public void flushAndPause() {
        if (this.executor == null) {
            throw new IllegalStateException("Executor is not initialised — cannot flush");
        }
        log.info("FLUSH: Pausing batch executor (drain buffered records)...");
        this.executor.pause();

        // Wait briefly for any in-flight batch to complete.
        // The pause flag is checked in beforeExecute(), so the currently running
        // task will finish normally; we just need to give it time.
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("FLUSH: Batch executor paused — no new writes will reach ClickHouse");
    }

    /**
     * Resumes the batch executor after a previous {@link #flushAndPause()}.
     *
     * @throws IllegalStateException if the executor is not initialised.
     */
    public void resumeAfterFlush() {
        if (this.executor == null) {
            throw new IllegalStateException("Executor is not initialised — cannot resume");
        }
        log.info("FLUSH: Resuming batch executor — writes to ClickHouse will restart");
        this.executor.resume();
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
    /**
     * Maximum time to wait for the writer to become quiescent before a DDL.
     */
    private static final long DDL_DRAIN_TIMEOUT_MS = 60_000;

    /**
     * Brings the writer to a standstill before a DDL is applied.
     *
     * <p>Three steps, in order: let the queued records be picked up and written
     * while the pool is still running -- on EVERY handoff path: the legacy
     * queue, every per-thread routed queue, and the batches a worker has
     * already dequeued but not yet acknowledged; stop new batches from
     * starting; then wait for the batches still inside a task body to finish.
     * Only then is every record that was read under the pre-ALTER schema
     * actually in ClickHouse.</p>
     *
     * <p>On timeout the DDL attempt is ABORTED with an {@link IllegalStateException}
     * naming the backlog; the caller raises it as a {@link DDLReplicationException}
     * and the engine halts. Applying the DDL over pending rows would write them
     * against the altered table with matching row counts -- silent corruption --
     * so the loud abort is the only safe outcome.</p>
     */
    private void drainBeforeDDL() {
        // Single-threaded mode has no worker pool and no async handoff queue:
        // singleThreadedWriter.persistRecords() runs inline on this same
        // Debezium thread, so every row read before this DDL is already in
        // ClickHouse and there is nothing to drain. `this.executor` is never
        // created in that mode (setupProcessingThread returns early), so the
        // pause()/awaitQuiescent() below would NPE and drop the DDL. Treat the
        // absence of a pool as "already quiescent".
        if (this.executor == null) {
            return;
        }

        long deadline = System.currentTimeMillis() + DDL_DRAIN_TIMEOUT_MS;

        // Step 1: let the pool consume what is already queued -- on EVERY
        // handoff path, not just the legacy queue.
        //
        // The barrier predicate is isPipelineQuiescent(): the legacy `records`
        // queue is empty AND every per-thread routed queue is empty AND no
        // handed-off batch is still unacknowledged. In hash-routing mode
        // (thread.pool.size > 1, the default) rows never touch `records` at
        // all; they sit on `routedQueues` until the owning worker's next tick.
        // Waiting only on `records` therefore observed an always-empty queue,
        // and step 3's awaitQuiescent() sees only batches inside a task body
        // RIGHT NOW -- zero between ticks even with every routed queue full.
        // The DDL was then applied while pre-DDL rows were still queued, and
        // those rows were written against the altered table: successful
        // inserts, matching row counts, wrong contents.
        //
        // The pool MUST still be running here. Pausing before the queues are
        // drained is a deadlock, not a safety measure: `pause()` parks every
        // pool thread in beforeExecute(), so nothing can dequeue, and this
        // loop then waits out the full timeout on queues that are guaranteed
        // never to shrink. It always ends in the abort below.
        //
        // Pausing first was introduced to close a "the queue never reaches
        // empty on a busy table" race. That race cannot occur: both producers
        // -- appendToRecords() and appendToRecordsWithHashRouting() -- are
        // called only from handleChangeEventBatch(), which runs on the very
        // Debezium thread that is executing this drain. While we are in here,
        // no new batch can be appended, so the queued set is already fixed and
        // the pool is free to consume it to empty. Step 2 then closes the
        // pause window properly.
        while (!isPipelineQuiescent()) {
            if (System.currentTimeMillis() >= deadline) {
                // NOT survivable. Applying the ALTER now writes records that
                // were read under the PREVIOUS schema against the NEW table --
                // the rows insert successfully with wrong contents and matching
                // row counts, which is the exact production failure. Aborting
                // instead routes into the DDL retry path, which drains again
                // from a consistent point.
                throw new IllegalStateException(String.format(
                        "DDL drain: %s still pending after %d ms. Applying the DDL now would "
                                + "write records captured under the previous schema against the "
                                + "altered table, silently corrupting them. Aborting this DDL "
                                + "attempt so it can be retried.",
                        describePendingHandoff(), DDL_DRAIN_TIMEOUT_MS));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "DDL drain interrupted before the writer was quiescent; aborting "
                                + "this DDL attempt rather than applying it over in-flight writes.");
            }
        }

        // Step 2: no new batches may start.
        this.executor.pause();

        // Step 3: wait out the batches already inside a task body.
        //
        // pause() + awaitQuiescent() together are what make the writer
        // genuinely quiescent, and the check-then-act window between them is
        // already closed inside ClickHouseBatchExecutor (the pause test and
        // the in-flight increment share one monitor). A batch that slipped
        // onto a thread just before the pause is therefore counted, and waited
        // out here, rather than racing the ALTER.
        long remaining = Math.max(0, deadline - System.currentTimeMillis());
        if (!this.executor.awaitQuiescent(remaining)) {
            throw new IllegalStateException(String.format(
                    "DDL drain: writer did not become quiescent within %d ms. Applying the "
                            + "DDL now would interleave it with in-flight writes captured under "
                            + "the previous schema. Aborting this DDL attempt so it can be "
                            + "retried.", DDL_DRAIN_TIMEOUT_MS));
        }
    }

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

        DDLParserService ddlParserService = DDLParserFactory.getParser(props, writer, config, databaseName);
        ddlParserService.parseSql(DDL, "", clickHouseQuery, isDropOrTruncate);


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

        // The most recent failure, carried as the cause of the terminal
        // exception so the operator sees the actual ClickHouse error.
        Exception lastFailure = null;

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
                    String rawDb = invalidationDatabaseName;
                    String overrideMapConfig = config.getString(
                            ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
                    Map<String, String> databaseOverrideMap = null;
                    if (overrideMapConfig != null) {
                        databaseOverrideMap =
                                Utils.parseSourceToDestinationDatabaseMap(overrideMapConfig);
                    }
                    if (invalidationDatabaseName != null) {
                        String dbPrefix = config.getString(
                                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_DATABASE_PREFIX.toString());
                        invalidationDatabaseName = Utils.applyDatabasePrefix(invalidationDatabaseName, dbPrefix);
                    }
                    if (invalidationDatabaseName != null && config.getBoolean(
                            ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_SCHEMA_SUFFIX.toString())) {
                        String schemaTemplate = config.getString(
                                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_SCHEMA_TEMPLATE.toString());
                        if (schemaTemplate != null && !schemaTemplate.isEmpty()) {
                            String topic = sr != null ? sr.topic() : null;
                            String schema = Utils.extractSchemaFromTopic(topic);
                            invalidationDatabaseName = Utils.applyDatabaseSchemaSuffix(invalidationDatabaseName, schemaTemplate, schema);
                        }
                    }
                    if (databaseOverrideMap != null) {
                        if (databaseOverrideMap.containsKey(invalidationDatabaseName)) {
                            invalidationDatabaseName = databaseOverrideMap.get(invalidationDatabaseName);
                        } else if (rawDb != null && databaseOverrideMap.containsKey(rawDb)) {
                            invalidationDatabaseName = databaseOverrideMap.get(rawDb);
                        }
                    }
                    List<String> affected = getTableNamesFromDDL(sr, DDL);
                    if (affected == null || affected.isEmpty()) {
                        // A PostgreSQL schema-change event may name no table in its
                        // tableChanges array, yet the topic still identifies it
                        // unambiguously. Resolve that before falling back, so the
                        // common Postgres DDL does not trigger a fleet-wide sweep.
                        String topicTable = Utils.getTableNameFromTopic(
                                sr.topic(), pgConfig.isSchemaPrefixEnabled(),
                                pgConfig.getCommonSchemaTemplate());
                        if (topicTable != null) {
                            affected = Collections.singletonList(topicTable);
                        }
                    }
                    if (affected == null || affected.isEmpty()) {
                        // The DDL changed something, but which table could not be
                        // resolved from the event, the statement text, or the topic.
                        // Leaving every cache in place would let writers keep binding
                        // against a schema this DDL just changed -- exactly the
                        // staleness that drops column values silently.
                        //
                        // Invalidate EVERYTHING instead. The cost is one metadata
                        // re-read per active table on its next batch; the cost of
                        // guessing wrong is undetectable data corruption.
                        log.warn("Could not resolve any table name for DDL [{}]; invalidating "
                                + "every cached schema rather than risk writing against a "
                                + "stale one.", DDL);
                        CacheInvalidationManager.getInstance().invalidateAll();
                    } else {
                        for (String tableName : affected) {
                            String tableKey = invalidationDatabaseName + "." + tableName;
                            CacheInvalidationManager.getInstance().invalidateTable(tableKey);
                            // Also invalidate the schema-drift detector cache so the next DML event
                            // re-fetches the updated ClickHouse schema immediately, without waiting
                            // for the TTL to expire.
                            if (pgConfig.getSchemaChangeDetector() != null) {
                                pgConfig.getSchemaChangeDetector().invalidateCache(tableKey);
                                log.debug("Schema-drift cache invalidated for {} after DDL: {}", tableKey, DDL);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Same reasoning: a failure to work out WHAT to invalidate must
                    // never leave stale caches in service after a DDL.
                    log.warn("Error invalidating cache for DDL [{}]; invalidating every "
                            + "cached schema as a fail-safe.", DDL, e);
                    try {
                        CacheInvalidationManager.getInstance().invalidateAll();
                    } catch (Exception inner) {
                        log.error("Fail-safe cache invalidation also failed after DDL [{}]. "
                                + "Cached schemas may be stale; the bind-time check will fail "
                                + "affected batches rather than write dropped columns.", DDL, inner);
                    }
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
                lastFailure = e;
                log.error("Error executing DDL", e);
                // insert data into the error table -- BEFORE the retry
                // decision, so the record exists whether or not we halt.
                try {
                    ErrorLogger.createErrorTable(systemDbConnection, config);
                    ErrorLogger.logError(systemDbConnection, e.getMessage(),
                        sr, databaseName, clickHouseQuery.toString(), props.getProperty("name"), errorTableName);
                } catch (SQLException ex) {
                    log.error("Failed to log DDL error to ClickHouse", ex);
                }
                if (retryDDLProperty == false) {
                    // ddl.retry decides only whether to try AGAIN; it never
                    // makes a failed DDL survivable. Leaving the loop normally
                    // here (the previous behaviour) acknowledged the DDL offset
                    // and let the row stream continue against a schema that no
                    // longer matched MySQL: the table stayed without the column
                    // while rows kept flowing, count-clean. Terminal and loud
                    // instead -- see DDLReplicationException and the catch in
                    // processEveryChangeRecord.
                    throw new DDLReplicationException(
                            "DDL failed and ddl.retry is not enabled, so it is not retried; "
                                    + "halting the pipeline rather than skipping the schema "
                                    + "change: [" + DDL + "]", e);
                }
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException ex) {
                    log.error("Error sleeping", ex);
                }
                numRetries++;
            }
            if (numRetries >= MAX_DDL_RETRIES) {
                // Terminal DDL failure. Raise the loud, non-swallowed type so
                // the pipeline halts here instead of advancing past a schema
                // change that never reached ClickHouse. See
                // DDLReplicationException and the catch in
                // processEveryChangeRecord.
                throw new DDLReplicationException(
                        "Max retries exceeded applying DDL to ClickHouse: [" + DDL + "]", lastFailure);
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

        Connection conn = createSystemDbConnectionWithRetry(jdbcUrl, dbCredentials, config);
        writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                BaseDbWriter.SYSTEM_DB, dbCredentials.getUserName(), dbCredentials.getPassword(),
                config, conn);
        return conn;
    }

    /**
     * Number of attempts made to obtain the initial system-database connection.
     */
    static final int SYSTEM_DB_CONNECT_ATTEMPTS = 30;

    /**
     * Delay between initial system-database connection attempts, in millis.
     */
    static final long SYSTEM_DB_CONNECT_RETRY_MS = 2000L;

    /**
     * Obtains the initial system-database connection, retrying while ClickHouse
     * is still starting up.
     * <p>
     * This connection is created exactly once at startup and is then reused for
     * the Debezium storage-database creation and the version lookup. When the
     * connection pool is disabled ({@code connection.pool.disable=true}, which
     * is the setting used by the docker-compose stacks), there is no pool to
     * obtain a replacement from later, so a {@code null} here is terminal for
     * the process: every later query fails with "connection is not available"
     * and the connector never creates the destination tables.
     * <p>
     * That is reachable on a cold start. compose gates the connector on the
     * ClickHouse container's healthcheck, but the healthcheck can pass moments
     * before the HTTP port is serving, and the two driver generations differ in
     * how they surface that window: verified against clickhouse-jdbc 0.9.8, the
     * V2 driver returns a connection object lazily even when nothing is
     * listening, while the V1 driver throws
     * "Connect to http://host:port failed: Connection refused" — which
     * {@code BaseDbWriter.createConnection} logs and converts to {@code null}.
     * Retrying here makes startup tolerant of that window for both drivers
     * instead of depending on which driver defers the connect.
     *
     * @param jdbcUrl       the system-database JDBC URL.
     * @param dbCredentials the database credentials.
     * @param config        the connector configuration.
     * @return the connection, or null if every attempt failed.
     */
    private Connection createSystemDbConnectionWithRetry(String jdbcUrl,
                                                         DBCredentials dbCredentials,
                                                         ClickHouseSinkConnectorConfig config) {
        return connectWithRetry(() -> BaseDbWriter.createConnection(
                jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                dbCredentials.getUserName(), dbCredentials.getPassword(),
                BaseDbWriter.SYSTEM_DB, config),
                SYSTEM_DB_CONNECT_RETRY_MS);
    }

    /**
     * Retry loop backing {@link #createSystemDbConnectionWithRetry}. Package
     * private so the retry behavior can be unit tested without a live server.
     *
     * @param supplier produces a connection, or null when unavailable.
     * @param retryMs  delay between attempts, in milliseconds.
     * @return the first non-null connection, or null if all attempts failed.
     */
    static Connection connectWithRetry(java.util.function.Supplier<Connection> supplier,
                                       long retryMs) {
        Connection conn = null;
        for (int attempt = 1; attempt <= SYSTEM_DB_CONNECT_ATTEMPTS; attempt++) {
            conn = supplier.get();
            if (conn != null) {
                if (attempt > 1) {
                    log.info("Obtained system database connection on attempt {}/{}",
                            attempt, SYSTEM_DB_CONNECT_ATTEMPTS);
                }
                return conn;
            }
            log.warn("System database connection not available yet "
                    + "(attempt {}/{}), retrying in {}ms",
                    attempt, SYSTEM_DB_CONNECT_ATTEMPTS, retryMs);
            if (attempt < SYSTEM_DB_CONNECT_ATTEMPTS) {
                try {
                    Thread.sleep(retryMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Could not obtain the system database connection after {} attempts; "
                + "startup will continue but ClickHouse operations will fail",
                SYSTEM_DB_CONNECT_ATTEMPTS);
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
        String dbName = "system";
        if (sr != null && sr.key() instanceof Struct) {
            Struct keyStruct = (Struct) sr.key();
            String recordDbName = null;
            // Try "databaseName" first (MySQL DDL key struct)
            if (keyStruct.schema().field("databaseName") != null) {
                recordDbName = (String) keyStruct.get("databaseName");
            }
            // Fall back to "db" (PostgreSQL key struct)
            if ((recordDbName == null || recordDbName.isEmpty()) && keyStruct.schema().field("db") != null) {
                recordDbName = (String) keyStruct.get("db");
            }
            if (recordDbName != null && !recordDbName.isEmpty()) {
                dbName = recordDbName;
            }
        }
        // Apply database prefix if configured (before suffix)
        dbName = Utils.applyDatabasePrefix(dbName, pgConfig.getCommonDatabasePrefix());
        // Apply database schema suffix if configured
        if (pgConfig.isDatabaseSchemaSuffix() && pgConfig.getCommonSchemaTemplate() != null
                && !pgConfig.getCommonSchemaTemplate().isEmpty()) {
            String topic = sr != null ? sr.topic() : null;
            String schema = Utils.extractSchemaFromTopic(topic);
            dbName = Utils.applyDatabaseSchemaSuffix(dbName, pgConfig.getCommonSchemaTemplate(), schema);
        }
        return dbName;
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
                    return Utils.getTableNameForSchemaPrefix(
                            tableName, sr.topic(), pgConfig.isSchemaPrefixEnabled(), pgConfig.getCommonSchemaTemplate());
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
     * <p>
     * For a {@code RENAME TABLE a TO b} the {@code tableChanges} entry identifies
     * the table by its NEW name, so resolving from {@code tableChanges} alone
     * leaves the OLD name's cached writer untouched. Any writer still keyed to
     * the old name would keep inserting into a table that no longer exists, so
     * the DDL text is additionally scanned for rename pairs and both names are
     * returned. See {@link #getRenamedTableNames}.
     *
     * @param sr The source record.
     * @param ddl The raw DDL statement, used to recover rename sources.
     * @return The distinct table names affected by the DDL, or an empty list.
     */
    private List<String> getTableNamesFromDDL(SourceRecord sr, String ddl) {
        List<String> tables = getTableNamesFromDDL(sr);
        for (String renamed : getRenamedTableNames(ddl)) {
            if (!tables.contains(renamed)) {
                tables.add(renamed);
            }
        }
        return tables;
    }

    /**
     * Extracts every table name participating in a rename, from either
     * {@code RENAME TABLE a TO b, c TO d} or {@code ALTER TABLE a RENAME TO b}.
     * <p>
     * Both sides are returned. The source name matters because its cached
     * writer must be discarded -- the table it points at is gone. The
     * destination name matters because a writer may already be cached for a
     * previous table of that name.
     *
     * @param ddl The raw DDL statement; may be null.
     * @return The bare table names involved in a rename, or an empty list.
     */
    List<String> getRenamedTableNames(String ddl) {
        List<String> names = new ArrayList<>();
        if (ddl == null || ddl.isEmpty()) {
            return names;
        }
        // ALTER TABLE a RENAME TO b -- the source precedes the RENAME keyword.
        Matcher alterMatcher = ALTER_RENAME.matcher(ddl);
        while (alterMatcher.find()) {
            collectNames(alterMatcher, names);
        }
        // RENAME TABLE a TO b, c TO d -- one match per pair.
        Matcher renameMatcher = RENAME_PAIR.matcher(ddl);
        while (renameMatcher.find()) {
            collectNames(renameMatcher, names);
        }
        return names;
    }

    /**
     * Adds the bare table names from both capture groups of a rename match,
     * skipping blanks and duplicates.
     */
    private void collectNames(Matcher matcher, List<String> names) {
        for (int group = 1; group <= 2; group++) {
            String name = extractTableFromId(matcher.group(group));
            if (name != null && !name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
    }

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
     * Extracts the table name from a Debezium DML topic.
     *
     * <p>For PostgreSQL, the topic format is:
     * {@code {topic.prefix}.{schema}.{table}}
     * The table name is the last dot-separated segment.
     *
     * @param topic Kafka topic string from a {@link SourceRecord}
     * @return the table name, or {@code null} if the topic is null/empty
     */
    private static String extractTableNameFromTopic(String topic) {
        return extractTableNameFromTopic(topic, false);
    }

    /**
     * Extracts the table name from a Debezium DML topic.
     *
     * <p>When {@code schemaPrefix} is true, returns {@code __<schema>__<table>}
     * using the second-to-last and last dot-separated segments.
     *
     * @param topic        Kafka topic string from a {@link SourceRecord}
     * @param schemaPrefix when true, prepend the schema segment
     * @return the table name, or {@code null} if the topic is null/empty
     */
    private static String extractTableNameFromTopic(String topic,
                                                     boolean schemaPrefix) {
        return extractTableNameFromTopic(topic, schemaPrefix, null);
    }

    /**
     * Extracts the table name from a Debezium DML topic with template support.
     *
     * <p>When {@code schemaPrefix} is true and {@code schemaTemplate} is
     * non-empty, the template is resolved and prepended.  Otherwise falls
     * back to the hardcoded {@code __<schema>__<table>} format.
     *
     * @param topic          Kafka topic string from a {@link SourceRecord}
     * @param schemaPrefix   when true, prepend the schema segment
     * @param schemaTemplate the shared template ({@code clickhouse.common.schema.template})
     * @return the table name, or {@code null} if the topic is null/empty
     */
    private static String extractTableNameFromTopic(String topic,
                                                     boolean schemaPrefix,
                                                     String schemaTemplate) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }
        if (schemaPrefix) {
            // Delegate to the shared utility that handles schema prefix extraction
            return Utils.getTableNameFromTopic(topic, true, schemaTemplate);
        }
        int lastDot = topic.lastIndexOf('.');
        if (lastDot < 0 || lastDot == topic.length() - 1) {
            return topic; // no dot → the whole topic is treated as the table name
        }
        return topic.substring(lastDot + 1);
    }

    /**
     * Extracts the ClickHouse target database name from a DML {@link SourceRecord}.
     *
     * <p>The database name is read from the {@code source} struct embedded in the
     * record value.  For PostgreSQL the relevant field is {@code db}.
     * Falls back to the existing {@link #getDatabaseName(SourceRecord)} logic
     * (which reads from the key struct – used for DDL records).
     *
     * @param sr the DML source record
     * @return the database name, or {@code null} if it cannot be determined
     */
    private String extractDatabaseNameFromRecord(SourceRecord sr) {
        return extractDatabaseNameFromRecord(sr, null);
    }

    private String extractDatabaseNameFromRecord(SourceRecord sr, ClickHouseSinkConnectorConfig config) {
        String dbName = null;
        // Try to read from the value's 'source' struct (standard Debezium envelope).
        try {
            if (sr.value() instanceof Struct) {
                Struct valueStruct = (Struct) sr.value();
                Object sourceObj = valueStruct.get("source");
                if (sourceObj instanceof Struct) {
                    Struct sourceStruct = (Struct) sourceObj;
                    // PostgreSQL uses "db" field; MySQL uses "db" as well.
                    try {
                        String db = (String) sourceStruct.get("db");
                        if (db != null && !db.isEmpty()) {
                            dbName = db;
                        }
                    } catch (Exception e) {
                        log.trace("'db' field not present in source struct: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract database name from source struct: {}", e.getMessage());
        }
        // Fall back to key-based extraction (used by DDL path).
        if (dbName == null) {
            String fallback = getDatabaseName(sr);
            dbName = "system".equals(fallback) ? null : fallback;
        }
        String rawDbName = dbName;
        // Apply database prefix if configured (before suffix)
        if (dbName != null && pgConfig != null) {
            dbName = Utils.applyDatabasePrefix(dbName, pgConfig.getCommonDatabasePrefix());
        }
        // Apply database schema suffix if configured
        if (dbName != null && pgConfig != null && pgConfig.isDatabaseSchemaSuffix()
                && pgConfig.getCommonSchemaTemplate() != null
                && !pgConfig.getCommonSchemaTemplate().isEmpty()) {
            String topic = sr != null ? sr.topic() : null;
            String schema = Utils.extractSchemaFromTopic(topic);
            dbName = Utils.applyDatabaseSchemaSuffix(dbName, pgConfig.getCommonSchemaTemplate(), schema);
        }
        // Apply database override map if configured
        if (config != null && dbName != null) {
            String overrideMapConfig = config.getString(
                    ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
            if (overrideMapConfig != null && !overrideMapConfig.isEmpty()) {
                try {
                    Map<String, String> databaseOverrideMap =
                            Utils.parseSourceToDestinationDatabaseMap(overrideMapConfig);
                    if (databaseOverrideMap.containsKey(dbName)) {
                        dbName = databaseOverrideMap.get(dbName);
                    } else if (rawDbName != null && databaseOverrideMap.containsKey(rawDbName)) {
                        dbName = databaseOverrideMap.get(rawDbName);
                    }
                } catch (Exception e) {
                    log.error("Error parsing database override map in extractDatabaseNameFromRecord: {}", e.getMessage());
                }
            }
        }
        return dbName;
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
        long schemaChangeTimeoutMs = config.getLong(
                ClickHouseSinkConnectorConfigVariables.DDL_SCHEMA_CHANGE_TIMEOUT_MS.toString());
        long schemaChangePollIntervalMs = config.getLong(
                ClickHouseSinkConnectorConfigVariables.DDL_SCHEMA_CHANGE_POLL_INTERVAL_MS.toString());
        DDLSchemaChangeWaiter schemaWaiter = new DDLSchemaChangeWaiter(schemaChangeTimeoutMs, schemaChangePollIntervalMs);
        String[] queries = clickHouseQuery.replaceAll(",$", "").split("\n");
        for (String query : queries) {
            if (!query.isEmpty()) {
                log.info("ClickHouse DDL: " + query);
                dbMetadata.executeSystemQuery(writer.getConnection(), query);
                // Wait for schema change to become visible in system.columns
                // before cache invalidation proceeds. Without this, the batch
                // insert thread may rebuild its column metadata cache before
                // the ALTER TABLE has propagated, silently dropping values
                // for newly added columns. See GitHub issue #1222.
                schemaWaiter.waitForSchemaVisibility(writer.getConnection(), query);
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
     * Handles one batch of change events delivered by the Debezium engine.
     * <p>
     * Extracted from the {@code ChangeConsumer} passed to
     * {@link #setupDebeziumEventCapture} so the batch semantics -- in
     * particular which records get their offset acknowledged -- can be
     * exercised without standing up an engine. The consumer is now a single
     * delegation to this method.
     * </p>
     *
     * @param list                        The batch of change events.
     * @param recordCommitter             The record committer for offset management.
     * @param props                       The connector properties.
     * @param debeziumRecordParserService The service to parse change events.
     * @param config                      The connector configuration.
     * @throws InterruptedException if acknowledging the offset is interrupted.
     */
    @VisibleForTesting
    void handleChangeEventBatch(List<ChangeEvent<SourceRecord, SourceRecord>> list,
                                DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter,
                                Properties props,
                                DebeziumRecordParserService debeziumRecordParserService,
                                ClickHouseSinkConnectorConfig config)
            throws InterruptedException {

        // A dead worker must stop the engine, not stall it (spec 03.01
        // section 3.3). Checked before anything else, including the empty-batch
        // return, so the failure surfaces on the very next source batch.
        failIfWorkerDied();

        if (list.isEmpty()) {
            return;
        }


        // The newest record in this batch that produced no row, and is not a
        // DDL (the DDL path acknowledges itself). Heartbeats and
        // transaction-boundary events land here. See
        // commitControlRecordOffset for why the newest one is enough.
        ChangeEvent<SourceRecord, SourceRecord> lastControlRecord = null;
        // Whether any row from this batch was handed to the writers. Rows
        // handed off are acknowledged by the writer once they are in
        // ClickHouse, and until then no control-record offset may be
        // committed past them.
        boolean handedOffRows = false;

        List<ClickHouseStruct> batch = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ChangeEvent<SourceRecord, SourceRecord> record = list.get(i);
            boolean lastRecordInBatch = false;
            if (i == list.size() - 1) {
                lastRecordInBatch = true;
            }
            boolean ddlRecord = isDDLRecord(record);

            // Only rows and DDL enter the version sequence. A control record
            // (heartbeat, transaction metadata) produces no row and needs no
            // version; its only timestamp is the envelope ts_ms - the connector's
            // wall clock - and running it through the sequence pinned the floor
            // to that clock, which on a lagging source pushed every later row's
            // version into the connector's second and made the restart window
            // lag-sized (spec 02.02 section 3.2).
            VersionAssignment assignment = null;
            if (ddlRecord || !isControlRecord(record.value())) {
                // Anchor the version to the SOURCE commit timestamp (source.ts_ms),
                // not the envelope/processing timestamp. The source timestamp is
                // identical on every Debezium re-delivery, so a re-delivered DELETE
                // keeps its original (lower) _version and can no longer out-rank a
                // later re-INSERT (issue #1346). The emitted formula is unchanged
                // from 2.8.0 (ts_ms * 1_000_000 + counter), so values stay in the
                // same numeric domain: upgrades AND downgrades remain safe.
                long recordTs = ClickHouseStruct.getSourceTsFromChangeEvent(record);

                // The intra-second counter is keyed on the source commit clock and its
                // anchor is global (survives batch boundaries and binlog rotations)
                // and never moves backward. The log position decides whether this
                // record is a FIRST delivery - which must rank above everything
                // already versioned, however old its statement timestamp is - or a
                // redelivery, which keeps its #1346 redelivery-stable version. See
                // nextVersionAssignment. The clamped effectiveTs travels with the
                // record so the GTID version is floored the same way.
                assignment = nextVersionAssignment(recordTs,
                        ClickHouseStruct.getSourcePositionFromChangeEvent(record));
                if (!ddlRecord) {
                    // Make the durable horizon cover this version BEFORE the row
                    // can reach the writers: the next start seeds its floor from
                    // that horizon, so no row may be in ClickHouse above it.
                    coverAssignedVersion(assignment.sequenceNumber);
                }
            }

            // A DDL inside this loop is applied to ClickHouse synchronously,
            // while the rows read before it in the same Debezium batch are
            // still sitting in the local `batch` list -- they are not handed
            // to the consumers until after the loop finishes. Those rows were
            // captured against the PRE-DDL schema but would reach ClickHouse
            // strictly AFTER the schema change, which inverts their order with
            // respect to the source. The inversion is guaranteed by control
            // flow, not a thread race, so it reproduces on every such batch.
            //
            // Hand the pending rows over BEFORE the DDL is applied, so the
            // schema change lands at its true position in the stream.
            if (ddlRecord && batch.size() > 0) {
                // Every handed-off batch MUST carry a terminal marker so the
                // writer path calls markBatchFinished() and flushes this batch's
                // offset. The Debezium-list index cannot supply it here (the DDL,
                // not a row, is the current record), so flag the last row of this
                // sub-batch explicitly. See markTerminalRecord (#1379 offset
                // progress).
                markTerminalRecord(batch);
                appendToRecords(new ArrayList<>(batch), config);
                batch.clear();
                handedOffRows = true;
            }

            ClickHouseStruct chStruct = processEveryChangeRecord(props, record,
                    debeziumRecordParserService, config, recordCommitter, lastRecordInBatch, assignment);
            if (chStruct != null) {
                batch.add(chStruct);
            } else if (!ddlRecord) {
                lastControlRecord = record;
            }
        }
        // Add sequence number.
        //addVersion(batch);

        if (batch.size() > 0) {
            // Guarantee a terminal marker on the handed-off batch even when the
            // Debezium batch ended with a control record (heartbeat / tx
            // boundary): otherwise no row carries isLastRecordInBatch, the writer
            // never calls markBatchFinished(), and this batch's offset would only
            // be committed later by a heartbeat. See markTerminalRecord (#1379).
            markTerminalRecord(batch);
            appendToRecords(batch, config);
            handedOffRows = true;
        }

        commitControlRecordOffset(lastControlRecord, recordCommitter, handedOffRows);
    }

    /**
     * Stops the engine loudly if any worker's scheduled task has terminated.
     * <p>
     * {@code ScheduledThreadPoolExecutor} cancels a periodic task whose run
     * throws; the task's future completes exceptionally and nothing else
     * happens. The worker's batch stays outstanding (correct: no offset may
     * pass it) but the process stays alive with one queue filling until the
     * source reader blocks in {@code put} -- a silent stall. Re-raising the
     * worker's cause from the Debezium thread leaves the {@code ChangeConsumer},
     * so the engine stops through its completion callback with the worker's
     * stack trace in the log; a restart resumes from the last committed offset.
     * </p>
     *
     * @throws RuntimeException carrying the dead worker's cause.
     */
    @VisibleForTesting
    void failIfWorkerDied() {
        for (int i = 0; i < this.workerFutures.size(); i++) {
            java.util.concurrent.ScheduledFuture<?> future = this.workerFutures.get(i);
            if (future == null || !future.isDone()) {
                continue;
            }
            Throwable cause = null;
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                cause = e.getCause() != null ? e.getCause() : e;
            } catch (java.util.concurrent.CancellationException e) {
                cause = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cause = e;
            }
            String message = String.format("Sink worker %d of %d is dead: its scheduled task "
                    + "has terminated%s. Batches queued for it can never be written, offsets can "
                    + "never advance past them, and its queue would fill until the source reader "
                    + "blocks. Stopping the engine so the failure is visible; a restart resumes "
                    + "from the last committed offset.", i, this.workerFutures.size(),
                    cause == null ? " normally (a worker task must run for the life of the engine)"
                            : "");
            log.error(message, cause);
            throw new RuntimeException(message, cause);
        }
    }

    /**
     * Flags the last record of a batch about to be handed to the writers as the
     * batch terminal, so {@code DebeziumOffsetManagement.acknowledgeRecords} calls

     * {@code markBatchFinished()} and this handoff unit's offset is flushed once
     * its rows are written.
     * <p>
     * {@code handleChangeEventBatch} otherwise sets {@code lastRecordInBatch} from
     * the record's index in the Debezium batch. When that batch ends with a
     * control record (a heartbeat or transaction boundary, which produces no
     * {@link ClickHouseStruct}), or is split ahead of a DDL, the last HANDED-OFF
     * row is not the last Debezium record and carries {@code false}. The handoff
     * then has no terminal record, {@code markBatchFinished()} is never called for
     * it, and its offset commit is deferred to a later heartbeat -- delaying
     * (on an idle source, stranding) snapshot-completion progress (issue #1379).
     * Marking the last handed-off row here makes offset progress independent of
     * where control records fall in the Debezium batch.
     * </p>
     *
     * @param batch the non-empty batch about to be handed off.
     */
    static void markTerminalRecord(List<ClickHouseStruct> batch) {
        if (batch != null && !batch.isEmpty()) {
            batch.get(batch.size() - 1).setLastRecordInBatch(true);
        }
    }

    /**
     * Commits the offset carried by a record that produced no row, when it is
     * safe to do so.
     * <p><b>Why this exists (issue #1379, "Initial Snapshot never finishes").</b>
     * A Debezium batch carries more than row changes. Heartbeats and
     * transaction-boundary events have a Struct value and no {@code op}
     * field, so the parser returns null for them -- that null is contract,
     * not failure. Such a record was then dropped outright: never
     * {@code markProcessed}, never {@code markBatchFinished}. Only records
     * that became a row were ever acknowledged.</p>
     *
     * <p>That silently strands the end-of-snapshot state. Debezium marks the
     * snapshot complete AFTER the last snapshot row is emitted
     * ({@code preSnapshotCompletion} / {@code postSnapshotCompletion} in
     * {@code RelationalSnapshotChangeEventSource#createDataEvents}), so every
     * snapshot ROW still carries {@code snapshot=INITIAL,
     * snapshot_completed=false}. The completed state rides only on records
     * emitted after the snapshot -- and on an idle source those are
     * exclusively heartbeats. Dropping them left
     * {@code replica_source_info} pinned at
     * {@code snapshot=INITIAL, snapshot_completed=false} forever, which is
     * both halves of the report: the status view never shows the snapshot
     * finishing, and on restart
     * {@code InitialSnapshotter#shouldSnapshotData} reads
     * {@code snapshotInProgress=true} and re-runs the whole snapshot.</p>
     *
     * <p>Committing the newest such record is sufficient: the engine's
     * committer stages offsets into an {@code OffsetStorageWriter} keyed by
     * source partition, so the newest offset supersedes the older ones in the
     * same batch.</p>
     *
     * <p><b>Ordering safety.</b> A heartbeat carries the connector's CURRENT
     * position, which is at or after every row already read. Committing it
     * while any row is still unwritten would move the committed offset past
     * that row and lose it on a crash -- the failure mode of issue #1285.
     * The offset is therefore committed only when nothing is in flight: no
     * row from this batch was handed off, both handoff queues are empty, and
     * no batch is awaiting persistence. On a busy pipeline that check fails
     * and the behaviour is exactly as before -- no regression -- because a
     * busy pipeline commits its offsets through the rows themselves. It is
     * precisely the idle case, where no row is coming, that needed this.</p>
     *
     * @param controlRecord   The newest record that produced no row; may be null.
     * @param recordCommitter The record committer for offset management.
     * @param handedOffRows   True if any row from this batch was handed to the writers.
     * @return true if the offset was committed.
     * @throws InterruptedException if acknowledging the offset is interrupted.
     */
    @VisibleForTesting
    boolean commitControlRecordOffset(ChangeEvent<SourceRecord, SourceRecord> controlRecord,
                                      DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter,
                                      boolean handedOffRows)
            throws InterruptedException {
        if (controlRecord == null || recordCommitter == null) {
            return false;
        }
        if (handedOffRows || !isPipelineQuiescent()) {
            return false;
        }
        DebeziumOffsetManagement.acknowledgeRecords(recordCommitter, controlRecord, true);
        log.debug("Committed the offset of a record that produced no row; "
                + "nothing was in flight. Record({})", controlRecord);
        return true;
    }

    /**
     * Reports whether nothing the connector has read is still waiting to be
     * written to ClickHouse.
     * <p>
     * Covers all three handoff paths: the legacy queue, the hash-routing
     * queue, and the batches the consumers have picked up but not yet
     * acknowledged. In single-threaded mode the writer runs inline on the
     * Debezium thread, so both queues stay empty and nothing is registered
     * in flight -- the predicate is trivially true, which is correct,
     * because by then the rows are already written.
     * </p>
     *
     * @return true if no record is awaiting persistence.
     */
    private boolean isPipelineQuiescent() {
        if (this.records != null && !this.records.isEmpty()) {
            return false;
        }
        if (this.routedQueues != null) {
            for (LinkedBlockingQueue<RoutedBatch> queue : this.routedQueues) {
                if (!queue.isEmpty()) {
                    return false;
                }
            }
        }
        return !DebeziumOffsetManagement.hasUnwrittenBatches();
    }

    /**
     * Names what {@link #isPipelineQuiescent()} is still waiting on, for the
     * DDL drain abort message: batches on the legacy queue, batches across the
     * routed queues, and whether handed-off batches are still unacknowledged.
     *
     * @return a human-readable summary of the pending handoff backlog.
     */
    private String describePendingHandoff() {
        int legacy = this.records == null ? 0 : this.records.size();
        int routed = 0;
        if (this.routedQueues != null) {
            for (LinkedBlockingQueue<RoutedBatch> queue : this.routedQueues) {
                routed += queue.size();
            }
        }
        return String.format(
                "%d legacy queue batch(es), %d routed queue batch(es), "
                        + "unacknowledged handed-off batches: %s",
                legacy, routed, DebeziumOffsetManagement.hasUnwrittenBatches() ? "yes" : "no");
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
     * @param assignment                  The version assignment of the record (sequence
     *                                    number and clamped timestamp); null for a
     *                                    control record, which produces no row.
     * @return A {@link ClickHouseStruct} representing the processed record,
     *         or null if the record is invalid.
     */
    private ClickHouseStruct processEveryChangeRecord(Properties props,
                                                      ChangeEvent<SourceRecord, SourceRecord> record,
                                                      DebeziumRecordParserService debeziumRecordParserService,
                                                      ClickHouseSinkConnectorConfig config,
                                                      DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter,
                                                      boolean lastRecordInBatch,
                                                      VersionAssignment assignment) {
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
                    // pause() stops NEW batches from starting; it does not drain
                    // what is already queued or already running. Records read
                    // under the pre-ALTER schema were therefore still being
                    // written AFTER the ALTER had been applied to ClickHouse.
                    //
                    // For an ALTER TABLE ... CHANGE COLUMN (rename) that is
                    // silent corruption: the buffered record still carries the
                    // OLD field name while the ClickHouse table now has the NEW
                    // column, so the writer finds no value for it and binds
                    // NULL over the real one. Row counts are unaffected, so
                    // count-based checksums report the table clean.
                    //
                    // Drain first, then apply the DDL.
                    //
                    // The resume MUST be in a finally: drainBeforeDDL() and
                    // performDDLOperation() can both throw (a drain that does
                    // not reach quiescence now aborts rather than applying the
                    // DDL over in-flight writes), and on that path the pool
                    // would stay paused forever -- replication stops dead with
                    // no error after the first one. Resuming unconditionally
                    // keeps a failed DDL a retryable event instead of a stall.
                    //
                    // A DDL failure must escape this method LOUDLY. The
                    // catch-all at the bottom of processEveryChangeRecord
                    // exists to stop one bad DML record from killing the
                    // stream; a DDL failure is the opposite case -- skipping
                    // the schema change but continuing the stream writes every
                    // later row against a schema that no longer matches MySQL,
                    // a silent count-clean divergence. So any failure here is
                    // wrapped in DDLReplicationException, which is re-thrown
                    // ahead of that catch-all (see below) and halts the engine:
                    // offsets are not committed past the unapplied DDL, and on
                    // restart Debezium re-delivers it from the last committed
                    // position -- the genuine retry the drain abort was always
                    // meant to enable.
                    //
                    // In single-threaded mode there is no worker pool: rows are
                    // persisted inline by singleThreadedWriter before this DDL
                    // record is ever handled, so there is nothing to drain and
                    // no executor to pause/resume. Guard both against the null
                    // executor -- dereferencing it here dropped the FIRST DDL in
                    // that mode with an NPE straight into the catch-all.
                    try {
                        drainBeforeDDL();

                        Map<String, Object> sourceObjStruct = new ClickHouseConverter().convertValue(sr);

                        ClickHouseStruct ddlStruct = new ClickHouseStruct();
                        ddlStruct.setAdditionalMetaData(sourceObjStruct);
                        if (assignment != null) {
                            ddlStruct.setSequenceNumber(assignment.sequenceNumber);
                            ddlStruct.setVersionTs(assignment.effectiveTs);
                        }
                        performDDLOperation(DDL, props, sr, config, recordCommitter, record, lastRecordInBatch, ddlStruct);
                    } catch (DDLReplicationException dre) {
                        // Already the loud, terminal type -- do not re-wrap.
                        throw dre;
                    } catch (Exception ddlEx) {
                        throw new DDLReplicationException(
                                "DDL replication failed for [" + DDL + "]; stopping the pipeline "
                                        + "rather than advancing offsets past an unapplied schema "
                                        + "change and silently diverging from MySQL.", ddlEx);
                    } finally {
                        if (this.executor != null) {
                            this.executor.resume();
                        }
                    }
                }
            } else {
                // Schema drift detection: check before writing to ClickHouse so that
                // any newly-added PostgreSQL columns are present in ClickHouse first.
                //
                // pgConfig is assigned in setup(); it is null on any path that
                // reaches record processing without it (notably MySQL-only unit
                // tests driving handleChangeEventBatch directly). Dereferencing
                // it unguarded threw an NPE BEFORE parse() was reached, which
                // re-broke the #1379 contract this branch is meant to preserve:
                // the record was dropped without being acknowledged. Schema
                // drift detection is PostgreSQL-only and optional, so its
                // absence must skip the check, never fail the record.
                if (pgConfig != null && pgConfig.getSchemaChangeDetector() != null) {
                    try {
                        String dmlTopic = sr.topic();
                        String dmlTable = Utils.getTableNameFromTopic(dmlTopic, pgConfig.isSchemaPrefixEnabled(), pgConfig.getCommonSchemaTemplate());
                        String dmlDatabase = extractDatabaseNameFromRecord(sr, config);
                        if (dmlTable != null && dmlDatabase != null) {
                            pgConfig.getSchemaChangeDetector().checkAndReconcile(sr, dmlTable, dmlDatabase);
                        }
                    } catch (Exception schemaEx) {
                        log.warn("Schema drift detection threw unexpectedly; continuing replication. Cause: {}",
                                schemaEx.getMessage(), schemaEx);
                    }
                }
                chStruct = debeziumRecordParserService.parse(record, recordCommitter, lastRecordInBatch);
                // NOTE: do NOT early-return on a null chStruct here. A null is
                // contract (heartbeat / transaction-boundary record), and the
                // caller -- handleChangeEventBatch -- must still see this record
                // so commitControlRecordOffset can acknowledge its offset. That
                // acknowledgement is the whole of the #1379 fix (#1428): on an
                // idle source the post-snapshot state rides exclusively on
                // heartbeats, so returning early here strands
                // snapshot_completed=false forever and re-snapshots on restart.
                try {
                    if (chStruct != null) {
                        if (assignment == null) {
                            // Cannot happen by construction (a parsed row carries an
                            // `op`, so it is not a control record); if it ever does,
                            // version the row now rather than hand it off unordered.
                            log.error("Row record reached the writer path without a version "
                                    + "assignment; assigning one now. Record({})", record);
                            assignment = nextVersionAssignment(
                                    ClickHouseStruct.getSourceTsFromChangeEvent(record),
                                    ClickHouseStruct.getSourcePositionFromChangeEvent(record));
                            coverAssignedVersion(assignment.sequenceNumber);
                        }
                        chStruct.setSequenceNumber(assignment.sequenceNumber);
                        chStruct.setVersionTs(assignment.effectiveTs);
                        ReplicationStatusSingleton rss = ReplicationStatusSingleton.getInstance();
                        rss.setReplicationLag(chStruct.getReplicationLag());
                        rss.setLastRecordTimestamp(chStruct.getTs_ms());
                        rss.setBinLogFile(chStruct.getFile());
                        rss.setBinLogPosition(String.valueOf(chStruct.getPos()));
                        rss.setGtid(String.valueOf(chStruct.getGtid()));
                    } else if (isControlRecord(sr)) {
                        // A heartbeat or transaction-metadata record: it has no
                        // `op` field, so parse() returns null BY CONTRACT --
                        // there is no row to write. Its offset is still
                        // committed by commitControlRecordOffset once the
                        // pipeline is quiescent (#1379). Logging this at WARN
                        // produced one warning per heartbeat interval for the
                        // life of the process and buried the warnings that
                        // matter.
                        log.debug("Control record (heartbeat/transaction metadata) - no row to "
                                + "write; its offset is committed once the pipeline is quiescent. "
                                + "Record({})", record);
                    } else {
                        // parse() returns null for a record it cannot convert, such as
                        // a null or non-Struct source value. Setting the sequence number
                        // before this check raised an NPE that the catch-all below then
                        // swallowed, so the record was dropped silently while the
                        // snapshot loop logged the same stack trace per record (#1379).
                        // This branch is reached only for a record that DOES carry an
                        // `op` field (or no Struct at all): a real row was dropped, so
                        // it must stay visible.
                        log.warn(String.format(
                                "Record could not be parsed to a ClickHouseStruct - skipping. Record(%s)",
                                record));
                    }
                } catch (Exception e) {
                    log.error("Error retrieving status metrics: Exception" + e.toString());
                }
            }
        } catch (DDLReplicationException dre) {
            // A DDL that could not be applied must NOT be swallowed like a bad
            // DML record. Re-throw so it leaves handleBatch and halts the
            // engine; continuing here would advance offsets past an unapplied
            // schema change and silently diverge from MySQL. Kept ahead of the
            // catch-all below, which would otherwise absorb it.
            throw dre;
        } catch (Exception e) {
            log.error("Exception processing record", e);
        }

        return chStruct;
    }

    /** Prefix of the topic Debezium emits heartbeat records on. */
    static final String HEARTBEAT_TOPIC_PREFIX = "__debezium-heartbeat";

    /**
     * Reports whether a record is a control record -- one that carries no row
     * by contract, so {@code parse()} returning null for it is expected: a
     * heartbeat (recognised by its topic) or any Struct-valued record whose
     * schema has no {@code op} field (heartbeat payloads carry only
     * {@code ts_ms}; transaction metadata carries {@code status}/{@code id}/
     * {@code event_count}).
     * <p>
     * A null or non-Struct value is NOT a control record: a null parse result
     * for such a record is a dropped record and must stay visible at WARN.
     * </p>
     *
     * @param sr the source record; may be null.
     * @return true if the record is a heartbeat or transaction-metadata record.
     */
    @VisibleForTesting
    static boolean isControlRecord(SourceRecord sr) {
        if (sr == null) {
            return false;
        }
        String topic = sr.topic();
        if (topic != null && topic.startsWith(HEARTBEAT_TOPIC_PREFIX)) {
            return true;
        }
        Object value = sr.value();
        if (!(value instanceof Struct)) {
            return false;
        }
        Struct struct = (Struct) value;
        return struct.schema() != null
                && struct.schema().field(SinkRecordColumns.OPERATION) == null;
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
     * Reports whether a change event carries a DDL statement.
     * <p>
     * Uses the same test as the DDL branch of
     * {@link #processEveryChangeRecord}: a schema-change event has a
     * {@code DDL} field in its value schema holding a non-empty statement.
     * Side-effect free, so it is safe to call while iterating a batch.
     *
     * @param record The change event to inspect.
     * @return true if the record carries a non-empty DDL statement.
     */
    boolean isDDLRecord(ChangeEvent<SourceRecord, SourceRecord> record) {
        try {
            if (record == null || record.value() == null) {
                return false;
            }
            Object value = record.value().value();
            if (!(value instanceof Struct)) {
                return false;
            }
            Struct struct = (Struct) value;
            if (struct.schema() == null || struct.schema().fields() == null) {
                return false;
            }
            // Match the field name case-insensitively, as the DDL branch does,
            // but read it back by the name that actually matched: Struct.get is
            // case-SENSITIVE and throws if handed a different spelling.
            Field ddlField = struct.schema().fields().stream()
                    .filter(f -> "DDL".equalsIgnoreCase(f.name()))
                    .findAny()
                    .orElse(null);
            if (ddlField == null) {
                return false;
            }
            Object ddl = struct.get(ddlField.name());
            return ddl instanceof String && !((String) ddl).isEmpty();
        } catch (Exception e) {
            // Never let this check break the batch loop. Treating an
            // unreadable record as non-DDL preserves the previous behaviour.
            log.debug("Could not determine whether the record carries DDL", e);
            return false;
        }
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
            // One queue per thread; each runnable drains ONLY its own queue, so
            // all batches for a table (routed to one thread) stay in FIFO order.
            this.routedQueues = new ArrayList<>(this.threadPoolSize);
            for (int i = 0; i < this.threadPoolSize; i++) {
                this.routedQueues.add(new LinkedBlockingQueue<>(maxQueueSize));
            }
            for (int i = 0; i < this.threadPoolSize; i++) {
                this.workerFutures.add(this.executor.scheduleAtFixedRate(
                        new ClickHouseBatchRunnable(this.routedQueues.get(i), i, config, new HashMap<>()),
                        0,
                        config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()),
                        TimeUnit.MILLISECONDS));
            }

        } else {
            // Single thread - use legacy mode
            log.info("********* Using legacy mode with single thread *********");
            for (int i = 0; i < this.threadPoolSize; i++) {
                this.workerFutures.add(this.executor.scheduleAtFixedRate(
                        new ClickHouseBatchRunnable(this.records, config, new HashMap<>()),
                        0,
                        config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()),
                        TimeUnit.MILLISECONDS));
            }

        }
    }

    /**
     * Hands the given records to the writers (spec 01.05).
     *
     * @param convertedRecords The list of {@link ClickHouseStruct} records, in
     *                         binlog order, terminal marker on the last row.
     * @param config           The connector configuration.
     * @throws InterruptedException if the handoff is interrupted; the unit's
     *                              registration is deliberately kept, so no
     *                              offset can pass rows that never reached a
     *                              queue, and the engine stops loudly.
     */
    private void appendToRecords(List<ClickHouseStruct> convertedRecords, ClickHouseSinkConnectorConfig config)
            throws InterruptedException {
        // If config is set to single threaded.
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())) {
            // Runs inline on this thread, so the rows are written (or have
            // thrown) before control returns. Nothing is ever outstanding
            // across a quiescence check, so no handoff sequence is involved.
            singleThreadedWriter.persistRecords(convertedRecords);
            return;
        }

        // Asynchronous handoff from here on. The unit is registered with its
        // handoff sequence BEFORE it becomes visible to any consumer: the
        // sequence is what orders its offset acknowledgement against every
        // other outstanding batch (binlog order, spec 09.01), and the
        // registration is what makes the batch read as unwritten from this
        // instant -- including the window between a worker's poll() and its
        // write -- see DebeziumOffsetManagement#hasUnwrittenBatches.
        if (this.threadPoolSize > 1 && this.routedQueues != null) {
            // Hash-based routing mode: group records by table and route to specific threads
            appendToRecordsWithHashRouting(convertedRecords);
        } else {
            // Legacy mode: single queue; the unit is its own single group.
            synchronized (this.records) {
                int remainingCapacity = this.records.remainingCapacity();
                int currentSize = this.records.size();
                int totalCapacity = remainingCapacity + currentSize;
                if (totalCapacity > 0 && currentSize >= (0.9 * totalCapacity)) {
                    log.warn("Queue is at 90% capacity! Current size: {}, Total capacity: {}", currentSize, totalCapacity);
                }
                if (remainingCapacity == 0) {
                    log.warn("Queue is full! Current size: {}, Total capacity: {}", this.records.size(), totalCapacity);
                }
                long sequence = DebeziumOffsetManagement.registerHandoff(convertedRecords,
                        java.util.Collections.singletonList(convertedRecords));
                this.records.put(convertedRecords);
                log.debug("Handed off {} records as sequence {}", convertedRecords.size(), sequence);
            }
        }
    }

    /**
     * Hands records to the writers using hash-based routing (spec 01.05 section 3.3).
     * Groups records by table and enqueues each group on its owning thread's
     * queue. The whole list is ONE handoff unit with ONE sequence: its offset
     * is acknowledged as a whole, in binlog order, once every group is written
     * and every earlier unit is acknowledged (spec 09.01 section 3.3).
     *
     * @param convertedRecords The list of {@link ClickHouseStruct} records.
     * @throws InterruptedException if enqueueing a group is interrupted.
     */
    private void appendToRecordsWithHashRouting(List<ClickHouseStruct> convertedRecords)
            throws InterruptedException {
        // Group records by routing key (database.table), preserving each
        // group's binlog order. Insertion-ordered so enqueueing is deterministic.
        Map<String, List<ClickHouseStruct>> routingGroups = new java.util.LinkedHashMap<>();

        for (ClickHouseStruct record : convertedRecords) {
            String routingKey = RoutedBatch.createRoutingKey(record.getTopic());
            routingGroups.computeIfAbsent(routingKey, k -> new ArrayList<>()).add(record);
        }

        // Register the unit with ALL its groups BEFORE any group is visible to
        // a worker: an idle worker can finish its group before a busy worker
        // has dequeued its sibling, and the unit must already exist -- with the
        // sibling counted as unwritten -- when that happens.
        long sequence = DebeziumOffsetManagement.registerHandoff(convertedRecords,
                new ArrayList<>(routingGroups.values()));

        // Enqueue each group on its OWNING thread's queue.
        for (Map.Entry<String, List<ClickHouseStruct>> entry : routingGroups.entrySet()) {
            String routingKey = entry.getKey();
            List<ClickHouseStruct> batch = entry.getValue();

            // Calculate which thread owns this table. The same table always maps
            // to the same thread, so its batches are drained in FIFO order.
            int threadId = RoutedBatch.calculateThreadId(routingKey, this.threadPoolSize);
            String tableName = RoutedBatch.extractTableName(batch.get(0).getTopic());
            LinkedBlockingQueue<RoutedBatch> queue = this.routedQueues.get(threadId);

            synchronized (queue) {
                int remainingCapacity = queue.remainingCapacity();
                int currentSize = queue.size();
                int totalCapacity = remainingCapacity + currentSize;
                if (totalCapacity > 0 && currentSize >= (0.9 * totalCapacity)) {
                    log.warn("Routed queue {} is at 90% capacity! Current size: {}, Total capacity: {}",
                            threadId, currentSize, totalCapacity);
                }
                if (remainingCapacity == 0) {
                    log.warn("Routed queue {} is full! Current size: {}, Total capacity: {}",
                            threadId, currentSize, totalCapacity);
                }
                // A failure here (InterruptedException) propagates: the unit
                // stays outstanding, nothing is acknowledged, the engine stops,
                // and a restart redelivers from the last committed offset.
                queue.put(new RoutedBatch(batch, threadId, tableName, sequence));
                log.debug("Routed {} records for table {} to thread {} as sequence {}",
                        batch.size(), tableName, threadId, sequence);
            }
        }
    }




    /**
     * Assigns the next ReplacingMergeTree {@code _version} for a record whose source
     * timestamp is {@code recordTs} (ms) and whose source-log position is
     * {@code position} ({@code null} when the record carries none).
     *
     * <p>The emitted value keeps the 2.8.0 formula, {@code ts * 1_000_000 + counter},
     * so upgrades AND downgrades remain safe:</p>
     * <ul>
     *   <li>The counter is keyed on the source commit clock: it resets to
     *   {@link #SEQUENCE_START} only when the clock advances by more than one second
     *   past {@link #sequenceAnchorTs}, and the anchor never moves backward - so it is
     *   kept across binlog rotations and a redelivered older event cannot re-arm the
     *   reset (the duplicate-{@code _version} race).</li>
     *   <li>The first record after start/resume seeds the counter at
     *   {@link #SEQUENCE_START_INITIAL} (500m), so events re-published from the last
     *   committed offset rank strictly below any pre-restart write of the same source
     *   second (which carried counters in the 1000m range).</li>
     *   <li>A FIRST delivery - a position above {@link #sequenceHighWaterPosition} -
     *   committed after every event already versioned in this run, so its timestamp
     *   component is floored at {@link #sequenceMaxSourceTs}. On MySQL
     *   {@code source.ts_ms} is the statement time, not the commit time: a long or
     *   concurrent transaction reaches the binlog AFTER transactions that committed
     *   while it was open, carrying an OLDER timestamp. Versioning it by that older
     *   timestamp put it below the earlier write of the same key whenever the counter
     *   had been reset in between, and ReplacingMergeTree discarded the newer row.</li>
     *   <li>A redelivery - a position at or below the mark - or a row without a
     *   position keeps the source-timestamp anchored assignment of issue #1346
     *   unchanged: identical on every redelivery, so a replayed DELETE can never
     *   out-rank the later re-INSERT.</li>
     *   <li>After a restart the mark is empty and the floor is seeded from the durable
     *   high-water mark ({@link #seedVersionFloor}), so the events Debezium re-publishes
     *   are first deliveries to the new run, clamped above everything the previous run
     *   wrote (spec 02.04 section 3.2).</li>
     * </ul>
     *
     * @param recordTs source timestamp of the record in ms (envelope timestamp for
     *                 records without one)
     * @param position source-log position of the record, or {@code null}
     * @return the {@code _version} to store with the record
     */
    static synchronized long nextSequenceNumber(long recordTs, SourcePosition position) {
        return nextVersionAssignment(recordTs, position).sequenceNumber;
    }

    /**
     * The outcome of one step of the version sequence: the sequence-domain
     * {@code _version} and the clamped timestamp it was built from. The timestamp
     * is what the GTID (snowflake) version must use instead of the raw statement
     * time, so that the commit-order floor governs both paths (spec 02.01 section 3.1).
     */
    static final class VersionAssignment {
        /** {@code effectiveTs * 1_000_000 + counter}. */
        final long sequenceNumber;
        /** The record's source timestamp, clamped up to the floor when a first delivery. */
        final long effectiveTs;

        VersionAssignment(long sequenceNumber, long effectiveTs) {
            this.sequenceNumber = sequenceNumber;
            this.effectiveTs = effectiveTs;
        }
    }

    /**
     * Same assignment as {@link #nextSequenceNumber}, also returning the clamped
     * timestamp. See that method for the rules.
     *
     * @param recordTs source timestamp of the record in ms
     * @param position source-log position of the record, or {@code null}
     * @return the version and the effective timestamp it encodes
     */
    static synchronized VersionAssignment nextVersionAssignment(long recordTs, SourcePosition position) {
        if (sequenceAnchorTs == 0L) {
            sequenceAnchorTs = recordTs;
            sequenceNumber = SEQUENCE_START_INITIAL;
        }
        long effectiveTs = recordTs;
        // A position is comparable with the mark only inside one binary log. After
        // a log basename change (log_bin reconfigured, failover to a differently
        // named log, RESET MASTER with the engine re-created in this JVM) the
        // prefix order is string order and would have classified the whole new
        // log as a redelivery ("binlog" < "mysql-bin"), never clamping it. The
        // first position of a differently named log is a first delivery and
        // becomes the mark (spec 01.02 section 3.1.1, 02.02 section 3.1).
        if (position != null
                && (sequenceHighWaterPosition == null
                        || !position.sameLog(sequenceHighWaterPosition)
                        || position.compareTo(sequenceHighWaterPosition) > 0)) {
            if (sequenceHighWaterPosition != null && !position.sameLog(sequenceHighWaterPosition)) {
                log.warn("Binary log identity changed: high-water position {} is replaced by {} from a "
                        + "differently named log; its first record is versioned as a first delivery",
                        sequenceHighWaterPosition, position);
            }
            sequenceHighWaterPosition = position;
            if (effectiveTs < sequenceMaxSourceTs) {
                effectiveTs = sequenceMaxSourceTs;
            }
        }
        // Every record that can move the anchor (and so reset the counter) also
        // raises the floor - including rows without a position. Otherwise the
        // counter reset would happen without the floor following it, and the
        // next late first delivery would again be versioned in its own older
        // second. Control records never reach this method (see the dispatch loop).
        if (effectiveTs > sequenceMaxSourceTs) {
            sequenceMaxSourceTs = effectiveTs;
        }
        int diff = (int) ((effectiveTs - sequenceAnchorTs) / 1000);
        if (diff > 1) {
            sequenceNumber = SEQUENCE_START;
            sequenceAnchorTs = effectiveTs;
        } else {
            sequenceNumber++;
        }
        return new VersionAssignment(effectiveTs * 1_000_000L + sequenceNumber, effectiveTs);
    }

    /**
     * Seeds the version floor from a high-water version at or above everything the
     * previous run handed to the writers (spec 02.02 section 3.5): the floor becomes
     * {@code floorDiv(highWaterVersion, 1_000_000) + 1}, the first whole-millisecond
     * slot whose versions all exceed it. Because a first delivery is versioned at
     * least {@code floor * 1_000_000 + 1}, every first delivery of this run then ranks
     * above every version of the previous run ({@code Replication.VersionFloor
     * .restart_boundary}). The floor is only ever raised; the anchor and counter keep
     * their start-of-run rules.
     *
     * @param highWaterVersion the persisted high-water version; {@code <= 0} is ignored
     * @return the floor in force after the call
     */
    static synchronized long seedVersionFloor(long highWaterVersion) {
        if (highWaterVersion <= 0) {
            return sequenceMaxSourceTs;
        }
        return raiseVersionFloor(VersionHighWaterMark.sequenceFloor(highWaterVersion));
    }

    /**
     * Raises the version floor to {@code floorMs} if it is higher than the floor in
     * force; never lowers it.
     *
     * @param floorMs the floor in ms
     * @return the floor in force after the call
     */
    static synchronized long raiseVersionFloor(long floorMs) {
        if (floorMs > sequenceMaxSourceTs) {
            sequenceMaxSourceTs = floorMs;
        }
        return sequenceMaxSourceTs;
    }

    /**
     * Establishes the durable high-water mark for this connector and seeds the
     * version floor from it -- or, when no mark exists yet (first start after an
     * upgrade, a ClickHouse replica that never saw this connector), from
     * {@code max(_version)} over the target tables (spec 02.02 section 3.5). Never
     * throws: a mark that cannot be read leaves this start unseeded, logged at
     * ERROR, and the mark still guards every handoff through {@link #coverAssignedVersion}.
     *
     * @param props  the Debezium properties (offset table name, database include list)
     * @param config the connector configuration
     */
    private void seedVersionFloorFromDurableMark(Properties props, ClickHouseSinkConnectorConfig config) {
        String offsetTable = props.getProperty(
                ClickHouseSinkConnectorConfigVariables.OFFSET_STORAGE_TABLE_NAME.toString());
        if (offsetTable == null || offsetTable.isBlank()) {
            log.error("{} is not set; the version floor cannot be persisted or seeded, so a restart "
                    + "can invert versions across the boundary (spec 02.02 section 3.5)",
                    ClickHouseSinkConnectorConfigVariables.OFFSET_STORAGE_TABLE_NAME);
            return;
        }
        VersionHighWaterMark mark = VersionHighWaterMark.forOffsetTable(this::systemConnection, offsetTable);
        this.versionHighWaterMark = mark;
        try {
            mark.ensureTable();
            long persisted = mark.load();
            long now = System.currentTimeMillis();
            String source = null;
            long floor = 0L;
            if (persisted > 0) {
                long candidate = VersionHighWaterMark.sequenceFloor(persisted);
                if (VersionHighWaterMark.isPlausibleFloor(candidate, now)) {
                    floor = seedVersionFloor(persisted);
                    source = "the persisted high-water version " + persisted;
                } else {
                    log.error("The persisted high-water version {} in {} decodes to floor {} ms, which is "
                            + "not a plausible timestamp; ignoring it", persisted, mark.qualifiedTableName(),
                            candidate);
                }
            }
            if (source == null) {
                long scanned = mark.scanTargets(VersionHighWaterMark.targetDatabases(props, config),
                        com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.VERSION_COLUMN);
                if (scanned > 0) {
                    floor = raiseVersionFloor(scanned);
                    source = "max(" + com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.VERSION_COLUMN + ") over the target tables";
                }
            }
            if (source == null) {
                log.warn("The version floor is UNSEEDED for this start: no row in {} and nothing usable in "
                        + "the target tables. Rows versioned in this run may rank below rows of a "
                        + "previous run for the lag at start (spec 02.06 section 6.2); the mark is "
                        + "written from the first handoff on.", mark.qualifiedTableName());
            } else {
                log.info("Version floor seeded to {} ms from {}", floor, source);
            }
        } catch (Exception e) {
            log.error("Could not establish the version high-water mark in {}; this start is unseeded and "
                    + "the first handoff will retry the mark before any row is written",
                    mark.qualifiedTableName(), e);
        }
    }

    private Connection systemConnection() {
        return this.writer != null ? this.writer.getConnection() : this.systemDbConnection;
    }

    /** Debezium's snapshot mode property; unset means the default {@code initial}. */
    static final String SNAPSHOT_MODE = "snapshot.mode";

    /**
     * Snapshot modes that read NO data rows. Every other mode ({@code initial},
     * {@code initial_only}, {@code always}, {@code when_needed}, and
     * {@code configuration_based} / {@code custom} when they snapshot data) writes
     * snapshot rows versioned on the sequence path.
     */
    static final Set<String> NO_DATA_SNAPSHOT_MODES = new HashSet<>(Arrays.asList(
            "never", "no_data", "schema_only", "recovery", "schema_only_recovery"));

    /**
     * Whether {@code snowflake.id=false} is combined with a snapshot mode that reads
     * data (spec 02.06 section 3.2.1). With the raw GTID transaction number as the
     * version (order 1e6-1e10) and snapshot rows on the sequence path (order 1.7e18),
     * every streamed change of a snapshotted key loses to the snapshot row,
     * permanently.
     *
     * @param props  the Debezium properties ({@code snapshot.mode}).
     * @param config the connector configuration ({@code snowflake.id}).
     * @return true if the combination is present.
     */
    static boolean rawGtidVersioningWithDataSnapshot(Properties props, ClickHouseSinkConnectorConfig config) {
        if (config == null || config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString())) {
            return false;
        }
        String mode = props == null ? null : props.getProperty(SNAPSHOT_MODE);
        if (mode == null || mode.trim().isEmpty()) {
            return true; // Debezium's default is `initial`: a data snapshot.
        }
        return !NO_DATA_SNAPSHOT_MODES.contains(mode.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Logs {@link #rawGtidVersioningWithDataSnapshot} at ERROR, naming both keys, the
     * consequence and the remediation. Never throws: under Invariant I11 an existing
     * configuration must keep starting after an upgrade.
     *
     * @param props  the Debezium properties.
     * @param config the connector configuration.
     */
    static void warnIfRawGtidVersioningWithDataSnapshot(Properties props, ClickHouseSinkConnectorConfig config) {
        if (!rawGtidVersioningWithDataSnapshot(props, config)) {
            return;
        }
        String mode = props == null ? null : props.getProperty(SNAPSHOT_MODE);
        log.error("{}=false with {}={} versions streamed GTID rows with the raw transaction number, "
                + "which ranks BELOW every snapshot row's sequence version: after the snapshot, every "
                + "UPDATE and DELETE of a snapshotted key is discarded by ReplacingMergeTree, with row "
                + "counts still matching. Set {}=true (the default) or use a no-data snapshot mode ({}). "
                + "Continuing because an existing configuration must keep starting (spec 02.06 "
                + "section 3.2.1).",
                ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID, SNAPSHOT_MODE,
                mode == null ? "<unset, default initial>" : mode,
                ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID, NO_DATA_SNAPSHOT_MODES);
    }

    /**
     * Makes the durable horizon cover a version just assigned to a row, before the
     * row is handed to the writers. Without a mark (unit tests driving the batch
     * handler directly) this is logged once and skipped.
     *
     * @param sequenceVersion the sequence-domain version assigned to the row
     * @throws IllegalStateException if the horizon could not be persisted; the batch
     *                               fails and the engine stops rather than hand off
     *                               a row the next start could not order above
     */
    private void coverAssignedVersion(long sequenceVersion) {
        VersionHighWaterMark mark = this.versionHighWaterMark;
        if (mark == null) {
            if (UNSEEDED_WARNED.compareAndSet(false, true)) {
                log.warn("Versioning rows without a durable high-water mark (no setup ran); a restart "
                        + "of this process will not be seeded (spec 02.02 section 3.5)");
            }
            return;
        }
        mark.cover(sequenceVersion);
    }

    /**
     * Adds a version (sequence number) to every record.
     * <p>
     * Same assignment as the streaming loop - see {@link #nextSequenceNumber}: anchored
     * on the SOURCE commit timestamp when available (redelivery-stable, issue #1346),
     * falling back to the processing timestamp for records without one, and floored at
     * the newest first-delivery timestamp for records whose log position proves them
     * to be first deliveries.
     * </p>
     *
     * @param chStructs The list of {@link ClickHouseStruct} records.
     */
    public static void addVersion(List<ClickHouseStruct> chStructs) {
        if (chStructs.isEmpty()) {
            return;
        }
        for (ClickHouseStruct chStruct : chStructs) {
            long recordTs = chStruct.getTs_ms() > 0
                    ? chStruct.getTs_ms() : chStruct.getDebezium_ts_ms();
            VersionAssignment assignment = nextVersionAssignment(recordTs, chStruct.getSourcePosition());
            chStruct.setSequenceNumber(assignment.sequenceNumber);
            chStruct.setVersionTs(assignment.effectiveTs);
        }
    }
}
