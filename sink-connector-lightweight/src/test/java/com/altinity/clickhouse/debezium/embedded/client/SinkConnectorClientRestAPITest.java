package com.altinity.clickhouse.debezium.embedded.client;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpGet;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.HttpEntity;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.io.entity.EntityUtils;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Testcontainers
@DisplayName("Test that validates the REST API calls used by sink connector client")
public class SinkConnectorClientRestAPITest {

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("datetime.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Test
    @Disabled
    public void testRestClient() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("database.include.list", "datatypes");
        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                injector.getInstance(DDLParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(40000);//

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME` VALUES ('DATETIME-INSERT','1000-01-01 00:00:00','2022-09-29 01:47:46','9999-12-31 23:59:59','9999-12-31 23:59:59');\n").execute();


        Thread.sleep(10000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME");


        HttpUriRequest request = new HttpGet("http://localhost:7000/status");

        // Validate the status call.
        CloseableHttpResponse httpResponse = HttpClientBuilder.create().build().execute( request );
        HttpEntity entity = httpResponse.getEntity();
        if(entity != null) {
            String json = EntityUtils.toString(entity);
            Assert.assertTrue(json.contains("Replica_Running"));
            Assert.assertTrue(json.contains("Database"));
            Assert.assertTrue(json.contains("Seconds_Behind_Source"));

            //String result = new String(entity.getContent().readAllBytes());
           // JSONArray resultArray = (JSONArray) new JSONParser().parse(json);

            //[{"Seconds_Behind_Source":0},{"Replica_Running":true},{"Database":"datatypes"}]
//            resultArray.forEach(item -> {
//                HashMap<String, Object> resultMap = (HashMap<String, Object>) item;
//                if(resultMap.containsKey("Replica_Running")) {
//                    Assert.assertTrue(resultMap.containsKey("Replica_Running"));
//                    Assert.assertTrue(resultMap.get("Replica_Running").equals(true));
//                }
//
//                if(resultMap.containsKey("Database")) {
//                    Assert.assertTrue(resultMap.containsKey("Database"));
//                    Assert.assertTrue(resultMap.get("Database").equals("datatypes"));
//                }
//                if(resultMap.containsKey("Seconds_Behind_Source")){
//                    Assert.assertTrue(resultMap.containsKey("Seconds_Behind_Source"));
//                }
//            });
           // System.out.println(result);
        } else {
            // There should be a respond body.
            Assert.fail("There should be a respond body.");
        }

        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().stop();

    }
}
