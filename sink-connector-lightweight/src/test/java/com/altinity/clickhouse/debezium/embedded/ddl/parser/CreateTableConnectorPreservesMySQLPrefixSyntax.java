package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@DisplayName("Integration test to check and fix preserves MySQL prefix syntax, leading to DDL error in ClickHouse")
public class CreateTableConnectorPreservesMySQLPrefixSyntax {

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
    public void testPreservesMySQLPrefixSyntax() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

        executorService.execute(() -> {
            try {

                // props.setProperty("offset.storage.jdbc.password", "abcd%t");
                // props.setProperty("schema.history.internal.jdbc.password", "abcd%t");
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        Connection conn = connectToMySQL(mySqlContainer);

        // -----------------------------------------------------------------------------
        // 1.  Create the source (MySQL) table with only 3 columns
        //     • id_registro  LONGTEXT         – business primary key
        //     • descripcion  VARCHAR(100)     – arbitrary text
        //     • gmt_time     DATETIME         – timestamp
        //
        //    Because id_registro is LONGTEXT, we create a prefix index (10 chars)
        //    as both PRIMARY KEY and secondary KEY, exactly like the production schema
        // -----------------------------------------------------------------------------
        conn.prepareStatement(
                "CREATE TABLE employees.REG_DIVORCIO_PD (" +
                        "  id_registro LONGTEXT NOT NULL," +
                        "  descripcion VARCHAR(100) NOT NULL," +
                        "  gmt_time DATETIME NOT NULL," +
                        "  PRIMARY KEY (id_registro(10))," +
                        "  KEY PK_DIVORCIO (id_registro(10))" +
                        ") ENGINE=InnoDB " +
                        "  DEFAULT CHARSET=utf8mb4 " +
                        "  COLLATE=utf8mb4_0900_ai_ci;"
        ).execute();

        // --- Give Debezium / connector time to pick up the DDL ------------------------
        Thread.sleep(20000);

        // -----------------------------------------------------------------------------
        // 2.  Insert a single test row
        // -----------------------------------------------------------------------------
        conn.prepareStatement(
                "INSERT INTO employees.REG_DIVORCIO_PD " +
                        "  (id_registro, descripcion, gmt_time) " +
                        "VALUES " +
                        "  ('DIV-0000000001-XYZ', 'Sample divorce record', '2025-04-10 12:34:56');"
        ).execute();

        // --- Give Debezium / connector time to ship the row --------------------------
        Thread.sleep(10000);

        // -----------------------------------------------------------------------------
        // 3.  Fetch ClickHouse metadata for the replicated table and assert mappings
        // -----------------------------------------------------------------------------
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata  dbMetadata = new DBMetadata(props);

        Map<String, String> colTypes =
                dbMetadata.getColumnsDataTypesForTable(
                        writer.getConnection(),
                        /* table */  "REG_DIVORCIO_PD",
                        /* schema */ "employees"
                );

        // Expected ClickHouse types after LONGTEXT → String and DATETIME → DateTime64
        Assert.assertTrue(colTypes.get("id_registro").equalsIgnoreCase("String"));
        Assert.assertTrue(colTypes.get("descripcion").equalsIgnoreCase("String"));
        Assert.assertTrue(colTypes.get("gmt_time").toLowerCase().startsWith("datetime")); // DateTime64, etc.

        // -----------------------------------------------------------------------------
        // 3-bis.  Query ClickHouse to verify that the values were replicated correctly
        //         (Debezium sends all timestamps to ClickHouse in UTC)
        // -----------------------------------------------------------------------------
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "SELECT id_registro, descripcion, gmt_time " +   // add extra cols if needed
                        "FROM employees.REG_DIVORCIO_PD",
                writer.getConnection()
        );

        boolean rowFound = false;

        while (rs.next()) {
            rowFound = true;

            // Print values to help troubleshooting in CI logs
            String idRegistro = rs.getString("id_registro");
            String descripcion = rs.getString("descripcion");
            String gmtTime     = rs.getString("gmt_time");

            System.out.printf("ClickHouse row → id_registro=%s, descripcion=%s, gmt_time=%s%n",
                    idRegistro, descripcion, gmtTime);

            // ---------- Assertions ----------
            Assert.assertEquals("DIV-0000000001-XYZ", idRegistro);
            Assert.assertEquals("Sample divorce record", descripcion);
            Assert.assertEquals("2025-04-10 12:34:56", gmtTime);
        }

        Assert.assertTrue("No rows found in ClickHouse!", rowFound);
        rs.close();

        writer.getConnection().close();
        Thread.sleep(10000);
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();

    }
}
