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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end regression coverage for the txnrepo-uat data loss of 2026-09-01,
 * where a connector upgrade silently replaced NULL column values with the
 * ClickHouse column DEFAULT.
 *
 * <p><b>Why this IT exists and why row counts are not enough.</b> Every
 * scenario below leaves MySQL and ClickHouse with IDENTICAL ROW COUNTS while
 * the cell VALUES diverge. The daily checksum job caught it only because it
 * compares values; a count-based check reported the affected tables clean for
 * two days. Every assertion here is therefore value-level, and each one
 * asserts NULL specifically rather than "not equal to the MySQL value" -- the
 * defect substituted a type-dependent DEFAULT (0 for Int, '' for String,
 * 1970-01-01 for DateTime), so a weaker assertion would pass on some column
 * types and fail on others.</p>
 *
 * <p>Production impact being pinned, from the connector's own error log over
 * roughly 48 hours on a single UAT instance:</p>
 * <pre>
 *   59,929  txnrepo_uat.event                kafka_offset
 *    3,264  aerion_uat.aerion_break_detail   break_original_date, estimate_resolution_date
 *    1,951  aerion_uat.aerion_rec            jump_count, clearing_count, rec_end_datetime, fail_reason
 *      433  trade_uat.enriched_trade         capped_by
 * </pre>
 *
 * <p>The scenarios are modelled on the real tables: an insert-only event table
 * with a nullable BIGINT ({@code event.kafka_offset}), a blob-bearing changelog
 * ({@code changelog.request}, whose loss was entirely silent -- it never even
 * appeared in the error log), a wide row where several columns null together
 * ({@code aerion_rec}), and the UPDATE-to-NULL transition that is how the
 * divergence accumulates on a ReplacingMergeTree
 * ({@code enriched_trade.capped_by}).</p>
 *
 * <p>Column types are deliberately varied (BIGINT, TEXT/BLOB, DATETIME,
 * VARCHAR, INT) because the substituted DEFAULT differs per type: a fix that
 * handled only integers would pass a single-type test.</p>
 */
@Testcontainers
@DisplayName("NULL column values must round-trip as NULL, never as the ClickHouse DEFAULT")
public class NullColumnValueRoundTripIT {

    private static final Logger log =
            LoggerFactory.getLogger(NullColumnValueRoundTripIT.class);

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

    /** Starts the connector against the two containers and lets it settle. */
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

    /**
     * Reads one column for one row and reports whether ClickHouse stored SQL
     * NULL, distinguishing it from a DEFAULT that merely looks empty.
     *
     * @return the value as an Object, or null when the stored value is SQL NULL.
     */
    private Object readCell(BaseDbWriter writer, String table, String column, int id)
            throws Exception {
        String sql = String.format(
                "select %s from employees.%s final where id = %d", column, table, id);
        ResultSet rs = ITCommon.executeQueryWithResultSet(sql, writer.getConnection());
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
                + " -- the value assertions below would be vacuous", found);
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
     * Scenario 1 -- {@code txnrepo_uat.event.kafka_offset}, 59,929 dropped
     * writes in production and 3,348 rows diverged in one day.
     *
     * <p>An insert-only table where one nullable BIGINT is NULL on some rows.
     * The defect wrote 0 instead of NULL, so MySQL NULL and ClickHouse 0
     * diverged while the row count matched exactly.</p>
     *
     * <p>Also covers the silent blob case
     * ({@code trade_uat.changelog.request}), which never appeared in the
     * connector's error log at all and so had no operational signal whatsoever.
     * A populated control row is inserted alongside so the test cannot pass by
     * the connector simply writing NULL everywhere.</p>
     */
    @Test
    public void testNullOnInsertStaysNull() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table event(id int not null, kafka_offset bigint null, "
                        + "request text null, recorded_at datetime null, "
                        + "primary key(id))").execute();

        // id=1: the production shape -- nullable columns genuinely NULL.
        conn.prepareStatement(
                "insert into event(id, kafka_offset, request, recorded_at) "
                        + "values(1, null, null, null)").execute();
        // id=2: control. Guards against a fix that writes NULL unconditionally.
        conn.prepareStatement(
                "insert into event(id, kafka_offset, request, recorded_at) "
                        + "values(2, 424652, 'payload-bytes', '2026-09-01 06:43:00')").execute();

        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        assertEquals("row counts matched throughout the production incident too; "
                        + "this assertion exists to prove the value checks below are the "
                        + "ones doing the work",
                2L, rowCount(writer, "event"));

        assertNull("kafka_offset is NULL in MySQL. Writing the ClickHouse DEFAULT (0) "
                        + "instead diverges silently -- 59,929 such writes in production",
                readCell(writer, "event", "kafka_offset", 1));
        assertNull("a NULL blob must stay NULL. This is the failure mode that produced "
                        + "NO error log line at all, making it the more dangerous of the two",
                readCell(writer, "event", "request", 1));
        assertNull("a NULL DateTime must not become 1970-01-01",
                readCell(writer, "event", "recorded_at", 1));

        assertEquals("the populated control row must be unaffected",
                424652L, ((Number) readCell(writer, "event", "kafka_offset", 2)).longValue());
        assertEquals("the populated blob control must be unaffected",
                "payload-bytes", String.valueOf(readCell(writer, "event", "request", 2)));

        conn.close();
    }

    /**
     * Scenario 2 -- {@code trade_uat.enriched_trade.capped_by}, 240 rows
     * diverged in one day.
     *
     * <p>The UPDATE-to-NULL transition, which is how divergence accumulates on
     * a ReplacingMergeTree: a row is inserted with a value, then the source
     * clears it. If the column is dropped from the UPDATE's INSERT, the new
     * version carries the DEFAULT while MySQL holds NULL -- and because
     * ReplacingMergeTree keeps the newest version, the wrong value is what
     * {@code FINAL} returns from then on.</p>
     */
    @Test
    public void testUpdateToNullClearsTheStoredValue() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table enriched_trade(id int not null, capped_by varchar(64) null, "
                        + "capped_qty int null, primary key(id))").execute();
        conn.prepareStatement(
                "insert into enriched_trade(id, capped_by, capped_qty) "
                        + "values(1, 'RISK_LIMIT', 500)").execute();

        Thread.sleep(12000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        assertEquals("precondition: the value must land before it can be cleared",
                "RISK_LIMIT", String.valueOf(readCell(writer, "enriched_trade", "capped_by", 1)));

        conn.prepareStatement(
                "update enriched_trade set capped_by = null, capped_qty = null where id = 1")
                .execute();

        Thread.sleep(12000);

        assertEquals("an UPDATE must not duplicate the row", 1L,
                rowCount(writer, "enriched_trade"));
        assertNull("the UPDATE cleared capped_by in MySQL; if the column is dropped from "
                        + "the generated INSERT the new version carries '' and FINAL returns "
                        + "the wrong value forever",
                readCell(writer, "enriched_trade", "capped_by", 1));
        assertNull("same for the numeric column, whose DEFAULT is 0 rather than ''",
                readCell(writer, "enriched_trade", "capped_qty", 1));

        conn.close();
    }

    /**
     * Scenario 3 -- {@code aerion_uat.aerion_rec} (1,951) and
     * {@code aerion_uat.aerion_break_detail} (3,264).
     *
     * <p>A wide row where SEVERAL columns are NULL at once, mixing types. In
     * production these tables dropped four and two columns respectively from
     * the same statement, so this asserts every one rather than a single
     * representative column.</p>
     */
    @Test
    public void testMultipleNullColumnsInOneRow() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table aerion_rec(id int not null, jump_count int null, "
                        + "clearing_count int null, rec_end_datetime datetime null, "
                        + "fail_reason varchar(255) null, primary key(id))").execute();
        conn.prepareStatement(
                "insert into aerion_rec(id, jump_count, clearing_count, rec_end_datetime, "
                        + "fail_reason) values(1, null, null, null, null)").execute();

        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        List<String> wrong = new ArrayList<>();
        for (String column : new String[]{
                "jump_count", "clearing_count", "rec_end_datetime", "fail_reason"}) {
            Object stored = readCell(writer, "aerion_rec", column, 1);
            if (stored != null) {
                wrong.add(column + "=" + stored);
            }
        }
        // Reported together: the production log showed these columns dropping as
        // a set, so a fix that rescues only some of them must be visible as such
        // rather than surfacing one column at a time across repeated runs.
        assertTrue("every NULL column must round-trip as NULL; these came back as the "
                + "ClickHouse DEFAULT instead: " + wrong, wrong.isEmpty());

        conn.close();
    }

    /**
     * Regression guard for #1389, which this coverage must not undo.
     *
     * <p>A row buffered before an {@code ALTER TABLE ... ADD COLUMN} genuinely
     * does not carry the new column, and must receive the column DEFAULT the
     * ALTER established rather than being bound to NULL over a real value. That
     * is the opposite requirement to every test above, and the two are
     * distinguished by whether the record's SCHEMA carries the column -- so this
     * is the test that keeps the fix honest.</p>
     */
    @Test
    public void testPreAlterRowStillReceivesTheColumnDefault() throws Exception {
        startConnector();

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "create table ddl_churn(id int not null, a_int bigint null, primary key(id))")
                .execute();
        conn.prepareStatement("insert into ddl_churn(id, a_int) values(1, 11)").execute();

        Thread.sleep(12000);

        conn.prepareStatement(
                "alter table ddl_churn add column c_int int not null default 7").execute();
        conn.prepareStatement("insert into ddl_churn(id, a_int) values(2, 22)").execute();

        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        Object postAlter = readCell(writer, "ddl_churn", "c_int", 2);
        assertTrue("a row inserted after ADD COLUMN must carry the DEFAULT the ALTER "
                        + "established (7), not NULL -- see #1389. Stored: " + postAlter,
                postAlter != null && ((Number) postAlter).intValue() == 7);

        assertEquals("the pre-ALTER row's own data must be untouched",
                11L, ((Number) readCell(writer, "ddl_churn", "a_int", 1)).longValue());

        conn.close();
    }
}
