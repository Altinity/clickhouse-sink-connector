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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
@Testcontainers
@DisplayName("Integration test to validate replication of DDL (ALTER TABLE modify column")
public class AlterTableModifyColumnIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
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
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),  false);
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> shipClassColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "ship_class", "employees");
        Map<String, String> addTestColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "add_test", "employees");

        Assert.assertTrue(shipClassColumns.get("class_name").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(shipClassColumns.get("tonange").equalsIgnoreCase("Nullable(Decimal(10, 10))"));

        Assert.assertTrue(addTestColumns.get("col1").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(addTestColumns.get("col2").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(addTestColumns.get("col3").equalsIgnoreCase("Nullable(Int32)"));

        // Validate logic of adding Nullable based on the existing schema.
        conn.prepareStatement("alter table office modify column office_code int").execute();
        Thread.sleep(10000);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
        HikariDbSource.close();
    }
}
