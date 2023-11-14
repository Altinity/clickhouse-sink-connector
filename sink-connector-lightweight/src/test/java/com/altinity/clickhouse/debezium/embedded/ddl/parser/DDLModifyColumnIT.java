package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
public class DDLModifyColumnIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_modify_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testModifyColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);

        Connection conn = connectToMySQL();

        conn.prepareStatement("alter table ship_class modify column class_name int;").execute();
        conn.prepareStatement("alter table ship_class modify column tonange decimal(10,10);").execute();
        conn.prepareStatement("alter table add_test modify column col1 int, modify column col2 varchar(255);").execute();
        conn.prepareStatement("alter table add_test modify column col1 int default 0;").execute();
        conn.prepareStatement("alter table add_test modify column col3 int first;").execute();
        conn.prepareStatement("alter table add_test modify column col2 int after col3;").execute();


        Thread.sleep(15000);

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        Map<String, String> shipClassColumns = writer.getColumnsDataTypesForTable("ship_class");
        Map<String, String> addTestColumns = writer.getColumnsDataTypesForTable("add_test");

        Assert.assertTrue(shipClassColumns.get("class_name").equalsIgnoreCase("Int32"));
        Assert.assertTrue(shipClassColumns.get("tonange").equalsIgnoreCase("Decimal(10, 10)"));

        Assert.assertTrue(addTestColumns.get("col1").equalsIgnoreCase("Int32"));
        Assert.assertTrue(addTestColumns.get("col2").equalsIgnoreCase("Int32"));
        Assert.assertTrue(addTestColumns.get("col3").equalsIgnoreCase("Int32"));

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

    }
}
