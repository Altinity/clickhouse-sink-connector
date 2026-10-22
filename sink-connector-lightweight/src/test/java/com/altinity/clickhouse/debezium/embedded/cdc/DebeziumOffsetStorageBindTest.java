package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offset table's {@code record_insert_ts} is a zone-less DateTime (spec 07.03 section 3.1.2), so a bind of a
 * {@link java.sql.Timestamp} without an explicit Calendar renders the instant in the JVM's default zone. The bind is
 * epoch seconds instead: ClickHouse reads an integer into DateTime as an instant, with no zone involved.
 *
 * <p>spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumOffsetStorage.java::updateDebeziumStorageRow</p>
 */
public class DebeziumOffsetStorageBindTest {

    /** Records every bind on the one prepared statement the method creates. */
    private static final class Recorder {
        final List<String> calls = new ArrayList<>();
        final PreparedStatement statement;
        final Connection connection;

        Recorder() {
            statement = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("set")) {
                            calls.add(method.getName() + "/" + args.length + ":" + (args.length > 1 ? args[1] : ""));
                        }
                        if (method.getName().equals("executeUpdate")) {
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    });
            connection = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> method.getName().equals("prepareStatement")
                            ? statement
                            : defaultValue(method.getReturnType()));
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
    }

    @Test
    @DisplayName("The record_insert_ts bind carries no JVM-zone Timestamp: setTimestamp is not called")
    public void recordInsertTsIsNotBoundAsTimestamp() throws Exception {
        Recorder recorder = new Recorder();
        new DebeziumOffsetStorage().updateDebeziumStorageRow(recorder.connection, "offsets", "key", "{}",
                1_700_000_000_123L);
        for (String call : recorder.calls) {
            assertTrue(!call.startsWith("setTimestamp"), "unexpected bind: " + call + " in " + recorder.calls);
        }
    }

    @Test
    @DisplayName("The record_insert_ts bind is the epoch second of the instant, truncated as DateTime is")
    public void recordInsertTsIsEpochSeconds() throws Exception {
        Recorder recorder = new Recorder();
        new DebeziumOffsetStorage().updateDebeziumStorageRow(recorder.connection, "offsets", "key", "{}",
                1_700_000_000_123L);
        assertEquals("setLong/2:1700000000", recorder.calls.get(3), recorder.calls.toString());
    }
}
