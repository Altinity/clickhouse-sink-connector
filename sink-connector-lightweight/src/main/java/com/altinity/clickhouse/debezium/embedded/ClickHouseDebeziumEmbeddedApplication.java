package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class ClickHouseDebeziumEmbeddedApplication {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseDebeziumEmbeddedApplication.class);

    private static ClickHouseDebeziumEmbeddedApplication embeddedApplication;

    private static DebeziumChangeEventCapture debeziumChangeEventCapture;


    private static Properties userProperties = new Properties();

    private static Injector injector;

    private static Properties props;

    private static Timer monitoringTimer;

    private static TimerTask monitoringTimerTask;

    /**
     * Main Entry for the application
     * @param args arguments
     * @throws Exception Exception
     */
    public static void main(String[] args) throws Exception {
        //BasicConfigurator.configure();
        System.setProperty("log4j.configurationFile", "resources/log4j2.xml");

        org.apache.log4j.Logger root = org.apache.log4j.Logger.getRootLogger();
        root.addAppender(new ConsoleAppender(new PatternLayout("%r %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %p %c %x - %m%n")));

        String loggingLevel = System.getenv("LOGGING_LEVEL");
        if(loggingLevel != null) {
            // If the user passes a wrong level, it defaults to DEBUG
            LogManager.getRootLogger().setLevel(Level.toLevel(loggingLevel));
        } else {
            LogManager.getRootLogger().setLevel(Level.INFO);
        }
        injector = Guice.createInjector(new AppInjector());

        props = new Properties();
        if(args.length > 0) {
            log.info(String.format("****** CONFIGURATION FILE: %s ********", args[0]));

            try {
                Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

                props.putAll(defaultProperties);
                Properties fileProps = new ConfigLoader().loadFromFile(args[0]);
                props.putAll(fileProps);
            } catch(Exception e) {
                log.error("Error parsing configuration file, USAGE: java -jar <jar_file> <yaml_config_file>: \n" + e.toString());
                System.exit(-1);
            }
        } else {

            props = injector.getInstance(ConfigurationService.class).parse();
        }

        embeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
        try {
            DebeziumEmbeddedRestApi.startRestApi(props, injector, debeziumChangeEventCapture, userProperties);
        } catch(Exception e) {
            log.error("Error starting REST API server", e);
        }

        setupMonitoringThread(new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props)), props);

        embeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                injector.getInstance(DDLParserService.class), props, false);
    }

    /**
     * Force start replication from REST API.
     * @param injector
     * @param props
     * @return
     * @throws InterruptedException
     */
    public static CompletableFuture<String> startDebeziumEventLoop(Injector injector, Properties props) throws InterruptedException {
        CompletableFuture<String> cf = new CompletableFuture<>();

        Executors.newCachedThreadPool().submit(() -> {
            debeziumChangeEventCapture.stop();

            Thread.sleep(500);
            // embeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
            embeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                    injector.getInstance(DDLParserService.class), props, true);
            return null;
        });

        return cf;
    }


    public static void start(DebeziumRecordParserService recordParserService,
                             DDLParserService ddlParserService, Properties props, boolean forceStart) throws Exception {


        debeziumChangeEventCapture = new DebeziumChangeEventCapture();
        debeziumChangeEventCapture.setup(props, recordParserService, ddlParserService, forceStart);


    }

    public static void stop() throws IOException {
        debeziumChangeEventCapture.stop();

    }

    /**
     * Function to setup monitoring thread.
     * @param config
     */
    private static void setupMonitoringThread(ClickHouseSinkConnectorConfig config, Properties props) {

        try {
            // Stop the timer, if its already running.
            boolean restartEventLoop = config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP));

            if (!restartEventLoop) {
                return;
            }

            long restartEventLoopTimeout = config.getLong(String.valueOf(ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP_TIMEOUT_PERIOD));

            monitoringTimerTask = new TimerTask() {
                @Override
                public void run() {
                    Thread.currentThread().setName("Sink connector Monitoring thread");
                    if (debeziumChangeEventCapture == null) {
                        return;
                    }
                    try {
                        long lastRecordTimestamp = debeziumChangeEventCapture.getLastRecordTimestamp();
                        if(lastRecordTimestamp == -1) {
                            // Check the last record timestamp from the table.
                            long storedOffsetsInTable = debeziumChangeEventCapture.getLatestRecordTimestamp(config, props);
                            if(storedOffsetsInTable == -1) {
                                lastRecordTimestamp = storedOffsetsInTable;
                            }
                        }
                        // calculate delta.
                        long deltaInSecs = (System.currentTimeMillis() - lastRecordTimestamp) / 1000;
                        log.info("Last Record Timestamp: " + lastRecordTimestamp + " Delta: " + deltaInSecs + " Restart Event Loop Timeout: " + restartEventLoopTimeout);
                        if (deltaInSecs < restartEventLoopTimeout) {
                            return;
                        }
                        log.info("******* Restarting Event Loop ********");
                        debeziumChangeEventCapture.stop();
                        Thread.sleep(3000);
                        start(injector.getInstance(DebeziumRecordParserService.class),
                                injector.getInstance(DDLParserService.class), props, true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }

                }
            };
            //running timer task as daemon thread
            monitoringTimer = new Timer(true);
            monitoringTimer.scheduleAtFixedRate(monitoringTimerTask, 0, restartEventLoopTimeout * 1000);

        } catch (Exception e) {
            log.error("Error setting up monitoring thread", e);
        }

    }

    public DebeziumChangeEventCapture getDebeziumEventCapture() {
        return debeziumChangeEventCapture;
    }
}