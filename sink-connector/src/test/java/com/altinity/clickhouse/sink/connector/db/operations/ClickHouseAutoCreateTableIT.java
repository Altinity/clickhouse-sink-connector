package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.kafka.connect.data.Field;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Testcontainers
public class ClickHouseAutoCreateTableIT extends com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTableBase {

    static Map<String, String> columnToDataTypesMap;

    static Connection conn;

    ClickHouseSinkConnectorConfig config;

    @Container
    private static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8")
            .withInitScript("./init_clickhouse.sql")
            .withUsername("root")
            .withPassword("root")
            .withExposedPorts(8123)
            .waitingFor(new HttpWaitStrategy().forPort(8123));

    @AfterAll
    public static void tearDown() throws SQLException {
        HikariDbSource.close();
    }

    @BeforeEach
    public void setUp() {
        config = new ClickHouseSinkConnectorConfig(new HashMap<>());
    }
    @BeforeAll
    public static void initialize() throws InterruptedException {
        clickHouseContainer.start();
        Thread.sleep(10000);

        columnToDataTypesMap =  getExpectedColumnToDataTypesMap();

        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "auto_create_table";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());


        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        Connection conn = DbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                BaseDbWriter.SYSTEM_DB, config);
        DbWriter writer = new DbWriter(hostName, port, database, tableName, userName, password, config, null, conn);

        conn = writer.getConnection();

    }


    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        Field[] fields = createFields();
        Map<String, String> colNameToDataTypeMap = act.getColumnNameToCHDataTypeMapping(fields,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Map<String, String> expectedColNameToDataTypeMap = getExpectedColumnToDataTypesMap();

        // Assert.assertTrue(colNameToDataTypeMap.equals(expectedColNameToDataTypeMap));
        Assert.assertFalse(colNameToDataTypeMap.isEmpty());
    }


    @Test
    @Tag("IntegrationTest")
    @Disabled
    public void testCreateMergeTreeHistoryTable() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "test";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees5_history";

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        Connection conn = DbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                BaseDbWriter.SYSTEM_DB, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        try {
            act.createHistoryTable(primaryKeys, tableName, "test", this.createFields(), writer.getConnection(), this.config);
        } catch(SQLException se) {
            Assert.assertTrue(false);
        }
    }


    @Test
    @Tag("IntegrationTest")
    @Disabled
    public void testCreateNewTable() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "test";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";


        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        Connection conn = DbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                BaseDbWriter.SYSTEM_DB, new ClickHouseSinkConnectorConfig(new HashMap<>()));



        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        try {
            act.createNewTable(primaryKeys, tableName, "test", this.createFields(), writer.getConnection(),
                    false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));
        } catch(SQLException se) {
            Assert.assertTrue(false);
        }
    }

}
