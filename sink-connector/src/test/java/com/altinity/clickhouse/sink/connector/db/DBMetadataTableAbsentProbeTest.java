package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.07 section 3.2: {@link DBMetadata#isTableAbsent} answers three
 * values, never two.
 *
 * <p>An unresolved {@code DbWriter} is produced by three different states --
 * the table does not exist, the connection was never established, and the
 * catalog read threw -- and only the first is permanent. A boolean probe
 * collapses the last two into "the table exists", which would leave a
 * dropped table retried forever (this incident), and a probe that reports
 * absence for an unreadable catalog would stop a connector on a table that
 * is there. So {@code null} means "could not answer" and must stay distinct
 * from {@code FALSE}.</p>
 */
public class DBMetadataTableAbsentProbeTest {

    /** Every query this fake was asked to run, in order. */
    private final List<String> queries = new ArrayList<>();

    /** A connection that refuses every query with a retriable ClickHouse code. */
    private static Connection refusingConnection() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "isValid":
                    return true;
                case "toString":
                    return "RefusingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "createStatement":
                    throw new SQLException("Code: 202. DB::Exception: Too many simultaneous queries for user.");
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /**
     * A connection that answers the {@code system.tables} count query with
     * {@code count} and records the SQL it was asked to run.
     */
    private Connection countingConnection(long count) {
        // getLong is only meaningful after next() has returned a row; drive
        // the cursor from a single boolean so the fake reads like a one-row
        // result set.
        final boolean[] advanced = {false};
        InvocationHandler rsHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    boolean hasRow = !advanced[0];
                    advanced[0] = true;
                    return hasRow;
                case "getLong":
                case "getInt":
                    return count;
                case "close":
                    return null;
                default:
                    return null;
            }
        };
        final Statement statement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery":
                            queries.add((String) args[0]);
                            return (ResultSet) Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[]{ResultSet.class}, rsHandler);
                        case "close":
                            return null;
                        default:
                            return null;
                    }
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createStatement":
                            return statement;
                        case "isClosed":
                            return false;
                        case "isValid":
                            return true;
                        case "toString":
                            return "CountingConnection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private static DBMetadata metadata() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new DBMetadata(new ClickHouseSinkConnectorConfig(props));
    }

    @Test
    @DisplayName("A table the catalog does not list is positively absent: TRUE")
    public void absentTableIsTrue() {
        assertEquals(Boolean.TRUE,
                metadata().isTableAbsent(countingConnection(0), "trade_staging", "bond_accrual_fx"),
                "a count of 0 from system.tables is the catalog saying the table is not there");
    }

    @Test
    @DisplayName("A table the catalog lists is not absent: FALSE")
    public void presentTableIsFalse() {
        assertEquals(Boolean.FALSE,
                metadata().isTableAbsent(countingConnection(1), "trade_staging", "bond_accrual_fx"),
                "a present table must never be reported absent -- that stops the connector");
    }

    @Test
    @DisplayName("No connection is 'could not answer' (null), never 'absent'")
    public void noConnectionIsUnknown() {
        assertNull(metadata().isTableAbsent(null, "trade_staging", "bond_accrual_fx"),
                "an unreachable server says nothing about whether the table exists");
    }

    @Test
    @DisplayName("A query that throws is 'could not answer' (null), never 'absent'")
    public void unreadableCatalogIsUnknown() {
        assertNull(metadata().isTableAbsent(refusingConnection(), "trade_staging", "bond_accrual_fx"),
                "a catalog read that failed must not be reported as a dropped table");
    }

    @Test
    @DisplayName("The probe asks the catalog about this one database and table")
    public void probeNamesTheTableItAsksAbout() {
        DBMetadata metadata = metadata();
        metadata.isTableAbsent(countingConnection(0), "trade_staging", "bond_accrual_fx");
        assertEquals(1, queries.size(), "one query per probe");
        String sql = queries.get(0);
        assertTrue(sql.contains("system.tables"), sql);
        assertTrue(sql.contains("trade_staging"), sql);
        assertTrue(sql.contains("bond_accrual_fx"), sql);
    }

    @Test
    @DisplayName("A probe that answered is not cached against a later, different answer")
    public void probeIsNotCached() {
        DBMetadata metadata = metadata();
        assertTrue(metadata.isTableAbsent(countingConnection(0), "db", "t"));
        // The table was recreated: the very next probe must see it.
        assertFalse(metadata.isTableAbsent(countingConnection(1), "db", "t"));
    }
}
