package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

/**
 * Integration tests for PostgreSQL UPDATE operations replication to ClickHouse.
 * Tests various UPDATE scenarios including single row, multi-column, batch updates,
 * NULL values, and numeric type updates.
 *
 * <p>Containers are started once per test <em>class</em> (via {@code @BeforeAll} /
 * {@code @AfterAll}) to avoid Podman socket exhaustion from rapid per-method
 * container cycling.  Each test method creates its own Debezium engine that does
 * a fresh snapshot, so test isolation is preserved at the logical level.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresUpdateOperationsIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresUpdateOperationsIT.class);

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

    private Network network;
    private ClickHouseContainer clickHouseContainer;
    private PostgreSQLContainer<?> postgreSQLContainer;

    @BeforeAll
    void startContainers() throws InterruptedException {
        // Podman socket can be transiently broken after many prior container starts.
        // Retry with increasing delay to give the socket time to recover.
        Exception lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                tryStartContainers();
                return; // success
            } catch (Exception e) {
                lastEx = e;
                log.warn("Container startup attempt {} failed: {}. Retrying in {}s…",
                        attempt, e.getMessage(), attempt * 10);
                if (network != null) {
                    try { network.close(); } catch (Exception ignored) {}
                    network = null;
                }
                Thread.sleep(attempt * 10_000L);
            }
        }
        throw new RuntimeException("Container startup failed after 3 attempts", lastEx);
    }

    private void tryStartContainers() throws InterruptedException {
        network = Network.newNetwork();

        clickHouseContainer = new ClickHouseContainer(
                DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123)
                .withNetwork(network);

        postgreSQLContainer = new PostgreSQLContainer<>(PG_IMAGE)
                .withInitScript("init_postgres.sql")
                .withDatabaseName("public")
                .withUsername("root")
                .withPassword("root")
                .withExposedPorts(5432)
                .withCommand("postgres -c wal_level=logical")
                .withNetworkAliases("postgres")
                .withAccessToHost(true)
                .withNetwork(network);

        // Start one at a time with a small gap to reduce Podman socket pressure
        clickHouseContainer.start();
        Thread.sleep(3_000);
        postgreSQLContainer.start();

        Thread.sleep(10_000);
        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
    }

    @AfterAll
    void stopContainers() {
        HikariDbSource.close();
        if (postgreSQLContainer != null) postgreSQLContainer.stop();
        if (clickHouseContainer != null) clickHouseContainer.stop();
        if (network != null) {
            try { network.close(); } catch (Exception ignored) {}
        }
    }

    private Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("table.include.list", "public.tm,public.protocol_test,public.redata");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        return properties;
    }

    // =========================================================================
    // Test 1 – single-row UPDATE
    // =========================================================================

    @Test
    @DisplayName("Test UPDATE operation on a single row - verifies basic UPDATE replication")
    public void testSingleRowUpdate() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        long snapshotCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.tm FINAL", 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.tm", snapshotCount >= 1);

        // Connect to PostgreSQL and execute UPDATE
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE public.tm SET ccatz = 'UPDATED_VALUE' " +
                "WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f71'"
        ).execute();

        // Poll until the updated value appears in ClickHouse (up to 60s)
        String updatedValue = null;
        {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT ccatz FROM public.tm FINAL " +
                            "WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f71'"
                    ).executeQuery();
                    if (rs.next()) {
                        String val = rs.getString("ccatz");
                        if ("UPDATED_VALUE".equals(val)) {
                            updatedValue = val;
                            break;
                        }
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
        }

        Assert.assertNotNull("Updated row should exist in ClickHouse", updatedValue);
        Assert.assertEquals("Updated value should match", "UPDATED_VALUE", updatedValue);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 2 – multi-column UPDATE
    // =========================================================================

    @Test
    @DisplayName("Test UPDATE operation on multiple columns - verifies multi-column UPDATE replication")
    public void testMultiColumnUpdate() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        long snapshotCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.tm FINAL", 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.tm", snapshotCount >= 1);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE public.tm SET ccatz = 'MULTI_UPDATE', vstatus = 'UPDATED_STATUS', " +
                "vbilling_currency = 'USD' WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f73'"
        ).execute();

        // Poll until the updated values appear in ClickHouse (up to 60s)
        String ccatz = null, vstatus = null, vbillingCurrency = null;
        {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT ccatz, vstatus, vbilling_currency FROM public.tm FINAL " +
                            "WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f73'"
                    ).executeQuery();
                    if (rs.next()) {
                        ccatz            = rs.getString("ccatz");
                        vstatus          = rs.getString("vstatus");
                        vbillingCurrency = rs.getString("vbilling_currency");
                        if ("MULTI_UPDATE".equals(ccatz)) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
        }

        Assert.assertEquals("ccatz should be updated",             "MULTI_UPDATE",   ccatz);
        Assert.assertEquals("vstatus should be updated",           "UPDATED_STATUS", vstatus);
        Assert.assertEquals("vbilling_currency should be updated", "USD",            vbillingCurrency);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3 – batch UPDATE (multiple rows)
    // =========================================================================

    @Test
    @DisplayName("Test batch UPDATE operation - verifies UPDATE affecting multiple rows")
    public void testBatchUpdate() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes — wait for protocol_test data
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        long snapshotCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.protocol_test FINAL", 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.protocol_test", snapshotCount >= 1);

        // IDs 1778392-1778395 are seeded via init_postgres.sql
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE public.protocol_test SET recomendation = 'BATCH_UPDATED' " +
                "WHERE id >= 1778392 AND id <= 1778395"
        ).executeUpdate();

        // Poll until all 4 updated rows appear in ClickHouse (up to 90s)
        String batchQuery = "SELECT count(*) as cnt FROM public.protocol_test FINAL " +
                "WHERE id >= 1778392 AND id <= 1778395 AND recomendation = 'BATCH_UPDATED'";
        long updatedInCH = ITCommon.waitForRowCount(writer.getConnection(), batchQuery, 4, 90_000, 5_000);

        Assert.assertEquals("All batch updated rows should be replicated", 4, updatedInCH);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4 – UPDATE to NULL
    // =========================================================================

    @Test
    @DisplayName("Test UPDATE to NULL value - verifies NULL handling in UPDATE replication")
    public void testUpdateToNull() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        long snapshotCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.tm FINAL", 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.tm", snapshotCount >= 1);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE public.tm SET vstatus = NULL, vbilling_currency = NULL " +
                "WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f73'"
        ).execute();

        // Poll until the NULL values appear in ClickHouse (up to 60s)
        // We detect the update by checking that vstatus IS NULL for the target row
        String vstatus = "NOT_NULL", vbillingCurrency = "NOT_NULL";
        {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT vstatus, vbilling_currency FROM public.tm FINAL " +
                            "WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f73'"
                    ).executeQuery();
                    if (rs.next()) {
                        vstatus          = rs.getString("vstatus");
                        vbillingCurrency = rs.getString("vbilling_currency");
                        if (vstatus == null) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
        }

        Assert.assertNull("vstatus should be NULL after update",           vstatus);
        Assert.assertNull("vbilling_currency should be NULL after update", vbillingCurrency);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5 – UPDATE numeric types
    // =========================================================================

    @Test
    @DisplayName("Test UPDATE on numeric types - verifies NUMERIC/DECIMAL type UPDATE replication")
    public void testUpdateNumericTypes() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        long snapshotCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.redata FINAL", 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.redata", snapshotCount >= 1);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE public.redata SET amount = 999.8888, total_amount = 1234.56789 " +
                "WHERE uid = 123456"
        ).execute();

        // Poll until the updated numeric values appear in ClickHouse (up to 60s)
        double amount = 0.0, totalAmount = 0.0;
        {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT amount, total_amount FROM public.redata FINAL WHERE uid = 123456"
                    ).executeQuery();
                    if (rs.next()) {
                        amount      = rs.getDouble("amount");
                        totalAmount = rs.getDouble("total_amount");
                        if (Math.abs(amount - 999.8888) < 0.001) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
        }

        Assert.assertEquals("amount should be updated correctly",       999.8888,   amount,      0.0001);
        Assert.assertEquals("total_amount should be updated correctly", 1234.56789, totalAmount, 0.00001);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}
