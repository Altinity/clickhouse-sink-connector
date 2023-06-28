package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import static com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage.*;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.javalin.Javalin;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.PatternLayout;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class ClickHouseDebeziumEmbeddedApplication {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseDebeziumEmbeddedApplication.class);

    private static ClickHouseDebeziumEmbeddedApplication embeddedApplication;

    private static DebeziumChangeEventCapture debeziumChangeEventCapture;



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
        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = new Properties();
        if(args.length > 0) {
            log.info(String.format("****** CONFIGURATION FILE: %s ********", args[0]));

            try {
                Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

                props.putAll(defaultProperties);
                Properties fileProps = new ConfigLoader().loadFromFile(args[0]);
                props.putAll(fileProps);
            } catch(Exception e) {
                log.error("Error parsing configuration file, USAGE: java -jar <jar_file> <yaml_config_file>");
                System.exit(-1);
            }
        } else {

            props = injector.getInstance(ConfigurationService.class).parse();
        }



        try {
            String cliPort = props.getProperty(SinkConnectorLightWeightConfig.CLI_PORT);
            if(cliPort == null || cliPort.isEmpty()) {
                cliPort = "7000";
            }

            Javalin app = Javalin.create().start(Integer.parseInt(cliPort));
            app.get("/", ctx -> {
                ctx.result("Hello World");
            });
            app.get("/stop", ctx -> {
                debeziumChangeEventCapture.stop();
            });
            Properties finalProps1 = props;
            app.get("/status", ctx -> {
                ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

                ctx.result(debeziumChangeEventCapture.getDebeziumStorageStatus(config, finalProps1));

            });

            app.post("/binlog", ctx -> {
                String body = ctx.body();
                JSONObject jsonObject = (JSONObject) new JSONParser().parse(body);
                String binlogFile = (String) jsonObject.get(BINLOG_FILE);
                String binlogPosition = (String) jsonObject.get(BINLOG_POS);
                String gtid = (String) jsonObject.get(GTID);
                ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

                debeziumChangeEventCapture.updateDebeziumStorageStatus(config, finalProps1, binlogFile, binlogPosition, gtid);
                log.info("Received update-binlog request: " + body);
            });

            app.post("/lsn", ctx -> {
                String body = ctx.body();
                JSONObject jsonObject = (JSONObject) new JSONParser().parse(body);
                String lsn = (String) jsonObject.get(LSN);

                ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

                debeziumChangeEventCapture.updateDebeziumStorageStatus(config, finalProps1, lsn);
                log.info("Received update-binlog request: " + body);
            });

            Properties finalProps = props;
            app.get("/start", ctx -> {

                CompletableFuture<String> cf = startDebeziumEventLoop(injector, finalProps);
                ctx.result("Started Debezium Event Loop");
            });

            // app.get("/updateBinLogStatus", ctx -> {
                //debeziumChangeEventCapture.updateDebeziumStorageStatus()
            //});
        } catch(Exception e) {
            log.error("Error starting REST API server", e);
        }

        embeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
        embeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                injector.getInstance(ConfigurationService.class),
                injector.getInstance(DDLParserService.class), props);






    }

    public static CompletableFuture<String> startDebeziumEventLoop(Injector injector, Properties props) throws InterruptedException {
        CompletableFuture<String> cf = new CompletableFuture<>();

        Executors.newCachedThreadPool().submit(() -> {
            debeziumChangeEventCapture.stop();

            Thread.sleep(500);
            embeddedApplication = new ClickHouseDebeziumEmbeddedApplication();

            embeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                    injector.getInstance(ConfigurationService.class),
                    injector.getInstance(DDLParserService.class), props);
            return null;
        });

        return cf;
    }


    public void start(DebeziumRecordParserService recordParserService,
                      ConfigurationService configurationService,
                      DDLParserService ddlParserService, Properties props) throws Exception {
        // Define the configuration for the Debezium Engine with MySQL connector...
       // log.debug("Loading properties");
        //final Properties props = new ConfigLoader().load();


        debeziumChangeEventCapture = new DebeziumChangeEventCapture();
        debeziumChangeEventCapture.setup(props, recordParserService, ddlParserService);

    }

    public void start(DebeziumRecordParserService recordParserService,
                      Properties props,
                      DDLParserService ddlParserService) throws Exception {
        // Define the configuration for the Debezium Engine with MySQL connector...
        log.debug("Loading properties");

        debeziumChangeEventCapture = new DebeziumChangeEventCapture();
        debeziumChangeEventCapture.setup(props, recordParserService, ddlParserService);

    }
}

