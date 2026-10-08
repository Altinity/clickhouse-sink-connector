package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.04 section 3.9: {@code addMissingColumns} must attempt every
 * missing column (one bad column must not block the others) but must no
 * longer be silent about a failure afterward. A column that cannot be added
 * means a row carrying it would otherwise be written to ClickHouse without
 * that value -- divergence, not a condition to log and continue past.
 *
 * <p>Against the pre-fix code this fails: every {@code ALTER TABLE} failure
 * was caught, logged at WARN/ERROR, and the method returned normally, so the
 * caller ({@code PostgresSchemaChangeDetector.checkAndReconcile}) believed
 * reconciliation had succeeded and let the row through.</p>
 */
public class PostgresSchemaReconcilerAddColumnFailureTest {

    private static final String CLICKHOUSE_REJECTION =
            "Code: 62. DB::Exception: Syntax error: failed at position 1";

    /**
     * A JDBC connection that reports itself open and rejects every ALTER
     * TABLE statement, counting how many distinct statements were attempted.
     */
    private static Connection rejectingConnection(AtomicInteger attempts) {
        InvocationHandler statementHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "execute":
                    attempts.incrementAndGet();
                    throw new SQLException(CLICKHOUSE_REJECTION);
                case "close":
                    return null;
                default:
                    return null;
            }
        };
        InvocationHandler connHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "prepareStatement":
                    return Proxy.newProxyInstance(
                            java.sql.PreparedStatement.class.getClassLoader(),
                            new Class<?>[]{java.sql.PreparedStatement.class}, statementHandler);
                case "createStatement":
                    return Proxy.newProxyInstance(
                            java.sql.Statement.class.getClassLoader(),
                            new Class<?>[]{java.sql.Statement.class}, statementHandler);
                case "toString":
                    return "rejecting-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connHandler);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("10.04 s3.9: a failed ALTER attempts every column, then throws naming all that failed")
    public void failedAlterThrowsAfterAttemptingAllColumns() {
        AtomicInteger attempts = new AtomicInteger(0);
        BaseDbWriter writer = new BaseDbWriter("localhost", 8123, "system", "default", "",
                config(), rejectingConnection(attempts));
        PostgresSchemaReconciler reconciler = new PostgresSchemaReconciler(writer, config());

        Map<String, Schema> missingColumns = new LinkedHashMap<>();
        missingColumns.put("col_a", Schema.OPTIONAL_STRING_SCHEMA);
        missingColumns.put("col_b", Schema.OPTIONAL_INT32_SCHEMA);
        missingColumns.put("col_c", Schema.OPTIONAL_INT64_SCHEMA);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> reconciler.addMissingColumns("appdb", "orders", missingColumns),
                "addMissingColumns must throw once every column has been attempted and all failed");

        assertEquals(3, attempts.get(),
                "every column must be attempted even though the connection rejects all of them "
                        + "(one bad column must not block the others)");
        assertTrue(thrown.getMessage().contains("col_a") && thrown.getMessage().contains("col_b")
                        && thrown.getMessage().contains("col_c"),
                "the failure must name every column that could not be added: " + thrown.getMessage());
        assertTrue(thrown.getCause() instanceof SQLException,
                "the first ClickHouse rejection must be preserved as the cause");
    }
}
