package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;

public class Debezium15KTablesLoadIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("15k_tables_mysql.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }

    @Test
    @Disabled
    @DisplayName("Test that validates skipping of large tables in schema_only mode")
    public void testLoadingTablesInSchemaOnlyMode() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("table.include.list","employees.table[1-3]$");
        props.setProperty("snapshot.mode","schema_only");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");

        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                        injector.getInstance(DDLParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(25000);

        // Confirm if only the whitelisted tabes were replicated.
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select name from system.tables where database='employees'");
        boolean insertCheck = false;
        List<String> tables = new ArrayList<>();
        while(dateTimeResult.next()) {
            insertCheck = true;
            System.out.println(dateTimeResult.getString("name"));
            tables.add(dateTimeResult.getString("name"));
            //Assert.assertTrue(dateTimeResult.getString("name").equalsIgnoreCase("DATETIME-INSERT55"));
        }
        Assert.assertTrue(insertCheck);

        Assert.assertTrue(tables.contains("table1"));
        Assert.assertTrue(tables.contains("table2"));
        Assert.assertTrue(tables.contains("table3"));

        Assert.assertFalse(tables.contains("table4"));
        Assert.assertFalse(tables.contains("table5"));
        Assert.assertFalse(tables.contains("table6"));

        // Close connection.
        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().stop();
    }
}
