package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
@Testcontainers
@DisplayName("Integration Test that validates replication of Truncate Table DDL statement")
public class TruncateTableIT {

    protected MySQLContainer mySqlContainer;
    protected ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("truncate_table.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        // Created per test rather than once in a static block. @AfterEach
        // stops BOTH containers, so a ClickHouse container shared across the
        // class is already stopped when the second test runs, and
        // getMappedPort() then throws "Mapped port can only be obtained after
        // the container is started". Every other IT in this package declares a
        // single test, which is why the shared static instance survived: this
        // class is the only one with two.
        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stop() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
    }

    @Test
    @DisplayName("Test that validates create table in CH when MySQL has is_deleted columns")
    public void testIsDeleted() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService() ,false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        Thread.sleep(30000);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);


        //Validate if ship_class was truncated also in ClickHouse.
        // Validate that the table is empty in ClickHouse
        ResultSet rs = ITCommon.executeQueryWithResultSet("select * from employees.ship_class", writer.getConnection());
        boolean recordFoundShipClass = false;
        while(rs.next()) {
            recordFoundShipClass = true;
        }
        Assert.assertFalse(recordFoundShipClass);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table new_table(col1 varchar(255), col2 int, is_deleted int, _sign int)").execute();

        Thread.sleep(10000);

        conn.prepareStatement("insert into new_table values('test', 1, 22, 1)").execute();
        conn.close();
        Thread.sleep(10000);

        rs = ITCommon.executeQueryWithResultSet("select * from employees.new_table", writer.getConnection());
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            Assert.assertTrue(rs.getString("col1").equalsIgnoreCase("test"));
            Assert.assertTrue(rs.getInt("col2") == 1);
            Assert.assertTrue(rs.getInt("is_deleted") == 22);
            Assert.assertTrue(rs.getInt("_sign") == 1);
        }
        Assert.assertTrue(recordFound);

        // Run truncate table in MySQL
        conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("truncate table new_table").execute();
        Thread.sleep(10000);
        conn.close();

        // Validate that the table is empty in ClickHouse
        rs = ITCommon.executeQueryWithResultSet("select * from employees.new_table", writer.getConnection());
        recordFound = false;
        while(rs.next()) {
            recordFound = true;
        }
        Assert.assertFalse(recordFound);


        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();

        HikariDbSource.close();
    }

    /**
     * Regression test: rows written after a TRUNCATE must survive it.
     * <p>
     * The truncate used to be executed at the end of the batch, after
     * {@code executeBatch()}. When a TRUNCATE and the inserts that logically
     * follow it landed in the same batch, the truncate therefore ran last and
     * wiped the very rows the batch had just inserted. The destination ended up
     * empty while the source held the post-truncate rows, and nothing was
     * logged -- the divergence only surfaced later via a checksum comparison.
     * <p>
     * The truncate is now executed at its binlog position: rows staged before
     * it are flushed first, the table is truncated, and rows after it are
     * inserted afterwards.
     */
    @Test
    @DisplayName("Rows inserted after a TRUNCATE must survive the truncate")
    public void testRowsInsertedAfterTruncateSurvive() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer),
                        new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        try {
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table truncate_then_insert(id int primary key, payload varchar(64))")
                .execute();
        Thread.sleep(10000);

        // Pre-truncate state.
        conn.prepareStatement("insert into truncate_then_insert values(1, 'before')").execute();
        conn.prepareStatement("insert into truncate_then_insert values(2, 'before')").execute();
        Thread.sleep(10000);

        // TRUNCATE immediately followed by the new state, with no pause in
        // between, so the truncate and the following inserts are handled in the
        // same batch -- this is what the defect required to reproduce.
        conn.prepareStatement("truncate table truncate_then_insert").execute();
        conn.prepareStatement("insert into truncate_then_insert values(10, 'after')").execute();
        conn.prepareStatement("insert into truncate_then_insert values(11, 'after')").execute();
        conn.prepareStatement("insert into truncate_then_insert values(12, 'after')").execute();
        conn.close();
        Thread.sleep(20000);

        // The three post-truncate rows must be present, and none of the
        // pre-truncate rows may remain.
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select id, payload from employees.truncate_then_insert final order by id",
                writer.getConnection());
        int afterRows = 0;
        boolean staleRowFound = false;
        while (rs.next()) {
            if ("before".equalsIgnoreCase(rs.getString("payload"))) {
                staleRowFound = true;
            } else {
                afterRows++;
            }
        }

        Assert.assertFalse("pre-truncate rows must not survive the truncate", staleRowFound);
        Assert.assertEquals("rows inserted after the truncate were wiped by it",
                3, afterRows);
        } finally {
            // Release the embedded engine and its CLI port unconditionally. If an
            // assertion above fails, a teardown placed after it would never run,
            // the port would stay bound, and every subsequent test in this class
            // would fail with "Port already in use" -- turning one real failure
            // into a cascade that hides its own cause.
            if (engine.get() != null) {
                engine.get().stop();
            }
            executorService.shutdown();
            HikariDbSource.close();
        }
    }
}
