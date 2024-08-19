package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

public class PostgresInitialDockerWKeeperMapStorageIT {

    static ClickHouseContainer clickHouseContainer;

    static GenericContainer zookeeperContainer = new GenericContainer(DockerImageName.parse("zookeeper:3.6.2"))
            .withExposedPorts(2181).withAccessToHost(true);


    @BeforeEach
    public void startContainers() throws InterruptedException {

        Network network = Network.newNetwork();
        zookeeperContainer.withNetwork(network).withNetworkAliases("zookeeper");
        zookeeperContainer.start();

        Thread.sleep(15000);

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:22.3")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withClasspathResourceMapping("config_replicated.xml", "/etc/clickhouse-server/config.d/config.xml", BindMode.READ_ONLY)
                .withClasspathResourceMapping("macros.xml", "/etc/clickhouse-server/config.d/macros.xml", BindMode.READ_ONLY)
                .withExposedPorts(8123)
                .waitingFor(new HttpWaitStrategy().forPort(zookeeperContainer.getFirstMappedPort()));
        clickHouseContainer.withNetwork(network).withNetworkAliases("clickhouse");
        clickHouseContainer.start();
    }


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
        properties.put("table.include.list", "public.tm,public.tm2,public.redata");
        properties.put("offset.storage.jdbc.offset.table.ddl", "CREATE TABLE if not exists %s on cluster '{cluster}' (id String, offset_key String, offset_val String, record_insert_ts DateTime, record_insert_seq UInt64) ENGINE =  KeeperMap('/asc_offsets201',10) PRIMARY KEY offset_key");
        properties.put("offset.storage.jdbc.offset.table.delete", "select 1");
        properties.put("skipped.operations","none");it
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
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);//
        Thread.sleep(50000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "public");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "public", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);
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

        // Get the columns in re_data.
        Map<String, String> reDataColumns = writer.getColumnsDataTypesForTable("redata");

        Assert.assertTrue(reDataColumns.get("amount").equalsIgnoreCase("Decimal(64, 18)"));
        Assert.assertTrue(reDataColumns.get("total_amount").equalsIgnoreCase("Decimal(21, 5)"));
        Assert.assertTrue(tmCount == 2);

        String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(getProperties(), writer);

        // Parse offsetvalue json and check the keys
        Assert.assertTrue(offsetValue.contains("last_snapshot_record"));
        Assert.assertTrue(offsetValue.contains("lsn"));
        Assert.assertTrue(offsetValue.contains("txId"));
        Assert.assertTrue(offsetValue.contains("ts_usec"));
        Assert.assertTrue(offsetValue.contains("snapshot"));

        // Connect to postgreSQL and issue a truncate table command.
        ITCommon.connectToPostgreSQL(postgreSQLContainer).prepareStatement("truncate table public.tm").execute();
        Thread.sleep(5000);

        // Check if the clickhouse table is empty.
        chRs = writer.getConnection().prepareStatement("select count(*) from tm").executeQuery();
        while(chRs.next()) {
            tmCount =  chRs.getInt(1);
        }

        Assert.assertTrue(tmCount == 0);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

    }
}
