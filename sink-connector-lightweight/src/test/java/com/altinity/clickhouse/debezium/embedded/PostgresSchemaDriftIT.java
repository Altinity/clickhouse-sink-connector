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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

/**
 * End-to-end integration test for the schema drift detection and auto-reconciliation
 * system ({@link com.altinity.clickhouse.debezium.embedded.postgres.schema.PostgresSchemaChangeDetector}
 * + {@link com.altinity.clickhouse.debezium.embedded.postgres.schema.PostgresSchemaReconciler}).
 *
 * <h2>Test scenario</h2>
 * <ol>
 *   <li>Start PostgreSQL + ClickHouse containers (once per class via {@code @BeforeAll})</li>
 *   <li>Start Debezium CDC connector (snapshot + streaming) per test method</li>
 *   <li>Verify initial row is replicated to ClickHouse</li>
 *   <li>Execute {@code ALTER TABLE ADD COLUMN} on PostgreSQL → schema drift</li>
 *   <li>Insert a new row that populates the new column(s) — this DML event triggers
 *       drift detection inside {@code processEveryChangeRecord()}</li>
 *   <li>Wait up to 120 s for the drift detector to add the column(s) in ClickHouse
 *       <strong>and</strong> for the new row to appear</li>
 *   <li>Assert ClickHouse table has the new column(s) and the new row's values are correct</li>
 * </ol>
 *
 * <p><strong>Important:</strong> Drift detection is <em>event-driven</em>.  The
 * {@code checkAndReconcile()} method runs only when a DML {@code SourceRecord}
 * arrives in the CDC pipeline.  Therefore, the INSERT must happen <em>before</em>
 * waiting for the column, not after.
 *
 * <p>Each test method uses its own dedicated table ({@code schema_drift_single},
 * {@code schema_drift_multi}) and a unique replication slot name to prevent
 * interference between sequential test runs.
 *
 * <p>Containers are started once per test <em>class</em> to avoid Podman socket
 * exhaustion from rapid per-method container cycling when multiple IT classes
 * run together in a single Maven Surefire invocation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresSchemaDriftIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaDriftIT.class);

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
                .withInitScript("init_clickhouse_schema_drift.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123)
                .withNetwork(network);

        postgreSQLContainer = new PostgreSQLContainer<>(PG_IMAGE)
                .withInitScript("init_postgres_schema_drift.sql")
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

    /**
     * Builds properties for a test, capturing only the specified table and
     * using the given replication slot name.
     */
    private Properties getProperties(String tableIncludeList, String slotName) throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("table.include.list", tableIncludeList);
        // Use unique slot name per test to avoid replication slot conflicts
        properties.put("slot.name", slotName);
        // More retries for slow CI environments
        properties.put("slot.max.retries", "12");
        properties.put("slot.retry.delay.ms", "10000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        return properties;
    }

    /**
     * Drops a PostgreSQL replication slot if it exists, to avoid conflicts
     * between test runs.
     */
    private void dropReplicationSlot(String slotName) {
        try {
            Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
            pgConn.prepareStatement(
                    "SELECT pg_drop_replication_slot(slot_name) " +
                    "FROM pg_replication_slots WHERE slot_name = '" + slotName + "'"
            ).execute();
            pgConn.close();
            log.info("Dropped replication slot '{}'", slotName);
        } catch (Exception e) {
            log.debug("Could not drop replication slot '{}': {}", slotName, e.getMessage());
        }
    }

    // =========================================================================
    // Test 1 – single new column
    // =========================================================================

    @Test
    @DisplayName("Schema drift: new column added to PostgreSQL is auto-propagated to ClickHouse")
    public void testColumnAddedToPostgresIsPropagatedToClickHouse() throws Exception {
        final String TABLE = "schema_drift_single";
        final String SLOT = "drift_slot_single";

        // Clean up any leftover slot from a previous run
        dropReplicationSlot(SLOT);

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(
                        getProperties("public." + TABLE, SLOT),
                        new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // ---- Wait for initial snapshot to complete ----
        System.out.println("[SchemaDriftIT] Waiting 60 s for initial snapshot…");
        Thread.sleep(60_000);

        // ---- Verify initial row was replicated ----
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        {
            ResultSet rs = writer.getConnection()
                    .prepareStatement("SELECT id, name FROM public." + TABLE + " FINAL WHERE id = 1")
                    .executeQuery();
            Assert.assertTrue("Initial row must exist in ClickHouse after snapshot", rs.next());
            Assert.assertEquals("initial_row", rs.getString("name"));
            System.out.println("[SchemaDriftIT] Initial row verified in ClickHouse.");
        }

        // ---- Step 4: ALTER TABLE ADD COLUMN in PostgreSQL ----
        System.out.println("[SchemaDriftIT] Executing ALTER TABLE ADD COLUMN in PostgreSQL…");
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "ALTER TABLE public." + TABLE + " ADD COLUMN extra_info TEXT"
        ).execute();
        System.out.println("[SchemaDriftIT] ALTER TABLE executed in PostgreSQL.");

        // ---- Step 5: INSERT immediately so the DML CDC event triggers drift detection ----
        // Drift detection is event-driven: checkAndReconcile() runs only when a
        // DML SourceRecord arrives in processEveryChangeRecord().  Without an INSERT,
        // the column will never appear in ClickHouse.
        pgConn.prepareStatement(
                "INSERT INTO public." + TABLE + " (id, name, extra_info) VALUES (99, 'new_row', 'drift_value')"
        ).execute();
        System.out.println("[SchemaDriftIT] Inserted new row with extra_info='drift_value' (triggers drift detection).");

        // ---- Step 6: Wait for both the column AND the row to appear in ClickHouse ----
        // The DML event arriving at the sink connector will:
        //   1) call checkAndReconcile() → detect 'extra_info' missing → ALTER TABLE in CH
        //   2) write the row data
        boolean columnAdded = waitForColumnInClickHouse(
                writer.getConnection(), "public", TABLE, "extra_info", 120);

        Assert.assertTrue(
                "ClickHouse table must have the new column 'extra_info' after drift detection",
                columnAdded);
        System.out.println("[SchemaDriftIT] Column 'extra_info' is now present in ClickHouse.");

        // ---- Step 7: Verify new row data (polling up to 90 s) ----
        String extraInfoValue = null;
        {
            long rowDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < rowDeadline) {
                ResultSet rs = writer.getConnection()
                        .prepareStatement(
                                "SELECT extra_info FROM public." + TABLE + " FINAL WHERE id = 99")
                        .executeQuery();
                while (rs.next()) {
                    extraInfoValue = rs.getString("extra_info");
                }
                if (extraInfoValue != null) break;
                Thread.sleep(3_000);
            }
        }
        Assert.assertNotNull("Row 99 must exist in ClickHouse", extraInfoValue);
        Assert.assertEquals("extra_info value must be 'drift_value'", "drift_value", extraInfoValue);

        System.out.println("[SchemaDriftIT] Test PASSED – schema drift detected and reconciled end-to-end.");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);

        // Clean up replication slot
        dropReplicationSlot(SLOT);
    }

    // =========================================================================
    // Test 2 – multiple new columns added at once
    // =========================================================================

    @Test
    @DisplayName("Schema drift: multiple new columns added are all propagated to ClickHouse")
    public void testMultipleColumnsAdded() throws Exception {
        final String TABLE = "schema_drift_multi";
        final String SLOT = "drift_slot_multi";

        // Clean up any leftover slot from a previous run
        dropReplicationSlot(SLOT);

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(
                        getProperties("public." + TABLE, SLOT),
                        new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        System.out.println("[SchemaDriftIT/multi] Waiting 60 s for initial snapshot…");
        Thread.sleep(60_000);

        // ---- Verify initial row replicated ----
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        {
            ResultSet rs = writer.getConnection()
                    .prepareStatement("SELECT COUNT(*) AS cnt FROM public." + TABLE + " FINAL")
                    .executeQuery();
            Assert.assertTrue(rs.next());
            long cnt = rs.getLong("cnt");
            Assert.assertTrue("At least the seeded row must be replicated; got " + cnt, cnt >= 1);
            System.out.println("[SchemaDriftIT/multi] Initial row count in ClickHouse: " + cnt);
        }

        // ---- ADD three columns to PostgreSQL ----
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "ALTER TABLE public." + TABLE + " " +
                        "ADD COLUMN score INT, " +
                        "ADD COLUMN label TEXT, " +
                        "ADD COLUMN active BOOLEAN"
        ).execute();
        System.out.println("[SchemaDriftIT/multi] Added 3 columns in PostgreSQL.");

        // ---- INSERT immediately so the DML CDC event triggers drift detection ----
        // Drift detection is event-driven: checkAndReconcile() runs only when a
        // DML SourceRecord arrives in processEveryChangeRecord().  Without an INSERT,
        // the columns will never appear in ClickHouse.
        pgConn.prepareStatement(
                "INSERT INTO public." + TABLE + " (id, name, score, label, active) " +
                        "VALUES (200, 'multi_drift', 42, 'hello', TRUE)"
        ).execute();
        System.out.println("[SchemaDriftIT/multi] Inserted row with score/label/active (triggers drift detection).");

        // ---- Wait for all three columns to appear in ClickHouse ----
        // The DML event arriving at the sink connector will:
        //   1) call checkAndReconcile() → detect score/label/active missing → ALTER TABLE in CH
        //   2) write the row data
        boolean scoreReady  = waitForColumnInClickHouse(writer.getConnection(), "public", TABLE, "score",  120);
        boolean labelReady  = waitForColumnInClickHouse(writer.getConnection(), "public", TABLE, "label",  30);
        boolean activeReady = waitForColumnInClickHouse(writer.getConnection(), "public", TABLE, "active", 30);

        Assert.assertTrue("Column 'score' must be added to ClickHouse",  scoreReady);
        Assert.assertTrue("Column 'label' must be added to ClickHouse",  labelReady);
        Assert.assertTrue("Column 'active' must be added to ClickHouse", activeReady);

        System.out.println("[SchemaDriftIT/multi] All 3 columns detected in ClickHouse.");

        // ---- Verify the row values (polling) ----
        String labelValue = null;
        Integer scoreValue = null;
        {
            long rowDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < rowDeadline) {
                ResultSet rs = writer.getConnection()
                        .prepareStatement(
                                "SELECT score, label FROM public." + TABLE + " FINAL WHERE id = 200")
                        .executeQuery();
                while (rs.next()) {
                    scoreValue = rs.getInt("score");
                    labelValue = rs.getString("label");
                }
                if (labelValue != null) break;
                Thread.sleep(3_000);
            }
        }
        Assert.assertNotNull("Row 200 must be present in ClickHouse", labelValue);
        Assert.assertEquals("score must be 42",      Integer.valueOf(42), scoreValue);
        Assert.assertEquals("label must be 'hello'", "hello",             labelValue);

        System.out.println("[SchemaDriftIT/multi] Test PASSED – all drift columns reconciled.");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);

        // Clean up replication slot
        dropReplicationSlot(SLOT);
    }

    // =========================================================================
    // Helper utilities
    // =========================================================================

    /**
     * Polls {@code system.columns} every 3 seconds until {@code columnName} appears
     * in the given table, or until {@code timeoutSeconds} elapses.
     *
     * @return {@code true} if the column was found within the timeout
     */
    private static boolean waitForColumnInClickHouse(
            Connection chConn,
            String database,
            String table,
            String columnName,
            int timeoutSeconds) {

        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1_000;
        int poll = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                String sql = String.format(
                        "SELECT count() FROM system.columns " +
                                "WHERE database='%s' AND table='%s' AND name='%s'",
                        database, table, columnName);
                ResultSet rs = chConn.prepareStatement(sql).executeQuery();
                if (rs.next() && rs.getLong(1) > 0) {
                    System.out.printf("[SchemaDriftIT] Column '%s' appeared after %d polls (~%d s)%n",
                            columnName, poll, poll * 3);
                    return true;
                }
            } catch (Exception e) {
                System.out.println("[SchemaDriftIT] Poll error: " + e.getMessage());
            }
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            poll++;
        }
        System.out.printf("[SchemaDriftIT] TIMEOUT after %d s waiting for column '%s'%n",
                timeoutSeconds, columnName);
        return false;
    }

    /**
     * Returns all column names present in a ClickHouse table by querying
     * {@code system.columns}.
     */
    private static List<String> getClickHouseColumns(Connection chConn, String database, String table)
            throws Exception {
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database='%s' AND table='%s' ORDER BY position",
                database, table);
        ResultSet rs = chConn.prepareStatement(sql).executeQuery();
        List<String> cols = new ArrayList<>();
        while (rs.next()) {
            cols.add(rs.getString(1));
        }
        return cols;
    }
}
