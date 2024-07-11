package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLBaseIT;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.connectToMySQL;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;

@Testcontainers
@DisplayName("Integration Test that validates replication of Create DDL with Generated columns")
public class MySQLGenerateColumnsTest {

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);


    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
              //  .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(25000);
    }

    @Test
    public void testMySQLGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                                "employees"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        Connection conn = connectToMySQL(mySqlContainer);

        conn.prepareStatement("\n" +
                "CREATE TABLE employees.contacts (id INT AUTO_INCREMENT PRIMARY KEY NOT NULL,\n" +
                "first_name VARCHAR(50) NOT NULL,\n" +
                "last_name VARCHAR(50) NOT NULL,\n" +
                "fullname varchar(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)),\n" +
                "email VARCHAR(100) NOT NULL);\n").execute();

        Thread.sleep(30000);

        conn.prepareStatement("insert into contacts(first_name, last_name, email) values('John', 'Doe', 'john.doe@gmail.com')").execute();
        Thread.sleep(20000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
        ClickHouseConnection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);
        Map<String, String> columnsToDataTypeMap = writer.getColumnsDataTypesForTable("contacts");

        Assert.assertTrue(columnsToDataTypeMap.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(columnsToDataTypeMap.get("first_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("last_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("fullname").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("email").equalsIgnoreCase("String"));

        ResultSet resultSet = writer.executeQueryWithResultSet("select fullname from contacts");
        boolean insertCheck = false;
        while (resultSet.next()) {
                insertCheck = true;
                String fullname = resultSet.getString("fullname");
                Assert.assertTrue(fullname.equalsIgnoreCase("John Doe"));
        }
        Thread.sleep(10000);

        Assert.assertTrue(insertCheck);
        writer.getConnection().close();

        Thread.sleep(10000);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
    }
}
