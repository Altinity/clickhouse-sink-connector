package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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


    private BaseDbWriter writer = null;

    static public boolean isNewReplacingMergeTreeEngine = true;

    private long replicationLag = 0;

    private long lastRecordTimestamp = -1;

    private boolean isReplicationRunning = false;

    final ExecutorService singleThreadDebeziumEventExecutor;

    private String binLogFile = "";

    private String binLogPosition = "";

    private String gtid = "";

    DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;

    // Keep one clickhouse connection.
    private ClickHouseConnection conn;

    public DebeziumChangeEventCapture() {
        singleThreadDebeziumEventExecutor = Executors.newFixedThreadPool(1);
    }


    /**
     * Function to perform DDL operation on the main thread.
     * @param DDL DDL to be executed.
     * @param props
     * @param sr
     * @param config
     */
    private void performDDLOperation(String DDL, Properties props, SourceRecord sr, ClickHouseSinkConnectorConfig config) {

        String databaseName = "system";
        if(sr != null && sr.key() != null) {
            if(sr.key() instanceof Struct) {
                Struct keyStruct = (Struct) sr.key();
                String recordDbName = (String) keyStruct.get("databaseName");
                if(recordDbName != null && recordDbName.isEmpty() == false) {
                    databaseName = recordDbName;
                }
            }
        }

        if(writer == null) {

            DBCredentials dbCredentials = parseDBConfiguration(config);
            String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                    databaseName);
            ClickHouseConnection conn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                    dbCredentials.getUserName(), dbCredentials.getPassword(), config);
            writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                    databaseName, dbCredentials.getUserName(),
                    dbCredentials.getPassword(), config, conn);
        }

        StringBuffer clickHouseQuery = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(config, databaseName);
        mySQLDDLParserService.parseSql(DDL, "", clickHouseQuery, isDropOrTruncate);
        ClickHouseAlterTable cat = new ClickHouseAlterTable();

        if (checkIfDDLNeedsToBeIgnored(props, sr, isDropOrTruncate)) {
            log.info("Ignored Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
            return;
        } else {
            log.info("Executed Source DB DDL: " + DDL + " Snapshot:" + isSnapshotDDL(sr));
        }

        long currentTime = System.currentTimeMillis();
        boolean ddlProcessingResult = true;
        Metrics.updateDdlMetrics(DDL, currentTime, 0, ddlProcessingResult);
        try {
            String formattedQuery = clickHouseQuery.toString().replaceAll(",$", "");
            if (formattedQuery != null && formattedQuery.isEmpty() == false) {
                if (formattedQuery.contains("\n")) {
                    String[] queries = formattedQuery.split("\n");
                    for (String query : queries) {
                        if (query != null && query.isEmpty() == false) {
                            log.info("ClickHouse DDL: " + query);
                            cat.runQuery(query, writer.getConnection());
                        }
                    }
                } else {
                    log.info("ClickHouse DDL: " + formattedQuery);
                    cat.runQuery(formattedQuery, writer.getConnection());
                }
            } else {
                log.error("DDL translation failed: " + DDL);
            }
        } catch (Exception e) {
            log.error("Error running DDL Query: " + e);
            ddlProcessingResult = false;
        }

        try {
            String clickHouseVersion = writer.getClickHouseVersion();
            isNewReplacingMergeTreeEngine = new DBMetadata()
                    .checkIfNewReplacingMergeTree(clickHouseVersion);
        } catch (Exception e) {
            log.error("Error retrieving version");
        }

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
                log.warn(String.format("STRUCT EMPTY - not a valid CDC record + Record(%s)", record.toString()));
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
                    this.executor.shutdown();
                    this.executor.awaitTermination(60, TimeUnit.SECONDS);

                    performDDLOperation(DDL, props, sr, config);
                    setupProcessingThread(config);
                }

            } else {
                chStruct = debeziumRecordParserService.parse(record, recordCommitter, lastRecordInBatch);
                try {
                    if(chStruct != null) {
                        this.replicationLag = chStruct.getReplicationLag();
                        this.lastRecordTimestamp = chStruct.getTs_ms();
                        this.binLogFile = chStruct.getFile();
                        this.binLogPosition = String.valueOf(chStruct.getPos());
                        this.gtid = String.valueOf(chStruct.getGtid());
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
    private boolean checkIfDDLNeedsToBeIgnored(Properties props, SourceRecord sr, AtomicBoolean isDropOrTruncate) {

        String disableDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.DISABLE_DDL);
        if (disableDDLProperty != null && disableDDLProperty.equalsIgnoreCase("true")) {
            log.debug("Ignoring DDL");
            return true;
        }

        boolean isSnapshotDDL = isSnapshotDDL(sr);

        String enableSnapshotDDLProperty = props.getProperty(SinkConnectorLightWeightConfig.ENABLE_SNAPSHOT_DDL);
        boolean enableSnapshotDDLPropertyFlag = false;
        if(enableSnapshotDDLProperty != null && enableSnapshotDDLProperty.equalsIgnoreCase("true" )) {
            enableSnapshotDDLPropertyFlag = true;
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
     * Function to create database for Debezium storage.
     * @param config
     */
    private void createDatabaseForDebeziumStorage(ClickHouseSinkConnectorConfig config, Properties props) {
        try {
            DBCredentials dbCredentials = parseDBConfiguration(config);

            String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                        "system");
            ClickHouseConnection conn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",dbCredentials.getUserName(), dbCredentials.getPassword(), config);
            BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                        "system", dbCredentials.getUserName(),
                        dbCredentials.getPassword(), config, conn);

            Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);
            String databaseName = tableNameDatabaseName.getRight();

            String createDbQuery = String.format("create database if not exists %s", databaseName);
            log.info("CREATING DEBEZIUM STORAGE Database: " + createDbQuery);
            writer.executeQuery(createDbQuery);

        } catch(Exception e) {
            log.error("Error creating Debezium storage database", e);
        }
    }

    /**
     * Function to create view for show_replica_status
     * @param config
     * @param props
     */
    private void createViewForShowReplicaStatus(ClickHouseSinkConnectorConfig config, Properties props) {
        String view = props.getProperty(ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString());
        if(view == null || view.isEmpty() == true) {
            log.warn("Skipping creating view for replica_status as the query was not provided in configuration");
            return;
        }
        DBCredentials dbCredentials = parseDBConfiguration(config);

        String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                "system");
        ClickHouseConnection conn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",dbCredentials.getUserName(), dbCredentials.getPassword(), config);
        BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                "system", dbCredentials.getUserName(),
                dbCredentials.getPassword(), config, conn);
        Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);

        String tableName = tableNameDatabaseName.getLeft();
        String dbName = tableNameDatabaseName.getRight();

        String formattedView = String.format(view, dbName, dbName + "." + tableName);
        // Remove quotes.
        formattedView = formattedView.replace("\"", "");
        try {
            writer.executeQuery(formattedView);
        } catch(Exception e) {
            log.error("**** Error creating VIEW **** " + formattedView);
        }
    }

    /**
     *
     * @param props
     * @return
     */
    private Pair<String, String> getDebeziumStorageDatabaseName(Properties props) {


        String tableName = props.getProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
        // if tablename is dbname.tablename and contains a dot.
        String databaseName = "system";
        // split tablename with dot.
        if(tableName.contains(".")) {
            String[] dbTableNameArray = tableName.split("\\.");
            if(dbTableNameArray.length >= 2) {
                databaseName = dbTableNameArray[0].replace("\"", "");
                tableName = dbTableNameArray[1].replace("\"", "");
            }
        }

        return Pair.of(tableName, databaseName);
    }

    /**
     * Function to get the status of Debezium storage.
     * @param props
     * @return
     */
    public String getDebeziumStorageStatus(ClickHouseSinkConnectorConfig config, Properties props) throws Exception {
        String response = "";

        Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();

        DBCredentials dbCredentials = parseDBConfiguration(config);

        if (writer == null  || writer.getConnection().isClosed() == true) {
            // Json error string
            log.error("**** Connection to ClickHouse is not established, re-initiating ****");
            String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                    databaseName);
            ClickHouseConnection conn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                    dbCredentials.getUserName(), dbCredentials.getPassword(), config);
            writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                    databaseName, dbCredentials.getUserName(),
                    dbCredentials.getPassword(), config, conn);
        }
        //DBCredentials dbCredentials = parseDBConfiguration(config);
        String debeziumStorageStatusQuery = String.format("select * from %s limit 1", databaseName + "." + tableName);
        ResultSet resultSet = writer.executeQueryWithResultSet(debeziumStorageStatusQuery);

        if(resultSet != null) {
            ResultSetMetaData md = resultSet.getMetaData();
            int numCols = md.getColumnCount();
            List<String> colNames = IntStream.range(0, numCols)
                    .mapToObj(i -> {
                        try {
                            return md.getColumnName(i + 1);
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return "?";
                        }
                    })
                    .collect(Collectors.toList());

            JSONArray result = new JSONArray();
            JSONObject replicationLag = new JSONObject();
            replicationLag.put("Seconds_Behind_Source", this.replicationLag / 1000);
            result.add(replicationLag);

            JSONObject replicationRunning = new JSONObject();
            replicationRunning.put("Replica_Running", this.isReplicationRunning);
            result.add(replicationRunning);
            // Add Database name and table name.
            JSONObject dbName = new JSONObject();

            dbName.put("Database", dbCredentials.getDatabase());
            result.add(dbName);

            while (resultSet.next()) {
                JSONObject row = new JSONObject();
                colNames.forEach(cn -> {
                    try {
                        row.put(cn, resultSet.getObject(cn));
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                result.add(row);
            }

            response = result.toJSONString();
        }
        return response;
    }

    /**
     * Function to get the latest timestamp from Debezium storage.
     */
    public long getLatestRecordTimestamp(ClickHouseSinkConnectorConfig config, Properties props) throws SQLException {

        long result = -1;
        DBCredentials dbCredentials = parseDBConfiguration(config);

        Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();

        BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                databaseName, dbCredentials.getUserName(),
                dbCredentials.getPassword(), config, this.conn);

        String latestRecordTs = new DebeziumOffsetStorage().getDebeziumLatestRecordTimestamp(props, writer);

        // Convert date string from  2024-01-26 21:57:47 format to milliseconds.
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = null;
        try {
            date = sdf.parse(latestRecordTs);
            this.lastRecordTimestamp = date.getTime();
        } catch ( java.text.ParseException e) {
            log.error("Error parsing date", e);
        }

        // Convert from date to long milliseconds
        if(date != null) {
            result = date.getTime();
        }
        return result;
    }
    /**
     * Function to update the status of Debezium storage.
     *
     * @param props
     * @param binlogFile
     * @param binLogPosition
     * @param gtid
     */
    public void updateDebeziumStorageStatus(ClickHouseSinkConnectorConfig config, Properties props,
                                            String binlogFile, String binLogPosition, String gtid) throws SQLException, ParseException {


        Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();

        DBCredentials dbCredentials = parseDBConfiguration(config);

        BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                databaseName, dbCredentials.getUserName(),
                dbCredentials.getPassword(), config, this.conn);
        String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(props, writer);

        String offsetKey = new DebeziumOffsetStorage().getOffsetKey(props);
        String updateOffsetValue = new DebeziumOffsetStorage().updateBinLogInformation(offsetValue,
                binlogFile, binLogPosition, gtid);

        new DebeziumOffsetStorage().deleteOffsetStorageRow(offsetKey, props, writer);
        new DebeziumOffsetStorage().updateDebeziumStorageRow(writer, tableName, offsetKey, updateOffsetValue,
                System.currentTimeMillis());

    }

    /**
     * Function to update the status of Debezium storage (LSN).
     * @param config
     * @param props
     * @param lsn
     * @throws SQLException
     * @throws ParseException
     */
    public void updateDebeziumStorageStatus(ClickHouseSinkConnectorConfig config, Properties props,
                                            String lsn) throws SQLException, ParseException {


        Pair<String, String> tableNameDatabaseName = getDebeziumStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();
        DBCredentials dbCredentials = parseDBConfiguration(config);

        BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                databaseName, dbCredentials.getUserName(),
                dbCredentials.getPassword(), config, this.conn);
        String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(props, writer);

        String offsetKey = new DebeziumOffsetStorage().getOffsetKey(props);
        String updateOffsetValue = new DebeziumOffsetStorage().updateLsnInformation(offsetValue,
                Long.parseLong(lsn));

        new DebeziumOffsetStorage().deleteOffsetStorageRow(offsetKey, props, writer);
        new DebeziumOffsetStorage().updateDebeziumStorageRow(writer, tableName, offsetKey, updateOffsetValue,
                System.currentTimeMillis());

    }

    public static int MAX_RETRIES = 25;
    public static int SLEEP_TIME = 10000;

    public int numRetries = 0;

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

        createDatabaseForDebeziumStorage(config, props);
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
                    for(int i = 0; i < list.size(); i++) {
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
                        appendToRecords(batch);
                    }
                }
            });
            this.engine = changeEventBuilder
                    .using(new DebeziumConnectorCallback()).using(new DebeziumEngine.CompletionCallback() {
                        @Override
                        public void handle(boolean b, String s, Throwable throwable) {
                            if (b == false) {

                                log.error("Error starting connector" + throwable + " Message:" + s);
                                if(throwable != null && throwable.getCause() != null && throwable.getCause().getLocalizedMessage() != null)
                                    log.error("Error stating connector: Cause" + throwable.getCause().getLocalizedMessage());

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

                    }).using(
                            new DebeziumEngine.ConnectorCallback() {
                                @Override
                                public void connectorStarted() {
                                    isReplicationRunning = true;
                                    log.debug("Connector started");
                                    // Create view.
                                    createViewForShowReplicaStatus(config, props);
                                }

                                @Override
                                public void connectorStopped() {
                                    isReplicationRunning = false;
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
                      DDLParserService ddlParserService, boolean forceStart) throws IOException, ClassNotFoundException {

        // Check if max queue size was defined by the user.
        if(props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()) != null) {
            int maxQueueSize = Integer.parseInt(props.getProperty(ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString()));
            this.records = new LinkedBlockingQueue<>(maxQueueSize);
        } else {
            this.records = new LinkedBlockingQueue<>();
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


        try {
            if (this.conn != null) {
                this.conn.close();
            }
        } catch(Exception e) {
            log.error("Error closing clickhouse connection", e);
        }

        Metrics.stop();
    }

    public long getReplicationLag() {
        return this.replicationLag;
    }

    public long getReplicationLagInSecs() {
        return this.replicationLag / 1000;
    }

    public long getLastRecordTimestamp() {
        return this.lastRecordTimestamp;
    }

    public boolean isReplicationRunning() {
        return this.isReplicationRunning;
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
     * Function to setup separate processing thread/thread pool.
     *
     * @param config
     */
    private void setupProcessingThread(ClickHouseSinkConnectorConfig config) {

        // Setup separate thread to read messages from shared buffer.
        // this.records = new ConcurrentLinkedQueue<>();
        //this.runnable = new ClickHouseBatchRunnable(this.records, config, new HashMap());
        ThreadFactory namedThreadFactory =
                new ThreadFactoryBuilder().setNameFormat("Sink Connector thread-pool-%d").build();
        this.executor = new ClickHouseBatchExecutor(config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()), namedThreadFactory);
        for(int i = 0; i < config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()); i++) {
            this.executor.scheduleAtFixedRate(new ClickHouseBatchRunnable(this.records, config, new HashMap()), 0,
                    config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
        }
        //this.executor.scheduleAtFixedRate(this.runnable, 0, config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
    }

    private void appendToRecords(List<ClickHouseStruct> convertedRecords) {

        synchronized (this.records) {
            this.records.add(convertedRecords);
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
