package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class ClickHouseDebeziumEmbeddedPostgresDecoderBufsDockerIT {

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("init_clickhouse.sql")
            .withExposedPorts(8123).withNetworkAliases("clickhouse").withAccessToHost(true);

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



    public Map<String, String> getDefaultProperties() throws IOException {


        Map<String, String> properties = new HashMap<String, String>();
        properties.put("database.hostname", "postgres");
        properties.put("database.port", "5432");
        properties.put("database.dbname", "public");
        properties.put("database.user", "root");
        properties.put("database.password", "root");
        properties.put("snapshot.mode", "initial");
        properties.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        properties.put("plugin.name", "decoderbufs");
        properties.put("plugin.path", "/");
        properties.put("table.include.list", "public.tm");
        properties.put("topic.prefix","test-server");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        
        properties.put("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");

        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
        Path tmpFilePath = Files.createTempFile("offsets", ".dat");

        if (tmpFilePath != null) {
            System.out.println("TEMP FILE PATH" + tmpFilePath);
        }

        Files.deleteIfExists(tmpFilePath);
        properties.put("offset.storage.file.filename", tmpFilePath.toString());
        properties.put("offset.flush.interval.ms", "60000");
        properties.put("auto.create.tables", "true");
        properties.put("clickhouse.server.url", "clickhouse");
        properties.put("clickhouse.server.port", "8123");
        properties.put("clickhouse.server.user", "default");
        properties.put("clickhouse.server.password", "");
        properties.put("clickhouse.server.database", "public");
        properties.put("replacingmergetree.delete.column", "_sign");
        properties.put("metrics.port", "8087");
        properties.put("database.allowPublicKeyRetrieval", "true");

        return properties;
    }

    @Test
    public void testDataTypesDB() throws Exception {
        Network network = Network.newNetwork();

        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);

        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
        GenericContainer sinkConnectorLightWeightContainer = new
                GenericContainer("registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:latest")
                .withEnv(getDefaultProperties()).withNetwork(network);

        sinkConnectorLightWeightContainer.start();
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

        Assert.assertTrue(tmCount == 1);

//        if(engine.get() != null) {
//            engine.get().stop();
//        }
//        executorService.shutdown();
//        Files.deleteIfExists(tmpFilePath);


    }
}
