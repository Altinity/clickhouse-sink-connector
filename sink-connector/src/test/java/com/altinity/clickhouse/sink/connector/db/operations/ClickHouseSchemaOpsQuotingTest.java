package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable.ALTER_TABLE_OPERATION;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.05 section 3.4 and spec 04.07: identifiers in generated DDL are backtick-quoted (table in
 * {@code ALTER TABLE}, key columns in {@code PRIMARY KEY} / {@code ORDER BY}, the database in
 * {@code CREATE DATABASE}), {@code ALTER_TABLE_DELETE_COLUMN} is the valid {@code drop column} (with no data type
 * after the name), and the missing-column test in {@code alterTable} is case-insensitive like the column refresh.
 * No database is contacted: a recording {@link Connection} double captures the SQL.
 */
public class ClickHouseSchemaOpsQuotingTest {

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>()));
    }

    /** A Connection whose prepareStatement records the SQL; a non-null failure makes execute() throw it. */
    private static Connection recordingConnection(List<String> sql, SQLException failure) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        sql.add((String) args[0]);
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                    if ("execute".equals(m.getName())) {
                                        if (failure != null) {
                                            throw failure;
                                        }
                                        return false;
                                    }
                                    if ("close".equals(m.getName())) {
                                        return null;
                                    }
                                    if ("toString".equals(m.getName())) {
                                        return "RecordingPreparedStatement";
                                    }
                                    return null;
                                });
                    }
                    switch (method.getName()) {
                        case "isClosed":
                            return false;
                        case "toString":
                            return "RecordingConnection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private static Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("order", "String");
        columns.put("id", "Int32");
        return columns;
    }

    // DESTRUCTIVE: unit-test constant/syntax assertions only; no database or table is modified.
    @Test
    @DisplayName("ALTER_TABLE_DELETE_COLUMN is the valid ClickHouse 'drop column'")
    void deleteColumnConstantIsDropColumn() {
        assertEquals("drop column", ClickHouseDbConstants.ALTER_TABLE_DELETE_COLUMN);
    }

    @Test
    @DisplayName("ADD quotes the table and every column and keeps the data type")
    void addQuotesTableAndColumns() {
        String sql = new ClickHouseAlterTable().createAlterTableSyntax("select", columns(), ALTER_TABLE_OPERATION.ADD);

        assertEquals("ALTER TABLE `select` add column `order` String,add column `id` Int32", sql);
    }

    // DESTRUCTIVE: unit-test SQL syntax assertion only; no database or table is modified.
    @Test
    @DisplayName("REMOVE emits 'drop column `col`' with no data type, which ClickHouse would reject")
    void removeEmitsValidDropColumn() {
        String sql = new ClickHouseAlterTable().createAlterTableSyntax("select", columns(), ALTER_TABLE_OPERATION.REMOVE);

        // DESTRUCTIVE: unit-test SQL syntax assertion only; no database or table is modified.
        assertEquals("ALTER TABLE `select` drop column `order`,drop column `id`", sql);
        assertFalse(sql.contains("delete column"), sql);
        assertFalse(sql.contains("String"), "a data type after DROP COLUMN is a syntax error: " + sql);
    }

    @Test
    @DisplayName("a record column that differs from a ClickHouse column only in case is not added again")
    void caseInsensitiveColumnComparison() throws SQLException {
        List<String> sql = new ArrayList<>();
        Map<String, String> existing = new HashMap<>();
        existing.put("customername", "String");
        List<Field> fields = List.of(new Field("CustomerName", 0, Schema.STRING_SCHEMA));

        new ClickHouseAlterTable().alterTable(fields, "t", recordingConnection(sql, null), existing, config());

        assertTrue(sql.isEmpty(), "ADD COLUMN for a case variant would duplicate the column: " + sql);
    }

    @Test
    @DisplayName("a genuinely missing column is added with a quoted table and column")
    void missingColumnIsAddedQuoted() throws SQLException {
        List<String> sql = new ArrayList<>();
        Map<String, String> existing = new HashMap<>();
        existing.put("id", "Int32");
        List<Field> fields = List.of(
                new Field("ID", 0, Schema.INT32_SCHEMA),
                new Field("new_col", 1, Schema.STRING_SCHEMA));

        new ClickHouseAlterTable().alterTable(fields, "t", recordingConnection(sql, null), existing, config());

        assertEquals(1, sql.size(), sql.toString());
        assertTrue(sql.get(0).startsWith("ALTER TABLE `t` add column `new_col` "), sql.get(0));
        assertFalse(sql.get(0).contains("`ID`"), "the case variant of an existing column is not re-added: " + sql);
    }

    @Test
    @DisplayName("a failed ALTER is surfaced to the caller instead of being reported as done")
    void failedAlterIsSurfaced() {
        List<String> sql = new ArrayList<>();
        SQLException denied = new SQLException("Code: 497. DB::Exception: Not enough privileges");
        Map<String, String> existing = new HashMap<>();
        existing.put("id", "Int32");
        List<Field> fields = List.of(new Field("new_col", 0, Schema.STRING_SCHEMA));

        assertThrows(SQLException.class, () -> new ClickHouseAlterTable()
                .alterTable(fields, "t", recordingConnection(sql, denied), existing, config()));
    }

    @Test
    @DisplayName("PRIMARY KEY and ORDER BY quote every key column")
    void primaryKeyAndOrderByAreQuoted() {
        ArrayList<String> primaryKey = new ArrayList<>(List.of("order", "id"));
        Field[] fields = {
                new Field("order", 0, Schema.STRING_SCHEMA),
                new Field("id", 1, Schema.INT32_SCHEMA)};

        String ddl = new ClickHouseAutoCreateTable().createTableSyntax(primaryKey, "t", "db",
                fields, columns(), true, false, null, config());

        assertTrue(ddl.contains("PRIMARY KEY(`order`,`id`) ORDER BY(`order`,`id`)"), ddl);
    }

    @Test
    @DisplayName("CREATE DATABASE IF NOT EXISTS quotes the database name")
    void createDatabaseQuotesName() throws SQLException {
        List<String> sql = new ArrayList<>();

        new ClickHouseAutoCreateTable().createHistoryDatabase("my-history.db",
                recordingConnection(sql, null), config());

        assertEquals(List.of("CREATE DATABASE IF NOT EXISTS `my-history.db`"), sql);
    }
}
