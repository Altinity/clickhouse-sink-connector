package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@DisplayName("Integration Test that validates handling of ALTER table(Add Column) DDL received from MYSQL")
@Testcontainers
public class AlterTableAddColumnIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
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

                Properties properties = getDebeziumProperties();
                // Add ddl.retry to true
                //properties.put(SinkConnectorLightWeightConfig.DDL_RETRY, "true");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
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
        conn.prepareStatement(" alter table add_test add column col5 ENUM ('M','F');").execute();
        conn.prepareStatement(" alter table add_test add column col6 JSON;").execute();
        conn.prepareStatement(" alter table add_test drop col4").execute();

        Thread.sleep(25000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata();
        Map<String, String> shipClassColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "ship_class", "employees");
        Map<String, String> addTestColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "add_test", "employees");

        // Validate all ship_class columns.
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(shipClassColumns.get("ship_spec")));
        Assert.assertTrue("Nullable(Int32)".equalsIgnoreCase(shipClassColumns.get("somecol")));
        Assert.assertTrue("Nullable(Bool)".equalsIgnoreCase(shipClassColumns.get("newcol")));
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(shipClassColumns.get("customer_address")));
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(shipClassColumns.get("customer_name")));

        // Validate all add_test columns.
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(addTestColumns.get("col8")));
        Assert.assertTrue("Nullable(Int32)".equalsIgnoreCase(addTestColumns.get("col2")));
        Assert.assertTrue("Nullable(Int32)".equalsIgnoreCase(addTestColumns.get("col3")));
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(addTestColumns.get("col5")));
        Assert.assertTrue("Nullable(String)".equalsIgnoreCase(addTestColumns.get("col6")));

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();


        HikariDbSource.close();
    }
}
