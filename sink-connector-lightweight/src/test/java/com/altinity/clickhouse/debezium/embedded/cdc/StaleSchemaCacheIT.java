package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end reproduction of the schema-cache staleness that caused the
 * txnrepo-uat data loss, exercised through DDL applied to a LIVE connector.
 *
 * <p><b>Why the earlier IT did not catch this.</b> A first attempt
 * ({@code NullColumnValueRoundTripIT}) created each table and then wrote NULL
 * values to it. That passed with the fix reverted, because on a freshly built
 * writer the cached column map matches the table exactly -- there is no
 * staleness to expose. NULL values were the SYMPTOM the checksum job saw; the
 * CAUSE is the cached schema drifting away from the source, and reproducing it
 * requires DDL to land while a writer is already cached and in use.</p>
 *
 * <p>Each test below therefore follows the same shape:</p>
 * <ol>
 *   <li>write rows so a writer is built and its column map cached;</li>
 *   <li>apply DDL that changes the table's shape;</li>
 *   <li>write more rows and assert the values are correct.</li>
 * </ol>
 *
 * <p>Assertions are value-level throughout. Every failure mode in this class
 * leaves row counts identical on both sides -- that is precisely why the
 * production incident ran for two days before a daily value-level checksum
 * caught it, while the connector reported success on all 65,577 affected
 * writes.</p>
 */
@Testcontainers
@DisplayName("A cached schema must never outlive the source metadata it describes")
public class StaleSchemaCacheIT {

    private static final Logger log = LoggerFactory.getLogger(StaleSchemaCacheIT.class);

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

    private ClickHouseDebeziumEmbeddedApplication application;
    private ExecutorService executorService;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }

    @AfterEach
    public void stopContainers() throws Exception {
        if (application != null && application.getDebeziumEventCapture() != null
                && application.getDebeziumEventCapture().engine != null) {
            application.getDebeziumEventCapture().engine.close();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null) {
            clickHouseContainer.stop();
        }
        HikariDbSource.close();
    }

    private void startConnector() throws Exception {
        Injector injector = Guice.createInjector(new AppInjector());
        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("auto.create.tables", "true");

        application = new ClickHouseDebeziumEmbeddedApplication();
        executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                application.start(injector.getInstance(DebeziumRecordParserService.class),
                        props, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(25000);
    }

    private Object readCell(BaseDbWriter writer, String table, String column, int id)
            throws Exception {
        ResultSet rs = ITCommon.executeQueryWithResultSet(String.format(
                "select %s from employees.%s final where id = %d", column, table, id),
                writer.getConnection());
        Object value = null;
        boolean found = false;
        while (rs.next()) {
            value = rs.getObject(1);
            if (rs.wasNull()) {
                value = null;
            }
            found = true;
        }
        assertTrue("row id=" + id + " never replicated to employees." + table
                + "; the value assertion below would be vacuous", found);
        return value;
    }

    private long rowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                String.format("select count() from employees.%s final", table),
                writer.getConnection());
        long count = 0;
        while (rs.next()) {
            count = rs.getLong(1);
        }
        return count;
    }

    /**
     * The production shape: a column added by DDL while the writer is already
     * cached must be populated on subsequent rows, not left at its DEFAULT.
     *
     * <p>This is what {@code txnrepo_uat.event.kafka_offset} looked like --
     * 59,929 dropped writes and 3,348 rows diverged in a single day, every one
     * of them logged as "Column index missing for column" and then written
     * anyway.</p>
     */
    @Test
    public void testColumnAddedAfterWriterIsCachedGetsItsRealValue() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table event(id int not null, event_message varchar(64) null, "
                        + "primary key(id))").execute();
        conn.prepareStatement(
                "insert into event(id, event_message) values(1, 'before-ddl')").execute();

        // Let the writer build and cache its column map for the pre-DDL shape.
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        assertEquals("precondition: the pre-DDL row must replicate before the cache can "
                        + "go stale", "before-ddl",
                String.valueOf(readCell(writer, "event", "event_message", 1)));

        // DDL against a live connector, with a writer already cached.
        conn.prepareStatement(
                "alter table event add column kafka_offset bigint null").execute();
        conn.prepareStatement(
                "insert into event(id, event_message, kafka_offset) "
                        + "values(2, 'after-ddl', 424652)").execute();

        Thread.sleep(20000);

        assertEquals("row counts match on both sides throughout this failure mode, which "
                + "is why a count-based checksum reported the table clean for two days",
                2L, rowCount(writer, "event"));

        Object stored = readCell(writer, "event", "kafka_offset", 2);
        assertNotNull("kafka_offset was added by DDL and carries 424652 in MySQL. A stale "
                        + "cached schema drops it from the INSERT and ClickHouse writes the "
                        + "DEFAULT instead -- 59,929 such writes in production", stored);
        assertEquals("the value must survive the DDL, not be replaced by the DEFAULT",
                424652L, ((Number) stored).longValue());

        conn.close();
    }

    /**
     * The same staleness reached from the opposite direction: a column DROPPED
     * from the source while a writer is cached.
     *
     * <p>The cached map still lists the column, so the INSERT reserves a
     * placeholder for it, but the record no longer carries it. Binding NULL
     * there overwrites the stored value. This is the RENAME/DROP half of the
     * NULL-fill defect that #1389 recorded as a known limitation.</p>
     *
     * <p>Asserted as "the surviving columns keep their real values": whether
     * the dropped column is removed from ClickHouse or retained is a policy
     * question, but the rest of the row must never be corrupted by it.</p>
     */
    @Test
    public void testColumnDroppedAfterWriterIsCachedDoesNotCorruptTheRow() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table aerion_rec(id int not null, jump_count int null, "
                        + "fail_reason varchar(64) null, primary key(id))").execute();
        conn.prepareStatement(
                "insert into aerion_rec(id, jump_count, fail_reason) "
                        + "values(1, 10, 'timeout')").execute();

        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        assertEquals("precondition", 10L,
                ((Number) readCell(writer, "aerion_rec", "jump_count", 1)).longValue());

        // DESTRUCTIVE: drops the fail_reason column. Bounded to the throwaway
        // `aerion_rec` table created by this test inside its own ephemeral
        // MySQL testcontainer, which is destroyed in stopContainers(). The drop
        // IS the condition under test -- it is what makes the cached schema
        // stale -- so it cannot be replaced with a non-destructive step.
        conn.prepareStatement("alter table aerion_rec drop column fail_reason").execute();
        conn.prepareStatement(
                "insert into aerion_rec(id, jump_count) values(2, 20)").execute();

        Thread.sleep(20000);

        assertEquals(2L, rowCount(writer, "aerion_rec"));
        assertEquals("the surviving column must carry its real value after the drop; a "
                        + "stale cache binds NULL across the row instead",
                20L, ((Number) readCell(writer, "aerion_rec", "jump_count", 2)).longValue());
        assertEquals("the pre-DDL row must be untouched by the schema change",
                10L, ((Number) readCell(writer, "aerion_rec", "jump_count", 1)).longValue());

        conn.close();
    }

    /**
     * An UPDATE arriving after DDL must write the real value of the new column,
     * not the DEFAULT.
     *
     * <p>Modelled on {@code trade_uat.enriched_trade.capped_by} (240 rows
     * diverged in a day). On a ReplacingMergeTree the newest version wins, so a
     * post-DDL UPDATE that carries the DEFAULT makes {@code FINAL} return the
     * wrong value from then on -- the divergence is permanent, not transient.</p>
     */
    @Test
    public void testUpdateAfterDdlCarriesTheRealValue() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table enriched_trade(id int not null, qty int null, "
                        + "primary key(id))").execute();
        conn.prepareStatement("insert into enriched_trade(id, qty) values(1, 100)")
                .execute();

        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        assertEquals("precondition", 100L,
                ((Number) readCell(writer, "enriched_trade", "qty", 1)).longValue());

        conn.prepareStatement(
                "alter table enriched_trade add column capped_by varchar(64) null").execute();
        conn.prepareStatement(
                "update enriched_trade set qty = 250, capped_by = 'RISK_LIMIT' where id = 1")
                .execute();

        Thread.sleep(20000);

        assertEquals("an UPDATE must not duplicate the row", 1L,
                rowCount(writer, "enriched_trade"));
        assertEquals("the pre-existing column must take its updated value",
                250L, ((Number) readCell(writer, "enriched_trade", "qty", 1)).longValue());
        Object cappedBy = readCell(writer, "enriched_trade", "capped_by", 1);
        assertNotNull("capped_by was added by DDL and set by the same UPDATE; a stale "
                + "cache drops it and FINAL returns the DEFAULT forever", cappedBy);
        assertEquals("RISK_LIMIT", String.valueOf(cappedBy));

        conn.close();
    }

    /**
     * Repeated DDL on one table: the cache must track EVERY change, not just
     * the first.
     *
     * <p>A refresh that fires once and then goes stale again is the same defect
     * with a longer fuse, so this applies three ALTERs in sequence with writes
     * between them and asserts every column across every row.</p>
     */
    @Test
    public void testRepeatedDdlKeepsTheCacheConsistent() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table churn(id int not null, c0 int null, primary key(id))").execute();
        conn.prepareStatement("insert into churn(id, c0) values(1, 1)").execute();
        Thread.sleep(12000);

        conn.prepareStatement("alter table churn add column c1 int null").execute();
        conn.prepareStatement("insert into churn(id, c0, c1) values(2, 2, 20)").execute();
        Thread.sleep(12000);

        conn.prepareStatement("alter table churn add column c2 int null").execute();
        conn.prepareStatement("insert into churn(id, c0, c1, c2) values(3, 3, 30, 300)")
                .execute();
        Thread.sleep(12000);

        conn.prepareStatement("alter table churn add column c3 varchar(32) null").execute();
        conn.prepareStatement(
                "insert into churn(id, c0, c1, c2, c3) values(4, 4, 40, 400, 'four')")
                .execute();
        Thread.sleep(20000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        assertEquals(4L, rowCount(writer, "churn"));

        assertEquals("c1 after the first ALTER", 20L,
                ((Number) readCell(writer, "churn", "c1", 2)).longValue());
        assertEquals("c1 must still be correct two ALTERs later", 30L,
                ((Number) readCell(writer, "churn", "c1", 3)).longValue());
        assertEquals("c2 after the second ALTER", 300L,
                ((Number) readCell(writer, "churn", "c2", 3)).longValue());
        assertEquals("c1 must still be correct three ALTERs later", 40L,
                ((Number) readCell(writer, "churn", "c1", 4)).longValue());
        assertEquals("c2 must still be correct after the third ALTER", 400L,
                ((Number) readCell(writer, "churn", "c2", 4)).longValue());
        assertEquals("c3 after the third ALTER", "four",
                String.valueOf(readCell(writer, "churn", "c3", 4)));

        conn.close();
    }
}
