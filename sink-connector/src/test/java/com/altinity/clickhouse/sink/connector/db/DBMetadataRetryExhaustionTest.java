package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 08.01 section 6: {@code DBMetadata.executeSystemQuery}, the helper that
 * runs every catalog DDL the connector issues (auto-create CREATE TABLE, CREATE
 * DATABASE, schema-evolution ADD COLUMN, the MATERIALIZED to DEFAULT
 * conversion, the replicated DDL itself in {@code executeDDL}, the offset and
 * status tables).
 *
 * <p>A non-retryable code is thrown to the caller. A retryable failure is
 * retried {@code MAX_RETRIES} times with a growing sleep -- and when the last
 * attempt fails too, the loop ends and the method returns normally, as if the
 * statement had run. The caller cannot tell a CREATE or ALTER that never
 * happened from one that did.</p>
 */
public class DBMetadataRetryExhaustionTest {

    @BeforeEach
    public void oneAttempt() {
        DBMetadata.setMaxRetries(1);
    }

    @AfterEach
    public void restore() {
        DBMetadata.setMaxRetries(10);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A connection whose every statement fails with the given server error. */
    private static Connection failing(String message, AtomicInteger attempts) {
        InvocationHandler ps = (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                attempts.incrementAndGet();
                throw new SQLException(message);
            }
            return null;
        };
        InvocationHandler conn = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return Proxy.newProxyInstance(DBMetadataRetryExhaustionTest.class.getClassLoader(),
                            new Class<?>[]{PreparedStatement.class}, ps);
                case "isClosed":
                    return false;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(DBMetadataRetryExhaustionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, conn);
    }

    @Test
    @DisplayName("a non-retryable server error reaches the caller at once (pinned)")
    public void nonRetryableErrorIsThrown() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(SQLException.class, () -> new DBMetadata(config()).executeSystemQuery(
                failing("Code: 60. DB::Exception: Table db.t does not exist", attempts),
                "ALTER TABLE db.t ADD COLUMN c Int32"));
        assertEquals(1, attempts.get());
    }

    @Test
    @Disabled("DEFECT FM-08.01-6: when every attempt fails with a retryable error, executeSystemQuery leaves its "
            + "retry loop and returns null instead of throwing, so a CREATE TABLE, ADD COLUMN or replicated DDL "
            + "that never ran is reported to its caller as done")
    @DisplayName("retry exhaustion on a retryable error is reported to the caller")
    public void retryExhaustionIsThrown() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(SQLException.class, () -> new DBMetadata(config()).executeSystemQuery(
                failing("Code: 159. DB::Exception: Timeout exceeded: elapsed 180 seconds", attempts),
                "ALTER TABLE db.t ADD COLUMN c Int32"),
                "the ALTER never ran; returning normally lets the caller acknowledge it");
        assertEquals(1, attempts.get());
    }

    @Test
    @Disabled("DEFECT FM-10.06b-4: errors.max.retries has no lower bound and DebeziumChangeEventCapture.setup "
            + "copies it into DBMetadata.MAX_RETRIES; at 0 (or below) the retry loop of executeSystemQuery never "
            + "runs, so every CREATE, ALTER and replicated DDL is silently skipped and returns normally")
    @DisplayName("a zero retry budget still executes the statement once")
    public void zeroRetryBudgetStillExecutesOnce() throws SQLException {
        DBMetadata.setMaxRetries(0);
        AtomicInteger attempts = new AtomicInteger();
        try {
            new DBMetadata(config()).executeSystemQuery(
                    failing("Code: 60. DB::Exception: Table db.t does not exist", attempts),
                    "ALTER TABLE db.t ADD COLUMN c Int32");
        } catch (SQLException expected) {
            // the statement ran and failed: that is the correct outcome here
        }
        assertEquals(1, attempts.get(), "errors.max.retries=0 must mean 'no retries', not 'never execute'");
    }
}
