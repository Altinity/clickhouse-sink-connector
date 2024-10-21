package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.Assert.assertTrue;

public class DatabaseOverrideRRMTIT {

    private static final Logger log = LoggerFactory.getLogger(DatabaseOverrideRRMTIT.class);


    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    static GenericContainer zookeeperContainer = new GenericContainer(DockerImageName.parse("zookeeper:3.6.2"))
            .withExposedPorts(2181).withAccessToHost(true);

    @BeforeEach
    public void startContainers() throws InterruptedException {

        Network network = Network.newNetwork();
        zookeeperContainer.withNetwork(network).withNetworkAliases("zookeeper");
        zookeeperContainer.start();
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
//                .withInitScript("15k_tables_mysql.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
                //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
                .withUsername("ch_user")
                .withPassword("password")
                .withClasspathResourceMapping("config_replicated.xml", "/etc/clickhouse-server/config.d/config.xml", BindMode.READ_ONLY)
                .withClasspathResourceMapping("macros.xml", "/etc/clickhouse-server/config.d/macros.xml", BindMode.READ_ONLY)
                .withExposedPorts(8123)
                .waitingFor(new HttpWaitStrategy().forPort(zookeeperContainer.getFirstMappedPort()));
        clickHouseContainer.withNetwork(network).withNetworkAliases("clickhouse");
        clickHouseContainer.start();

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }


    @DisplayName("Test that validates overriding database name in ClickHouse for ReplicatedReplacingMergeTree(RRMT)")
    @Test
    public void testDatabaseOverride() throws Exception {

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "system");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        writer.executeQuery("CREATE DATABASE employees2");
        writer.executeQuery("CREATE DATABASE productsnew");

        Thread.sleep(10000);
        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "schema_only");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("clickhouse.database.override.map", "employees:employees2, products:productsnew");
        props.setProperty("database.include.list", "employees, products, customers");
        props.setProperty(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        props.setProperty(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString(), "true");
        props.setProperty("ddl.retry", "true");
        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),  props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(25000);

        // Employees table
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();

        // Insert a new row in the table
        conn.prepareStatement("insert into newtable values('a', 1, 1)").execute();


        conn.prepareStatement("create database products").execute();
        conn.prepareStatement("create table products.prodtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();
        conn.prepareStatement("insert into products.prodtable values('a', 1, 1)").execute();

        conn.prepareStatement("create database customers").execute();
        conn.prepareStatement("create table customers.custtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();
        conn.prepareStatement("insert into customers.custtable values('a', 1, 1)").execute();


        Thread.sleep(10000);

        // Validate in Clickhouse the last record written is 29999


        long col2 = 0L;
        ResultSet version1Result = writer.executeQueryWithResultSet("select col2 from employees2.newtable final where col1 = 'a'");
        while(version1Result.next()) {
            col2 = version1Result.getLong("col2");
        }
        Thread.sleep(10000);
        assertTrue(col2 == 1);

        long productsCol2 = 0L;
        ResultSet productsVersionResult = writer.executeQueryWithResultSet("select col2 from productsnew.prodtable final where col1 = 'a'");
        while(productsVersionResult.next()) {
            productsCol2 = productsVersionResult.getLong("col2");
        }
        assertTrue(productsCol2 == 1);
        Thread.sleep(10000);

        long customersCol2 = 0L;
        ResultSet customersVersionResult = writer.executeQueryWithResultSet("select col2 from customers.custtable final where col1 = 'a'");
        while(customersVersionResult.next()) {
            customersCol2 = customersVersionResult.getLong("col2");
        }
        assertTrue(customersCol2 == 1);


        Thread.sleep(10000);
        // Execute the query in MySQL to rename table.
        conn.prepareStatement("rename table products.prodtable to products.prodtable2").execute();
        Thread.sleep(10000);
        ResultSet customersVersionResult2 = writer.executeQueryWithResultSet("select col2 from customers.custtable2 final where col1 = 'a'");
        while(customersVersionResult2.next()) {
            customersCol2 = customersVersionResult2.getLong("col2");
        }
        assertTrue(customersCol2 == 2);

        // validate that the table prodtaable2 is present in clickhouse
        ResultSet chRs = writer.executeQueryWithResultSet("select * from products.prodtable2");
        boolean recordFound = false;
        while(chRs.next()) {
            recordFound = true;
            assert chRs.getInt("id") == 1;
            //assert rs.getString("name").equalsIgnoreCase("test");
        }

        assertTrue(recordFound);

        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine.close();

        conn.close();
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
    }
}
