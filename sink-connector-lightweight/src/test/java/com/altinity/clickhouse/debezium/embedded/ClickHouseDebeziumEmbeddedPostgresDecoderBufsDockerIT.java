package com.altinity.clickhouse.debezium.embedded;

import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
public class ClickHouseDebeziumEmbeddedPostgresDecoderBufsDockerIT {

    @Container
    public static org.testcontainers.clickhouse.ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    public static  DockerImageName myImage = DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

    @Container
    public static PostgreSQLContainer postgreSQLContainer = (PostgreSQLContainer) new PostgreSQLContainer(myImage)
            .withInitScript("init_postgres.sql")
            .withDatabaseName("public")
            .withUsername("root")
            .withPassword("root")
            .withExposedPorts(5432)
            .withCommand("postgres -c wal_level=logical")
            .withNetworkAliases("postgres").withAccessToHost(true);



    public Properties getProperties() throws Exception {

        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "decoderbufs");
        properties.put("plugin.path", "/");
        properties.put("table.include.list", "public.tm");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("table.include.list", "public.tm,public.tm2");

        return properties;
    }

    @Test
    @DisplayName("Integration Test - Validates PostgreSQL replication when the plugin is set to DecoderBufs")
    public void testDecoderBufsPlugin() throws Exception {
        Network network = Network.newNetwork();

        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);

        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until the 'tm' table has 23 columns and at least 2 rows (up to 180s)
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        Assert.assertTrue("Timed out waiting for 'tm' table to have 23 columns in ClickHouse",
                ITCommon.waitForTableColumns(writer.getConnection(), "public", "tm", 23, 180_000));

        long tmCount = ITCommon.waitForRowCount(writer.getConnection(),
                "select count(*) from public.tm", 2, 180_000, 5_000);
        Assert.assertEquals("Expected 2 rows in public.tm", 2, tmCount);

        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> tmColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "tm", "public");
        Assert.assertTrue(tmColumns.size() == 23);

        Assert.assertTrue(tmColumns.get("id").equalsIgnoreCase("UUID"));
        Assert.assertTrue(tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
        //Assert.assertTrue(tmColumns.get("am").equalsIgnoreCase("Nullable(Decimal(21,5))"));
        Assert.assertTrue(tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6))"));

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        HikariDbSource.close();
    }
}
