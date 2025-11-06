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
@DisplayName("Integration test validating table creation with schema overrides by YAML data‑type mappings")
public class CreateTableSchemaOverrideByDataTypeMappingIT {

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
    public void testMySQLGeneratedColumnsByDataTypeMapping() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                // props.setProperty("replication.history.enable", "true");
                props.setProperty("default_column_datatype_mapping.transaction_id", "String");
                props.setProperty("default_column_datatype_mapping.gmt_time", "String");

                props.setProperty("databases.employees.tables.contacts.partition_by", "id");
                props.setProperty("databases.employees.tables.contacts.primary_key", "last_name");
                props.setProperty("databases.employees.tables.contacts.settings", "allow_nullable_key=1");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),  false);
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
                "email VARCHAR(100) NOT NULL,\n" +
                "gmt_time DATETIME NOT NULL);\n").execute();

        Thread.sleep(30000);

        conn.prepareStatement("insert into contacts(first_name, last_name, email , gmt_time) values('John', 'Doe', 'john.doe@gmail.com','2025-04-10 12:34:56')").execute();
        Thread.sleep(20000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(props);
        Map<String, String> columnsToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "contacts", "employees");

        Assert.assertTrue(columnsToDataTypeMap.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(columnsToDataTypeMap.get("first_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("last_name").equalsIgnoreCase("String"));
        // Assert.assertTrue(columnsToDataTypeMap.get("fullname").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("email").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time").equalsIgnoreCase("String"));

        ResultSet resultSet = ITCommon.executeQueryWithResultSet("select gmt_time from employees.contacts", writer.getConnection());
        boolean insertCheck = false;
        while (resultSet.next()) {
            insertCheck = true;
            String gmtTime = resultSet.getString("gmt_time");
            System.out.println(gmtTime);
            Assert.assertTrue(gmtTime.equalsIgnoreCase("2025-04-10 12:34:56.000"));
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

        HikariDbSource.close();
    }
}
