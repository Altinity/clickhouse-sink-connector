package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.*;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for schema-aware naming (all 4 configs).
 * <p>
 * Validates that the full schema-aware naming pipeline works correctly
 * in the DML write path.  All four config knobs are enabled:
 * <ol>
 *   <li>{@code clickhouse.table.schema.prefix = true}</li>
 *   <li>{@code clickhouse.common.schema.template = "__{{ schema }}__"}</li>
 *   <li>{@code clickhouse.database.schema.suffix = true}</li>
 *   <li>{@code clickhouse.common.database.prefix = "dev_"}</li>
 * </ol>
 *
 * <h2>Expected naming</h2>
 * <ul>
 *   <li>Source: PostgreSQL database="public", schema="public", table="tm"</li>
 *   <li>ClickHouse database: "dev_public__public__"
 *       (prefix "dev_" + raw db "public" + suffix "__public__")</li>
 *   <li>ClickHouse table: "__public__tm"
 *       (schema prefix resolved via template)</li>
 * </ul>
 *
 * <p>The database prefix/suffix is applied in
 * {@code ClickHouseBatchRunnable.processRecordsByTopic()} via
 * {@link com.altinity.clickhouse.sink.connector.common.Utils#applyDatabasePrefix(String, String)}
 * and
 * {@link com.altinity.clickhouse.sink.connector.common.Utils#applyDatabaseSchemaSuffix(String, String, String)}.
 *
 * <p>Follows the Pattern B test structure (class-level containers with retry).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresSchemaAwareNamingIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaAwareNamingIT.class);

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

    private Network network;
    private ClickHouseContainer clickHouseContainer;
    private PostgreSQLContainer<?> postgreSQLContainer;

    static final String SLOT_NAME = "schema_naming_slot";

    // Expected names (all 4 naming configs enabled):
    //   prefix="dev_" + raw_db="public" + suffix="__public__" → "dev_public__public__"
    //   table schema prefix via template: "__public__tm"
    static final String EXPECTED_DATABASE = "dev_public__public__";
    static final String EXPECTED_TABLE = "__public__tm";

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
                .withInitScript("init_clickhouse_schema_naming.sql")
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
        dropReplicationSlot(SLOT_NAME);
        HikariDbSource.close();
        if (postgreSQLContainer != null) postgreSQLContainer.stop();
        if (clickHouseContainer != null) clickHouseContainer.stop();
        if (network != null) {
            try { network.close(); } catch (Exception ignored) {}
        }
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

    /**
     * Builds properties for the schema-aware naming test.
     * Enables all 4 schema-aware naming configs.
     */
    private Properties getProperties() throws Exception {
        Properties props = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        props.put("plugin.name", "pgoutput");
        props.put("plugin.path", "/");
        props.put("table.include.list", "public.tm");
        // Use unique slot name to avoid conflicts with other tests
        props.put("slot.name", SLOT_NAME);
        // Unique offset storage and schema history tables
        props.put("offset.storage.jdbc.table.name",
                "altinity_sink_connector.replica_source_info_" + SLOT_NAME);
        props.put("schema.history.internal.jdbc.schema.history.table.name",
                "altinity_sink_connector.replicate_schema_history_" + SLOT_NAME);
        // More retries for slow CI environments
        props.put("slot.max.retries", "12");
        props.put("slot.retry.delay.ms", "10000");
        props.put("database.allowPublicKeyRetrieval", "true");
        props.put("snapshot.mode", "initial");

        // === All 4 schema-aware naming configs ===
        // 1) Table schema prefix: prepend schema to table name
        props.put("clickhouse.table.schema.prefix", "true");
        // 2) Shared template: used by both table prefix and database suffix
        props.put("clickhouse.common.schema.template", "__{{ schema }}__");
        // 3) Database schema suffix: append resolved template to database name
        props.put("clickhouse.database.schema.suffix", "true");
        // 4) Database prefix: prepend static prefix to database name
        props.put("clickhouse.common.database.prefix", "dev_");

        return props;
    }

    @Test
    @DisplayName("Full schema-aware naming: PostgreSQL public.tm → ClickHouse dev_public__public__.__public__tm")
    public void testFullSchemaAwareNaming() throws Exception {
        // Clean up any leftover slot from a previous run
        dropReplicationSlot(SLOT_NAME);

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

        try {
            // ---- Wait for snapshot to complete (polling-based, up to 120 s) ----
            System.out.println("[SchemaAwareNamingIT] Waiting for snapshot (polling up to 120 s)…");
            System.out.println("[SchemaAwareNamingIT] Expected: " + EXPECTED_DATABASE + "." + EXPECTED_TABLE);

            // Connect to ClickHouse using the expected database name
            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, EXPECTED_DATABASE);

            boolean snapshotDone = false;
            long snapshotDeadline = System.currentTimeMillis() + 120_000L;
            int pollCount = 0;
            while (System.currentTimeMillis() < snapshotDeadline) {
                try {
                    ResultSet rs = writer.getConnection().prepareStatement(
                            "SELECT count() AS cnt FROM `" + EXPECTED_DATABASE + "`.`" + EXPECTED_TABLE + "` FINAL"
                    ).executeQuery();
                    if (rs.next()) {
                        long cnt = rs.getLong("cnt");
                        if (cnt > 0) {
                            snapshotDone = true;
                            System.out.printf("[SchemaAwareNamingIT] Found %d rows after %d polls (~%d s).%n",
                                    cnt, pollCount, pollCount * 5);
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.out.printf("[SchemaAwareNamingIT] Snapshot poll %d: %s%n", pollCount, e.getMessage());
                }
                Thread.sleep(5_000);
                pollCount++;
            }

            assertTrue(snapshotDone,
                    "Expected rows in " + EXPECTED_DATABASE + "." + EXPECTED_TABLE
                    + " after snapshot (waited 120 s). "
                    + "This verifies the connector applied schema-aware naming in all paths.");

            // ---- Verify row count matches init_postgres.sql (2 rows in tm) ----
            ResultSet countRs = writer.getConnection().prepareStatement(
                    "SELECT count() AS cnt FROM `" + EXPECTED_DATABASE + "`.`" + EXPECTED_TABLE + "` FINAL"
            ).executeQuery();
            assertTrue(countRs.next(), "Expected result from count query");
            long count = countRs.getLong("cnt");
            assertTrue(count >= 2,
                    "Expected at least 2 rows from init_postgres.sql in "
                    + EXPECTED_DATABASE + "." + EXPECTED_TABLE + " but got " + count);

            // ---- Verify the original table name "tm" does NOT exist in the target DB ----
            boolean originalTableExists = true;
            try {
                ResultSet origRs = writer.getConnection().prepareStatement(
                        "SELECT count() FROM `" + EXPECTED_DATABASE + "`.`tm` FINAL"
                ).executeQuery();
                if (origRs.next()) {
                    long origCount = origRs.getLong(1);
                    if (origCount == 0) {
                        originalTableExists = false;
                    }
                }
            } catch (Exception e) {
                originalTableExists = false;
            }
            assertFalse(originalTableExists,
                    "The original table 'tm' should NOT have data in " + EXPECTED_DATABASE
                    + " — schema prefix should have renamed it to '" + EXPECTED_TABLE + "'");

            // ---- Verify data does NOT exist in the raw (un-prefixed) database ----
            boolean rawDbHasData = false;
            try {
                BaseDbWriter rawWriter = ITCommon.getDBWriter(clickHouseContainer, "public");
                ResultSet rawRs = rawWriter.getConnection().prepareStatement(
                        "SELECT count() FROM `public`.`tm` FINAL"
                ).executeQuery();
                if (rawRs.next() && rawRs.getLong(1) > 0) {
                    rawDbHasData = true;
                }
            } catch (Exception e) {
                // Table/database doesn't exist — expected
            }
            assertFalse(rawDbHasData,
                    "No data should exist in raw database 'public.tm' — "
                    + "database prefix/suffix should have redirected to '" + EXPECTED_DATABASE + "'");

            System.out.println("[SchemaAwareNamingIT] Test PASSED — full schema-aware naming verified end-to-end.");
            System.out.println("[SchemaAwareNamingIT]   Database: " + EXPECTED_DATABASE);
            System.out.println("[SchemaAwareNamingIT]   Table: " + EXPECTED_TABLE);
            System.out.println("[SchemaAwareNamingIT]   Rows: " + count);

        } finally {
            if (engine.get() != null) engine.get().stop();
            executorService.shutdown();
            executorService.awaitTermination(30, TimeUnit.SECONDS);

            // Clean up replication slot
            dropReplicationSlot(SLOT_NAME);
        }
    }
}
