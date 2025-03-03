package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchWriter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.relational.history.SchemaHistory;
import io.debezium.storage.jdbc.history.JdbcSchemaHistoryConfig;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.text.SimpleDateFormat;
/**
 * Setup Debezium engine with the configuration passed by the user
 * Create a separate thread pool to read the records
 * that are inserted from the setup function.
 */
public class DebeziumChangeEventCapture {

    private static final Logger log = LogManager.getLogger(DebeziumChangeEventCapture.class);

    private ClickHouseBatchExecutor executor;

    // Records grouped by Topic Name
    private LinkedBlockingQueue<List<ClickHouseStruct>> records;
    static public boolean isNewReplacingMergeTreeEngine = true;
    final ExecutorService singleThreadDebeziumEventExecutor;


    DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;
    ClickHouseBatchWriter singleThreadedWriter;

    DebeziumJdbcStorageOperations debeziumJdbcStorageOperations;

    BaseDbWriter writer;
    Connection systemDbConnection;

    @Getter
    @Setter
    private String lastIgnoredDDL;

    public DebeziumChangeEventCapture() {
        singleThreadDebeziumEventExecutor = Executors.newFixedThreadPool(1);
        this.debeziumJdbcStorageOperations = new DebeziumJdbcStorageOperations();
    }

    public static int MAX_RETRIES = 25;
    public static int SLEEP_TIME = 10000;

    public int numRetries;

    /**
     *
     * @param props
     * @param debeziumRecordParserService
     * @param config
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void setupDebeziumEventCapture(Properties props, DebeziumRecordParserService debeziumRecordParserService,
                                          ClickHouseSinkConnectorConfig config) throws IOException, ClassNotFoundException {

        DBCredentials dbCredentials = parseDBConfiguration(config);
        systemDbConnection  = setSystemDbConnection(dbCredentials, config);

        try {
            this.debeziumJdbcStorageOperations.createDatabaseForDebeziumStorage(systemDbConnection, props);
        } catch (SQLException e) {
            log.error("Error creating Debezium storage database", e);
        }
        try {
            DBMetadata dbMetadata = new DBMetadata();
            String clickHouseVersion = dbMetadata.getClickHouseVersion(systemDbConnection);
            isNewReplacingMergeTreeEngine = new DBMetadata().checkIfNewReplacingMergeTree(clickHouseVersion);
        } catch (Exception e) {
            log.error("Error retrieving version", e);
        }

        // This is required for Debezium JDBC storage to identify the clickhouse driver.
        // when it's bundled as a shaded JAR.
        Class chDriver = Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        // Create the engine with this configuration ...
        try {
            DebeziumEngine.Builder<ChangeEvent<SourceRecord, SourceRecord>> changeEventBuilder = DebeziumEngine.create(Connect.class);

            changeEventBuilder.using(props);
            changeEventBuilder.notifying(new DebeziumEngine.ChangeConsumer<ChangeEvent<SourceRecord, SourceRecord>>() {
                @Override
                public void handleBatch(List<ChangeEvent<SourceRecord, SourceRecord>> list,
                                        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> recordCommitter) throws InterruptedException {

                    List<ClickHouseStruct> batch = new ArrayList<ClickHouseStruct>();
                    for (int i = 0; i < list.size(); i++) {
                        ChangeEvent<SourceRecord, SourceRecord> record = list.get(i);
                        boolean lastRecordInBatch = false;
                        if(i == list.size() - 1) {
                            lastRecordInBatch = true;
                        }
                        ClickHouseStruct chStruct = processEveryChangeRecord(props, record, debeziumRecordParserService, config, recordCommitter, lastRecordInBatch);
                        if(chStruct != null) {
                            batch.add(chStruct);
                        }
                    }
                    // Add sequence number.
                    addVersion(batch);


                    if(batch.size() > 0) {
                        appendToRecords(batch, config);
                    }
                }
            });
            this.engine = changeEventBuilder
                    .using(new DebeziumConnectorCallback()).using(new DebeziumEngine.CompletionCallback() {
                        @Override
                        public void handle(boolean b, String s, Throwable throwable) {
                            if(b == false) {

                                log.error("Error starting connector" + throwable + " Message:" + s);
                                if(throwable != null && throwable.getCause() != null && throwable.getCause().getLocalizedMessage() != null) {
                                    log.error("Error stating connector: Cause" + throwable.getCause().getLocalizedMessage());
                                }

                                log.error("Retrying - try number:" + numRetries);
                                if(numRetries++ <= MAX_RETRIES) {
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

                    }).using(
                            new DebeziumEngine.ConnectorCallback() {
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
                                }

                                @Override
                                public void connectorStopped() {
                                    ReplicationStatusSingleton.getInstance().setIsReplicationRunning(false);
                                    log.debug("Connector stopped");
                                }
                            }
                    )
                    //.build();
                    .using(OffsetCommitPolicy.always()).build();
            singleThreadDebeziumEventExecutor.submit(() -> {
                Thread.currentThread().setName("Sink connector Debezium Event Thread");
                try {
                    engine.run();
                } catch (Exception e) {
                    log.error("Debezium event capture starting Exception", e);
                }

            });
            //engine.run();

        } catch (Exception e) {
            log.error("Exception", e);
            //   throw new RuntimeException(e);
            if(this.engine != null) {
                this.engine.close();;
            }
        }

    }

    /**
     * Setup Debezium engine
     *
     * @param props
     * @param debeziumRecordParserService
     */
    public void setup(Properties props, DebeziumRecordParserService debeziumRecordParserService,
                      boolean forceStart) throws IOException, ClassNotFoundException {

        // Check if max queue size was defined by the user.
        if(props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()) != null) {
            int maxQueueSize = Integer.parseInt(props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()));
            this.records = new LinkedBlockingQueue<>(maxQueueSize);
        } else {
            this.records = new LinkedBlockingQueue<>();
        }

        try {
            if (props.getProperty(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString()) != null) {
                Integer maxRetries = Integer.parseInt(props.getProperty(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString()));
                DBMetadata.setMaxRetries(maxRetries);
            }
        }
        catch(Exception e) {
            log.error("Error retrieving max retries", e);
        }
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props));
        Metrics.initialize(props.getProperty(ClickHouseSinkConnectorConfigVariables.ENABLE_METRICS.toString()),
                props.getProperty(ClickHouseSinkConnectorConfigVariables.METRICS_ENDPOINT_PORT.toString()));

        // Start debezium event loop if its requested from REST API.
        if(!config.getBoolean(ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString()) || forceStart) {
            this.setupProcessingThread(config);
            setupDebeziumEventCapture(props, debeziumRecordParserService, config);
        } else {
            log.info(ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString() + " variable set to true, Replication is skipped, use sink-connector-client to start replication");
        }
    }

    public void stop() throws IOException {


        try {
            if (this.executor != null) {
                this.executor.shutdown();
                this.executor.awaitTermination(60, TimeUnit.SECONDS);
            }
        } catch(Exception e) {
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
        } catch(Exception e) {
            log.error("Error stopping debezium engine", e);
        }


//        try {
//            if (this.conn != null) {
//                this.conn.close();
//            }
//        } catch(Exception e) {
//            log.error("Error closing clickhouse connection", e);
//        }

        Metrics.stop();
    }

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
     * @param DDL DDL to be executed.
     * @param props
     * @param sr
     * @param config
     */
    private void performDDLOperation(String DDL, Properties props, SourceRecord sr,
                                     ClickHouseSinkConnectorConfig config,
                                     DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                                             recordCommitter, ChangeEvent<SourceRecord, SourceRecord> cdcRecord,
                                     boolean lastRecordInBatch) {
        String databaseName = getDatabaseName(sr);

        StringBuffer clickHouseQuery = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(writer, config, databaseName);
        mySQLDDLParserService.parseSql(DDL, "", clickHouseQuery, isDropOrTruncate);

        if (checkIfDDLNeedsToBeIgnored(DDL, props, sr, isDropOrTruncate)) {
            log.info("Ignored Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
            return;
        }

        log.info("Executed Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
        // Add max retries of 10
        // Add sleep time of 10 seconds
        int MAX_DDL_RETRIES = 10;
        int SLEEP_TIME = 10000;
        int numRetries = 0;

        // Check if configuration is set to retry DDL
        String retryDDL = props.getProperty(SinkConnectorLightWeightConfig.DDL_RETRY.toString());
        boolean retryDDLProperty = false;
        if(retryDDL != null && retryDDL.equalsIgnoreCase("true")) {
            retryDDLProperty = true;
        }

        while(numRetries < MAX_DDL_RETRIES) {
            try {
                executeDDL(clickHouseQuery.toString(), writer);
                DebeziumOffsetManagement.acknowledgeRecords(recordCommitter,
                        cdcRecord, lastRecordInBatch);
                break;
            } catch (Exception e) {
                log.error("Error executing DDL", e);
                if(retryDDLProperty == false) {
                    break;
                }
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException ex) {
                    log.error("Error sleeping", ex);
                }
                numRetries++;
            }
            if(numRetries >= MAX_DDL_RETRIES) {
                throw new RuntimeException("Max retries exceeded for DDL");
            }
        }
        updateMetrics(DDL);
    }

    private Connection setSystemDbConnection(DBCredentials dbCredentials, ClickHouseSinkConnectorConfig config) {
        String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                BaseDbWriter.SYSTEM_DB);

        Connection conn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                dbCredentials.getUserName(), dbCredentials.getPassword(), BaseDbWriter.SYSTEM_DB, config);
                writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                BaseDbWriter.SYSTEM_DB, dbCredentials.getUserName(),
                dbCredentials.getPassword(), config, conn);
        return conn;
    }
    /**
     * Function to get the database name from the SourceRecord.
     * If the database name is not present in the SourceRecord, then
     * the database name is set to "system".
     * Also if a database is overridden in the configuration, then
     * the database name is set to the overridden database name.
     * @param sr
     * @return
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

    private void executeDDL(String clickHouseQuery, BaseDbWriter writer) throws SQLException {
        ClickHouseAlterTable cat = new ClickHouseAlterTable();
        DBMetadata dbMetadata = new DBMetadata();
        String[] queries = clickHouseQuery.replaceAll(",$", "").split("\n");
        for (String query : queries) {
            if (!query.isEmpty()) {
                log.info("ClickHouse DDL: " + query);
                dbMetadata.executeSystemQuery(writer.getConnection(), query);
            }
        }
    }

    private void updateMetrics(String DDL) {
        long currentTime = System.currentTimeMillis();
        boolean ddlProcessingResult = true;
        Metrics.updateDdlMetrics(DDL, currentTime, 0, ddlProcessingResult);


        long elapsedTime = System.currentTimeMillis() - currentTime;
        Metrics.updateDdlMetrics(DDL, currentTime, elapsedTime, ddlProcessingResult);
    }

    /**
     * Function to process every change event record
     * as received from Debezium
     *
     * @param record ChangeEvent Record
     */
    private ClickHouseStruct processEveryChangeRecord(Properties props, ChangeEvent<SourceRecord, SourceRecord> record,
                                          DebeziumRecordParserService debeziumRecordParserService,
                                          ClickHouseSinkConnectorConfig config,
                                          DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                                                  recordCommitter, boolean lastRecordInBatch) {
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

            List<Field> schemaFields = struct.schema().fields();
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


                if (DDL != null && DDL.isEmpty() == false)
                {
                    log.info("***** DDL received, Flush all existing records");
                    this.executor.pause();

                    performDDLOperation(DDL, props, sr, config, recordCommitter, record, lastRecordInBatch);
                    this.executor.resume();
                }

            } else {
                chStruct = debeziumRecordParserService.parse(record, recordCommitter, lastRecordInBatch);
                try {
                    if(chStruct != null) {
                        ReplicationStatusSingleton.getInstance().setReplicationLag(chStruct.getReplicationLag());
                        ReplicationStatusSingleton.getInstance().setLastRecordTimestamp(chStruct.getTs_ms());
                        ReplicationStatusSingleton.getInstance().setBinLogFile(chStruct.getFile());
                        ReplicationStatusSingleton.getInstance().setBinLogPosition(String.valueOf(chStruct.getPos()));
                        ReplicationStatusSingleton.getInstance().setGtid(String.valueOf(chStruct.getGtid()));
                    }
                } catch(Exception e) {
                    log.error("Error retrieving status metrics: Exception" + e.toString());
                }
            }

        } catch (Exception e) {
            log.error("Exception processing record", e);
        }

        return chStruct;
    }

    @VisibleForTesting
    void setWriter(BaseDbWriter writer) {
        this.writer = writer;
    }

    private boolean isSnapshotDDL(SourceRecord sr) {
        boolean snapshotDDL = false;

        if(sr.sourceOffset() != null) {
            if (sr.sourceOffset().containsKey("snapshot")) {
                snapshotDDL = (Boolean) sr.sourceOffset().get("snapshot");
            }
        }

        return snapshotDDL;
    }
    /***
     * Function that checks if the DDL needs to be ignored.
     * @param props Properties (passed by user)
     * @param sr
     * @return
     */
    private boolean checkIfDDLNeedsToBeIgnored(String DDL, Properties props, SourceRecord sr, AtomicBoolean isDropOrTruncate) {

        String disableDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DDL);
        if (disableDDLProperty != null && disableDDLProperty.equalsIgnoreCase("true")) {
            log.debug("Ignoring DDL");
            return true;
        }

        boolean isSnapshotDDL = isSnapshotDDL(sr);

        String enableSnapshotDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.ENABLE_SNAPSHOT_DDL);
        boolean enableSnapshotDDLPropertyFlag = false;
        if(enableSnapshotDDLProperty != null && enableSnapshotDDLProperty.equalsIgnoreCase("true")) {
            enableSnapshotDDLPropertyFlag = true;
        }

        // Also if the DDL matches the regex, then ignore it.
        String ignoreDDLRegexProperty = props.getProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX);
        // The IGNORE_DDL_REGEX will be a list of regex separated by 2 pipe(##).
        // Example: ^ALTER TABLE .* ADD COLUMN .*##^ALTER TABLE .* DROP COLUMN .*##^ALTER TABLE .* MODIFY COLUMN .*
        // Separate the list.

        if(ignoreDDLRegexProperty != null && !ignoreDDLRegexProperty.isEmpty()) {
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

        String disableDropAndTruncateProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
        if(disableDropAndTruncateProperty != null && disableDropAndTruncateProperty.equalsIgnoreCase("true") && isDropOrTruncate.get()== true) {
            log.debug("Ignoring Drop or Truncate");
            return true;
        }
        if(isSnapshotDDL== true && enableSnapshotDDLPropertyFlag == false) {
            // User wants to ignore snapshot
            return true;
        } else {
            return false;
        }

    }


    /**
     * Function to setup separate processing thread/thread pool.
     *
     * @param config
     */
    private void setupProcessingThread(ClickHouseSinkConnectorConfig config) {


        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())) {
            log.info("********* Running in Single Threaded mode *********");
            singleThreadedWriter = new ClickHouseBatchWriter(config, new HashMap());
        }

            ThreadFactory namedThreadFactory =
                    new ThreadFactoryBuilder().setNameFormat("Sink Connector thread-pool-%d").build();
            this.executor = new ClickHouseBatchExecutor(config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()), namedThreadFactory);
            for (int i = 0; i < config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()); i++) {
                this.executor.scheduleAtFixedRate(new ClickHouseBatchRunnable(this.records, config, new HashMap()), 0,
                        config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
            }

        //this.executor.scheduleAtFixedRate(this.runnable, 0, config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
    }

    private void appendToRecords(List<ClickHouseStruct> convertedRecords, ClickHouseSinkConnectorConfig config) {

        // If config is set to single threaded.
        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())) {
            singleThreadedWriter.persistRecords(convertedRecords);

        } else {

            synchronized (this.records) {
                this.records.add(convertedRecords);
            }
        }


    }

    public static final long SEQUENCE_START = 1000000000;
    public static final long SEQUENCE_START_INITIAL = 500000000;

    public static long sequenceNumber = SEQUENCE_START;
    /**
     * Function to add version to every record.
     * @param chStructs
     */
    public static void addVersion(List<ClickHouseStruct> chStructs) {

        // Start the sequence from 1 million and increment for every record
        // and reset the sequence back to 1 million in the next second
        if(chStructs.isEmpty()) {
            return;
        }
        long sequenceStartTime = chStructs.get(0).getDebezium_ts_ms();
        //long sequence = SEQUENCE_START;

        for(ClickHouseStruct chStruct: chStructs) {
            // Get the first ts_ms from chStruct
            // Subsequent records add 1 to sequence.
            // If its been more than a second from the first
            // ts_ms then reset the sequence.
            // Get diff in seconds
            int diff = (int) (chStruct.getDebezium_ts_ms() - sequenceStartTime) / 1000;
            if(diff > 1) {
                sequenceNumber = SEQUENCE_START;
                sequenceStartTime = chStruct.getDebezium_ts_ms();
            }   else {
                sequenceNumber++;
            }
            // Pad the sequence number with 0s
            chStruct.setSequenceNumber(chStruct.getDebezium_ts_ms() * 1000000 + sequenceNumber);
        }
    }
}
