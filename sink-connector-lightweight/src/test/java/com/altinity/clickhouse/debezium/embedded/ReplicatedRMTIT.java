package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration test that validates auto creation of Replicated RMT when the flag is set in config(auto.create.replicated.tables)")
public class ReplicatedRMTIT {
    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        // clickHouseContainer.start();
        Thread.sleep(15000);
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
            "clickhouse/clickhouse-server:latest",
            "clickhouse/clickhouse-server:22.3"
    })
    @DisplayName("Test that validates DDL(Create, ALTER, RENAME)")
    public void testTableOperations(String clickHouseServerVersion) throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        Thread.sleep(20000);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
    }

}
