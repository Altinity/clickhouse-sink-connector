package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * Obtains the initial system-database connection, retrying while ClickHouse is
 * still starting up (used for both the Debezium storage database and the
 * offset-storage JDBC URL, which point at the same system database).
 *
 * <p>Stateless retry logic; extracted from {@link DebeziumChangeEventCapture}
 * so it is owned and tested on its own.</p>
 */
final class SystemDbConnectionRetry {

    /**
     * Logger for SystemDbConnectionRetry class.
     */
    private static final Logger log = LogManager.getLogger(SystemDbConnectionRetry.class);

    private SystemDbConnectionRetry() {
    }

    /**
     * Number of attempts made to obtain the initial system-database connection.
     */
    static final int SYSTEM_DB_CONNECT_ATTEMPTS = 30;

    /**
     * Delay between initial system-database connection attempts, in millis.
     */
    static final long SYSTEM_DB_CONNECT_RETRY_MS = 2000L;

    /**
     * Obtains the initial system-database connection, retrying while ClickHouse
     * is still starting up.
     * <p>
     * This connection is created exactly once at startup and is then reused for
     * the Debezium storage-database creation and the version lookup. When the
     * connection pool is disabled ({@code connection.pool.disable=true}, which
     * is the setting used by the docker-compose stacks), there is no pool to
     * obtain a replacement from later, so a {@code null} here is terminal for
     * the process: every later query fails with "connection is not available"
     * and the connector never creates the destination tables.
     * <p>
     * That is reachable on a cold start. compose gates the connector on the
     * ClickHouse container's healthcheck, but the healthcheck can pass moments
     * before the HTTP port is serving, and the two driver generations differ in
     * how they surface that window: verified against clickhouse-jdbc 0.9.8, the
     * V2 driver returns a connection object lazily even when nothing is
     * listening, while the V1 driver throws
     * "Connect to http://host:port failed: Connection refused" — which
     * {@code BaseDbWriter.createConnection} logs and converts to {@code null}.
     * Retrying here makes startup tolerant of that window for both drivers
     * instead of depending on which driver defers the connect.
     *
     * @param jdbcUrl       the system-database JDBC URL.
     * @param dbCredentials the database credentials.
     * @param config        the connector configuration.
     * @return the connection, or null if every attempt failed.
     */
    static Connection createSystemDbConnectionWithRetry(String jdbcUrl,
                                                         DBCredentials dbCredentials,
                                                         ClickHouseSinkConnectorConfig config) {
        return connectWithRetry(() -> BaseDbWriter.createConnection(
                jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                dbCredentials.getUserName(), dbCredentials.getPassword(),
                BaseDbWriter.SYSTEM_DB, config),
                SYSTEM_DB_CONNECT_RETRY_MS);
    }

    /**
     * Retry loop backing {@link #createSystemDbConnectionWithRetry}. Package
     * private so the retry behavior can be unit tested without a live server.
     *
     * @param supplier produces a connection, or null when unavailable.
     * @param retryMs  delay between attempts, in milliseconds.
     * @return the first non-null connection, or null if all attempts failed.
     */
    static Connection connectWithRetry(Supplier<Connection> supplier,
                                       long retryMs) {
        Connection conn = null;
        for (int attempt = 1; attempt <= SYSTEM_DB_CONNECT_ATTEMPTS; attempt++) {
            conn = supplier.get();
            if (conn != null) {
                if (attempt > 1) {
                    log.info("Obtained system database connection on attempt {}/{}",
                            attempt, SYSTEM_DB_CONNECT_ATTEMPTS);
                }
                return conn;
            }
            log.warn("System database connection not available yet "
                    + "(attempt {}/{}), retrying in {}ms",
                    attempt, SYSTEM_DB_CONNECT_ATTEMPTS, retryMs);
            if (attempt < SYSTEM_DB_CONNECT_ATTEMPTS) {
                try {
                    Thread.sleep(retryMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Could not obtain the system database connection after {} attempts; "
                + "startup will continue but ClickHouse operations will fail",
                SYSTEM_DB_CONNECT_ATTEMPTS);
        return conn;
    }
}
