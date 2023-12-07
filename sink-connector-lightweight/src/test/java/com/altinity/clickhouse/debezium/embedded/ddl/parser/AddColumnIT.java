package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
public class AddColumnIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testAddColumn() throws Exception {

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

        Thread.sleep(10000);//

        Connection conn = connectToMySQL();
        // alter table ship_class change column class_name class_name_new int;
        // alter table ship_class change column tonange tonange_new decimal(10,10);

        conn.prepareStatement("alter table ship_class add column ship_spec varchar(150) first, add somecol int after start_build, algorithm=instant;").execute();
        conn.prepareStatement("alter table ship_class ADD newcol bool null DEFAULT 0;").execute();
        conn.prepareStatement("alter table ship_class add column customer_address varchar(100) not null, add column customer_name varchar(20) null;").execute();
        conn.prepareStatement("alter table add_test add column col8 varchar(255) first;").execute();
        conn.prepareStatement("alter table add_test add column col99 int default 1 after col8;").execute();

        conn.prepareStatement("alter table add_test modify column col99 tinyint;").execute();
        conn.prepareStatement("alter table add_test add column col22 varchar(255);").execute();
        conn.prepareStatement("alter table add_test add column col4 varchar(255);").execute();
        conn.prepareStatement("alter table add_test rename column col99 to col101;").execute();
        conn.prepareStatement(" alter table add_test drop column col101;").execute();

        Thread.sleep(25000);


        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        Map<String, String> shipClassColumns = writer.getColumnsDataTypesForTable("ship_class");
        Map<String, String> addTestColumns = writer.getColumnsDataTypesForTable("add_test");

        // Validate all ship_class columns.
        Assert.assertTrue(shipClassColumns.get("ship_spec").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(shipClassColumns.get("somecol").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(shipClassColumns.get("newcol").equalsIgnoreCase("Nullable(Bool)"));
        Assert.assertTrue(shipClassColumns.get("customer_address").equalsIgnoreCase("String"));
        Assert.assertTrue(shipClassColumns.get("customer_name").equalsIgnoreCase("Nullable(String)"));

        // Validate all add_test columns.
        Assert.assertTrue(addTestColumns.get("col8").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(addTestColumns.get("col2").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(addTestColumns.get("col3").equalsIgnoreCase("Nullable(Int32)"));

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();



    }
}
