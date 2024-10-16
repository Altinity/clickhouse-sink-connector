package com.altinity.clickhouse.debezium.embedded.api;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.client.internal.apache.hc.client5.http.classic.methods.HttpGet;
import com.clickhouse.client.internal.apache.hc.client5.http.classic.methods.HttpUriRequest;
import com.clickhouse.client.internal.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import com.clickhouse.client.internal.apache.hc.core5.http.HttpResponse;
import com.google.inject.Guice;
import io.javalin.http.HttpStatus;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import io.javalin.testtools.JavalinTest;


@Disabled
public class DebeziumEmbeddedRestApiIT {

    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                //  .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        // clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stopContainers() {
        if(mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();;
        }
        if(clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }

    }

    static {
        clickHouseContainer = new org.testcontainers.clickhouse.ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();
    }
    @ParameterizedTest
    @CsvSource({
            "clickhouse/clickhouse-server:latest"
    })
    @DisplayName("Test that validates that the REST API is working.")
    public void testRESTAPI(String clickHouseServerVersion) throws Exception {

        Thread.sleep(5000);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `newtable`(col1 varchar(255), col2 int, col3 int)").execute();

        Thread.sleep(10000);


        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Properties props = ITCommon.getDebeziumPropertiesForSchemaOnly(mySqlContainer, clickHouseContainer);

        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumPropertiesForSchemaOnly(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "datatypes"),false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        try {
            DebeziumEmbeddedRestApi.startRestApi(props,   Guice.createInjector(new AppInjector()), engine.get(), props);
        } catch(Exception e) {
            System.out.println("Error starting REST API" + e.toString());
        }

        Thread.sleep(10000);
        conn.prepareStatement("insert into `newtable` values('test', 1, 2)").execute();
        conn.close();

        Thread.sleep(10000);

        DebeziumChangeEventCapture dec = engine.get();
        long getStoredRecordTs = dec.getLatestRecordTimestamp(new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(props)), props);

        Assert.assertTrue(getStoredRecordTs > 0);

        // Given
        HttpUriRequest request = new HttpGet( "http://localhost:7000/status" );

        // When
        HttpResponse httpResponse = HttpClientBuilder.create().build().execute( request );

        // Then
        Assert.assertTrue(httpResponse.getCode() == HttpStatus.OK.getCode());

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

    }
}
