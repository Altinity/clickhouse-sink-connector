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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;

/**
 * End-to-end regression coverage for issue #1379 ("Initial Snapshot never
 * finishes").
 *
 * <p>A running MySQL to ClickHouse pipeline carries more than row changes.
 * Transaction-boundary events and heartbeats are ordinary Debezium records
 * with a Struct value and no {@code op} field, so
 * {@code SourceRecordParserService.parse} returns null for them -- that null
 * is contract, not failure. {@code processEveryChangeRecord} called
 * {@code setSequenceNumber} on the return value before testing it for null,
 * so every one of those records raised a NullPointerException. The catch-all
 * in the same method swallowed it, which is why the reported symptom was a
 * snapshot that never completed while the log filled with the same stack
 * trace rather than an outright crash.</p>
 *
 * <p>{@link NullParsedRecordSkipTest} pins the caller's behaviour directly.
 * This test pins it where it actually failed: a real MySQL, a real Debezium
 * engine and a real ClickHouse, with the unparseable records produced by the
 * connector itself rather than by a stub.</p>
 *
 * <p>Two things are asserted, and the first exists so the second cannot pass
 * vacuously:</p>
 * <ol>
 *   <li>the unparseable path was actually reached during the run -- either a
 *       deliberate skip (fixed) or a NullPointerException (broken). If
 *       neither appears, no such record was ever produced and the test would
 *       be proving nothing, so it fails;</li>
 *   <li>no NullPointerException was raised while processing records, and the
 *       rows written after those records are all in ClickHouse -- the
 *       pipeline kept making progress.</li>
 * </ol>
 */
@Testcontainers
@DisplayName("Records the parser cannot convert are skipped without stalling the pipeline (#1379)")
public class UnparseableRecordProgressIT {

    /** Rows inserted after the unparseable records; all must reach ClickHouse. */
    private static final int ROWS = 4;

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

    /**
     * Collects everything {@link DebeziumChangeEventCapture} logs during the
     * run, including the throwables, so the assertions can inspect the
     * exception objects rather than match on rendered text.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-1379-it", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            this.events.add(event.toImmutable());
        }
    }

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
        Thread.sleep(15000);
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

    @Test
    @DisplayName("Transaction-boundary and heartbeat records do not raise a NullPointerException, and rows keep landing")
    public void unparseableRecordsDoNotStallTheEngine() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        // Both of these make the connector emit records with a Struct value
        // and no "op" field -- exactly the records parse() returns null for.
        // Nothing is stubbed: the connector produces its own poison.
        props.setProperty("provide.transaction.metadata", "true");
        props.setProperty("heartbeat.interval.ms", "1000");

        // Attach before the engine starts, so nothing logged during startup or
        // the snapshot is missed.
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);

        ClickHouseDebeziumEmbeddedApplication application =
                new ClickHouseDebeziumEmbeddedApplication();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Connection conn = null;

        try {
            executorService.execute(() -> {
                try {
                    application.start(injector.getInstance(DebeziumRecordParserService.class),
                            props, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(25000);

            conn = ITCommon.connectToMySQL(mySqlContainer);
            conn.prepareStatement("create table `ledger`(id int not null, note varchar(255), "
                    + "primary key(id))").execute();

            // Autocommit is on, so each insert is its own transaction and
            // produces its own pair of transaction-boundary records.
            for (int id = 1; id <= ROWS; id++) {
                conn.prepareStatement("insert into ledger values(" + id + ", 'row" + id + "')")
                        .execute();
                Thread.sleep(2000);
            }

            Thread.sleep(25000);

            // (1) The unparseable path must have been reached, otherwise this
            // test asserts nothing. Post-fix that shows up as the deliberate
            // skip; pre-fix as the NullPointerException asserted on below.
            long skips = appender.events.stream()
                    .filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN))
                    .filter(e -> e.getMessage().getFormattedMessage().toLowerCase()
                            .contains("skipping"))
                    .count();
            List<Throwable> npes = collectNullPointerExceptions(appender);
            Assert.assertTrue("no unparseable record reached the connector during this run, so "
                            + "this test exercised nothing; expected transaction-boundary or "
                            + "heartbeat records to be produced",
                    skips > 0 || !npes.isEmpty());

            // (2) The regression itself. A record the parser cannot convert
            // must be skipped deliberately, never by way of an NPE.
            Assert.assertTrue("processing a record the parser could not convert raised "
                            + npes.size() + " NullPointerException(s); issue #1379 raised one per "
                            + "such record for the whole snapshot. First occurrence:\n"
                            + firstStackTrace(npes),
                    npes.isEmpty());

            // (3) ... and the pipeline kept moving: every row written after
            // those records is in ClickHouse.
            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
            long landed = 0;
            ResultSet rs = ITCommon.executeQueryWithResultSet(
                    "select count(*) as row_count from employees.ledger final",
                    writer.getConnection());
            while (rs.next()) {
                landed = rs.getLong("row_count");
            }
            Assert.assertEquals("every row inserted after the unparseable records must reach "
                    + "ClickHouse", ROWS, landed);
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
            try {
                if (application.getDebeziumEventCapture() != null
                        && application.getDebeziumEventCapture().engine != null) {
                    application.getDebeziumEventCapture().engine.close();
                }
            } catch (Exception ignored) {
                // Teardown must not mask the assertion that brought us here.
            }
            if (conn != null) {
                conn.close();
            }
            executorService.shutdown();
            HikariDbSource.close();
        }
    }

    /**
     * Every NullPointerException logged by the class under test, unwrapping
     * cause chains so a wrapped NPE is not missed.
     *
     * @param appender the appender holding this run's log events
     * @return the NullPointerExceptions that were logged, in order
     */
    private static List<Throwable> collectNullPointerExceptions(CapturingAppender appender) {
        List<Throwable> found = new ArrayList<>();
        for (LogEvent event : new ArrayList<>(appender.events)) {
            for (Throwable t = event.getThrown(); t != null; t = t.getCause()) {
                if (t instanceof NullPointerException) {
                    found.add(t);
                    break;
                }
                if (t.getCause() == t) {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Renders the first captured throwable so a failure names the defect
     * instead of merely asserting that one exists.
     *
     * @param throwables the captured throwables
     * @return a printable stack trace, or a placeholder when there are none
     */
    private static String firstStackTrace(List<Throwable> throwables) {
        if (throwables.isEmpty()) {
            return "(none)";
        }
        StringWriter sw = new StringWriter();
        throwables.get(0).printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
