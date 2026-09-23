package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Spec 06.09 end to end: a source {@code ALTER TABLE} that changes a table's
 * row identity (or widens / renames / drops a key column) is followed on
 * ClickHouse by rebuilding the replica under the new sorting key at the DDL
 * barrier -- MySQL 8.0 -> embedded connector -> ClickHouse, the same harness
 * as the other DDL ITs ({@code AbstractCDCBaseIT} / {@code DDLBaseIT}:
 * testcontainers, {@link ITCommon} properties, {@link BaseDbWriter}).
 *
 * <p>Every scenario: the source table and its rows exist BEFORE the engine
 * starts (snapshot), DML runs under the old key, the ALTER runs, DML runs
 * under the new key including a relocation ({@code UPDATE ... SET <new key>
 * = ...}) and a DELETE, and the replica must then hold, value for value, the
 * rows MySQL holds ({@code FINAL}, live rows), keyed by the expected sorting
 * key, with no scratch table left behind and an untouched control table
 * still matching.</p>
 *
 // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
 * <p>Containers are created per test (as {@code TruncateTableIT} does): each
 * scenario needs its own snapshot, and a shared ClickHouse would hand the
 * next engine the previous scenario's offset.</p>
 */
@Testcontainers
@DisplayName("Integration test: primary-key changes rebuild the ClickHouse table at the DDL barrier (Spec 06.09)")
public class PrimaryKeyChangeIT {

    private static final String DB = "employees";
    private static final String CONTROL_TABLE = "t_ctl";

    /** CI containers are slow to start; the rebuild itself is quick. */
    private static final long SNAPSHOT_TIMEOUT_MS = 300_000;
    private static final long CONVERGE_TIMEOUT_MS = 300_000;
    private static final long POLL_MS = 2_000;

    protected MySQLContainer mySqlContainer;
    protected ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName(DB).withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                // MySQL does not speak HTTP; wait for the port (DDLBaseIT).
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123)
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(10000);
    }

    @AfterEach
    public void stopContainers() {
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    // ------------------------------------------------------------------
    // Scenarios (Spec 06.09 §5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The production migration: composite key -> added AUTO_INCREMENT id, source values joined in")
    public void compositeKeyToAutoIncrementId() throws Exception {
        String t = "t_pk_ai";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (a INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (a, b))",
                        "INSERT INTO " + t + " (a, b, v) VALUES (1, 1, 'a'), (1, 2, 'b'), (2, 1, 'c'), (2, 2, 'd')")
                .oldOrderBy("a, b")
                .oldKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (3, 1, 'e')",
                        "UPDATE " + t + " SET v = 'b2' WHERE a = 1 AND b = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 2 AND b = 1")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT "
                        + "FIRST, ADD PRIMARY KEY (id)")
                .newKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (4, 4, 'f')",
                        "UPDATE " + t + " SET v = 'a2' WHERE a = 1 AND b = 1",
                        "UPDATE " + t + " SET id = 100 WHERE a = 3 AND b = 1",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 2 AND b = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnType("id", "UInt32")
                .run();
    }

    @Test
    @DisplayName("Re-keying onto an existing column: DROP PRIMARY KEY, ADD PRIMARY KEY (b), no source read")
    public void rekeyOntoExistingColumn() throws Exception {
        String t = "t_pk_rekey";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, b, v) VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, b, v) VALUES (4, 40, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)")
                .newKeyDml("INSERT INTO " + t + " (id, b, v) VALUES (5, 50, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE b = 10",
                        "UPDATE " + t + " SET b = 21 WHERE b = 20",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE b = 40")
                .newOrderBy("b")
                .expectSortingKey("b")
                .run();
    }

    @Test
    @DisplayName("Superset key: (a) -> (a, b)")
    public void supersetKey() throws Exception {
        String t = "t_pk_super";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (a INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (a))",
                        "INSERT INTO " + t + " (a, b, v) VALUES (1, 1, 'a'), (2, 1, 'b'), (3, 1, 'c')")
                .oldOrderBy("a")
                .oldKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (4, 1, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE a = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 3")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD PRIMARY KEY (a, b)")
                .newKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (1, 2, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE a = 1 AND b = 1",
                        "UPDATE " + t + " SET b = 9 WHERE a = 2 AND b = 1",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 4 AND b = 1")
                .newOrderBy("a, b")
                .expectSortingKey("a, b")
                .run();
    }

    @Test
    @DisplayName("ADD PRIMARY KEY on a nullable column: MySQL makes it NOT NULL, the replica key column is non-Nullable")
    public void addPrimaryKeyOnNullableColumn() throws Exception {
        String t = "t_pk_nullable";
        new Scenario(t)
                // No PRIMARY KEY, no UNIQUE: the replica keys the table by every
                // column (Spec 06.05 §3.6) until the source declares a key.
                .setup("SET SESSION sql_generate_invisible_primary_key = OFF",
                        "CREATE TABLE " + t + " (id INT NOT NULL, c INT NULL, v VARCHAR(32))",
                        "INSERT INTO " + t + " (id, c, v) VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, c, v) VALUES (4, 40, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " ADD PRIMARY KEY (c)")
                .newKeyDml("INSERT INTO " + t + " (id, c, v) VALUES (5, 50, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE c = 10",
                        "UPDATE " + t + " SET c = 21 WHERE c = 20",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE c = 40")
                .newOrderBy("c")
                .expectSortingKey("c")
                .expectColumnType("c", "Int32")
                .run();
    }

    @Test
    @DisplayName("DROP PRIMARY KEY without a replacement: the all-columns identity of a keyless table")
    public void dropPrimaryKeyBecomesKeyless() throws Exception {
        String t = "t_pk_keyless";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                // Without GIPK the table is left keyless, as the statement says.
                .alter("SET SESSION sql_generate_invisible_primary_key = OFF",
                        "ALTER TABLE " + t + " DROP PRIMARY KEY")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        "UPDATE " + t + " SET id = 6 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id, v")
                .expectSortingKey("id, v")
                .run();
    }

    @Test
    // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
    @DisplayName("A GIPK table promoted to an explicit key: DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)")
    public void gipkTablePromotedToExplicitKey() throws Exception {
        String t = "t_pk_gipk";
        new Scenario(t)
                // Created under GIPK: MySQL adds my_row_id BIGINT UNSIGNED
                // AUTO_INCREMENT INVISIBLE PRIMARY KEY, which the replica keys by.
                .setup("SET SESSION sql_generate_invisible_primary_key = ON",
                        "CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                // The same GIPK session: the generated key may only be dropped
                // together with its column and with a replacement key.
                .alter("SET SESSION sql_generate_invisible_primary_key = ON",
                        "ALTER TABLE " + t + " DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        "UPDATE " + t + " SET id = 6 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnAbsent("my_row_id")
                .run();
    }

    @Test
    @DisplayName("A widened key column: MODIFY id BIGINT rebuilds under the same key with the wider type")
    public void keyColumnWidened() throws Exception {
        String t = "t_pk_widen";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " MODIFY id BIGINT NOT NULL")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5000000000, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        // A value no Int32 can hold: the widening must have landed.
                        "UPDATE " + t + " SET id = 6000000000 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnType("id", "Int64")
                .run();
    }

    @Test
    @DisplayName("A renamed key column: CHANGE id ref_id INT rebuilds keyed by the new name")
    public void keyColumnRenamed() throws Exception {
        String t = "t_pk_rename";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " CHANGE id ref_id INT NOT NULL")
                .newKeyDml("INSERT INTO " + t + " (ref_id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE ref_id = 1",
                        "UPDATE " + t + " SET ref_id = 6 WHERE ref_id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE ref_id = 2")
                .newOrderBy("ref_id")
                .expectSortingKey("ref_id")
                .expectColumnType("ref_id", "Int32")
                .expectColumnAbsent("id")
                .run();
    }

    // ------------------------------------------------------------------
    // The shared scenario runner
    // ------------------------------------------------------------------

    /** One primary-key change scenario: setup before the snapshot, DML under the old key, ALTER, DML under the new key. */
    private final class Scenario {
        private final String table;
        private final List<String> setup = new ArrayList<>();
        private final List<String> oldKeyDml = new ArrayList<>();
        private final List<String> alter = new ArrayList<>();
        private final List<String> newKeyDml = new ArrayList<>();
        private final List<String[]> expectedColumnTypes = new ArrayList<>();
        private final List<String> absentColumns = new ArrayList<>();
        private String oldOrderBy;
        private String newOrderBy;
        private String expectedSortingKey;

        Scenario(String table) {
            this.table = table;
        }

        Scenario setup(String... sql) {
            setup.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario oldOrderBy(String orderBy) {
            this.oldOrderBy = orderBy;
            return this;
        }

        Scenario oldKeyDml(String... sql) {
            oldKeyDml.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario alter(String... sql) {
            alter.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario newKeyDml(String... sql) {
            newKeyDml.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario newOrderBy(String orderBy) {
            this.newOrderBy = orderBy;
            return this;
        }

        Scenario expectSortingKey(String sortingKey) {
            this.expectedSortingKey = sortingKey;
            return this;
        }

        Scenario expectColumnType(String column, String type) {
            expectedColumnTypes.add(new String[] {column, type});
            return this;
        }

        Scenario expectColumnAbsent(String column) {
            absentColumns.add(column);
            return this;
        }

        void run() throws Exception {
            // One MySQL connection for the whole scenario, so SET SESSION
            // statements govern the CREATE / ALTER that follow them.
            Connection mysql = connectToMySQLWithRetry();
            try {
                // 1. Source state before the engine starts: the scenario table
                //    and the untouched control table are snapshotted.
                execute(mysql, "CREATE TABLE " + CONTROL_TABLE + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))");
                execute(mysql, "INSERT INTO " + CONTROL_TABLE + " (id, v) VALUES (1, 'ctl-a'), (2, 'ctl-b')");
                for (String sql : setup) {
                    execute(mysql, sql);
                }

                AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
                ExecutorService executorService = Executors.newFixedThreadPool(1);
                Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
                executorService.execute(() -> {
                    try {
                        engine.set(new DebeziumChangeEventCapture());
                        engine.get().setup(props, new SourceRecordParserService(), false);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
                Connection ch = writer.getConnection();
                try {
                    // 2. The snapshot rows are in ClickHouse.
                    awaitMatch(mysql, ch, table, oldOrderBy, SNAPSHOT_TIMEOUT_MS, "snapshot of " + table);
                    awaitMatch(mysql, ch, CONTROL_TABLE, "id", SNAPSHOT_TIMEOUT_MS, "snapshot of " + CONTROL_TABLE);

                    // 3. DML under the old key.
                    for (String sql : oldKeyDml) {
                        execute(mysql, sql);
                    }
                    awaitMatch(mysql, ch, table, oldOrderBy, CONVERGE_TIMEOUT_MS, "DML under the old key of " + table);

                    // 4. The primary-key change, then DML under the new key,
                    //    including a relocation and a DELETE.
                    for (String sql : alter) {
                        execute(mysql, sql);
                    }
                    for (String sql : newKeyDml) {
                        execute(mysql, sql);
                    }
                    awaitMatch(mysql, ch, table, newOrderBy, CONVERGE_TIMEOUT_MS,
                            "the rebuild and the DML under the new key of " + table);

                    // 5. The replica is keyed as the source is, no scratch table
                    //    remains, the control table is untouched.
                    Assert.assertEquals("sorting key of " + DB + "." + table, expectedSortingKey,
                            scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB
                                    + "' AND name = '" + table + "'"));
                    for (String[] expected : expectedColumnTypes) {
                        Assert.assertEquals("type of " + table + "." + expected[0], expected[1],
                                scalar(ch, "SELECT type FROM system.columns WHERE database = '" + DB + "' AND table = '"
                                        + table + "' AND name = '" + expected[0] + "'"));
                    }
                    for (String absent : absentColumns) {
                        Assert.assertEquals(table + "." + absent + " must be gone", "0",
                                scalar(ch, "SELECT count() FROM system.columns WHERE database = '" + DB
                                        + "' AND table = '" + table + "' AND name = '" + absent + "'"));
                    }
                    Assert.assertEquals("no __pk_rebuild_ scratch table may remain", "0",
                            scalar(ch, "SELECT count() FROM system.tables WHERE database = '" + DB
                                    + "' AND name LIKE '%pk_rebuild%'"));
                    assertMatch(mysql, ch, CONTROL_TABLE, "id", "the control table " + CONTROL_TABLE);
                    Assert.assertEquals("sorting key of the control table", "id",
                            scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB
                                    + "' AND name = '" + CONTROL_TABLE + "'"));
                } finally {
                    // Release the engine and its port unconditionally, so one
                    // failed assertion does not cascade into the next test.
                    if (engine.get() != null) {
                        engine.get().stop();
                    }
                    executorService.shutdown();
                    HikariDbSource.close();
                }
            } finally {
                mysql.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers: execute, poll, compare value by value
    // ------------------------------------------------------------------

    private Connection connectToMySQLWithRetry() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            Connection conn = ITCommon.connectToMySQL(mySqlContainer);
            if (conn != null) {
                return conn;
            }
            Thread.sleep(3000);
        }
        throw new AssertionError("could not connect to the MySQL container");
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A query result as rows of strings, plus the column names it was read with. */
    private static final class Rows {
        final List<String> columns;
        final List<List<String>> values;

        Rows(List<String> columns, List<List<String>> values) {
            this.columns = columns;
            this.values = values;
        }
    }

    private static Rows read(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(md.getColumnLabel(i));
            }
            List<List<String>> values = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(normalise(rs.getString(i)));
                }
                values.add(row);
            }
            return new Rows(columns, values);
        }
    }

    /** Every column is compared as a string; trailing zeros/whitespace differences are not expected for the types used. */
    private static String normalise(String value) {
        return value == null ? null : value.trim();
    }

    /** The source rows, every visible column, ordered by the given key. */
    private static Rows sourceRows(Connection mysql, String table, String orderBy) throws SQLException {
        return read(mysql, "SELECT * FROM `" + table + "` ORDER BY " + orderBy);
    }

    /** The replica's live rows under the source's column list, ordered by the same key. */
    private static Rows replicaRows(Connection ch, String table, List<String> columns, String orderBy)
            throws SQLException {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append('`').append(columns.get(i)).append('`');
        }
        select.append(" FROM `").append(DB).append("`.`").append(table).append("` FINAL WHERE `is_deleted` = 0 ORDER BY ")
                .append(orderBy);
        return read(ch, select.toString());
    }

    /** Polls until the replica holds exactly the source's rows, then asserts it (with the diff on timeout). */
    private static void awaitMatch(Connection mysql, Connection ch, String table, String orderBy, long timeoutMs,
                                   String what) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Rows source = sourceRows(mysql, table, orderBy);
                Rows replica = replicaRows(ch, table, source.columns, orderBy);
                if (Objects.equals(source.values, replica.values)) {
                    return;
                }
            } catch (SQLException e) {
                // The table or a column may not exist on the replica yet
                // (before the snapshot DDL, or mid-statement); keep polling.
            }
            Thread.sleep(POLL_MS);
        }
        assertMatch(mysql, ch, table, orderBy, what);
    }

    private static void assertMatch(Connection mysql, Connection ch, String table, String orderBy, String what)
            throws SQLException {
        Rows source = sourceRows(mysql, table, orderBy);
        List<List<String>> replica;
        try {
            replica = replicaRows(ch, table, source.columns, orderBy).values;
        } catch (SQLException e) {
            replica = Collections.singletonList(Collections.singletonList("<query failed: " + e.getMessage() + ">"));
        }
        Assert.assertEquals(what + ": ClickHouse " + DB + "." + table + " must hold the MySQL rows value for value "
                + "(columns " + source.columns + ")", source.values, replica);
    }
}
