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
import com.altinity.clickhouse.sink.connector.db.DDLSchemaChangeWaiter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.ErrorLogger;
import com.altinity.clickhouse.sink.connector.db.TableReplicationFreezeManager;
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
import java.util.concurrent.atomic.AtomicLong;
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
    static public volatile boolean isNewReplacingMergeTreeEngine = true;

    /**
     * Executor service for single-thread Debezium event processing.
     */
    final ExecutorService singleThreadDebeziumEventExecutor;

    /**
     * Executor that performs engine restarts after a failure.
     *
     * <p>Deliberately separate from {@link #singleThreadDebeziumEventExecutor}.
     * The restart is triggered from the engine's completion callback; running
     * it there, or on the thread that runs the engine, would build the
     * replacement engine underneath the failed one and nest one stack frame
     * per attempt. A dedicated single thread lets the callback return and
     * unwind before the next attempt begins, so retries are sequential and
     * each starts from a clean stack.</p>
     */
    final ExecutorService debeziumRestartExecutor;

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
        debeziumRestartExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sink connector Debezium Restart Thread");
            // Daemon: a pending back-off sleep must never hold up JVM shutdown.
            thread.setDaemon(true);
            return thread;
        });
        this.debeziumJdbcStorageOperations = new DebeziumJdbcStorageOperations();
    }

    /**
     * Maximum number of retries for Debezium setup and other operations.
     * Default value, can be overridden by errors.max.retries configuration.
     */
    public static volatile int MAX_RETRIES = 10;

    /**
     * Sleep time (in milliseconds) between retries.
     */
    public static int SLEEP_TIME = 10000;

    /**
     * This field tracks how many times an operation has been retried.
     */
    public volatile int numRetries = 0;

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
     *
     * <p>Must be an AtomicLong, not a primitive: this counter feeds
     * ClickHouseStruct.sequenceNumber, which becomes the {@code _version}
     * used by ReplacingMergeTree to decide which row version wins. It is
     * incremented from the Debezium change-event thread while being read
     * elsewhere, so a non-atomic read-modify-write could hand two records
     * the same version and let RMT silently collapse them, or emit a torn
     * value on a 32-bit-split long read. Every call site below uses
     * set()/get()/incrementAndGet().</p>
     */
    private final AtomicLong sequenceNumber = new AtomicLong(SEQUENCE_START);

    /**
     * Guards the counter together with its source-timestamp anchor.
     *
     * <p>Assigning a version is a check-and-act over TWO pieces of state: the
     * time anchor is read to decide whether the second boundary was crossed,
     * and only then is the counter reset or incremented. An atomic counter
     * alone cannot make that pair consistent — two threads crossing the same
     * boundary would both reset and emit an identical {@code _version},
     * which ReplacingMergeTree would silently collapse. Every read and write
     * of the counter/anchor pair happens while holding this lock.</p>
     */
    private final Object sequenceLock = new Object();

    /**
     * Sentinel meaning the source-timestamp anchor has not been set yet.
     */
    private static final long UNANCHORED = Long.MIN_VALUE;

    /**
     * Source-commit-timestamp anchor the sequence counter is measured from.
     *
     * <p>Instance state, deliberately NOT a per-call local. Re-seeding it from
     * record[0] on every call made the reset branch reachable by every batch
     * that spanned a time gap, and each such batch assigned the same constant
     * {@link #SEQUENCE_START} — so two concurrent batches produced an
     * identical {@code _version} and ReplacingMergeTree collapsed one of the
     * rows. Persisting the anchor means the counter re-anchors once when the
     * source clock advances, and later records at that timestamp increment.
     * Guarded by {@link #sequenceLock}.</p>
     */
    private long sequenceStartTime = UNANCHORED;

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

                    // No batch-level anchor seeding here. The anchor is seeded
                    // inside nextSequenceNumber, under the same lock that
                    // guards the counter: an anchor established per batch lets
                    // two batches independently decide to reset and hand their
                    // first record the same value. Anchoring is now keyed on
                    // the per-record SOURCE timestamp (#1346), so there is also
                    // no correct single timestamp to anchor a whole batch on.
                    List<ClickHouseStruct> batch = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        ChangeEvent<SourceRecord, SourceRecord> record = list.get(i);
                        boolean lastRecordInBatch = false;
                        if (i == list.size() - 1) {
                            lastRecordInBatch = true;
                        }
                        // Anchor the version to the SOURCE commit timestamp
                        // (source.ts_ms), not the envelope/processing timestamp.
                        // The source timestamp is identical on every Debezium
                        // re-delivery, so a re-delivered DELETE keeps its
                        // original (lower) _version and can no longer out-rank a
                        // later re-INSERT (#1346).
                        long recordTs = ClickHouseStruct.getSourceTsFromChangeEvent(record);

                        // The VALUE SCHEME stays identical to 2.8.0:
                        //     _version = ts_ms * 1_000_000 + counter
                        // _version decides which row survives a
                        // ReplacingMergeTree merge, so a table already holding
                        // 2.8.0-written rows and a connector emitting a
                        // different scheme put two incomparable magnitudes in
                        // the same column: the larger always wins regardless of
                        // which change actually happened later, and no
                        // downgrade can undo the rows already written. Changing
                        // this formula is a one-way door.
                        //
                        // The ASSIGNMENT goes through nextSequenceNumber, which
                        // does the whole read-modify-write under one lock.
                        // Inline, it was not atomic: the reset branch is a
                        // check-and-act over two pieces of state (read anchor,
                        // decide, reset counter, re-anchor) and the increment
                        // branch did incrementAndGet() then a separate get().
                        // Two batch threads interleaving in either window hand
                        // two different records an identical _version, which
                        // the ReplacingMergeTree then silently collapses to one
                        // row. The counter is keyed exclusively on the source
                        // commit clock -- never on binlog file/position -- so it
                        // is preserved across binary log rotations, and the
                        // anchor is global so re-delivered older events cannot
                        // re-arm the reset.
                        long recordSequenceNumber = nextSequenceNumber(recordTs);

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
                        if (isDDLRecord(record) && batch.size() > 0) {
                            appendToRecords(new ArrayList<>(batch), config);
                            batch.clear();
                        }

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
                                log.error("Error starting connector: " + message, throwable);
                                if (throwable != null && throwable.getCause() != null &&
                                        throwable.getCause().getLocalizedMessage() != null)
                                    log.error("Error starting connector: Cause: " +
                                            throwable.getCause().getLocalizedMessage());
                                // The engine has stopped. Nothing else restarts it:
                                // setup() calls setupDebeziumEventCapture() exactly once
                                // and returns, so if this callback does not retry, the
                                // connector stays down for the rest of the process and
                                // every subsequent change is silently never replicated.
                                //
                                // Retry must NOT run on this thread. The callback is
                                // invoked from the engine's own completion path, so
                                // calling setupDebeziumEventCapture() inline builds the
                                // next engine underneath the failed one's frame and each
                                // further failure nests again — MAX_RETRIES deep, which
                                // is what previously overflowed the stack. Handing the
                                // restart to a separate single-thread executor lets this
                                // callback return and unwind first, so every attempt
                                // starts from a fresh stack while the retry budget and
                                // back-off are still honoured.
                                // Bound matches upstream (<=), so the retry budget is
                                // unchanged by this PR: MAX_RETRIES+1 attempts.
                                if (numRetries++ <= MAX_RETRIES) {
                                    log.error("Retrying - try number:" + numRetries);
                                    scheduleDebeziumRestart(props, debeziumRecordParserService, config);
                                } else {
                                    log.error("Connector failed after " + numRetries + " retries. "
                                            + "The connector will need to be restarted externally.");
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
     * Rebuilds the Debezium engine after a failure, off the callback thread.
     *
     * <p>Called from the engine's completion callback when the engine has
     * stopped with an error and the retry budget is not yet exhausted. The
     * work is handed to {@link #debeziumRestartExecutor} rather than done
     * inline so that the failing callback returns and its frame unwinds
     * before the replacement engine is constructed; building it inline is
     * what made repeated failures nest one stack frame per attempt.</p>
     *
     * <p>The executor is single-threaded, so restarts are serialised and two
     * failures can never race to build two engines.</p>
     *
     * @param props                       The connector properties.
     * @param debeziumRecordParserService The service to parse change events.
     * @param config                      The ClickHouse sink connector config.
     */
    private void scheduleDebeziumRestart(Properties props,
                                         DebeziumRecordParserService debeziumRecordParserService,
                                         ClickHouseSinkConnectorConfig config) {
        try {
            debeziumRestartExecutor.submit(() -> {
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    // Shutdown requested during back-off: restore the flag and
                    // abandon the restart rather than reconnecting into a
                    // connector that is on its way down.
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while waiting to restart Debezium event capture", e);
                    return;
                }
                try {
                    setupDebeziumEventCapture(props, debeziumRecordParserService, config);
                } catch (Exception e) {
                    // Never rethrow here. This runs on the restart executor, so
                    // an escaping exception would be swallowed into a Future
                    // nobody reads and the failure would go unreported.
                    log.error("Error restarting Debezium event capture", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Expected when stop() has already shut the executor down.
            log.warn("Debezium restart not scheduled - connector is shutting down");
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
            this.records = new LinkedBlockingQueue<>(10000);
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

            // Advisory check: snapshot.mode=initial re-snapshots and wipes offset state,
            // so warn when existing replication state is present. This is WARN-ONLY by
            // default and must stay that way: snapshot.mode=initial is the documented
            // default, so refusing to start on it would break every ordinary restart of
            // an already-replicating connector. Operators who want the hard stop opt in
            // with snapshot.initial.require.confirmation=true.
            String snapshotMode = props.getProperty("snapshot.mode", "");
            if (snapshotMode.equalsIgnoreCase("initial")) {
                validateInitialSnapshotSafety(props, config);
            }

            this.setupProcessingThread(config);
            setupDebeziumEventCapture(props, debeziumRecordParserService, config);
        } else {
            log.info(ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString() +
                    " variable set to true, Replication is skipped, use sink-connector-client to start replication");
        }
    }

    /**
     * Validates that it is safe to run with snapshot.mode=initial.
     * <p>
     * When snapshot.mode is set to "initial", Debezium performs a full re-snapshot
     * which resets offset tracking and re-reads all data from the source database.
     * This is destructive if replication was previously running, as it wipes the
     * stored binlog position and schema history.
     * </p>
     * <p>
     * This method checks whether offset storage or schema history tables already
     * contain data and logs a prominent warning if they do.
     * </p>
     * <p>
     * The check is advisory by default and never blocks startup. {@code initial} is
     * the documented default snapshot mode, so a connector that has been replicating
     * for a while still has it set; failing startup on that condition would turn
     * every routine restart into an outage requiring manual intervention on the host.
     * </p>
     * <p>
     * Deployments that would rather not start at all than re-snapshot can opt in to
     * the hard stop with {@code snapshot.initial.require.confirmation=true}. In that
     * mode a {@code .confirm_initial_snapshot} file in the working directory permits
     * one run and is renamed to {@code .confirm_initial_snapshot.used} so it cannot
     * be re-used on the next restart.
     * </p>
     *
     * @param props  The connector properties.
     * @param config The ClickHouse sink connector configuration.
     * @throws RuntimeException only when {@code snapshot.initial.require.confirmation}
     *                          is enabled, existing state is found, and no confirmation
     *                          file exists.
     */
    private void validateInitialSnapshotSafety(Properties props, ClickHouseSinkConnectorConfig config) {
        log.warn("snapshot.mode=initial detected — checking for existing replication state");

        boolean hasExistingState = false;
        String stateDetails = "";

        try {
            // Check if offset storage table has data
            DebeziumJdbcStorageOperations storageOps = new DebeziumJdbcStorageOperations();
            if (systemDbConnection != null) {
                String status = storageOps.getDebeziumStorageStatus(systemDbConnection, config, props);
                if (status != null && !status.isEmpty() && !status.equals("[]")) {
                    hasExistingState = true;
                    stateDetails = "Offset storage contains existing replication state. ";
                    log.warn("Found existing offset storage data: " + status);
                }
            }
        } catch (Exception e) {
            // If we can't check, it might be a fresh install — allow startup
            log.info("Could not check existing offset state (may be fresh install): " + e.getMessage());
        }

        if (hasExistingState) {
            String warning = "snapshot.mode=initial was requested but existing replication "
                    + "state was found. " + stateDetails
                    + "Running with snapshot.mode=initial will WIPE the current binlog position "
                    + "and re-snapshot ALL data from the source database. "
                    + "If you want to resume replication from where it left off, "
                    + "change snapshot.mode to 'schema_only' or 'when_needed'.";

            // Opt-in only. Defaulting this to fatal would stop a healthy connector from
            // restarting, which is a worse failure than the re-snapshot it guards against.
            boolean requireConfirmation = Boolean.parseBoolean(
                    props.getProperty("snapshot.initial.require.confirmation", "false"));

            if (!requireConfirmation) {
                log.warn(warning);
                log.warn("Proceeding anyway. Set snapshot.initial.require.confirmation=true "
                        + "to refuse startup in this situation.");
                return;
            }

            java.io.File confirmFile = new java.io.File(".confirm_initial_snapshot");
            if (!confirmFile.exists()) {
                String errorMsg = "SAFETY GUARD: " + warning
                        + " snapshot.initial.require.confirmation is enabled, so startup is "
                        + "refused. To confirm this is intentional, create a file named "
                        + "'.confirm_initial_snapshot' in the sink-connector working directory "
                        + "(touch .confirm_initial_snapshot) and restart.";
                log.error(errorMsg);
                throw new RuntimeException(errorMsg);
            } else {
                log.warn("Confirmation file .confirm_initial_snapshot found — proceeding with initial snapshot");
                // Rename the file to prevent accidental re-use on next restart
                java.io.File usedFile = new java.io.File(".confirm_initial_snapshot.used");
                if (!confirmFile.renameTo(usedFile)) {
                    log.warn("Could not rename .confirm_initial_snapshot to .confirm_initial_snapshot.used");
                }
            }
        } else {
            log.info("No existing replication state found — safe to proceed with initial snapshot");
        }
    }

    /**
     * Stops the Debezium engine and shuts down all executor services.
     *
     * @throws IOException If an I/O error occurs during shutdown.
     */
    public void stop() throws IOException {
        // Shut the restart executor down FIRST, and interrupt it: a retry that
        // is mid-back-off would otherwise wake up after the engine has been
        // closed and build a fresh engine on a connector that is shutting down.
        // shutdownNow() both cancels queued restarts and interrupts the sleep.
        try {
            if (this.debeziumRestartExecutor != null) {
                this.debeziumRestartExecutor.shutdownNow();
                this.debeziumRestartExecutor.awaitTermination(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("Error stopping debezium restart executor", e);
        }

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

        try {
            if (this.systemDbConnection != null) {
                this.systemDbConnection.close();
                this.systemDbConnection = null;
            }
        } catch (Exception e) {
            log.error("Error closing system DB connection", e);
        }

        try {
            if (this.replicationHistoryDbConnection != null) {
                this.replicationHistoryDbConnection.close();
                this.replicationHistoryDbConnection = null;
            }
        } catch (Exception e) {
            log.error("Error closing replication history DB connection", e);
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

        // The DDL MUST be parsed before checkIfDDLNeedsToBeIgnored() is consulted.
        // parseSql() is what populates isDropOrTruncate, and the DISABLE_DROP_TRUNCATE
        // guard inside checkIfDDLNeedsToBeIgnored() reads that flag. Calling the guard
        // first made it observe the freshly-initialised `false` on every invocation, so
        // DISABLE_DROP_TRUNCATE=true silently allowed every DROP/TRUNCATE through to
        // ClickHouse. The guard could only ever fail open.
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(writer, config, databaseName);
        mySQLDDLParserService.parseSql(DDL, "", clickHouseQuery, isDropOrTruncate);

        if (checkIfDDLNeedsToBeIgnored(DDL, props, sr, isDropOrTruncate)) {
            log.info("Ignored Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
            // Acknowledge offset even for ignored DDL to prevent replication from
            // getting stuck replaying the same ignored DDL repeatedly.
            // acknowledgeRecords() is a checked-exception method (it commits
            // offsets under the shared lock); on interrupt, restore the flag and
            // return WITHOUT acknowledging rather than swallowing it — a silent
            // failure here would advance the offset past a DDL whose commit
            // never completed.
            try {
                DebeziumOffsetManagement.acknowledgeRecords(
                        recordCommitter, cdcRecord, lastRecordInBatch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while acknowledging ignored DDL: " + DDL, e);
            }
            return;
        }


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

        // Freeze replication for this table BEFORE executing the ALTER, so no
        // batch insert thread can write rows against the stale (pre-ALTER)
        // column cache while the schema change is in flight. The freeze is held
        // until the destination schema is visible, the cache has been
        // invalidated, and (on the insert side) source-vs-destination integrity
        // is confirmed. This closes the race behind GitHub issue #1222, where a
        // column added by an in-flight ALTER replicated as NULL for historical
        // rows. Freeze is best-effort: if the table name cannot be resolved we
        // proceed without it (the DDL waiter still runs).
        String ddlFreezeTable = null;
        try {
            // Pass the DDL text: getTableName now resolves the table from the
            // statement itself. The old record-key lookup ALWAYS returned null
            // (Debezium's SchemaChangeKey carries only databaseName), which
            // would have made this freeze silently never engage.
            String tableName = getTableName(sr, DDL);
            if (tableName != null) {
                // The freeze key MUST be built the same way the batch consumers
                // build their lookup key — i.e. with clickhouse.database.override.map
                // applied. Freezing "sourcedb.tbl" while the insert path awaits
                // "destdb.tbl" means the freeze never blocks anything, which is
                // exactly the stale-column-cache race it exists to prevent.
                ddlFreezeTable =
                        resolveInvalidationDatabaseName(databaseName, config)
                                + "." + tableName;
                TableReplicationFreezeManager.getInstance().freeze(ddlFreezeTable);
            }
        } catch (Exception e) {
            log.warn("Could not resolve table for DDL freeze: " + DDL, e);
        }

        try {
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
                    // Both sides of the merge are kept, because each fixes a
                    // DIFFERENT half of the invalidation key:
                    //  - 2.10.0 fixes the DATABASE half: the key must be the
                    //    override-mapped DESTINATION database (see
                    //    resolveInvalidationDatabaseName), which is what the
                    //    batch consumers use. Using the raw `databaseName`
                    //    here skips clickhouse.database.override.map, so the
                    //    key never matches the consumer's.
                    //  - develop fixes the TABLE half: resolve every affected
                    //    table, and never silently skip a table-scoped DDL.
                    // 2.10.0 resolves tables from the record value's
                    // tableChanges array (so multi-table DDL invalidates all of
                    // them); develop resolves it from the DDL text. Try the
                    // value first, fall back to the text, and only then report.
                    String invalidationDatabaseName =
                            resolveInvalidationDatabaseName(databaseName, config);
                    // Two-arg resolver from 2.10.0: reads the record value's
                    // tableChanges array (so a multi-table DDL invalidates all
                    // of them) and also returns the SOURCE name of a rename,
                    // whose cached writer points at a table that no longer
                    // exists (#1384).
                    List<String> affectedTables = getTableNamesFromDDL(sr, DDL);
                    if (affectedTables.isEmpty()) {
                        // Fall back to parsing the DDL text. The value-based
                        // resolver returns nothing for DDL that carries no
                        // tableChanges entry, and skipping invalidation there
                        // is exactly the stale-cache divergence below.
                        String parsedTableName = getTableName(sr, DDL);
                        if (parsedTableName != null) {
                            affectedTables = java.util.Collections
                                    .singletonList(parsedTableName);
                        }
                    }
                    for (String tableName : affectedTables) {
                        CacheInvalidationManager.getInstance()
                                .invalidateTable(invalidationDatabaseName + "." + tableName);
                    }
                    if (affectedTables.isEmpty()) {
                        if (isTableScopedDDL(DDL)) {
                            // Never silently skip invalidation for a TABLE-scoped
                            // DDL: a cached DbWriter that is not rebuilt keeps
                            // writing the pre-DDL column list, so the new column
                            // is dropped from every subsequent INSERT and
                            // ClickHouse diverges from MySQL permanently.
                            // Surface it loudly instead.
                            log.error("Could not determine the table affected by DDL, so the "
                                    + "DbWriter schema cache could NOT be invalidated. Subsequent "
                                    + "inserts may use a stale column list and silently diverge "
                                    + "from the source. DDL: {}", DDL);
                        } else {
                            // Database-scoped DDL (CREATE/DROP/ALTER DATABASE) has
                            // no table subject and no DbWriter cache to invalidate.
                            // Expected, not an error.
                            log.debug("DDL is not table-scoped; no schema cache to invalidate. "
                                    + "DDL: {}", DDL);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error invalidating cache for DDL: " + DDL, e);
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
        } finally {
            // Always unfreeze, even if the DDL failed/threw, so the table is
            // never left frozen forever. After a successful ALTER the cache is
            // already marked for invalidation; the next insert rebuilds it and
            // runs the source-vs-destination integrity check. On failure we
            // unfreeze so inserts (which would also have failed against the old
            // schema) are not blocked indefinitely -- the error was already
            // logged to the error table above.
            if (ddlFreezeTable != null) {
                TableReplicationFreezeManager.getInstance().unfreeze(ddlFreezeTable);
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
        if (sr != null && sr.key() instanceof Struct) {
            String recordDbName = (String) ((Struct) sr.key()).get("databaseName");
            if (recordDbName != null && !recordDbName.isEmpty()) {
                return recordDbName;
            }
        }
        return "system";
    }

    /**
     * Resolves the database half of a schema-cache key, applying
     * {@code clickhouse.database.override.map} exactly the way the batch
     * consumers ({@code ClickHouseBatchRunnable} /
     * {@code ClickHouseBatchWriter}) do when they build their
     * {@code "database.table"} lookup key.
     *
     * <p>The consumer's key is
     * {@code override(replicationHistoryEnabled ? historyDb : sourceDb)}, so
     * BOTH steps must be applied here. Passing the caller's already
     * history-adjusted {@code databaseName} covers the first step; this method
     * applies the override map on top. A key built any other way silently
     * never matches the consumer's key, so the invalidation (or freeze) would
     * be registered against a table nobody ever looks up — the cache would
     * appear to be invalidated while every worker kept its stale column
     * list.</p>
     *
     * @param databaseName The caller's database name, already adjusted for
     *                     replication history.
     * @param config       The connector configuration.
     * @return The destination database name to use in the cache key.
     */
    private String resolveInvalidationDatabaseName(
            String databaseName, ClickHouseSinkConnectorConfig config) {
        String resolved = databaseName;
        String overrideMapConfig = config.getString(
                ClickHouseSinkConnectorConfigVariables
                        .CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
        if (overrideMapConfig != null) {
            try {
                Map<String, String> databaseOverrideMap =
                        Utils.parseSourceToDestinationDatabaseMap(overrideMapConfig);
                if (databaseOverrideMap.containsKey(resolved)) {
                    resolved = databaseOverrideMap.get(resolved);
                }
            } catch (Exception e) {
                // Log LOUDLY rather than swallowing: an unparseable override map
                // means the key computed here may not match the one the batch
                // consumers use, so a cache invalidation or replication freeze
                // could be registered against a table nobody looks up. The
                // unmapped name is still the best available key, but an operator
                // must see that the override map is broken.
                log.error("Could not parse {}='{}' while resolving the schema-cache "
                                + "key for database '{}'. Falling back to the unmapped "
                                + "name; if an override is configured for it, DDL cache "
                                + "invalidation and the replication freeze will NOT match "
                                + "the insert path and inserts may use a stale schema.",
                        ClickHouseSinkConnectorConfigVariables
                                .CLICKHOUSE_DATABASE_OVERRIDE_MAP,
                        overrideMapConfig, databaseName, e);
            }
        }
        return resolved;
    }

    /**
     * Returns true when the DDL operates on a table (and therefore has a DbWriter
     * schema cache that must be invalidated). Database-scoped DDL such as
     * CREATE/DROP/ALTER DATABASE has no table subject, so a missing table name
     * there is expected rather than an error worth alerting on.
     *
     * @param ddl the DDL statement text.
     * @return true if the statement is table-scoped.
     */
    private boolean isTableScopedDDL(String ddl) {
        if (ddl == null) {
            return false;
        }
        return ddl.toUpperCase(java.util.Locale.ROOT).contains("TABLE");
    }

    /**
     * Function to get the table name affected by a schema-change (DDL) record.
     *
     * <p>The table name is resolved from the DDL text, NOT from the record key.
     * Debezium's {@code SchemaChangeKey} struct carries exactly one field,
     * {@code databaseName} — there is no {@code tableName} field on it. Reading
     * "tableName" from the key therefore always failed and returned null, which
     * meant the DDL cache invalidation below was never registered for any table,
     * leaving every cached DbWriter serving a stale column list forever.</p>
     *
     * @param sr  The source record (used only as a fallback source of the value).
     * @param ddl The DDL statement text.
     * @return The table name, or null if it could not be determined.
     */
    private String getTableName(SourceRecord sr, String ddl) {
        String tableName = MySQLDDLParserService.extractTableName(ddl);
        if (tableName != null && !tableName.isEmpty()) {
            return tableName;
        }
        // Fall back to the record key in case a future connector version does
        // expose a table name there.
        if (sr != null && sr.key() instanceof Struct) {
            try {
                String keyTableName = (String) ((Struct) sr.key()).get("tableName");
                if (keyTableName != null && !keyTableName.isEmpty()) {
                    return keyTableName;
                }
            } catch (Exception e) {
                log.debug("tableName field not present in source record key");
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
log.error("SCHEMA EMPTY - cannot process record without schema. Record: {}", record.toString());
                return null;
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
                    // In single.threaded mode there is no batch executor:
                    // setupProcessingThread() creates singleThreadedWriter and
                    // returns, so this.executor stays null and records are
                    // written synchronously on this same thread — DDL and DML
                    // are already serialized and there is nothing to pause.
                    // Upstream calls pause() unconditionally and the resulting
                    // NullPointerException was silently swallowed by the
                    // log-only catch below (skipping the DDL); now that the
                    // catch fails loudly, the null guard is required — without
                    // it the FIRST DDL in single-threaded mode stops the
                    // engine and cascades across the whole suite.
                    pauseBatchExecutor();
                    try {
                        Map<String, Object> sourceObjStruct = new ClickHouseConverter().convertValue(sr);

                        ClickHouseStruct ddlStruct = new ClickHouseStruct();
                        ddlStruct.setAdditionalMetaData(sourceObjStruct);
                        ddlStruct.setSequenceNumber(sequenceNumber);
                        performDDLOperation(DDL, props, sr, config, recordCommitter, record, lastRecordInBatch, ddlStruct);
                    } finally {
                        // Always resume executor — leaving it paused permanently stalls replication
                        resumeBatchExecutor();
                    }
                }
            } else {
                chStruct = debeziumRecordParserService.parse(record, recordCommitter, lastRecordInBatch);
                if (chStruct == null) {
                    log.warn("Record parser returned null — skipping record");
                    return null;
                }
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
            log.error("Exception processing record: " + record.toString(), e);
            throw new RuntimeException("Failed to process CDC record", e);
        }

        return chStruct;
    }

    /**
     * Pauses the batch executor before a DDL is applied, if one exists.
     *
     * <p>In single.threaded mode {@link #setupProcessingThread} creates a
     * {@code singleThreadedWriter} instead of a batch executor, so
     * {@code this.executor} is null and DML is written synchronously on the
     * Debezium event thread — the same thread that processes DDL — so there
     * is no concurrent batch thread to pause and this is safely a no-op.</p>
     */
    @VisibleForTesting
    void pauseBatchExecutor() {
        if (this.executor != null) {
            this.executor.pause();
        }
    }

    /**
     * Resumes the batch executor after a DDL completes, if one exists.
     * No-op in single.threaded mode (see {@link #pauseBatchExecutor}).
     */
    @VisibleForTesting
    void resumeBatchExecutor() {
        if (this.executor != null) {
            this.executor.resume();
        }
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
                String snapshotMode = String.valueOf(sr.sourceOffset().get("snapshot"));
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

        // Check DDL against the built-in regex patterns from
        // IgnoreDDLRegexLoader. Record the statement here too: a DDL ignored by
        // a built-in pattern is just as ignored as one matched by the
        // user-configured IGNORE_DDL_REGEX above, and getLastIgnoredDDL() is
        // the only way an operator can see which statement was skipped.
        // Without this the two paths disagree — making a built-in pattern
        // case-insensitive silently stopped a lowercase DDL from ever being
        // reported, because the built-in branch claimed it first and returned
        // without recording it.
        if (checkDDLAgainstRegexPatterns(DDL)) {
            lastIgnoredDDL = DDL;
            log.info("Ignoring DDL: " + DDL
                    + " as it matches a built-in ignore pattern");
            return true;
        }

        // Snapshot-phase DDL is exempt from the DISABLE_DROP_TRUNCATE guard. During a
        // snapshot Debezium replays schema bootstrap as a drop-and-recreate
        // pair for every captured table; when the operator has enabled
        // snapshot DDL, suppressing only the drop half leaves any
        // pre-existing destination table in place and the paired CREATE then
        // fails with TABLE_ALREADY_EXISTS, stalling the DDL stream in a retry
        // loop. The guard exists to protect the destination from destructive
        // STREAMING DDL (an accidental drop on the source mid-replication),
        // which is still blocked below. Snapshot DDL that the operator has NOT
        // enabled never reaches ClickHouse anyway via the final check in this
        // method.
        String disableDropAndTruncateProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
        if (disableDropAndTruncateProperty != null && disableDropAndTruncateProperty.equalsIgnoreCase("true")
                && isDropOrTruncate.get() == true && !isSnapshotDDL) {
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
     * Seeds the shared sequence anchor for a new batch.
     *
     * <p>Runs under {@link #sequenceLock} because the anchor and the counter
     * are a single unit of state: a thread that re-anchors without holding the
     * counter's lock can race a thread deciding whether to reset.</p>
     *
     * <p>The anchor only ever moves <b>forward</b>. The anchor is shared by all
     * batch threads, but each batch seeds it from its own first record, so with
     * an unconditional assignment a batch carrying an older timestamp can drag
     * the anchor <em>backward</em> past a point another thread has already
     * passed. That re-arms the {@code diff > 1} reset branch for timestamps
     * already in use, and the counter is reset to the constant
     * {@link #SEQUENCE_START} a second time — so two different records at the
     * same source timestamp receive an identical {@code _version} and
     * ReplacingMergeTree silently discards one of them. Measured
     * single-threaded, with the anchor re-seeded backward between assignments:
     * 199 duplicate versions in 200 records, and the second value is
     * <em>lower</em> than the first, so an older row can also outrank a newer
     * one.</p>
     *
     * <p>The guard changes no emitted value for a non-decreasing source clock —
     * a MySQL binlog's commit timestamps only advance, so {@code ts > anchor}
     * holds on every ordinary seed and the assignment is identical to the
     * unconditional one. Verified by replaying an ascending stream through both
     * forms and comparing the full output: byte-for-byte equal. The 2.8.0
     * {@code _version} format is therefore untouched (pinned by
     * {@code Version280CompatibilityTest}); only an out-of-order re-seed, which
     * previously corrupted the sequence, is now ignored.</p>
     *
     * @param debeziumTsMs the Debezium timestamp of the batch's first record.
     */
    void seedSequenceAnchor(long debeziumTsMs) {
        synchronized (sequenceLock) {
            if (sequenceStartTime == UNANCHORED || debeziumTsMs > sequenceStartTime) {
                sequenceStartTime = debeziumTsMs;
            }
        }
    }

    /**
     * Produces the next {@code _version} value for a record.
     *
     * <p>The returned value is <b>bit-for-bit the 2.8.0 formula</b>:</p>
     * <pre>    _version = debezium_ts_ms * 1_000_000 + counter</pre>
     * <p>with the counter reset to {@link #SEQUENCE_START} when the Debezium
     * clock advances more than one second past the current anchor, and
     * incremented otherwise. The formula is deliberately unchanged, because
     * {@code _version} decides which row survives a ReplacingMergeTree merge:
     * a table that already holds rows written by 2.8.0 cannot also hold rows
     * from a different scheme without the larger magnitude winning every merge
     * regardless of which change actually happened later — and no downgrade
     * can undo rows already written.</p>
     *
     * <p>What is fixed is the <em>assignment</em>, not the value. The previous
     * code read, modified and wrote the shared counter without holding a lock,
     * in two separate windows: the reset branch is a check-and-act across the
     * anchor and the counter, and the increment branch called
     * {@code incrementAndGet()} and then re-read the counter with a separate
     * {@code get()}. Two batch threads interleaving in either window observe
     * the same counter and hand two distinct records an identical
     * {@code _version}, which the ReplacingMergeTree silently collapses into
     * one row. Holding the lock across the whole decision closes both windows,
     * and the value used is the one produced inside the critical section.</p>
     *
     * @param debeziumTsMs the record's Debezium timestamp.
     * @return the {@code _version} value to assign to the record.
     */
    long nextSequenceNumber(long recordTsMs) {
        synchronized (sequenceLock) {
            final long assignedSequence;
            if (sequenceStartTime == UNANCHORED) {
                // First record after start/resume: seed the counter at
                // SEQUENCE_START_INITIAL (500m) so events re-published from the
                // last committed offset rank strictly BELOW any pre-restart
                // write of the same source second, which carried counters in
                // the SEQUENCE_START (1000m) range (#1346 / #1378).
                sequenceStartTime = recordTsMs;
                sequenceNumber.set(SEQUENCE_START_INITIAL);
                assignedSequence = SEQUENCE_START_INITIAL;
                return recordTsMs * 1000000 + assignedSequence;
            }
            // Identical to 2.8.0: reset only when the clock advances more than
            // one whole second past the anchor.
            int diff = (int) ((recordTsMs - sequenceStartTime) / 1000);
            if (diff > 1) {
                sequenceNumber.set(SEQUENCE_START);
                assignedSequence = SEQUENCE_START;
                sequenceStartTime = recordTsMs;
            } else {
                assignedSequence = sequenceNumber.incrementAndGet();
            }
            return recordTsMs * 1000000 + assignedSequence;
        }
    }

    /**
     * Adds a version (sequence number) to every record.
     *
     * <p>Delegates to {@link #nextSequenceNumber(long)} so this path and the
     * live batch-consumer path can never drift apart into two different
     * {@code _version} schemes.</p>
     *
     * @param chStructs The list of {@link ClickHouseStruct} records.
     */
    public void addVersion(List<ClickHouseStruct> chStructs) {
        if (chStructs.isEmpty()) {
            return;
        }
        for (ClickHouseStruct chStruct : chStructs) {
            // Anchor on the SOURCE commit timestamp when available
            // (redelivery-stable, #1346); fall back to the processing
            // timestamp for records without one. Assignment goes through
            // nextSequenceNumber so this path and the live batch-consumer path
            // share ONE locked counter -- doing the read-modify-write inline
            // here is the duplicate-_version race the lock exists to close.
            long recordTs = chStruct.getTs_ms() > 0
                    ? chStruct.getTs_ms() : chStruct.getDebezium_ts_ms();
            chStruct.setSequenceNumber(nextSequenceNumber(recordTs));
        }
    }
}
