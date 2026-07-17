package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Integration Test that validates the connector persists a row to the error table
 * ({@code altinity_sink_connector.replica_source_error}) when a replicated record
 * fails to be inserted into ClickHouse.
 *
 * <p>The failure is induced deterministically: auto-create is disabled and the
 * ClickHouse target table is pre-created with an incompatible schema (the source
 * {@code VARCHAR} column {@code name} is mapped to {@code Int32}). Replicating a row
 * whose {@code name} is a non-numeric string therefore fails during the batch insert,
 * which the connector logs to the error table via the runtime error path in
 * {@code ClickHouseBatchRunnable}.
 */
@DisplayName("Integration Test that validates errors are persisted to replica_source_error")
@Testcontainers
public class ReplicaSourceErrorPersistedIT extends DDLBaseIT {

    private static final String ERROR_DATABASE = "altinity_sink_connector";
    private static final String ERROR_TABLE = "replica_source_error";

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("error_persist_test.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();

        // Clear any pending invalidations from previous tests
        CacheInvalidationManager.getInstance().clearAll();

        Thread.sleep(15000);
    }

    @Override
    protected Properties getDebeziumProperties() throws Exception {
        Properties props = super.getDebeziumProperties();
        props.setProperty("auto.create.tables", "false");
        // Ignore ALL DDL so the connector does not drop/recreate our pre-created
        // incompatible ClickHouse table (snapshot DDL replication otherwise replaces
        // it with a correct schema, and the insert would then succeed).
        props.setProperty("disable.ddl", "true");
        props.setProperty("error.logging.enable", "true");
        props.setProperty("default.error.table", ERROR_TABLE);
        return props;
    }

    @Test
    @DisplayName("A failed insert should be persisted to the error table")
    public void testErrorPersistedToErrorTable() throws Exception {

        // Open a system connection and pre-create the incompatible ClickHouse schema
        // before the connector starts, so the replicated insert fails deterministically.
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(),
                clickHouseContainer.getFirstMappedPort(), BaseDbWriter.SYSTEM_DB);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        Connection chConn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(),
                BaseDbWriter.SYSTEM_DB, config);

        try (Statement stmt = chConn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS " + ERROR_DATABASE);
            stmt.execute("CREATE DATABASE IF NOT EXISTS employees");
            // Mirror the schema the connector expects (id, name, amount plus the
            // _version/is_deleted virtual columns) but make `name` Int32 while the
            // MySQL column is VARCHAR. Replicating a non-numeric string into this
            // column fails the insert, which the connector logs to the error table.
            stmt.execute("CREATE TABLE IF NOT EXISTS employees.error_persist_test ("
                    + "id Int32, name Int32, amount Nullable(Int32), "
                    + "_version UInt64, is_deleted UInt8) "
                    + "ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id");
        }

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for the connector to start and reach streaming.
        Thread.sleep(30000);

        // Insert a row whose non-numeric name cannot be inserted into the Int32 column.
        Connection mysqlConn = connectToMySQL();
        mysqlConn.prepareStatement(
                "INSERT INTO error_persist_test (id, name, amount) VALUES (1, 'not-a-number', 42)")
                .execute();

        // Wait for the failing batch to be attempted and the error to be logged.
        Thread.sleep(25000);

        long errorCount = 0;
        try (Statement stmt = chConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) as cnt FROM " + ERROR_DATABASE + "." + ERROR_TABLE)) {
            if (rs.next()) {
                errorCount = rs.getLong("cnt");
            }
        }
        Assert.assertTrue("A row should be persisted to the error table after a failed insert",
                errorCount >= 1);

        try (Statement stmt = chConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT error, source_database FROM " + ERROR_DATABASE + "." + ERROR_TABLE
                             + " ORDER BY error_timestamp DESC LIMIT 1")) {
            Assert.assertTrue("Latest error row should be readable", rs.next());
            Assert.assertEquals("employees", rs.getString("source_database"));
            String error = rs.getString("error");
            Assert.assertTrue("Error message should not be empty",
                    error != null && !error.isEmpty());
        }

        // The NumberFormatException is a deterministic client-side conversion error and
        // is classified as FATAL: the connector logs it once, discards the batch and
        // stops the task rather than retrying forever. Verify the error-row count does
        // not keep growing across scheduling intervals (i.e. no infinite retry loop).
        Thread.sleep(15000);
        long errorCountAfterWait = 0;
        try (Statement stmt = chConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) as cnt FROM " + ERROR_DATABASE + "." + ERROR_TABLE)) {
            if (rs.next()) {
                errorCountAfterWait = rs.getLong("cnt");
            }
        }
        Assert.assertEquals("Error table should not keep growing (fatal error must not be retried)",
                errorCount, errorCountAfterWait);

        // Cleanup
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }
}
