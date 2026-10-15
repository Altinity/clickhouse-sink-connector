package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 05.01 section 6, FM-05.01-1: a sorting-key read that FAILS is
 * indistinguishable from a table that HAS no sorting key.
 *
 * <p>{@code DBMetadata.getSortingKeyColumns} logs the failure and returns an
 * empty list. {@code DbWriter} stores that list for the writer's lifetime
 * (it is re-read only when the writer is rebuilt: a DDL through the
 * connector, or a restart), and {@code updateRelocatesSortingKey} treats an
 * empty key as "no relocation possible". One transient failure of the
 * {@code system.columns} query when a writer is built -- a timeout under
 * load, a connection reset -- therefore disables the relocation tombstone for
 * that table until the next restart: every UPDATE that moves a row to another
 * sorting key leaves a live ghost row at the old key, silently.</p>
 */
public class SortingKeyReadFailureTest {

    /** A connection whose every statement fails, as a timed-out or reset connection does. */
    private static Connection failingConnection() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createStatement":
                case "prepareStatement":
                    throw new SQLException("Code: 159. DB::Exception: Timeout exceeded: elapsed 30.000 seconds. "
                            + "(TIMEOUT_EXCEEDED)");
                case "isClosed":
                    return false;
                case "toString":
                    return "FailingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    @Test
    @Disabled("DEFECT FM-05.01-1: a failed sorting-key read returns an empty list, which DbWriter caches for its "
            + "lifetime and the executor reads as 'no sorting key' -- relocating UPDATEs then leave ghost rows")
    @DisplayName("A failed sorting-key read is reported as a failure, never as an empty sorting key")
    public void aFailedSortingKeyReadIsNotAnEmptySortingKey() {
        DBMetadata metadata = new DBMetadata(new ClickHouseSinkConnectorConfig(new HashMap<>()));
        assertThrows(Exception.class, () -> {
            List<String> key = metadata.getSortingKeyColumns(failingConnection(), "db", "t");
            // Reaching here means the failure was swallowed.
            throw new AssertionError("the read failed but returned " + key
                    + ", which the writer caches and reads as 'this table has no sorting key'");
        }, "the caller must be able to tell 'unknown' from 'empty' so the writer is not built on it");
    }
}
