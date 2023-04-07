package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Setup Debezium engine with the configuration passed by the user
 * Create a separate thread pool to read the records
 * that are inserted from the setup function.
 */
public class DebeziumChangeEventCapture {

    private static final Logger log = LoggerFactory.getLogger(DebeziumChangeEventCapture.class);

    private ClickHouseBatchExecutor executor;

    private ClickHouseBatchRunnable runnable;

    // Records grouped by Topic Name
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records;


    DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;

    private void performDDLOperation(String DDL,  ClickHouseSinkConnectorConfig config) {

        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(DDL, "", clickHouseQuery);
        ClickHouseAlterTable cat = new ClickHouseAlterTable();

        DBCredentials dbCredentials = parseDBConfiguration(config);
        BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                dbCredentials.getDatabase(), dbCredentials.getUserName(),
                dbCredentials.getPassword(), config);

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
            //throw new RuntimeException(e);
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
    private void processEveryChangeRecord(Properties props, ChangeEvent<SourceRecord, SourceRecord> record,
                                          DebeziumRecordParserService debeziumRecordParserService,
                                          ClickHouseSinkConnectorConfig config) {
        try {

            SourceRecord sr = record.value();
            Struct struct = (Struct) sr.value();

            if (struct == null) {
                log.error("STRUCT EMPTY");
                //return;
            }
            if (struct.schema() == null) {
                log.error("SCHEMA EMPTY");
            }

            List<Field> schemaFields = struct.schema().fields();
            if (schemaFields == null) {
                return;
            }
            Field matchingDDLField = schemaFields.stream()
                    .filter(f -> "DDL".equalsIgnoreCase(f.name()))
                    .findAny()
                    .orElse(null);
            if (matchingDDLField != null) {
                String DDL = (String) struct.get("ddl");
                log.info("Source DB DDL: " + DDL);

                String disableDDLProperty = props.getProperty("disable.ddl");
                if (disableDDLProperty != null && disableDDLProperty.equalsIgnoreCase("true")) {
                    log.debug("Ignoring DDL");
                    return;
                }
                if (DDL != null && DDL.isEmpty() == false)
                //&& ((DDL.toUpperCase().contains("ALTER TABLE") || DDL.toUpperCase().contains("RENAME TABLE"))))
                {
                    log.info("***** DDL received, Flush all existing records");
                    this.executor.shutdown();
                    this.executor.awaitTermination(60, TimeUnit.SECONDS);

                    performDDLOperation(DDL, config);
                    this.executor = new ClickHouseBatchExecutor(config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()));

                    this.executor.scheduleAtFixedRate(this.runnable, 0, config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
                }

            } else {
                ClickHouseStruct chStruct = debeziumRecordParserService.parse(sr);

                ConcurrentLinkedQueue<ClickHouseStruct> queue = new ConcurrentLinkedQueue<ClickHouseStruct>();
                if (chStruct != null) {
                    queue.add(chStruct);
                }
                synchronized (this.records) {
                    if (chStruct != null) {
                        addRecordsToSharedBuffer(chStruct.getTopic(), chStruct);
                    }
                }
            }

            String value = String.valueOf(record.value());
            log.debug(String.format("Record %s", value));
        } catch (Exception e) {
            log.error("Exception processing record", e);
        }
    }

    public static int MAX_RETRIES = 5;
    public static int SLEEP_TIME = 10000;

    public int numRetries = 0;

    public void setupDebeziumEventCapture(Properties props, DebeziumRecordParserService debeziumRecordParserService,
                                          ClickHouseSinkConnectorConfig config) throws IOException {
        // Create the engine with this configuration ...
        try {
            DebeziumEngine.Builder<ChangeEvent<SourceRecord, SourceRecord>> changeEventBuilder = DebeziumEngine.create(Connect.class);
            changeEventBuilder.using(props);
            changeEventBuilder.notifying(record -> {
                processEveryChangeRecord(props, record, debeziumRecordParserService, config);

            });
            this.engine = changeEventBuilder
                    .using(new DebeziumConnectorCallback()).using(new DebeziumEngine.CompletionCallback() {
                        @Override
                        public void handle(boolean b, String s, Throwable throwable) {
                            if (b == false) {

                                log.error("Error starting connector" + throwable);
                                log.error("Retrying - try number:" + numRetries);
                                if (numRetries++ <= MAX_RETRIES) {
                                    try {
                                        Thread.sleep(SLEEP_TIME);
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    try {
                                        setupDebeziumEventCapture(props, debeziumRecordParserService, config);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }
                            log.info("Completion callback");
                        }
                    }).build();
            engine.run();

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
                      DDLParserService ddlParserService) throws IOException {

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props));

        Metrics.initialize(props.getProperty(ClickHouseSinkConnectorConfigVariables.ENABLE_METRICS.toString()),
                props.getProperty(ClickHouseSinkConnectorConfigVariables.METRICS_ENDPOINT_PORT.toString()));

        this.setupProcessingThread(config, ddlParserService);

        setupDebeziumEventCapture(props, debeziumRecordParserService, config);
    }

    public void stop() throws IOException {
        if(this.engine != null) {
            this.engine.close();
        }
    }

    private DBCredentials parseDBConfiguration(ClickHouseSinkConnectorConfig config) {
        DBCredentials dbCredentials = new DBCredentials();

        dbCredentials.setHostName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString()));
        dbCredentials.setDatabase(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE.toString()));
        dbCredentials.setPort(config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString()));
        dbCredentials.setUserName(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString()));
        dbCredentials.setPassword(config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString()));

        return dbCredentials;
    }

    /**
     * Function to setup separate processing thread/thread pool.
     *
     * @param config
     */
    private void setupProcessingThread(ClickHouseSinkConnectorConfig config, DDLParserService ddlParserService) {
        // Setup separate thread to read messages from shared buffer.
        this.records = new ConcurrentHashMap<>();
        this.runnable = new ClickHouseBatchRunnable(this.records, config, new HashMap());
        this.executor = new ClickHouseBatchExecutor(config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString()));
        this.executor.scheduleAtFixedRate(this.runnable, 0, config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString()), TimeUnit.MILLISECONDS);
    }

    /**
     * Function to write the transformed
     * records to shared queue.
     *
     * @param topicName
     * @param chs
     */
    private void addRecordsToSharedBuffer(String topicName, ClickHouseStruct chs) {
        ConcurrentLinkedQueue<ClickHouseStruct> structs;

        if (this.records.containsKey(topicName)) {
            structs = this.records.get(topicName);
        } else {
            structs = new ConcurrentLinkedQueue<>();
        }
        structs.add(chs);
        synchronized (this.records) {
            this.records.put(topicName, structs);
        }
    }
}