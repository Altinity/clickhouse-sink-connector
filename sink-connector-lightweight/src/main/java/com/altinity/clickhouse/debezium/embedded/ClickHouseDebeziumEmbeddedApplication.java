package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class ClickHouseDebeziumEmbeddedApplication {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseDebeziumEmbeddedApplication.class);

    /**
     * Main Entry for the application
     * @param args arguments
     * @throws Exception Exception
     */
    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();

        System.setProperty("log4j.configurationFile", "resources/log4j2.xml");

        Injector injector = Guice.createInjector(new AppInjector());

        ClickHouseDebeziumEmbeddedApplication csg = new ClickHouseDebeziumEmbeddedApplication();
        csg.start(injector.getInstance(DebeziumRecordParserService.class),
                injector.getInstance(ConfigurationService.class),
                injector.getInstance(DDLParserService.class));
    }


    public void start(DebeziumRecordParserService recordParserService,
                      ConfigurationService configurationService,
                      DDLParserService ddlParserService) throws Exception {
        // Define the configuration for the Debezium Engine with MySQL connector...
        log.info("Loading properties");
        //final Properties props = new ConfigLoader().load();

        Properties props = configurationService.parse();

        DebeziumChangeEventCapture eventCapture = new DebeziumChangeEventCapture();
        eventCapture.setup(props, recordParserService, ddlParserService);

    }

    public void start(DebeziumRecordParserService recordParserService,
                      Properties props,
                      DDLParserService ddlParserService) throws Exception {
        // Define the configuration for the Debezium Engine with MySQL connector...
        log.info("Loading properties");

        DebeziumChangeEventCapture eventCapture = new DebeziumChangeEventCapture();
        eventCapture.setup(props, recordParserService, ddlParserService);

    }
}

