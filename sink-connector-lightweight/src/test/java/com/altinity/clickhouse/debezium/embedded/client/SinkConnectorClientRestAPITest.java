package com.altinity.clickhouse.debezium.embedded.client;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpGet;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.HttpEntity;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.io.entity.EntityUtils;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
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


    protected Properties getDebeziumProperties() throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        // **** OVERRIDE set to schema only
        defaultProps.setProperty("snapshot.mode", "schema_only");
        defaultProps.setProperty("disable.drop.truncate", "true");

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
        defaultProps.setProperty("clickhouse.server.database", "employees");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;

    }
    @Test
    public void testRestClient() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties();
        props.setProperty("database.include.list", "datatypes");
        props.setProperty("clickhouse.server.database", "datatypes");
        // Override clickhouse server timezone.


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
                    ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
                    clickHouseDebeziumEmbeddedApplication.startRestApi(props, injector);
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                injector.getInstance(DDLParserService.class), props, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(40000);//

        Connection conn = connectToMySQL();
        // alter table ship_class change column class_name class_name_new int;
        // alter table ship_class change column tonange tonange_new decimal(10,10);

        //conn.prepareStatement("insert into dt values('2008-01-01 00:00:01', 'this is a test', 11, 1)").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME` VALUES ('DATETIME-INSERT','1000-01-01 00:00:00','2022-09-29 01:47:46','9999-12-31 23:59:59','9999-12-31 23:59:59');\n").execute();


        Thread.sleep(10000);


        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

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

        // Validate the stop call.


    }
    Connection connectToMySQL() {
        Connection conn = null;
        try {

            String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort(),
                    mySqlContainer.getDatabaseName(), mySqlContainer.getUsername(), mySqlContainer.getPassword());
            conn = DriverManager.getConnection(connectionUrl);


        } catch (SQLException ex) {
            // handle any errors

        }

        return conn;
    }
}
