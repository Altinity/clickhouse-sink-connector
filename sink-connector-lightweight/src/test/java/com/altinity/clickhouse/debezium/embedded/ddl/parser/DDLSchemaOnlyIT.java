package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
public class DDLSchemaOnlyIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Override
    protected Properties getDebeziumProperties() throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        // **** OVERRIDE set to schema only
        defaultProps.setProperty("snapshot.mode", "schema_only");
        defaultProps.setProperty("disable.drop.truncate", "true");

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
        defaultProps.setProperty("clickhouse.server.database", "employees");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;

    }
    @Test
    public void testSchemaOnlyMode() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(40000);//

        Connection conn = connectToMySQL();
        // alter table ship_class change column class_name class_name_new int;
        // alter table ship_class change column tonange tonange_new decimal(10,10);

        conn.prepareStatement("insert into dt values('2008-01-01 00:00:01', 'this is a test', 11, 2)").execute();


        Thread.sleep(10000);


        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        String result = writer.executeQuery("select timestamp from employees.dt");

        System.out.println(result);
        Assert.assertTrue(result.equalsIgnoreCase("2008-01-01 00:00:01"));
        // Validate all ship_class columns.
    //        Assert.assertTrue(shipClassColumns.get("ship_spec").equalsIgnoreCase("Nullable(String)"));


        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();



    }
}
