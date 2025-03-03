package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Disabled
@Testcontainers
@DisplayName("Integration Test that validates auto create tables feature which creates tables when a CDC record(Insert) is received")
public class AutoCreateTableIT {
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

    static {
        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
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
    @DisplayName("Test that validates auto create table when table name has dashes")
    public void testAutoCreateTable(String clickHouseServerVersion) throws Exception {

        Thread.sleep(5000);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `new-table`(col1 varchar(255), col2 int, col3 int)").execute();

        Thread.sleep(20000);


        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);
        conn.prepareStatement("insert into `new-table` values('test', 1, 2)").execute();
        conn.close();

        Thread.sleep(10000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Thread.sleep(10000);
        ResultSet dateTimeResult = ITCommon.executeQueryWithResultSet("select count(*) from employees.`new-table`", writer.getConnection());
        boolean resultReceived = false;

        while(dateTimeResult.next()) {
            resultReceived = true;
            Assert.assertEquals(1, dateTimeResult.getInt(1));
        }
        Assert.assertTrue(resultReceived);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
        HikariDbSource.close();
    }
}
