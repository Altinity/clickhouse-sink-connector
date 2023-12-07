package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration Test that validates DDL replication of ALTER table column, first, after and MODIFY column")
public class DDLChangeColumnIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_change_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testChangeColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);

        Connection conn = connectToMySQL();
        // alter table ship_class change column class_name class_name_new int;
        // alter table ship_class change column tonange tonange_new decimal(10,10);

        conn.prepareStatement("alter table ship_class change column class_name class_name_new int").execute();
        conn.prepareStatement("alter table ship_class change column tonange tonange_new decimal(10,10)").execute();
        conn.prepareStatement("alter table add_test change column col1 col1_new int, modify column col2 varchar(255)").execute();
        conn.prepareStatement("alter table add_test change column col2 new_col2_name int after col3;").execute();
        conn.prepareStatement("alter table add_test change column col3 new_col3_name int first").execute();

//        conn.prepareStatement("alter table add_test change column col1 int").execute();
//        conn.prepareStatement("alter table add_test change column col3 int first").execute();
//        conn.prepareStatement("alter table add_test change column col2 int after col3").execute();

        Thread.sleep(10000);

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        Map<String, String> shipClassColumns = writer.getColumnsDataTypesForTable("ship_class");
        Map<String, String> addTestColumns = writer.getColumnsDataTypesForTable("add_test");

        Thread.sleep(10000);
        // Validate all ship_class columns.
        Assert.assertTrue(shipClassColumns.get("class_name_new").equalsIgnoreCase("Int32"));
        Assert.assertTrue(shipClassColumns.get("tonange_new").equalsIgnoreCase("Decimal(10, 10)"));
        Assert.assertTrue(shipClassColumns.get("max_length").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        // Files.deleteIfExists(tmpFilePath);
        Assert.assertTrue(addTestColumns.get("new_col3_name").equalsIgnoreCase("Int32"));
        Assert.assertTrue(addTestColumns.get("col1_new").equalsIgnoreCase("Int32"));
        Assert.assertTrue(addTestColumns.get("new_col2_name").equalsIgnoreCase("Int32"));


        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();


    }
}
