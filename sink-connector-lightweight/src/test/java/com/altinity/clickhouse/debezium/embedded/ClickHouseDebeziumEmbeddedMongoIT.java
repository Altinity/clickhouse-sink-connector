//package com.altinity.clickhouse.debezium.embedded;
//
//import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
//import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
//import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
//import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
//import com.mongodb.MongoException;
//import com.mongodb.client.MongoClient;
//import com.mongodb.client.MongoClients;
//import com.mongodb.client.MongoCollection;
//import com.mongodb.client.MongoDatabase;
//import com.mongodb.client.result.InsertOneResult;
//import org.bson.Document;
//import org.bson.types.ObjectId;
//import org.junit.jupiter.api.Test;
//import org.testcontainers.containers.ClickHouseContainer;
//import org.testcontainers.containers.MongoDBContainer;
//import org.testcontainers.containers.wait.strategy.Wait;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import org.testcontainers.utility.MountableFile;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.Properties;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//
//@Testcontainers
//public class ClickHouseDebeziumEmbeddedMongoIT {
//
//    @Container
//    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
//            .withInitScript("init_clickhouse.sql")
//            .withExposedPorts(8123);
//
//    //https://github.com/testcontainers/testcontainers-java/issues/3066
//    @Container
//    public static MongoDBContainer mongoContainer = new MongoDBContainer("mongo:latest")
////            .withEnv("MONGO_INITDB_ROOT_USERNAME", "project")
////            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "project")
//            .withEnv("MONGO_INITDB_DATABASE", "project")
//            .withCopyFileToContainer(MountableFile.forClasspathResource("mongo-init.js"),
//                    "/docker-entrypoint-initdb.d/mongo-init.js")
//            .waitingFor(Wait.forLogMessage("(?i).*Waiting for connections*.*", 1));
//    // .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2))
//           // .withStartupTimeout(Duration.ofSeconds(10));
//
////            .withInitScript("init_postgres.sql")
////            .withDatabaseName("public")
////            .withUsername("root")
////            .withPassword("adminpass")
////            .withExposedPorts(5432)
////            .withCommand("postgres -c wal_level=logical");
//
//    @Test
//    //@Disabled
//    public void testDataTypesDB() throws Exception {
//
//
//        // Start the debezium embedded application.
//
//        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
//        System.out.println("MYSQL HOST" + mongoContainer.getHost());
//        System.out.println("Connection string" + mongoContainer.getConnectionString());
//        defaultProps.setProperty("mongodb.connection.string", mongoContainer.getConnectionString());
//        defaultProps.setProperty("mongodb.members.auto.discover", "true");
//        defaultProps.setProperty("topic.prefix", "mongo-ch");
//        defaultProps.setProperty("collection.include.list", "project.items");
//        defaultProps.setProperty("snapshot.include.collection.list", "project.items");
//        defaultProps.setProperty("database.include.list", "project");
//        defaultProps.setProperty("key.converter", "org.apache.kafka.connect.json.JsonConverter");
//
//        defaultProps.setProperty("value.converter", "org.apache.kafka.connect.storage.StringConverter");
//        defaultProps.setProperty("value.converter.schemas.enable", "true");
//
//        //defaultProps.setProperty("mongodb.hosts", mongoContainer.getHost() + ":" + mongoContainer.getFirstMappedPort());
//       // defaultProps.setProperty("topic.prefix", mongoContainer.getC());
//        //System.out.println("JDBC URL" + mySqlContainer.getJdbcUrl());
////        defaultProps.setProperty("database.hostname", mongoContainer.getHost());
////        defaultProps.setProperty("database.port", String.valueOf(mongoContainer.getFirstMappedPort()));
//       defaultProps.setProperty("database.dbname", "project");
//        defaultProps.setProperty("database.user", "project");
//        defaultProps.setProperty("database.password", "project");
//
//        // defaultProps.setProperty("database.include.list", "public");
//        defaultProps.setProperty("snapshot.mode", "initial");
//        defaultProps.setProperty("connector.class", "io.debezium.connector.mongodb.MongoDbConnector");
//        //defaultProps.setProperty("plugin.name", "pgoutput");
//        //defaultProps.setProperty("table.include.list", "public.tm");
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
//        defaultProps.setProperty("clickhouse.server.database", "project");
//        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
//        defaultProps.setProperty("metrics.port", "8087");
//        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
//
//        ExecutorService executorService = Executors.newFixedThreadPool(1);
//        executorService.execute(() -> {
//            try {
//                new DebeziumChangeEventCapture().setup(defaultProps, new SourceRecordParserService(),
//                        new MySQLDDLParserService());
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//        Thread.sleep(15000);
//
//        insertNewDocument();
//        Thread.sleep(60000);
//
////        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
////                "public", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);
////        Map<String, String> tmColumns = writer.getColumnsDataTypesForTable("tm");
////        Assert.assertTrue(tmColumns.size() == 22);
////        Assert.assertTrue(tmColumns.get("id").equalsIgnoreCase("UUID"));
////        Assert.assertTrue(tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
////        //Assert.assertTrue(tmColumns.get("am").equalsIgnoreCase("Nullable(Decimal(21,5))"));
////        Assert.assertTrue(tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6))"));
////
////
////        int tmCount = 0;
////        ResultSet chRs = writer.getConnection().prepareStatement("select count(*) from tm").executeQuery();
////        while(chRs.next()) {
////            tmCount =  chRs.getInt(1);
////        }
//
//       // Assert.assertTrue(tmCount == 2);
//
//        executorService.shutdown();
//        Files.deleteIfExists(tmpFilePath);
//
//
//    }
//
//    private void insertNewDocument() {
//        try (MongoClient mongoClient = MongoClients.create(mongoContainer.getConnectionString())) {
//            MongoDatabase database = mongoClient.getDatabase("project");
//            MongoCollection<Document> collection = database.getCollection("items");
//            try {
//                InsertOneResult result = collection.insertOne(new Document()
//                        .append("uuid", new ObjectId())
//                        .append("price", 44)
//                        .append("name", "Record one"));
//                System.out.println("Success! Inserted document id: " + result.getInsertedId());
//            } catch (MongoException me) {
//                System.err.println("Unable to insert due to an error: " + me);
//            }
//        }
//    }
//}
