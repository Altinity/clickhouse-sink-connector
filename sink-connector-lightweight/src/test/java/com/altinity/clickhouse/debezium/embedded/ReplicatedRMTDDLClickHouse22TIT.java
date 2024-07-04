package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration test that validates auto creation of Replicated RMT when the flag is set in config(auto.create.replicated.tables)")
public class ReplicatedRMTDDLClickHouse22TIT {
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
                .withInitScript("data_types_test.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        // clickHouseContainer.start();
        Thread.sleep(15000);

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:22.3")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withClasspathResourceMapping("config_replicated.xml", "/etc/clickhouse-server/config.d/config.xml", BindMode.READ_ONLY)
                .withClasspathResourceMapping("macros.xml", "/etc/clickhouse-server/config.d/macros.xml", BindMode.READ_ONLY)
                .withExposedPorts(8123)
                        .waitingFor(new HttpWaitStrategy().forPort(zookeeperContainer.getFirstMappedPort()));
        clickHouseContainer.withNetwork(network).withNetworkAliases("clickhouse");
        clickHouseContainer.start();
    }


    @ParameterizedTest
    @CsvSource({
            "clickhouse/clickhouse-server:22.3"
    })
    @DisplayName("Test that validates creation of Replicated Replacing Merge Tree")
    public void testReplicatedRMTAutoCreate(String clickHouseServerVersion) throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        props.setProperty(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString(), "true");
        //props.setProperty(SinkConnectorLightWeightConfig.DISABLE_DDL, "true");


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        Thread.sleep(30000);
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
        ClickHouseConnection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);

        ResultSet rs = writer.executeQueryWithResultSet("show create table string_types_MEDIUMTEXT_utf8mb4");
        // Validate that all the tables are created.
        boolean resultValidated = false;
        while(rs.next()) {
            resultValidated = true;
            String createTableDML = rs.getString(1);
            System.out.println(createTableDML);
            assert(createTableDML.contains("ReplicatedReplacingMergeTree"));
        }

        Assert.assertTrue(resultValidated);

        boolean dataValidated = false;
        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select * from string_types_MEDIUMTEXT_utf8mb4");

        while(dateTimeResult.next()) {
            dataValidated = true;
            System.out.println(dateTimeResult.getString("Type").toString());
            System.out.println(dateTimeResult.getString("Value").toString());

            Assert.assertTrue(dateTimeResult.getString("Type").toString().equalsIgnoreCase("mediumtext"));
            Assert.assertTrue(dateTimeResult.getString("Value").toString().equalsIgnoreCase("????"));
        }
        Assert.assertTrue(dataValidated);

        // Create a new table in MySQL
        conn.createStatement().execute("CREATE TABLE `l1` (`uid` int unsigned NOT NULL,\n" +
                " `st` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                " `dt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "  `dt_tz` int DEFAULT NULL,\n" +
                "  `lat` decimal(9,7) DEFAULT NULL,\n" +
                "  `long` decimal(10,7) DEFAULT NULL,\n" +
                "  `did` int unsigned DEFAULT NULL,\n" +
                "   `lname_id` int unsigned DEFAULT NULL,\n" +
                " PRIMARY KEY (`uid`,`dt`),\n" +
                "KEY `st` (`st`)\n" +
                " ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ");

        Thread.sleep(10000);

        // Verify on ClickHouse if the table is created.
        rs = writer.executeQueryWithResultSet("show create table l1");
        // Validate that all the tables are created.
        resultValidated = false;
        while(rs.next()) {
            resultValidated = true;
            String createTableDML = rs.getString(1);
            System.out.println(createTableDML);
            assert(createTableDML.contains("ReplicatedReplacingMergeTree"));
        }
        Assert.assertTrue(resultValidated);

        if(engine.get() != null) {
            engine.get().stop();
        }
        conn.close();
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
   }

}
