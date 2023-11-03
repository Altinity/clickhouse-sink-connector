//package com.altinity.clickhouse.debezium.embedded;
//
//import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
//import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
//import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
//import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
//import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
//import org.junit.Assert;
//import org.junit.jupiter.api.Test;
//import org.testcontainers.containers.ClickHouseContainer;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.sql.ResultSet;
//import java.util.Map;
//import java.util.Properties;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.atomic.AtomicReference;
//
//@Testcontainers
//public class ClickHouseDebeziumEmbeddedPostgreSQLIT {
//
//    @Container
//    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
//            .withInitScript("init_clickhouse.sql")
//            .withExposedPorts(8123);
//
//    @Container
//    public static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
//            .withInitScript("init_postgres.sql")
//            .withDatabaseName("public")
//            .withUsername("root")
//            .withPassword("adminpass")
//            .withExposedPorts(5432)
//            .withCommand("postgres -c wal_level=logical");
//
//    @Test
//    public void testDataTypesDB() throws Exception {
//
//        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
//
//        // Start the debezium embedded application.
//
//        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
//        System.out.println("MYSQL HOST" + postgreSQLContainer.getHost());
//        //System.out.println("JDBC URL" + mySqlContainer.getJdbcUrl());
//        defaultProps.setProperty("database.hostname", postgreSQLContainer.getHost());
//        defaultProps.setProperty("database.port", String.valueOf(postgreSQLContainer.getFirstMappedPort()));
//        defaultProps.setProperty("database.dbname", "public");
//        defaultProps.setProperty("database.user", "root");
//        defaultProps.setProperty("database.password", "adminpass");
//
//        // defaultProps.setProperty("database.include.list", "public");
//        defaultProps.setProperty("snapshot.mode", "initial");
//        defaultProps.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
//        defaultProps.setProperty("plugin.name", "pgoutput");
//        defaultProps.setProperty("table.include.list", "public.tm");
//
//
//        defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
//
//        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
//        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
//
//        if (tmpFilePath != null) {
//            System.out.println("TEMP FILE PATH" + tmpFilePath);
//        }
//
//        Files.deleteIfExists(tmpFilePath);
//        defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toString());
//        defaultProps.setProperty("offset.flush.interval.ms", "60000");
//        defaultProps.setProperty("auto.create.tables", "true");
//        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        defaultProps.setProperty("clickhouse.server.user", "default");
//        defaultProps.setProperty("clickhouse.server.password", "");
//        defaultProps.setProperty("clickhouse.server.database", "public");
//        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
//        defaultProps.setProperty("metrics.port", "8087");
//        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
//
//        ExecutorService executorService = Executors.newFixedThreadPool(1);
//        executorService.execute(() -> {
//            try {
//                engine.set(new DebeziumChangeEventCapture());
//                engine.get().setup(defaultProps, new SourceRecordParserService(),
//                        new MySQLDDLParserService());
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//        Thread.sleep(20000);
//
//        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
//                "public", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);
//        Map<String, String> tmColumns = writer.getColumnsDataTypesForTable("tm");
//        Assert.assertTrue(tmColumns.size() == 22);
//        Assert.assertTrue(tmColumns.get("id").equalsIgnoreCase("UUID"));
//        Assert.assertTrue(tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
//        //Assert.assertTrue(tmColumns.get("am").equalsIgnoreCase("Nullable(Decimal(21,5))"));
//        Assert.assertTrue(tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6))"));
//
//
//        int tmCount = 0;
//        ResultSet chRs = writer.getConnection().prepareStatement("select count(*) from tm").executeQuery();
//        while(chRs.next()) {
//            tmCount =  chRs.getInt(1);
//        }
//
//        Assert.assertTrue(tmCount == 1);
//
//        if(engine.get() != null) {
//            engine.get().stop();
//        }
//        executorService.shutdown();
//        Files.deleteIfExists(tmpFilePath);
//
//
//    }
//}
