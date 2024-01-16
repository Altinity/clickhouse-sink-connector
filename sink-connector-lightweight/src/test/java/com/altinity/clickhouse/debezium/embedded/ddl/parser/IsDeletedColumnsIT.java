package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
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
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration Test that validates DDL Creation when there are source columns with the same name(is_deleted)")
public class IsDeletedColumnsIT {

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
    @DisplayName("Test that validates create table in CH when MySQL has is_deleted columns")
    public void testIsDeleted(String clickHouseServerVersion) throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())),false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        Thread.sleep(10000);
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table new_table(col1 varchar(255), col2 int, is_deleted int, _sign int)").execute();

        Thread.sleep(10000);

        conn.prepareStatement("insert into new_table values('test', 1, 22, 1)").execute();
        conn.close();
        Thread.sleep(10000);

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);
        ResultSet rs = writer.executeQueryWithResultSet("select * from new_table");
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            Assert.assertTrue(rs.getString("col1").equalsIgnoreCase("test"));
            Assert.assertTrue(rs.getInt("col2") == 1);
            Assert.assertTrue(rs.getInt("is_deleted") == 22);
            Assert.assertTrue(rs.getInt("_sign") == 1);
        }
        Assert.assertTrue(recordFound);

        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
    }
}
