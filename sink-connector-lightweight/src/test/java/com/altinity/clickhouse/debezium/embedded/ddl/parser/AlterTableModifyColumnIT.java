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
import java.util.LinkedHashMap;
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

        Thread.sleep(10000); // Allow engine to start

        Connection conn = connectToMySQL();

        conn.prepareStatement("alter table ship_class modify column class_name int;").execute();
        conn.prepareStatement("alter table ship_class modify column tonange decimal(10,10);").execute();
        conn.prepareStatement("alter table add_test modify column col1 int, modify column col2 varchar(255);").execute();
        conn.prepareStatement("alter table add_test modify column col1 int default 0;").execute();
        conn.prepareStatement("alter table add_test modify column col3 int first;").execute();
        conn.prepareStatement("alter table add_test modify column col2 int after col3;").execute();

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());

        // Poll until EVERY column this test asserts on has been replicated.
        //
        // The six source ALTERs above are replicated one at a time, so the
        // schema passes through intermediate states in which some columns are
        // converted and others are not yet. Waiting on a subset and then
        // asserting on a superset makes the test pass or fail on where the
        // replication stream happened to be when the last poll ran.
        //
        // Observed: the loop exited as soon as class_name and col2 were
        // converted, and testModifyColumn then failed on tonange 0.8s later,
        // because only the class_name ALTER had been applied at that point.
        // The remaining five ALTERs arrived afterwards.
        Map<String, String> expectedShipClass = new LinkedHashMap<>();
        expectedShipClass.put("class_name", "Nullable(Int32)");
        expectedShipClass.put("tonange", "Nullable(Decimal(10, 10))");

        Map<String, String> expectedAddTest = new LinkedHashMap<>();
        expectedAddTest.put("col1", "Nullable(Int32)");
        expectedAddTest.put("col2", "Nullable(Int32)");
        expectedAddTest.put("col3", "Nullable(Int32)");

        Map<String, String> shipClassColumns = null;
        Map<String, String> addTestColumns = null;
        for (int retry = 0; retry < 10; retry++) {
            shipClassColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "ship_class", "employees");
            addTestColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "add_test", "employees");
            if (matchesExpected(shipClassColumns, expectedShipClass)
                    && matchesExpected(addTestColumns, expectedAddTest)) {
                break;
            }
            Thread.sleep(5000);
        }

        // Asserted with the observed value in the message. A bare assertTrue
        // on equalsIgnoreCase reports only "java.lang.AssertionError", which
        // says neither which column failed nor what it actually held -- and
        // it throws NullPointerException instead of failing when the column
        // is missing entirely.
        assertColumns(shipClassColumns, expectedShipClass, "ship_class");
        assertColumns(addTestColumns, expectedAddTest, "add_test");

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

    @Test
    @DisplayName("End-to-end test for ADD PRIMARY KEY (no-op skipped) and MODIFY COLUMN NOT NULL (stays Nullable)")
    public void testAlterAddPrimaryKeyAndModifyNotNull() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000); // Allow engine to start

        Connection conn = connectToMySQL();

        // 1. ADD PRIMARY KEY alone (no ClickHouse equivalent; must translate to empty and be skipped without Code: 62)
        conn.prepareStatement("alter table ship_class add primary key (id);").execute();

        // 2. ADD COLUMN and MODIFY COLUMN ... NOT NULL (must keep col1 Nullable to prevent Code: 36)
        conn.prepareStatement("alter table add_test add column col4 varchar(100), modify column col1 int not null;").execute();

        // 3. Multi-clause ALTER with ADD PRIMARY KEY as first clause (must not leave leading comma).
        //
        // Runs against `branch`, which the init script creates WITHOUT a primary
        // key. `office` already declares one, and MySQL rejects a second
        // ("Multiple primary key defined") before anything reaches the
        // connector -- so the statement has to target a keyless table for the
        // translation under test to be exercised at all.
        conn.prepareStatement("alter table branch add primary key (branch_id), modify column branch_name varchar(100) not null, add column branch_status varchar(20) not null;").execute();

        // 4. Insert data after the DDLs to prove replication continues cleanly without stalling
        conn.prepareStatement("insert into add_test (col1, col2, col3, col4) values (101, 202, 303, 'active');").execute();

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());

        Map<String, String> expectedAddTest = new LinkedHashMap<>();
        expectedAddTest.put("col1", "Nullable(Int32)");
        expectedAddTest.put("col4", "Nullable(String)");

        // From the multi-clause ALTER in step 3: ADD PRIMARY KEY emits nothing,
        // MODIFY ... NOT NULL keeps the existing column Nullable, and ADD COLUMN
        // ... NOT NULL is honored because a new column has no rows to violate it.
        Map<String, String> expectedBranch = new LinkedHashMap<>();
        expectedBranch.put("branch_name", "Nullable(String)");
        expectedBranch.put("branch_status", "String");

        Map<String, String> addTestColumns = null;
        Map<String, String> branchColumns = null;
        for (int retry = 0; retry < 10; retry++) {
            addTestColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "add_test", "employees");
            branchColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "branch", "employees");
            if (matchesExpected(addTestColumns, expectedAddTest)
                    && matchesExpected(branchColumns, expectedBranch)) {
                break;
            }
            Thread.sleep(5000);
        }

        assertColumns(addTestColumns, expectedAddTest, "add_test");
        assertColumns(branchColumns, expectedBranch, "branch");

        // Verify the row inserted after the DDLs replicated into ClickHouse
        boolean rowFound = false;
        for (int retry = 0; retry < 10; retry++) {
            java.sql.ResultSet rs = writer.getConnection().createStatement().executeQuery(
                    "select col1, col4 from employees.add_test where col1 = 101");
            if (rs.next()) {
                Assert.assertEquals(101, rs.getInt("col1"));
                Assert.assertEquals("active", rs.getString("col4"));
                rowFound = true;
                break;
            }
            Thread.sleep(3000);
        }
        Assert.assertTrue("Row inserted after ALTER TABLE was not replicated; stream may be stalled", rowFound);

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    /**
     * True when every expected column is present with the expected type.
     *
     * <p>Used as the poll predicate so the loop waits for the same set the
     * assertions check, rather than a subset of it.</p>
     */
    private static boolean matchesExpected(Map<String, String> actual,
                                           Map<String, String> expected) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String observed = actual.get(e.getKey());
            if (observed == null || !observed.equalsIgnoreCase(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Asserts each expected column, naming the column and the observed value.
     *
     * <p>A missing column fails with that message instead of throwing
     * NullPointerException, so an unreplicated ALTER is reported as the test
     * failure it is rather than as an error in the test itself.</p>
     */
    private static void assertColumns(Map<String, String> actual,
                                      Map<String, String> expected,
                                      String tableName) {
        Assert.assertNotNull("no columns were read back for " + tableName, actual);
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String column = e.getKey();
            String observed = actual.get(column);
            Assert.assertNotNull(tableName + "." + column
                    + " is absent from ClickHouse; the ALTER was not replicated. "
                    + "Columns present: " + actual, observed);
            Assert.assertTrue(tableName + "." + column + " expected "
                    + e.getValue() + " but was " + observed,
                    observed.equalsIgnoreCase(e.getValue()));
        }
    }
}
