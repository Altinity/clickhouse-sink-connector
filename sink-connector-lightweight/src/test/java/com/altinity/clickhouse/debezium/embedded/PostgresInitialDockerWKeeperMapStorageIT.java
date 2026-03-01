package com.altinity.clickhouse.debezium.embedded;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

/**
 * Integration test that validates initial PostgreSQL snapshot replication to ClickHouse.
 * Uses standard JDBC offset storage (ReplacingMergeTree) — no ZooKeeper dependency.
 * Previously tested KeeperMap offset storage; rewritten to avoid the ZooKeeper container
 * and the 'keeper_map_path_prefix' requirement introduced in ClickHouse 24.8.
 */
public class PostgresInitialDockerWKeeperMapStorageIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresInitialDockerWKeeperMapStorageIT.class);

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(
            DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    public static DockerImageName myImage = DockerImageName.parse("debezium/postgres:15-alpine")
            .asCompatibleSubstituteFor("postgres");

    @Container
    public static PostgreSQLContainer postgreSQLContainer = (PostgreSQLContainer) new PostgreSQLContainer(myImage)
            .withInitScript("init_postgres.sql")
            .withDatabaseName("public")
            .withUsername("root")
            .withPassword("root")
            .withExposedPorts(5432)
            .withCommand("postgres -c wal_level=logical")
            .withNetworkAliases("postgres").withAccessToHost(true);

    // Single shared network avoids Podman socket instability from repeated network creation
    private static final Network NETWORK = Network.newNetwork();

    public Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("table.include.list", "public.tm,public.tm2,public.redata");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        return properties;
    }

    @Test
    @DisplayName("Integration Test - Validates PostgreSQL initial snapshot replication and TRUNCATE propagation")
    public void testDecoderBufsPlugin() throws Exception {
        postgreSQLContainer.withNetwork(NETWORK).start();
        clickHouseContainer.withNetwork(NETWORK).start();
        Thread.sleep(10000);

        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Future<?> engineFuture = executorService.submit(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                log.error("Engine setup() threw exception", e);
                throw new RuntimeException(e);
            }
        });

        // Wait for initial snapshot to complete (poll up to 120s for Java 21 JVM warmup)
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> tmColumns = java.util.Collections.emptyMap();
        for (int i = 0; i < 24; i++) {
            Thread.sleep(5000);
            if (engineFuture.isDone()) {
                try {
                    engineFuture.get();
                } catch (ExecutionException ee) {
                    log.error("Engine future completed with exception: {}",
                            ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage(), ee.getCause());
                }
            }
            tmColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "tm", "public");
            log.debug("Poll #{} – tm columns: {}", i + 1, tmColumns.keySet());
            if (!tmColumns.isEmpty()) break;
        }

        Assert.assertTrue("tm table must have columns after snapshot", tmColumns.size() > 0);
        Assert.assertTrue("id column must be UUID type", tmColumns.get("id").equalsIgnoreCase("UUID"));
        Assert.assertTrue("secid column must be Nullable(UUID)", tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
        Assert.assertTrue("created column must be Nullable(DateTime64(6))", tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6))"));

        int tmCount = 0;
        ResultSet chRs = writer.getConnection().prepareStatement("select count(*) from public.tm").executeQuery();
        while (chRs.next()) {
            tmCount = chRs.getInt(1);
        }

        // Get columns for redata table
        Map<String, String> reDataColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "redata", "public");
        Assert.assertTrue("redata.amount must be Decimal(64, 18)", reDataColumns.get("amount").equalsIgnoreCase("Decimal(64, 18)"));
        Assert.assertTrue("redata.total_amount must be Decimal(21, 5)", reDataColumns.get("total_amount").equalsIgnoreCase("Decimal(21, 5)"));
        Assert.assertTrue("tm should have 2 rows after initial snapshot", tmCount == 2);

        // Validate offset storage is functioning (non-null, contains replication fields)
        String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(getProperties(), writer.getConnection());
        Assert.assertTrue("Offset must contain last_snapshot_record", offsetValue.contains("last_snapshot_record"));
        Assert.assertTrue("Offset must contain lsn", offsetValue.contains("lsn"));
        Assert.assertTrue("Offset must contain txId", offsetValue.contains("txId"));
        Assert.assertTrue("Offset must contain ts_usec", offsetValue.contains("ts_usec"));
        Assert.assertTrue("Offset must contain snapshot", offsetValue.contains("snapshot"));

        // Verify TRUNCATE replication
        ITCommon.connectToPostgreSQL(postgreSQLContainer).prepareStatement("truncate table public.tm").execute();
        Thread.sleep(15000);

        chRs = writer.getConnection().prepareStatement("select count(*) from public.tm").executeQuery();
        while (chRs.next()) {
            tmCount = chRs.getInt(1);
        }
        Assert.assertTrue("TRUNCATE operation should empty the table in ClickHouse", tmCount == 0);

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }
}
