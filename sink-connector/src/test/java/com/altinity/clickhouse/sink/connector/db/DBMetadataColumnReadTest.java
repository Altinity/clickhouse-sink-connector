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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads of system.columns through {@link DBMetadata}: a read that cannot be
 * completed is raised, never returned as an empty column map, and every
 * statement and result set it opens is closed.
 */
public class DBMetadataColumnReadTest {

    /** A connection whose every query is refused, as ClickHouse does under load. */
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
                case "prepareStatement":
                    throw new SQLException("Code: 202. DB::Exception: Too many simultaneous queries for user.");
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /**
     * A connection that answers system.columns with the given rows (name, type,
     * default_kind), counting queries and how often each statement and result
     * set is closed.
     */
    private static final class CountingConnection {
        final List<String[]> rows = new ArrayList<>();
        /** What system.tables reports for the table: 0 means the table does not exist. */
        long tablesCount;
        int queries;
        int statementsClosed;
        int resultSetsClosed;

        CountingConnection(String[]... rows) {
            for (String[] row : rows) {
                this.rows.add(row);
            }
        }

        /** A result set with one row holding the system.tables count. */
        private ResultSet tablesResultSet() {
            final boolean[] advanced = {false};
            final long count = tablesCount;
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        boolean hasRow = !advanced[0];
                        advanced[0] = true;
                        return hasRow;
                    case "getLong":
                        return count;
                    case "getInt":
                        return (int) count;
                    case "close":
                        resultSetsClosed++;
                        return null;
                    default:
                        return null;
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        /** A result set over the rows, positioned before the first one. */
        private ResultSet resultSet() {
            final int[] cursor = {-1};
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    case "getString":
                        String[] row = rows.get(cursor[0]);
                        if (args[0] instanceof Integer) {
                            return row[(Integer) args[0] - 1];
                        }
                        switch ((String) args[0]) {
                            case "name":
                                return row[0];
                            case "type":
                                return row[1];
                            default:
                                return row[2];
                        }
                    case "close":
                        resultSetsClosed++;
                        return null;
                    default:
                        return null;
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        Connection connection() {
            InvocationHandler statementHandler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        queries++;
                        if (((String) args[0]).contains("system.tables")) {
                            return tablesResultSet();
                        }
                        return resultSet();
                    case "close":
                        statementsClosed++;
                        return null;
                    default:
                        return null;
                }
            };
            final Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, statementHandler);
            InvocationHandler connectionHandler = (proxy, method, args) -> {
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
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, connectionHandler);
        }
    }

    private static DBMetadata metadata() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new DBMetadata(new ClickHouseSinkConnectorConfig(props));
    }

    @Test
    @DisplayName("An unreadable column list is raised, not returned as an empty map")
    public void unreadableColumnListIsRaisedNotReturnedAsEmpty() {
        assertThrows(ColumnMetadataUnavailableException.class,
                () -> metadata().getColumnsDataTypesForTable("t", refusingConnection(), "db"),
                "an empty result would make every source column look absent");
    }

    @Test
    @DisplayName("An unreadable column list is raised through the (connection, table, database) overload too")
    public void unreadableColumnListIsRaisedThroughTheOtherOverload() {
        assertThrows(ColumnMetadataUnavailableException.class,
                () -> metadata().getColumnsDataTypesForTable(refusingConnection(), "t", "db"));
    }

    @Test
    @DisplayName("A table that system.tables does not list is an empty map, not an error")
    public void absentTableIsAnEmptyMap() {
        CountingConnection ch = new CountingConnection();
        Map<String, String> columns = metadata().getColumnsDataTypesForTable("t", ch.connection(), "db");
        assertTrue(columns.isEmpty());
    }

    @Test
    @DisplayName("An empty listing for a table that system.tables lists is raised, not returned as an empty map")
    public void emptyListingForAnExistingTableIsRaised() {
        // A row policy on system.columns hides every row, so the listing succeeds with no rows while the table exists.
        CountingConnection ch = new CountingConnection();
        ch.tablesCount = 1;
        assertThrows(ColumnMetadataUnavailableException.class,
                () -> metadata().getColumnsDataTypesForTable("t", ch.connection(), "db"),
                "an empty column map would make the writer build with no columns");
    }

    @Test
    @DisplayName("An empty listing for a table that system.tables lists is raised through the (connection, table, database) overload too")
    public void emptyListingForAnExistingTableIsRaisedThroughTheOtherOverload() {
        CountingConnection ch = new CountingConnection();
        ch.tablesCount = 1;
        assertThrows(ColumnMetadataUnavailableException.class,
                () -> metadata().getColumnsDataTypesForTable(ch.connection(), "t", "db"),
                "an empty column map would make the writer build with no columns");
    }

    @Test
    @DisplayName("A successful read closes its statement and result set")
    public void successfulReadClosesItsStatementAndResultSet() {
        CountingConnection ch = new CountingConnection(
                new String[]{"id", "Int32", ""},
                new String[]{"note", "Nullable(String)", "ALIAS"},
                new String[]{"name", "String", ""});
        Map<String, String> columns = metadata().getColumnsDataTypesForTable("t", ch.connection(), "db");
        assertEquals(2, columns.size());
        assertFalse(columns.containsKey("note"), "ALIAS columns are not bound");
        assertEquals(1, ch.statementsClosed, "statement must be closed");
        assertEquals(1, ch.resultSetsClosed, "result set must be closed");
    }

    @Test
    @DisplayName("Alias and materialized columns are excluded by one listing, not a second query")
    public void aliasColumnsNeedNoSecondQuery() {
        CountingConnection ch = new CountingConnection(
                new String[]{"id", "Int32", ""},
                new String[]{"total", "Int64", "MATERIALIZED"});
        Map<String, String> columns = metadata().getColumnsDataTypesForTable("t", ch.connection(), "db");
        assertEquals(1, columns.size());
        assertEquals(1, ch.queries, "one system.columns query per read");
    }

    @Test
    @DisplayName("The alias helper raises when its read cannot complete")
    public void aliasHelperRaisesWhenUnreadable() {
        assertThrows(ColumnMetadataUnavailableException.class,
                () -> metadata().getAliasAndMaterializedColumnsForTableAndDatabase("t", "db",
                        refusingConnection()));
    }

    @Test
    @DisplayName("The alias helper returns the alias names it reads")
    public void aliasHelperReturnsAliasNames() throws SQLException {
        // The fake does not evaluate SQL: it answers with the rows the default_kind filter would return.
        CountingConnection ch = new CountingConnection(
                new String[]{"note", "Nullable(String)", "ALIAS"});
        Set<String> aliases = metadata().getAliasAndMaterializedColumnsForTableAndDatabase("t", "db",
                ch.connection());
        assertEquals(Set.of("note"), aliases);
        assertEquals(1, ch.statementsClosed);
    }
}
