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
 * Integration tests for PostgreSQL DELETE operations replication to ClickHouse.
 * Tests DELETE operation handling in ReplacingMergeTree tables.
 *
 * <p>ClickHouse ReplacingMergeTree(_version, is_deleted): when querying with FINAL,
 * deduplicates by primary key (keeping highest _version) AND filters out rows
 * where is_deleted = 1, making deleted rows invisible to FINAL queries.
 *
 * <p>Containers are started once per test <em>class</em> (via {@code @BeforeAll} /
 * {@code @AfterAll}) to avoid Podman socket exhaustion from rapid per-method
 * container cycling when multiple IT classes run together.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresDeleteOperationsIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresDeleteOperationsIT.class);

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

    private Network network;
    private ClickHouseContainer clickHouseContainer;
    private PostgreSQLContainer<?> postgreSQLContainer;

    @BeforeAll
    void startContainers() throws InterruptedException {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                tryStartContainers();
                return;
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

        // Configure the delete column to match the table DDL (is_deleted UInt8 DEFAULT 0).
        // The connector sets is_deleted=1 for DELETE events; ReplacingMergeTree(_version, is_deleted)
        // then filters those rows out when querying with FINAL.
        properties.put("replacingmergetree.delete.column", "is_deleted");

        return properties;
    }

    // =========================================================================
    // Test 1 – single-row DELETE
    // =========================================================================

    @Test
    @DisplayName("Test DELETE operation on a single row - verifies basic DELETE replication")
    public void testSingleRowDelete() throws Exception {
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

        // Poll until initial snapshot completes — wait for tm table data
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        String countQuery = "SELECT count(*) as cnt FROM public.tm FINAL";
        long initialCount = ITCommon.waitForRowCount(writer.getConnection(), countQuery, 1, 180_000, 5_000);
        Assert.assertTrue("Initial snapshot must replicate at least 1 row to public.tm", initialCount >= 1);

        // Connect to PostgreSQL and execute DELETE
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        int deletedCount = pgConn.prepareStatement(
                "DELETE FROM public.tm WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f71'"
        ).executeUpdate();
        Assert.assertEquals("One row should be deleted from PostgreSQL", 1, deletedCount);

        // Poll until the count decreases (up to 60s)
        long expectedAfterDelete = initialCount - 1;
        long finalCount = ITCommon.waitForRowCount(writer.getConnection(), countQuery, 0, 60_000, 5_000);
        // waitForRowCount returns when count >= 0 (always true), so re-check the exact value
        finalCount = ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.tm FINAL WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f71'", 0, 60_000, 3_000);
        // The row should be gone. Poll until it is 0.
        {
            long deadline = System.currentTimeMillis() + 60_000;
            long specificCount = 1;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT count(*) as cnt FROM public.tm FINAL WHERE id = '9cb52b2a-8ef2-4987-8856-c79a1b2c2f71'"
                    ).executeQuery();
                    if (rs.next()) {
                        specificCount = rs.getLong("cnt");
                        if (specificCount == 0) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
            Assert.assertEquals("Deleted row should not be present when querying with FINAL", 0, specificCount);
        }

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 2 – batch DELETE (multiple rows)
    // =========================================================================

    @Test
    @DisplayName("Test batch DELETE operation - verifies DELETE affecting multiple rows")
    public void testBatchDelete() throws Exception {
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
        String countQuery = "SELECT count(*) as cnt FROM public.protocol_test FINAL WHERE id >= 1778400 AND id <= 1778405";
        // Wait for snapshot to replicate protocol_test rows
        ITCommon.waitForRowCount(writer.getConnection(),
                "SELECT count(*) FROM public.protocol_test FINAL", 1, 180_000, 5_000);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        int deletedCount = pgConn.prepareStatement(
                "DELETE FROM public.protocol_test WHERE id >= 1778400 AND id <= 1778405"
        ).executeUpdate();
        Assert.assertTrue("Multiple rows should be deleted from PostgreSQL", deletedCount > 0);

        // Poll until the specific rows are gone (up to 60s)
        {
            long deadline = System.currentTimeMillis() + 60_000;
            long finalCount = -1;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(countQuery).executeQuery();
                    if (rs.next()) {
                        finalCount = rs.getLong("cnt");
                        if (finalCount == 0) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
            Assert.assertEquals("All deleted rows should be filtered out with FINAL", 0, finalCount);
        }

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3 – DELETE with complex WHERE clause
    // =========================================================================

    @Test
    @DisplayName("Test DELETE with WHERE clause - verifies DELETE with complex conditions")
    public void testDeleteWithWhereClause() throws Exception {
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
        String countQuery = "SELECT count(*) as cnt FROM public.protocol_test FINAL WHERE consultation_id > 20000000";

        // Wait for matching rows to appear in ClickHouse (snapshot)
        long initialCount = ITCommon.waitForRowCount(writer.getConnection(), countQuery, 1, 180_000, 5_000);
        Assert.assertTrue("Should have rows matching WHERE clause before DELETE", initialCount > 0);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        int deletedCount = pgConn.prepareStatement(
                "DELETE FROM public.protocol_test WHERE consultation_id > 20000000"
        ).executeUpdate();
        Assert.assertEquals("Deleted count should match initial count", (int) initialCount, deletedCount);

        // Poll until the rows are gone (up to 60s)
        {
            long deadline = System.currentTimeMillis() + 60_000;
            long finalCount = -1;
            while (System.currentTimeMillis() < deadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(countQuery).executeQuery();
                    if (rs.next()) {
                        finalCount = rs.getLong("cnt");
                        if (finalCount == 0) break;
                    }
                } catch (Exception e) { /* ignore */ }
                Thread.sleep(3_000);
            }
            Assert.assertEquals("All rows matching WHERE clause should be deleted", 0, finalCount);
        }

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}
