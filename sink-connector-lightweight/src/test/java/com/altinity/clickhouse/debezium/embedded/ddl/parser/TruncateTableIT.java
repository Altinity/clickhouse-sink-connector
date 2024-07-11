package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration Test that validates replication of Truncate Table DDL statement")
public class TruncateTableIT {

    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("truncate_table.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        // clickHouseContainer.start();
        Thread.sleep(15000);
    }

    static {
        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();
    }


    @Test
    @DisplayName("Test that validates create table in CH when MySQL has is_deleted columns")
    public void testIsDeleted() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "datatypes"),false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        Thread.sleep(30000);
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);


        //Validate if ship_class was truncated also in ClickHouse.
        // Validate that the table is empty in ClickHouse
        ResultSet rs = writer.executeQueryWithResultSet("select * from ship_class");
        boolean recordFoundShipClass = false;
        while(rs.next()) {
            recordFoundShipClass = true;
        }
        Assert.assertFalse(recordFoundShipClass);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table new_table(col1 varchar(255), col2 int, is_deleted int, _sign int)").execute();

        Thread.sleep(10000);

        conn.prepareStatement("insert into new_table values('test', 1, 22, 1)").execute();
        conn.close();
        Thread.sleep(10000);

        rs = writer.executeQueryWithResultSet("select * from new_table");
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            Assert.assertTrue(rs.getString("col1").equalsIgnoreCase("test"));
            Assert.assertTrue(rs.getInt("col2") == 1);
            Assert.assertTrue(rs.getInt("is_deleted") == 22);
            Assert.assertTrue(rs.getInt("_sign") == 1);
        }
        Assert.assertTrue(recordFound);

        // Run truncate table in MySQL
        conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("truncate table new_table").execute();
        Thread.sleep(10000);
        conn.close();

        // Validate that the table is empty in ClickHouse
        rs = writer.executeQueryWithResultSet("select * from new_table");
        recordFound = false;
        while(rs.next()) {
            recordFound = true;
        }
        Assert.assertFalse(recordFound);


        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
    }
}
