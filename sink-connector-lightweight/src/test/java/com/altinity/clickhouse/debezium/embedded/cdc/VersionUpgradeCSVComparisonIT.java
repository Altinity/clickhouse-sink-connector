package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test simulating a connector version upgrade.
 *
 * <p>Pre-loads a 2.8.0 CSV export directly into the ClickHouse target table
 * ({@code test.customers} ReplacingMergeTree), then starts the new connector which
 * snapshots the same rows from MySQL. Verifies that:
 * <ul>
 *   <li>Pre-existing rows are NOT overwritten by the new connector's snapshot</li>
 *   <li>New rows inserted post-snapshot appear correctly</li>
 *   <li>No rows are stuck with is_deleted=1</li>
 * </ul>
 *
 * <p>To use with real production data, replace {@code version_upgrade_baseline.csv}
 * with the actual ClickHouse export from 2.8.0.
 */
@Testcontainers
@Tag("upgrade")
@DisplayName("Verifies version upgrade: pre-existing rows not overwritten by new connector")
public class VersionUpgradeCSVComparisonIT {

    private static final Logger log = LoggerFactory.getLogger(VersionUpgradeCSVComparisonIT.class);

    private static final String BASELINE_CSV = "version_upgrade_baseline.csv";
    private static final String TARGET_TABLE = "test.customers";
    private static final int INITIAL_ROWS = 122;
    private static final int NEW_ROWS = 3;
    private static final int TOTAL_ROWS = INITIAL_ROWS + NEW_ROWS;

    protected MySQLContainer mySqlContainer;
    private final AtomicReference<Throwable> connectorError = new AtomicReference<>();

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(
            DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("test").withUsername("root").withPassword("adminpass")
                .withInitScript("version_upgrade_mysql_init.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stopContainers() {
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    @Test
    @DisplayName("Pre-existing rows preserved after connector snapshot and new inserts")
    public void oldRowsNotOverwrittenAfterNewInserts() throws Exception {
        Injector injector = Guice.createInjector(new AppInjector());
        Properties props = buildProps();

        Connection ch = ITCommon.getDBWriter(clickHouseContainer, "default").getConnection();
        Connection mysql = ITCommon.connectToMySQL(mySqlContainer);

        // Phase 1: Pre-load the 2.8.0 CSV directly into the target table.
        // This simulates ClickHouse already containing data from the old connector version.
        preloadCSVIntoTarget(ch);
        int preloadedCount = liveCount(ch);
        log.info("Phase 1: Pre-loaded {} rows from 2.8.0 CSV into {}", preloadedCount, TARGET_TABLE);
        assertTrue(preloadedCount > 0, "CSV must contain at least one row");

        // Phase 2: Start the new connector. It will snapshot from MySQL and write the same
        // rows with its own _version values. The RMT merge keeps the higher _version, so
        // pre-existing rows should remain intact.
        ExecutorService exec = startConnector(injector, props);
        Thread.sleep(30000);
        log.info("Phase 2: Connector started and snapshot completed. Live count: {}", liveCount(ch));

        // Phase 3: INSERT new rows into MySQL (binlog replication path)
        try (Statement s = mysql.createStatement()) {
            s.execute("INSERT INTO test.customers VALUES "
                    + "(901,'New Corp Alpha','Smith','John','555-0901','100 New St',NULL,'Austin','TX','73301','USA',NULL,50000.00),"
                    + "(902,'New Corp Beta','Jones','Mary','555-0902','200 New Ave',NULL,'Denver','CO','80201','USA',NULL,75000.00),"
                    + "(903,'New Corp Gamma','Williams','Bob','555-0903','300 New Blvd','Suite 5','Seattle','WA','98101','USA',NULL,100000.00)");
        }
        log.info("Phase 3: Inserted {} new rows into MySQL", NEW_ROWS);
        Thread.sleep(30000);

       // stopConnector(exec);

        // Force merge for deterministic FINAL results
        try (Statement s = ch.createStatement()) {
            s.execute("OPTIMIZE TABLE " + TARGET_TABLE + " FINAL");
        }
        Thread.sleep(3000);

        // Phase 4: Assertions using FINAL
        int finalLiveCount = liveCount(ch);
        log.info("Phase 4: Final live count = {} (expected {})", finalLiveCount, TOTAL_ROWS);

        assertEquals(TOTAL_ROWS, finalLiveCount,
                "Expected " + TOTAL_ROWS + " live rows after FINAL, got " + finalLiveCount);

        // 4a: No stuck-deleted rows
        int stuckDeleted = scalarInt(ch,
                "SELECT count() FROM (SELECT customerNumber, argMax(is_deleted, _version) AS d "
                + "FROM " + TARGET_TABLE + " GROUP BY customerNumber HAVING d = 1)");
        assertEquals(0, stuckDeleted,
                "Found " + stuckDeleted + " rows stuck with is_deleted=1");

        // 4b: All original PKs still present (customerNumber range from MySQL init)
        int originalCount = scalarInt(ch,
                "SELECT count() FROM " + TARGET_TABLE + " FINAL "
                + "WHERE is_deleted=0 AND customerNumber NOT IN (901, 902, 903)");
        assertEquals(INITIAL_ROWS, originalCount,
                "Expected " + INITIAL_ROWS + " original rows, found " + originalCount);

        // 4c: New rows are present
        int newRowCount = scalarInt(ch,
                "SELECT count() FROM " + TARGET_TABLE + " FINAL "
                + "WHERE is_deleted=0 AND customerNumber IN (901, 902, 903)");
        assertEquals(NEW_ROWS, newRowCount,
                "Expected " + NEW_ROWS + " new rows, found " + newRowCount);

        // 4d: Verify pre-existing row data values (customerNumber 486)
        verifyRow(ch, 486, "Motor Mint Distributors Inc.", "Salazar", "Philadelphia", "72600");

        // 4e: Verify newly inserted row data values
        verifyRow(ch, 901, "New Corp Alpha", "Smith", "Austin", "50000");
        verifyRow(ch, 902, "New Corp Beta", "Jones", "Denver", "75000");
        verifyRow(ch, 903, "New Corp Gamma", "Williams", "Seattle", "100000");

        log.info("Version upgrade verification PASSED: {} old rows intact, {} new rows present, row values verified",
                INITIAL_ROWS, NEW_ROWS);

        stopConnector(exec);
    }

    // ------------------------------------------------------------------
    // ClickHouse setup: load CSV directly into the target table
    // ------------------------------------------------------------------

    private void preloadCSVIntoTarget(Connection ch) throws Exception {
        try (Statement s = ch.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS test");
            s.execute("CREATE TABLE IF NOT EXISTS " + TARGET_TABLE + " ("
                    + "`customerNumber` Int32, "
                    + "`customerName` String, "
                    + "`contactLastName` String, "
                    + "`contactFirstName` String, "
                    + "`phone` String, "
                    + "`addressLine1` String, "
                    + "`addressLine2` Nullable(String), "
                    + "`city` String, "
                    + "`state` Nullable(String), "
                    + "`postalCode` Nullable(String), "
                    + "`country` String, "
                    + "`salesRepEmployeeNumber` Nullable(Int32), "
                    + "`creditLimit` Nullable(Decimal(10, 2)), "
                    + "`_version` UInt64, "
                    + "`is_deleted` UInt8"
                    + ") ENGINE = ReplacingMergeTree(_version, is_deleted) "
                    + "ORDER BY customerNumber");
        }

        InputStream csvStream = getClass().getClassLoader().getResourceAsStream(BASELINE_CSV);
        if (csvStream == null) {
            fail("Baseline CSV resource not found: " + BASELINE_CSV);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvStream, StandardCharsets.UTF_8));
             Statement s = ch.createStatement()) {
            StringBuilder csvData = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                csvData.append(line).append("\n");
            }
            if (csvData.length() > 0) {
                s.execute("INSERT INTO " + TARGET_TABLE + " FORMAT CSV\n" + csvData);
            }
        }
    }

    // ------------------------------------------------------------------
    // Connector lifecycle
    // ------------------------------------------------------------------

    private Properties buildProps() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "initial");
        props.setProperty("database.include.list", "test");
        props.setProperty("clickhouse.datetime.timezone", "UTC");
        props.setProperty("disable.ddl", "true");
        props.setProperty("auto.create.tables", "false");
        return props;
    }

    private ExecutorService startConnector(Injector injector, Properties props) {
        ClickHouseDebeziumEmbeddedApplication app =
                injector.getInstance(ClickHouseDebeziumEmbeddedApplication.class);
        ExecutorService exec = Executors.newFixedThreadPool(1);
        exec.submit(() -> {
            try {
                app.start(
                        injector.getInstance(DebeziumRecordParserService.class),
                        props, false);
            } catch (Exception e) {
                connectorError.set(e);
                log.error("Connector start failed", e);
            }
        });
        return exec;
    }

    private void stopConnector(ExecutorService exec) throws InterruptedException, java.io.IOException {
        ClickHouseDebeziumEmbeddedApplication.stop();
        exec.shutdownNow();
        HikariDbSource.close();
        Thread.sleep(5000);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int liveCount(Connection ch) {
        return scalarInt(ch, "SELECT count() FROM " + TARGET_TABLE + " FINAL WHERE is_deleted=0");
    }

    private int scalarInt(Connection ch, String sql) {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            return -1;
        }
    }

    private void verifyRow(Connection ch, int customerNumber, String expectedName,
                           String expectedLastName, String expectedCity, String expectedCredit) {
        String sql = "SELECT customerName, contactLastName, city, "
                + "toString(creditLimit) AS credit "
                + "FROM " + TARGET_TABLE + " FINAL "
                + "WHERE customerNumber = " + customerNumber + " AND is_deleted = 0";
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "Row not found for customerNumber=" + customerNumber);
            assertEquals(expectedName, rs.getString("customerName"),
                    "customerName mismatch for customerNumber=" + customerNumber);
            assertEquals(expectedLastName, rs.getString("contactLastName"),
                    "contactLastName mismatch for customerNumber=" + customerNumber);
            assertEquals(expectedCity, rs.getString("city"),
                    "city mismatch for customerNumber=" + customerNumber);
            String actualCredit = rs.getString("credit");
            assertTrue(actualCredit != null && actualCredit.startsWith(expectedCredit),
                    "creditLimit mismatch for customerNumber=" + customerNumber
                    + ": expected=" + expectedCredit + " actual=" + actualCredit);
            log.info("Row verified: customerNumber={} name='{}' city='{}' credit={}",
                    customerNumber, expectedName, expectedCity, actualCredit);
        } catch (Exception e) {
            fail("Failed to verify row customerNumber=" + customerNumber + ": " + e.getMessage());
        }
    }
}
