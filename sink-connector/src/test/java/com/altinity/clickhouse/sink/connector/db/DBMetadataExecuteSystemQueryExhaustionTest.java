package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 06.08 section 6, FM-06.08-2: {@code executeSystemQuery} is the call
 * every translated DDL statement goes through ({@code executeDDL}). A
 * statement ClickHouse keeps refusing must reach the caller as an
 * exception; returning normally makes the caller treat the DDL as applied.
 */
public class DBMetadataExecuteSystemQueryExhaustionTest {

    private static final String KEY_ALTER = "ALTER TABLE db.t MODIFY COLUMN id Int64";
    private static final String KEY_REFUSAL = "Code: 524. DB::Exception: ALTER of key column id is forbidden";

    @AfterEach
    public void restoreRetries() {
        DBMetadata.setMaxRetries(10);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static Connection refusing(String message, AtomicInteger attempts) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                case "createStatement":
                    attempts.incrementAndGet();
                    throw new SQLException(message);
                case "isClosed":
                    return false;
                case "toString":
                    return "refusing-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** A non-retryable refusal is thrown at once, without consuming the retry budget. */
    @Test
    @DisplayName("FM-06.08-1: a non-retryable refusal of a DDL is rethrown on the first attempt")
    public void nonRetryableRefusalIsRethrownAtOnce() {
        DBMetadata.setMaxRetries(3);
        AtomicInteger attempts = new AtomicInteger();
        SQLException e = assertThrows(SQLException.class, () -> new DBMetadata(config()).executeSystemQuery(
                refusing("Code: 62. DB::Exception: Syntax error", attempts), "ALTER TABLE db.t ADD COLUMN c Int32"));
        assertTrue(e.getMessage().contains("Code: 62"), e.getMessage());
        assertEquals(1, attempts.get());
    }

    /**
     * What 2.11.0 does today with a retryable refusal: every attempt is made,
     * then the method RETURNS NORMALLY (null). Pins the defect the disabled
     * test below rejects.
     */
    @Test
    @DisplayName("FM-06.08-2 current behaviour: a retryable refusal returns normally once the budget is spent")
    public void retryableRefusalReturnsNormallyToday() {
        DBMetadata.setMaxRetries(1);
        AtomicInteger attempts = new AtomicInteger();
        String[] result = new String[1];
        assertDoesNotThrow(() -> result[0] = new DBMetadata(config()).executeSystemQuery(
                refusing(KEY_REFUSAL, attempts), KEY_ALTER));
        assertNull(result[0]);
        assertEquals(1, attempts.get());
    }

    /**
     * FM-06.08-2 (DEFECT): after the last retry the refusal must be thrown,
     * carrying the ClickHouse error, as {@code truncateTable} and
     * {@code getPreparedStatement} already do (Spec 04.05).
     */
    @Test
    @Disabled("DEFECT FM-06.08-2: executeSystemQuery returns normally after exhausting retries on a retryable error")
    @DisplayName("FM-06.08-2: a retryable refusal is rethrown once the retry budget is spent")
    public void retryableRefusalIsRethrownAfterRetries() {
        DBMetadata.setMaxRetries(2);
        AtomicInteger attempts = new AtomicInteger();
        SQLException e = assertThrows(SQLException.class, () -> new DBMetadata(config()).executeSystemQuery(
                refusing(KEY_REFUSAL, attempts), KEY_ALTER));
        assertEquals(2, attempts.get());
        assertTrue(String.valueOf(e.getMessage()).contains("524")
                || (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("524")));
    }
}
