package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumJdbcStorageOperations;
import com.altinity.clickhouse.debezium.embedded.cdc.ReplicationStatusSingleton;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.jul.Log4jBridgeHandler;

import java.io.IOException;
import java.sql.Connection;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.sink.connector.db.BaseDbWriter.SYSTEM_DB;

/**
 * ClickHouseDebeziumEmbeddedApplication is the main entry point
 * for the Debezium Embedded engine, managing event capture,
 * REST API, and scheduled monitoring tasks.
 *
 * <p>It initializes logging, loads configuration files,
 * and starts the Debezium engine to listen for change
 * events from the source database, which are then processed
 * and written to ClickHouse.
 */
public class ClickHouseDebeziumEmbeddedApplication {

    /**
     * Named constant to avoid magic numbers: number of milliseconds
     * to sleep before restarting the event loop.
     */
    private static final long THREAD_SLEEP_RESTART_MS = 3000L;

    /**
     * Named constant to avoid magic numbers: number of milliseconds
     * to sleep before forcing the start of the event loop.
     */
    private static final long THREAD_SLEEP_FORCE_START_MS = 500L;

    /**
     * Logger for the ClickHouseDebeziumEmbeddedApplication class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseDebeziumEmbeddedApplication.class
    );

    /**
     * The singleton instance of ClickHouseDebeziumEmbeddedApplication
     * used to manage the Debezium engine lifecycle.
     */
    private static ClickHouseDebeziumEmbeddedApplication embeddedApplication;

    /**
     * The DebeziumChangeEventCapture instance that captures and
     * processes change events from the source database.
     */
    private static DebeziumChangeEventCapture debeziumChangeEventCapture;

    /**
     * A Properties object used to hold additional user-defined
     * properties.
     */
    private static Properties userProperties = new Properties();

    /**
     * The Guice Injector used for dependency injection
     * throughout the application.
     */
    private static Injector injector;

    /**
     * The main properties object that contains combined
     * configuration settings for Debezium and related
     * components.
     */
    private static Properties props;

    /**
     * A Timer object that schedules and executes monitoring tasks
     * at fixed intervals.
     */
    private static Timer monitoringTimer;

    /**
     * The task executed by the monitoringTimer to observe
     * replication status and potentially restart the event loop.
     */
    private static TimerTask monitoringTimerTask;

    /**
     * Stores the configuration file path so that it can be
     * refreshed on restart.
     */
    // Store the configuration file so that it can be
    // refreshed on restart.
    private static String configurationFile;

    /**
     * Gets a database connection using BaseDbWriter.createConnection.
     * 
     * @param props The connector properties containing ClickHouse connection details
     * @return Connection to the database
     * @throws Exception if connection cannot be established
     */
    private static Connection getDatabaseConnection(Properties props) throws Exception {
        String clickhouseUrl = props.getProperty("clickhouse.server.url");
        String clickhousePort = props.getProperty("clickhouse.server.port");
        String clickhouseUser = props.getProperty("clickhouse.server.user");
        String clickhousePassword = props.getProperty("clickhouse.server.password");
        
        String jdbcUrl = BaseDbWriter.getConnectionString(clickhouseUrl, Integer.parseInt(clickhousePort), SYSTEM_DB);
        return BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, 
                clickhouseUser, clickhousePassword, SYSTEM_DB, 
                new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props)));
    }

    /**
     * Main entry method for the application.
     *
     * @param args command-line arguments (may include a config file path)
     * @throws Exception if any error occurs
     */
    public static void main(String[] args) throws Exception {
        Log4jBridgeHandler.install(false, "", true);
        System.setProperty(
                "java.util.logging.manager",
                "org.apache.logging.log4j.jul.LogManager"
        );
        System.setProperty(
                "log4j.configurationFile", "resources/log4j2.xml"
        );

        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");

        embeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
        String loggingLevel = System.getenv("LOGGING_LEVEL");
        if (loggingLevel != null) {
            // If user passes a wrong level, default is DEBUG
            LogManager.getRootLogger().atLevel(Level.toLevel(loggingLevel));
        } else {
            LogManager.getRootLogger().atLevel(Level.INFO);
        }

        injector = Guice.createInjector(new AppInjector());

        printDockerInfo();
        props = new Properties();
        if (args.length > 0) {
            log.info(String.format(
                    "****** CONFIGURATION FILE: %s ********", args[0]
            ));
            try {
                configurationFile = args[0];
                embeddedApplication.loadPropertiesFile(configurationFile);
            } catch (Exception e) {
                log.error("Error parsing configuration file, USAGE: "
                        + "java -jar <jar_file> <yaml_config_file>:\n"
                        + e.toString());
                System.exit(-1);
            }
        } else {
            props = injector.getInstance(
                    ConfigurationService.class).parse();
        }

        setupMonitoringThread(
                new ClickHouseSinkConnectorConfig(
                        PropertiesHelper.toMap(props)
                ),
                props
        );

        embeddedApplication.start(
                injector.getInstance(DebeziumRecordParserService.class),
                props,
                false
        );

        try {
            DebeziumEmbeddedRestApi.startRestApi(
                    props, injector, debeziumChangeEventCapture, userProperties
            );
        } catch (Exception e) {
            log.error("Error starting REST API server", e);
        }
    }

    /**
     * Prints Docker-related info from environment variables, if any.
     */
    private static void printDockerInfo() {
        try {
            String dockerTag = System.getenv("DOCKER_TAG");
            if (dockerTag != null) {
                log.info("***** Sink Connector Release version: *** "
                        + dockerTag);
            }
        } catch (Exception e) {
            log.error("Error printing docker info", e);
        }
    }

    /**
     * Loads properties from a user-provided configuration file.
     *
     * @param filePath the path to the user-provided file
     * @throws Exception if an error occurs while loading the file
     */
    private static void loadPropertiesFile(String filePath)
            throws Exception {
        props.clear();
        Properties defaultProperties =
                PropertiesHelper.getProperties("config.properties");
        props.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().loadFromFile(filePath);
        props.putAll(fileProps);
    }

    /**
     * Force-starts replication from the REST API.
     *
     * @param injector Guice injector instance
     * @param props   the application properties
     * @return a CompletableFuture that signals completion
     * @throws InterruptedException if the thread is interrupted
     */
    public static CompletableFuture<String> startDebeziumEventLoop(
            Injector injector, Properties props) throws InterruptedException {
        CompletableFuture<String> cf = new CompletableFuture<>();
        Executors.newCachedThreadPool().submit(() -> {
            debeziumChangeEventCapture.stop();
            Thread.sleep(THREAD_SLEEP_FORCE_START_MS);
            embeddedApplication.start(
                    injector.getInstance(DebeziumRecordParserService.class),
                    props,
                    true
            );
            return null;
        });
        return cf;
    }

    /**
     * Starts the Debezium event capture loop.
     *
     * @param recordParserService the parser service for incoming events
     * @param props the properties controlling Debezium
     * @param forceStart if true, reloads the config file
     * @throws Exception if an error occurs starting Debezium
     */
    public static void start(
            DebeziumRecordParserService recordParserService,
            Properties props,
            boolean forceStart
    ) throws Exception {
        if (forceStart) {
            log.info(String.format(
                    "******* Reloading configuration file (%s) from "
                            + "disk ******",
                    configurationFile
            ));
            loadPropertiesFile(configurationFile);
        }

        debeziumChangeEventCapture = new DebeziumChangeEventCapture();
        debeziumChangeEventCapture.setup(
                props, recordParserService, forceStart
        );
    }

    /**
     * Stops the Debezium event capture loop.
     *
     * @throws IOException if an error occurs stopping the engine
     */
    public static void stop() throws IOException {
        debeziumChangeEventCapture.stop();
        //Stop Rest API
        //DebeziumEmbeddedRestApi.stop();
    }

    /**
     * Sets up the monitoring thread that periodically checks the
     * replication progress and restarts the event loop if needed.
     *
     * @param config the ClickHouseSinkConnectorConfig
     * @param props  the properties controlling Debezium
     */
    private static void setupMonitoringThread(
            ClickHouseSinkConnectorConfig config,
            Properties props
    ) {
        try {
            boolean restartEventLoop = config.getBoolean(String.valueOf(
                    ClickHouseSinkConnectorConfigVariables
                            .RESTART_EVENT_LOOP
            ));
            if (!restartEventLoop) {
                return;
            }

            long restartEventLoopTimeout = config.getLong(
                    String.valueOf(
                            ClickHouseSinkConnectorConfigVariables
                                    .RESTART_EVENT_LOOP_TIMEOUT_PERIOD
                    )
            );

            monitoringTimerTask = new TimerTask() {
                @Override
                public void run() {
                    Thread.currentThread().setName(
                            "Sink connector Monitoring thread"
                    );
                    if (debeziumChangeEventCapture == null) {
                        return;
                    }
                    try {
                        long lastRecordTimestamp =
                                ReplicationStatusSingleton
                                        .getInstance()
                                        .getLastRecordTimestamp();
                        if (lastRecordTimestamp == -1) {
                            DebeziumJdbcStorageOperations ops =
                                    new DebeziumJdbcStorageOperations();
                            Connection conn = getDatabaseConnection(props);
                            long storedOffsetsInTable =
                                    ops.getLatestRecordTimestamp(
                                            conn, props
                                    );
                            conn.close();
                            if (storedOffsetsInTable == -1) {
                                lastRecordTimestamp = storedOffsetsInTable;
                            }
                        }
                        long deltaInSecs = (System.currentTimeMillis()
                                - lastRecordTimestamp) / 1000;
                        log.info("Last Record Timestamp: "
                                + lastRecordTimestamp + " Delta: "
                                + deltaInSecs + " Restart Event Loop "
                                + "Timeout: " + restartEventLoopTimeout);
                        if (deltaInSecs < restartEventLoopTimeout) {
                            return;
                        }
                        log.info("******* Restarting Event Loop ********");
                        debeziumChangeEventCapture.stop();
                        Thread.sleep(THREAD_SLEEP_RESTART_MS);
                        start(
                                injector.getInstance(
                                        DebeziumRecordParserService.class
                                ),
                                props,
                                true
                        );
                    } catch (IOException e) {
                        log.error("**** ERROR: Restarting Event Loop ****", e);
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        log.error("**** ERROR: Restarting Event Loop ****", e);
                        throw new RuntimeException(e);
                    }
                }
            };

            monitoringTimer = new Timer(true);
            monitoringTimer.scheduleAtFixedRate(
                    monitoringTimerTask,
                    0L,
                    restartEventLoopTimeout * 1000
            );

        } catch (Exception e) {
            log.error("Error setting up monitoring thread", e);
        }
    }

    /**
     * Returns the DebeziumChangeEventCapture instance.
     *
     * @return the DebeziumChangeEventCapture
     */
    public DebeziumChangeEventCapture getDebeziumEventCapture() {
        return debeziumChangeEventCapture;
    }
}
