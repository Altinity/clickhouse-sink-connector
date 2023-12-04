package com.altinity.clickhouse.debezium.embedded;

import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class ClickHouseDebeziumEmbeddedPostgresDecoderBufsDockerIT {

    @Container
    public static org.testcontainers.clickhouse.ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
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
                engine.get().setup(getProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);//
        Thread.sleep(50000);

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "public", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);
        Map<String, String> tmColumns = writer.getColumnsDataTypesForTable("tm");
        Assert.assertTrue(tmColumns.size() == 22);
        Assert.assertTrue(tmColumns.get("id").equalsIgnoreCase("UUID"));
        Assert.assertTrue(tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
        //Assert.assertTrue(tmColumns.get("am").equalsIgnoreCase("Nullable(Decimal(21,5))"));
        Assert.assertTrue(tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6))"));


        int tmCount = 0;
        ResultSet chRs = writer.getConnection().prepareStatement("select count(*) from tm").executeQuery();
        while(chRs.next()) {
            tmCount =  chRs.getInt(1);
        }

        Assert.assertTrue(tmCount == 2);
    }
}
