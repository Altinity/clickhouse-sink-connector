package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration test to validate support for replication of multiple databases.
 */
@Testcontainers
@DisplayName("Integration Test that validates handling of multiple databases")
public class MySQLJsonIT
{

    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                // .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
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

    @DisplayName("Integration Test that validates handle of JSON data type from MySQL")
    @Test
    public void testMultipleDatabases() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        // Set the list of databases captured.
        props.put("database.whitelist", "employees,test_db,test_db2");
        props.put("database.include.list", "employees,test_db,test_db2");

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "test_db"),false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);

        conn.createStatement().execute("CREATE DATABASE test_db2");
        Thread.sleep(5000);
        // Create a new database
        conn.createStatement().execute("CREATE TABLE employees.audience ("
                + "id int unsigned NOT NULL AUTO_INCREMENT, "
                + "client_id int unsigned NOT NULL, "
                + "list_id int unsigned NOT NULL, "
                + "status tinyint NOT NULL, "
                + "email varchar(200) CHARACTER SET utf16 COLLATE utf16_unicode_ci NOT NULL, "
                + "custom_properties JSON, "
                + "source tinyint unsigned NOT NULL DEFAULT '0', "
                + "created_date datetime DEFAULT NULL, "
                + "modified_date datetime DEFAULT NULL, "
                + "property_update_date datetime DEFAULT NULL, "
                + "PRIMARY KEY (id), "
                + "KEY cid_email (client_id,email), "
                + "KEY cid (client_id,list_id,status), "
                + "KEY contact_created (created_date), "
                + "KEY idx_email (email)"
                + ") ENGINE=InnoDB CHARSET=utf16 COLLATE=utf16_unicode_ci");


        Thread.sleep(5000);
        // Insert a new row.
        conn.createStatement().execute("INSERT INTO employees.audience (client_id, list_id, status, email, custom_properties, source, created_date, modified_date, property_update_date)" +
                " VALUES (1, 100, 1, 'example@example.com', '{\"name\": \"John\", \"age\": 30}', 1, '2024-05-13 12:00:00', '2024-05-13 12:00:00', '2024-05-13 12:00:00')");

        Thread.sleep(10000);
        conn.close();

        // Create connection to clickhouse and validate if the tables are replicated.
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "system");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "system", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);
        // query clickhouse connection and get data for test_table1 and test_table2


        ResultSet rs = writer.executeQueryWithResultSet("SELECT * FROM employees.audience");
        // Validate the data
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            assert rs.getInt("id") == 1;
            //assert rs.getString("name").equalsIgnoreCase("test");
        }
        Assert.assertTrue(recordFound);



        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();


    }
}
