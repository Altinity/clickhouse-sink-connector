package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
public class ClickHouseDebeziumEmbeddedMongoIT {

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("init_clickhouse.sql")
            .withExposedPorts(8123);

    //https://github.com/testcontainers/testcontainers-java/issues/3066
    @Container
    public static MongoDBContainer mongoContainer = new MongoDBContainer("mongo:latest")
            .withEnv("MONGO_INITDB_DATABASE", "project")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mongo-init.js"),
                    "/docker-entrypoint-initdb.d/mongo-init.js")
                    .withExposedPorts(27017)
                    .withNetworkAliases("mongo")
            .withCommand("--replSet docker-rs").withCommand("--bind_ip_all").withCommand("--port 27017")
            .waitingFor(Wait.forLogMessage("(?i).*Waiting for connections*.*", 1));


    @Test
    //@Disabled
    public void testDataTypesDB() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        Properties defaultProps = ITCommon.getDebeziumProperties(mongoContainer, clickHouseContainer);
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(defaultProps, new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "datatypes"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(15000);

        insertNewDocument();
        Thread.sleep(60000);

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

       // Assert.assertTrue(tmCount == 2);

       if(engine.get() != null) {
        engine.get().stop();
    }
    // Files.deleteIfExists(tmpFilePath);
    executorService.shutdown();

    //writer.getConnection().close();


    }

    private void insertNewDocument() {
        try (MongoClient mongoClient = MongoClients.create(mongoContainer.getConnectionString())) {
            MongoDatabase database = mongoClient.getDatabase("project");
            MongoCollection<Document> collection = database.getCollection("items");
            try {
                InsertOneResult result = collection.insertOne(new Document()
                        .append("uuid", new ObjectId())
                        .append("price", 44)
                        .append("name", "Record one"));
                System.out.println("Success! Inserted document id: " + result.getInsertedId());
            } catch (MongoException me) {
                System.err.println("Unable to insert due to an error: " + me);
            }
        }
    }
}
