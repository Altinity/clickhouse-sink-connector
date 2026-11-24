package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector waits for a MySQL source that is still coming up, within the retry budget, before the start-time
 * preflights read it (spec 01.01 section 3.2, spec 10.04 section 3.6).
 *
 * <p><b>The gap.</b> {@link BinlogRowImagePreflight} refuses to start when the source cannot be asked. A connector
 * started while its source was still coming up (the compose start order of the checksum job: the MySQL container
 * runs its init script with the network off, the connector only waits for ClickHouse) therefore exited within
 * seconds and never recovered, where it used to ride the wait out in Debezium's engine retry loop. {@link
 * MySqlSourceWait} spends the same budget (errors.max.retries probes after the first) before the preflights run.</p>
 *
 * <p>The source is a stub {@link BinlogRowImagePreflight.ConnectionOpener}; the pause is a stub {@link
 * MySqlSourceWait.Sleeper}, so no test connects or sleeps.</p>
 */
public class MySqlSourceWaitTest {

    private BinlogRowImagePreflight.ConnectionOpener savedOpener;
    private MySqlSourceWait.Sleeper savedSleeper;

    @BeforeEach
    public void save() {
        savedOpener = MySqlSourceWait.opener;
        savedSleeper = MySqlSourceWait.sleeper;
    }

    @AfterEach
    public void restore() {
        MySqlSourceWait.opener = savedOpener;
        MySqlSourceWait.sleeper = savedSleeper;
        Thread.interrupted();
    }

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-source-wait", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static List<LogEvent> capture(Runnable body) {
        Logger coreLogger = (Logger) LogManager.getLogger(MySqlSourceWait.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            body.run();
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }
        return new ArrayList<>(appender.events);
    }

    private static Properties mysqlProps() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "mysql-host");
        props.setProperty("database.port", "3307");
        props.setProperty("database.user", "u");
        props.setProperty("database.password", "p");
        return props;
    }

    /** What the stub source saw: probes opened, connections closed, the URL, user and password it was given. */
    private static final class Source {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final List<String> urls = Collections.synchronizedList(new ArrayList<>());
        final List<String> users = Collections.synchronizedList(new ArrayList<>());
        final List<String> passwords = Collections.synchronizedList(new ArrayList<>());
        final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Installs a source that refuses the first {@code failures} probes with {@code failure} and then accepts, and
     * a pause that records its length and never sleeps.
     */
    private static Source install(int failures, Exception failure) {
        Source seen = new Source();
        MySqlSourceWait.opener = (url, user, password) -> {
            seen.urls.add(url);
            seen.users.add(user);
            seen.passwords.add(password);
            if (seen.opened.getAndIncrement() < failures) {
                if (failure instanceof SQLException) {
                    throw (SQLException) failure;
                }
                throw (RuntimeException) failure;
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            seen.closed.incrementAndGet();
                        }
                        return method.getReturnType() == boolean.class ? (Object) false : null;
                    });
        };
        MySqlSourceWait.sleeper = seen.sleeps::add;
        return seen;
    }

    private static SQLException refused() {
        return new SQLException("Communications link failure", "08S01", 0);
    }

    @Test
    @DisplayName("A source that answers at once is probed once, with no pause")
    public void reachableAtOnceIsProbedOnce() {
        Source seen = install(0, null);
        assertTrue(MySqlSourceWait.await(mysqlProps(), 20, 10000));
        assertEquals(1, seen.opened.get());
        assertTrue(seen.sleeps.isEmpty(), "nothing to wait for: " + seen.sleeps);
    }

    @Test
    @DisplayName("A source that comes up after k failed probes is waited for with exactly k pauses of the retry sleep")
    public void comesUpLaterIsWaitedFor() {
        Source seen = install(7, refused());
        assertTrue(MySqlSourceWait.await(mysqlProps(), 20, 10000),
                "the old retry loop rode a source that came up within the budget out; so must the wait");
        assertEquals(8, seen.opened.get());
        assertEquals(Collections.nCopies(7, 10000L), seen.sleeps);
    }

    @Test
    @DisplayName("The budget is errors.max.retries pauses after the first probe: a source up exactly on the last probe is caught")
    public void lastProbeStillCounts() {
        Source seen = install(20, refused());
        assertTrue(MySqlSourceWait.await(mysqlProps(), 20, 10000));
        assertEquals(21, seen.opened.get());
        assertEquals(20, seen.sleeps.size());
    }

    @Test
    @DisplayName("A source that never answers is given maxRetries + 1 probes and maxRetries pauses, then the wait returns false, not an exception")
    public void neverReachableSpendsTheBudgetThenReturnsFalse() {
        Source seen = install(Integer.MAX_VALUE, refused());
        assertFalse(MySqlSourceWait.await(mysqlProps(), 20, 10000));
        assertEquals(21, seen.opened.get());
        assertEquals(Collections.nCopies(20, 10000L), seen.sleeps);
        assertEquals(0, seen.closed.get(), "no connection was opened, so none can leak");
    }

    @Test
    @DisplayName("For every number of failures and every budget the probe count is min(failures, budget) + 1 and the answer is failures <= budget")
    public void budgetGrid() {
        for (int budget = 0; budget <= 6; budget++) {
            for (int failures = 0; failures <= 9; failures++) {
                Source seen = install(failures, refused());
                boolean reached = MySqlSourceWait.await(mysqlProps(), budget, 25);
                String at = "budget=" + budget + " failures=" + failures;
                assertEquals(failures <= budget, reached, at);
                assertEquals(Math.min(failures, budget) + 1, seen.opened.get(), at);
                assertEquals(Math.min(failures, budget), seen.sleeps.size(), at);
            }
        }
    }

    @Test
    @DisplayName("A budget of zero, or below zero, probes once and never sleeps")
    public void zeroAndNegativeBudgetProbeOnce() {
        for (int budget : new int[] {0, -1, Integer.MIN_VALUE}) {
            Source seen = install(Integer.MAX_VALUE, refused());
            assertFalse(MySqlSourceWait.await(mysqlProps(), budget, 10000), "budget " + budget);
            assertEquals(1, seen.opened.get(), "budget " + budget);
            assertTrue(seen.sleeps.isEmpty(), "budget " + budget);
        }
    }

    @Test
    @DisplayName("Every probe that opened is closed")
    public void everyOpenedConnectionIsClosed() {
        Source seen = install(0, null);
        MySqlSourceWait.await(mysqlProps(), 3, 1);
        assertEquals(seen.opened.get(), seen.closed.get());
    }

    @Test
    @DisplayName("A runtime failure from the driver (no suitable driver, bad URL) is a failed probe too, not a crash")
    public void runtimeFailureIsAFailedProbe() {
        Source seen = install(2, new IllegalArgumentException("bad url"));
        assertTrue(MySqlSourceWait.await(mysqlProps(), 5, 1));
        assertEquals(3, seen.opened.get());
    }

    @Test
    @DisplayName("The probe uses the preflights' URL (host, port, transport settings) and the configured login")
    public void probeUsesThePreflightUrlAndLogin() {
        Properties props = mysqlProps();
        props.setProperty("database.ssl.mode", "verify_ca");
        Source seen = install(0, null);
        MySqlSourceWait.await(props, 1, 1);
        assertEquals(KeylessTablePreflight.jdbcUrl("mysql-host", "3307", props), seen.urls.get(0));
        assertTrue(seen.urls.get(0).contains("mysql-host:3307"), seen.urls.get(0));
        assertTrue(seen.urls.get(0).contains("requireSSL=true"), "ssl mode must carry over: " + seen.urls.get(0));
        assertEquals("u", seen.users.get(0));
        assertEquals("p", seen.passwords.get(0));
    }

    @Test
    @DisplayName("The port defaults to 3306, as in the preflights")
    public void portDefaultsTo3306() {
        Properties props = mysqlProps();
        props.remove("database.port");
        Source seen = install(0, null);
        MySqlSourceWait.await(props, 1, 1);
        assertTrue(seen.urls.get(0).contains("mysql-host:3306"), seen.urls.get(0));
    }

    @Test
    @DisplayName("A non-MySQL connector is not waited for and not probed")
    public void nonMysqlIsNotProbed() {
        Properties postgres = new Properties();
        postgres.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        postgres.setProperty("database.hostname", "pg-host");
        postgres.setProperty("database.user", "u");
        Source seen = install(Integer.MAX_VALUE, refused());
        assertTrue(MySqlSourceWait.await(postgres, 20, 10000));
        assertEquals(0, seen.opened.get());
        assertTrue(seen.sleeps.isEmpty());
    }

    @Test
    @DisplayName("A configuration with no host or no user returns false at once: nothing to wait for, the preflights refuse it")
    public void missingHostOrUserIsNotWaitedFor() {
        Properties noHost = mysqlProps();
        noHost.remove("database.hostname");
        Properties noUser = mysqlProps();
        noUser.remove("database.user");
        Source seen = install(0, null);
        assertFalse(MySqlSourceWait.await(noHost, 20, 10000));
        assertFalse(MySqlSourceWait.await(noUser, 20, 10000));
        assertEquals(0, seen.opened.get());
        assertTrue(seen.sleeps.isEmpty());
    }

    @Test
    @DisplayName("An interrupt while waiting stops the wait with the interrupt flag kept")
    public void interruptStopsTheWait() {
        Source seen = install(Integer.MAX_VALUE, refused());
        MySqlSourceWait.sleeper = millis -> {
            throw new InterruptedException("shutdown");
        };
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MySqlSourceWait.await(mysqlProps(), 20, 10000));
        assertTrue(ex.getMessage().contains("Interrupted"), ex.getMessage());
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must survive for the caller");
        assertEquals(1, seen.opened.get(), "no probe after the interrupt");
    }

    @Test
    @DisplayName("Every failed probe is logged at WARN with its cause; the give-up says the checks below decide")
    public void failuresAreLoggedNeverSilent() {
        install(Integer.MAX_VALUE, refused());
        List<LogEvent> events = capture(() -> MySqlSourceWait.await(mysqlProps(), 3, 5));
        List<LogEvent> warns = new ArrayList<>();
        for (LogEvent e : events) {
            if (e.getLevel() == Level.WARN) {
                warns.add(e);
            }
        }
        assertEquals(4, warns.size(), "one WARN per failed probe: " + events);
        assertTrue(warns.get(0).getMessage().getFormattedMessage().contains("Communications link failure"),
                warns.get(0).getMessage().getFormattedMessage());
        assertTrue(warns.get(0).getMessage().getFormattedMessage().contains("mysql-host:3307"));
        assertTrue(warns.get(3).getMessage().getFormattedMessage().contains("decide"),
                warns.get(3).getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("The password is never logged")
    public void passwordIsNeverLogged() {
        Properties props = mysqlProps();
        props.setProperty("database.password", "s3cr3t-value");
        install(Integer.MAX_VALUE, refused());
        List<LogEvent> events = capture(() -> MySqlSourceWait.await(props, 2, 1));
        for (LogEvent e : events) {
            assertFalse(e.getMessage().getFormattedMessage().contains("s3cr3t-value"),
                    e.getMessage().getFormattedMessage());
        }
    }

    @Test
    @DisplayName("A source that comes up is announced once, at INFO, with the number of failed probes")
    public void recoveryIsAnnounced() {
        install(2, refused());
        List<LogEvent> events = capture(() -> MySqlSourceWait.await(mysqlProps(), 5, 1));
        long infos = events.stream().filter(e -> e.getLevel() == Level.INFO).count();
        assertEquals(1, infos, events.toString());
        assertTrue(events.get(events.size() - 1).getMessage().getFormattedMessage().contains("2 failed probe"),
                events.get(events.size() - 1).getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("The real driver against a closed port is a failed probe that returns false, not an exception")
    public void realUnreachableHostReturnsFalse() {
        MySqlSourceWait.opener = savedOpener;
        MySqlSourceWait.sleeper = millis -> { };
        Properties unreachable = mysqlProps();
        unreachable.setProperty("database.hostname", "127.0.0.1");
        unreachable.setProperty("database.port", "1");
        assertFalse(MySqlSourceWait.await(unreachable, 1, 1));
    }
}
