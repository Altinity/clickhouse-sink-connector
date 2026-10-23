package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Waits for the MySQL source to accept a connection before the start-time preflights read it
 * (spec 01.01 section 3.2, spec 10.04 section 3.6).
 *
 * <p><b>The gap.</b> The preflights read the source once, at start, and
 * {@link BinlogRowImagePreflight} refuses to start when the source cannot be asked. A connector that is
 * started while its source is still coming up (a container whose MySQL runs its init script with the
 * network off, a host restarted together with its replica, a failover) used to survive that: before the
 * refusal existed the connector only logged, Debezium's own engine retry loop covered the wait, and the
 * connector started as soon as MySQL answered. With the refusal the same start order ended the process
 * within seconds, with nothing left to retry. The wait restores the old tolerance without weakening the
 * refusal.</p>
 *
 * <p><b>What it does.</b> Opens a connection to the source (the same URL, transport settings and
 * credentials as the preflights, and closes it at once), at most {@code maxRetries + 1} times,
 * {@code sleepMillis} apart: the budget the engine's own retry loop gives a failing engine
 * ({@code errors.max.retries} times the retry sleep). It returns as soon as one connection opens. When the
 * budget is spent it returns {@code false} and carries on: the preflights then read the source themselves
 * and refuse with their own message, so an unreachable source still ends the start loudly, only later.
 * Each failed probe is logged with its cause, so the wait is never silent.</p>
 *
 * <p>Read-only: it opens and closes a connection and runs no statement.</p>
 */
public final class MySqlSourceWait {

    private static final Logger log = LogManager.getLogger(MySqlSourceWait.class);

    /** Pauses between probes. A test replaces it, so no test sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Opens the connection one probe makes. A test replaces it with a stub source. */
    static volatile BinlogRowImagePreflight.ConnectionOpener opener = DriverManager::getConnection;

    static volatile Sleeper sleeper = Thread::sleep;

    private MySqlSourceWait() {
    }

    /**
     * Waits for the configured MySQL source to accept a connection.
     *
     * <p>Only MySQL is waited for; other connectors return {@code true} untouched. A configuration that
     * names no host or user returns {@code false} at once: there is nothing to wait for, and the
     * preflights refuse it.</p>
     *
     * @param props       the connector properties.
     * @param maxRetries  how many times to probe again after the first failure; a value below zero is zero.
     * @param sleepMillis the pause between two probes.
     * @return {@code true} when the source accepted a connection (or is not a MySQL source), {@code false}
     *         when the budget was spent or nothing could be probed.
     * @throws IllegalStateException when the thread is interrupted while waiting; the interrupt flag is kept.
     */
    public static boolean await(Properties props, int maxRetries, long sleepMillis) {
        String connector = props.getProperty("connector.class", "");
        if (!connector.toLowerCase().contains("mysql")) {
            return true;
        }
        String host = props.getProperty("database.hostname");
        String port = props.getProperty("database.port", "3306");
        String user = props.getProperty("database.user");
        String password = props.getProperty("database.password");
        if (host == null || user == null) {
            return false;
        }
        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        int retries = Math.max(0, maxRetries);
        for (int probe = 0; probe <= retries; probe++) {
            Exception failure = probe(url, user, password);
            if (failure == null) {
                if (probe > 0) {
                    log.info("The MySQL source at {}:{} accepts connections after {} failed probe(s); starting.",
                            host, port, probe);
                }
                return true;
            }
            if (probe == retries) {
                log.warn("The MySQL source at {}:{} did not accept a connection in {} probe(s), {} ms apart "
                        + "(last failure: {}). Starting anyway: the checks below read the source themselves and "
                        + "decide.", host, port, probe + 1, sleepMillis, failure.toString());
                return false;
            }
            log.warn("The MySQL source at {}:{} does not accept connections yet ({}). Probe {} of {}; "
                    + "waiting {} ms before the next one.", host, port, failure.toString(), probe + 1, retries + 1,
                    sleepMillis);
            pause(sleepMillis);
        }
        return false;
    }

    /** One probe: opens a connection and closes it. Returns the failure, or {@code null} when it opened. */
    private static Exception probe(String url, String user, String password) {
        try (Connection conn = opener.open(url, user, password)) {
            return null;
        } catch (SQLException | RuntimeException e) {
            return e;
        }
    }

    private static void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the MySQL source to accept connections", e);
        }
    }
}
