package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * End-to-end cover for the row identity the connector gives a source table that
 * declares neither a PRIMARY KEY nor a UNIQUE key.
 *
 * <p>Such a table was created with {@code ORDER BY tuple()}, which makes every
 * row compare equal, so ReplacingMergeTree kept exactly ONE row for the whole
 * table. Fixing that by keying on the data columns then FROZE the schema --
 * ClickHouse forbids altering any column in the sorting key -- and keying on a
 * MATERIALIZED expression over those columns was brittle in both directions: a
 * column feeding the expression could not be dropped, and a column added later
 * was absent from it, so rows differing only in the new column collapsed.</p>
 *
 * <p>The identity is now a plain {@code _row_key UInt64} whose value the
 * connector computes per record. These tests pin the properties that choice
 * has to deliver, each one against a live MySQL and ClickHouse:</p>
 *
 * <ol>
 *   <li>rows are not collapsed, and UPDATE/DELETE still resolve to one row;</li>
 *   <li>the key is stable across a connector RESTART -- it is derived from the
 *       record's values, so a re-sent row must replace rather than duplicate,
 *       including for binary columns, where a per-JVM object identity would
 *       silently change the key on every restart or host move;</li>
 *   <li>the table stays alterable: ADD, MODIFY, RENAME and DROP COLUMN all
 *       succeed, and rows added after an ADD COLUMN are still told apart;</li>
 *   <li>a source column already named {@code _row_key} does not collide with
 *       the generated one;</li>
 *   <li>a table WITH a primary key is left completely untouched.</li>
 * </ol>
 */
@Testcontainers
@DisplayName("End-to-end: row identity for source tables with no PRIMARY KEY and no UNIQUE key")
public class KeylessRowIdentityIT extends DDLBaseIT {

    /**
     * Both containers are recreated for every test.
     *
     * <p>The base class declares the ClickHouse container statically but stops
     * it in {@code @AfterEach}. Every other subclass carries exactly one test,
     * so that never showed; this class carries five, and the second one would
     * otherwise run against a stopped container. Recreating it also gives each
     * test its own Debezium offset and schema-history storage, so the connector
     * restart performed inside one test cannot resume another test's offsets.</p>
     */
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("keyless_row_identity.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        BasicConfigurator.configure();
        clickHouseContainer.start();
        mySqlContainer.start();
        Thread.sleep(15000);

        // Re-resolved per test: a stopped container no longer reports the
        // mapped ports these properties are built from.
        connectorProps = null;
    }

    /**
     * Resolved once per test, while both containers are known to be up.
     *
     * <p>Container ports must not be read lazily from inside the engine
     * thread: they are only available while the container is running, and a
     * restart test starts a second engine later in the same test.</p>
     */
    private java.util.Properties connectorProps;

    private java.util.Properties props() throws Exception {
        if (connectorProps == null) {
            connectorProps = getDebeziumProperties();
        }
        return connectorProps;
    }

    private AtomicReference<DebeziumChangeEventCapture> startConnector() throws Exception {
        final java.util.Properties resolved = props();
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(resolved, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return engine;
    }

    /** Rows visible to a consumer: FINAL, excluding tombstones. */
    private long liveRows(BaseDbWriter writer, String table) throws Exception {
        try (Statement st = writer.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count() FROM employees." + table + " FINAL WHERE is_deleted = 0")) {
            Assert.assertTrue("count query returned nothing for " + table, rs.next());
            return rs.getLong(1);
        }
    }

    private long mysqlRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            Assert.assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private void optimize(BaseDbWriter writer, String table) throws Exception {
        try (Statement st = writer.getConnection().createStatement()) {
            st.execute("OPTIMIZE TABLE employees." + table + " FINAL");
        }
    }

    /**
     * The core property, and the one the original defect broke: a keyless table
     * must keep every distinct row, and an UPDATE or DELETE must still resolve
     * to exactly one of them rather than duplicating or emptying the table.
     */
    @Test
    public void testKeylessRowsAreNotCollapsedAndUpdatesResolve() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = startConnector();
        Thread.sleep(10000);

        Connection conn = mySqlContainer.createConnection("");
        conn.prepareStatement("insert into keyless_basic values(1,'one'),(2,'two'),(3,'three')").execute();
        conn.prepareStatement("insert into keyless_nulls values(null,null),(1,'y'),(2,'z')").execute();
        Thread.sleep(20000);

        conn.prepareStatement("update keyless_basic set b='two_updated' where a=2").execute();
        // DESTRUCTIVE: two single-row DELETEs, bounded by an equality predicate
        // on one row each, against the throwaway MySQL testcontainer this test
        // creates in @BeforeEach and destroys in @AfterEach -- never a shared or
        // production database. The DELETE is the subject under test: it must
        // resolve through the generated row identity to exactly one row.
        conn.prepareStatement("delete from keyless_basic where a=3").execute();
        // DESTRUCTIVE: as above -- one row in the same throwaway container,
        // here the all-NULL row, which is the case an identity built on
        // concatenated text is most likely to mismatch.
        conn.prepareStatement("delete from keyless_nulls where a is null").execute();
        Thread.sleep(25000);

        BaseDbWriter writer = com.altinity.clickhouse.debezium.embedded.ITCommon
                .getDBWriter(clickHouseContainer);
        optimize(writer, "keyless_basic");
        optimize(writer, "keyless_nulls");

        Assert.assertEquals("a keyless table must keep every distinct row, and an UPDATE must "
                        + "replace rather than duplicate",
                mysqlRows(conn, "keyless_basic"), liveRows(writer, "keyless_basic"));
        Assert.assertEquals("deleting one NULL-bearing row must not empty the table",
                mysqlRows(conn, "keyless_nulls"), liveRows(writer, "keyless_nulls"));

        if (engine.get() != null) {
            engine.get().stop();
        }
    }

    /**
     * The row identity must be a function of the row's VALUES, so that a
     * connector restart -- or a move to a different host -- recomputes the same
     * key and a re-sent row replaces the existing one.
     *
     * <p>Binary data is the case that catches an unstable implementation:
     * rendering a {@code byte[]} through {@code Object.toString()} embeds the
     * JVM identity hash, so the key silently changes in the new process and the
     * row is inserted a second time instead of replacing itself.</p>
     */
    @Test
    public void testRowIdentitySurvivesConnectorRestart() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = startConnector();
        Thread.sleep(10000);

        Connection conn = mySqlContainer.createConnection("");
        conn.prepareStatement(
                "insert into keyless_binary values(1, x'0102030405'),(2, x'FF00FF00')").execute();
        Thread.sleep(20000);

        BaseDbWriter writer = com.altinity.clickhouse.debezium.embedded.ITCommon
                .getDBWriter(clickHouseContainer);
        optimize(writer, "keyless_binary");
        long beforeRestart = liveRows(writer, "keyless_binary");
        Assert.assertEquals("both binary rows must replicate", 2L, beforeRestart);

        // Restart the connector in a fresh engine, exactly as a service restart
        // or a move to another host would.
        if (engine.get() != null) {
            engine.get().stop();
        }
        Thread.sleep(5000);
        AtomicReference<DebeziumChangeEventCapture> restarted = startConnector();
        Thread.sleep(15000);

        // Re-send the SAME logical rows. A stable key means these replace; an
        // unstable one means they duplicate.
        conn.prepareStatement("update keyless_binary set payload = payload where a in (1,2)").execute();
        Thread.sleep(20000);
        optimize(writer, "keyless_binary");

        Assert.assertEquals("a re-sent row must replace, not duplicate, after a connector restart "
                        + "-- the row key must depend on the value, never on per-JVM object identity",
                mysqlRows(conn, "keyless_binary"), liveRows(writer, "keyless_binary"));

        if (restarted.get() != null) {
            restarted.get().stop();
        }
    }

    /**
     * The identity must not cost the table its ability to take any other
     * operation, and must keep working as the column set changes -- the failure
     * mode of keying on the data columns, in either direction.
     */
    @Test
    public void testSchemaStaysAlterableAndIdentityFollowsIt() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = startConnector();
        Thread.sleep(10000);

        Connection conn = mySqlContainer.createConnection("");
        conn.prepareStatement("insert into keyless_evolves values(1,'one'),(2,'two')").execute();
        Thread.sleep(15000);

        // Every one of these was rejected when the data columns were the key.
        conn.prepareStatement("alter table keyless_evolves add column c int").execute();
        Thread.sleep(8000);
        conn.prepareStatement("alter table keyless_evolves modify column b varchar(128)").execute();
        Thread.sleep(8000);
        conn.prepareStatement("alter table keyless_evolves change column b b_renamed varchar(128)").execute();
        Thread.sleep(8000);

        // Rows that differ ONLY in the column added after creation must remain
        // distinct: a fingerprint fixed at CREATE time would collapse them.
        conn.prepareStatement("insert into keyless_evolves(a,b_renamed,c) values (9,'same',1),(9,'same',2)")
                .execute();
        Thread.sleep(20000);

        BaseDbWriter writer = com.altinity.clickhouse.debezium.embedded.ITCommon
                .getDBWriter(clickHouseContainer);
        optimize(writer, "keyless_evolves");

        DBMetadata metadata = new DBMetadata(props());
        Assert.assertTrue("the renamed column must exist in ClickHouse, so the ALTER was applied "
                        + "rather than rejected and retried",
                metadata.getColumnsDataTypesForTable(writer.getConnection(), "keyless_evolves",
                        "employees").containsKey("b_renamed"));

        Assert.assertEquals("rows differing only in a column added AFTER the table was created must "
                        + "stay distinct",
                mysqlRows(conn, "keyless_evolves"), liveRows(writer, "keyless_evolves"));

        if (engine.get() != null) {
            engine.get().stop();
        }
    }

    /**
     * A source column already called {@code _row_key} must not collide with the
     * generated one: two definitions of the same name make ClickHouse reject
     * the CREATE (Code: 44) and replication of that table never starts.
     *
     * <p>The source column's VALUES are asserted too, not just the row count.
     * The generated column is recognised by the shape of its name, so a source
     * column of that name is a candidate for being overwritten with the
     * fingerprint -- corruption that leaves the row count untouched and would
     * pass a count-only check.</p>
     */
    @Test
    public void testSourceColumnNamedRowKeyDoesNotCollide() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = startConnector();
        Thread.sleep(10000);

        Connection conn = mySqlContainer.createConnection("");
        conn.prepareStatement("insert into keyless_name_clash values(1,'one'),(2,'two')").execute();
        Thread.sleep(20000);

        BaseDbWriter writer = com.altinity.clickhouse.debezium.embedded.ITCommon
                .getDBWriter(clickHouseContainer);
        optimize(writer, "keyless_name_clash");

        Assert.assertEquals("the table must exist and replicate despite the name clash",
                mysqlRows(conn, "keyless_name_clash"), liveRows(writer, "keyless_name_clash"));

        try (Statement st = writer.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT `_row_key` FROM employees.keyless_name_clash "
                     + "FINAL WHERE is_deleted = 0 ORDER BY `_row_key`")) {
            Assert.assertTrue("the source _row_key column must carry its own values", rs.next());
            Assert.assertEquals("the source column must keep the value MySQL sent, not the "
                    + "generated fingerprint", 1L, rs.getLong(1));
            Assert.assertTrue(rs.next());
            Assert.assertEquals(2L, rs.getLong(1));
        }

        if (engine.get() != null) {
            engine.get().stop();
        }
    }

    /**
     * Control. A table that declares a PRIMARY KEY must be completely
     * unaffected: it keeps its own key and gains no generated column.
     */
    @Test
    public void testKeyedTableIsUntouched() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = startConnector();
        Thread.sleep(10000);

        Connection conn = mySqlContainer.createConnection("");
        conn.prepareStatement("insert into keyed_control values(1,'one'),(2,'two')").execute();
        Thread.sleep(20000);

        BaseDbWriter writer = com.altinity.clickhouse.debezium.embedded.ITCommon
                .getDBWriter(clickHouseContainer);
        optimize(writer, "keyed_control");

        DBMetadata metadata = new DBMetadata(props());
        Assert.assertFalse("a keyed table must not gain the generated row-key column",
                metadata.getColumnsDataTypesForTable(writer.getConnection(), "keyed_control",
                        "employees").containsKey("_row_key"));
        Assert.assertEquals(mysqlRows(conn, "keyed_control"), liveRows(writer, "keyed_control"));

        if (engine.get() != null) {
            engine.get().stop();
        }
    }
}
