package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.connectToMySQL;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;

@Testcontainers
@DisplayName("Integration test validating table alter (add column) with schema overrides by YAML data‑type mappings")
@Disabled
public class AlterTableSchemaOverrideByDataTypeMappingIT {

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
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

    @AfterEach
    public void tearDown() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
    }

    @Test
    public void testMySQLAddColumnsOverridesByDataTypeMapping() throws Exception {
        // Start Debezium Change Event Capture in a separate thread
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        executorService.execute(() -> {
            try {

                props.setProperty("default_column_datatype_mapping.gmt_time", "String");
                props.setProperty("default_column_datatype_mapping.gmt_time3", "String");
                props.setProperty("default_column_datatype_mapping.gmt_time4", "String");

                props.setProperty("databases.employees.tables.contacts.partition_by", "id");
                props.setProperty("databases.employees.tables.contacts.primary_key", "last_name");
                props.setProperty("databases.employees.tables.contacts.settings", "allow_nullable_key=1");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Give Debezium time to initialize
        Thread.sleep(30000);

        // Connect to MySQL and create the `contacts` table
        Connection conn = connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "CREATE TABLE employees.contacts (\n" +
                        "  id INT AUTO_INCREMENT PRIMARY KEY NOT NULL,\n" +
                        "  first_name VARCHAR(50) NOT NULL,\n" +
                        "  last_name VARCHAR(50) NOT NULL,\n" +
                        "  fullname VARCHAR(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)) STORED,\n" +
                        "  email VARCHAR(100) NOT NULL,\n" +
                        "  gmt_time DATETIME NOT NULL\n" +
                        ");"
        ).execute();
        // Wait for the table creation to propagate
        Thread.sleep(30000);

        // Alter the table to add two new DATETIME columns
        conn.prepareStatement(
                "ALTER TABLE employees.contacts\n" +
                        "  ADD COLUMN gmt_time3 DATETIME NOT NULL,\n" +
                        "  ADD COLUMN gmt_time4 DATETIME NOT NULL;"
        ).execute();
        // Allow the schema change to take effect
        Thread.sleep(20000);

        // Insert a row including values for the new columns
        conn.prepareStatement(
                "INSERT INTO employees.contacts (" +
                        " first_name, last_name, email, gmt_time, gmt_time3, gmt_time4" +
                        ") VALUES (" +
                        " 'John', 'Doe', 'john.doe@gmail.com'," +
                        " '2025-04-10 12:34:56'," +
                        " '2025-04-10 12:35:56'," +
                        " '2025-04-10 12:36:56'" +
                        ");"
        ).execute();
        // Give Debezium time to capture the insert event
        Thread.sleep(20000);

        // Obtain ClickHouse writer and metadata utility
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(props);
        Map<String, String> columnsToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "contacts", "employees"
        );

        // Assert that the two new DATETIME columns map to ClickHouse String type

        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time3").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time4").equalsIgnoreCase("Nullable(String)"));

        // Query ClickHouse to verify the actual stored values (converted to UTC)
        ResultSet resultSet = ITCommon.executeQueryWithResultSet(
                "SELECT gmt_time, gmt_time3, gmt_time4 FROM employees.contacts",
                writer.getConnection()
        );
        boolean insertCheck = false;

        while (resultSet.next()) {
            insertCheck = true;
            String gmtTime = resultSet.getString("gmt_time");
            System.out.println(gmtTime);

            String gmtTime3 = resultSet.getString("gmt_time3");
            System.out.println(gmtTime3);

            String gmtTime4 = resultSet.getString("gmt_time4");
            System.out.println(gmtTime4);

             Assert.assertEquals("2025-04-10 12:34:56.000", resultSet.getString("gmt_time"));
             Assert.assertEquals("2025-04-10 12:35:56.000", resultSet.getString("gmt_time3"));
             Assert.assertEquals("2025-04-10 12:36:56.000", resultSet.getString("gmt_time4"));
        }

        // Clean up resources: close connections and stop background services
        writer.getConnection().close();
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }
}
