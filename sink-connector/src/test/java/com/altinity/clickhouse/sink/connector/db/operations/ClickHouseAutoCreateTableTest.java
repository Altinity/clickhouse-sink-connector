package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Testcontainers
public class ClickHouseAutoCreateTableTest extends com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTableBase {

    private Map<String, String> columnToDataTypesMap;
    private ClickHouseSinkConnectorConfig config;

    @BeforeEach
    public void setUp() {
        columnToDataTypesMap = getExpectedColumnToDataTypesMap();
        config = new ClickHouseSinkConnectorConfig(new HashMap<>());
    }


    @Test
    public void testCreateTableSyntax() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", "employees",
                createFields(), this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));
        System.out.println("QUERY" + query);
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE `employees`.`auto_create_table`(`customerName` String,`occupation` String,`quantity` Int32,`amount_1` Float32,`amount` Float64,`employed` Bool,`blob_storage` String,`blob_storage_scale` Decimal,`json_output` JSON,`max_amount` Float64,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY(customerName)"));
    }

    /** Spec 08.05 section 3.2: the all-columns key emitted for the shared test fields (none nullable). */
    private static final String ALL_COLUMNS_ORDER_BY =
            "ORDER BY(`customerName`,`occupation`,`quantity`,`amount_1`,`amount`,`employed`,"
                    + "`blob_storage`,`blob_storage_scale`,`json_output`,`max_amount`)";

    @Test
    public void testCreateTableEmptyPrimaryKey() {

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(null, "auto_create_table", "employees", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        String expectedQuery = "CREATE TABLE `employees`.`auto_create_table`(`customerName` String,`occupation` String,`quantity` Int32,`amount_1` Float32,`amount` Float64,`employed` Bool,`blob_storage` String,`blob_storage_scale` Decimal,`json_output` JSON,`max_amount` Float64,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) " + ALL_COLUMNS_ORDER_BY;
        Assert.assertTrue(query, query.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testCreateTableMultiplePrimaryKeys() {
        // Key columns that are NOT in the column map: the record cannot supply
        // the declared key, so the keyless fallback applies.
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");
        primaryKeys.add("customer_name");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", "customers", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        String expectedQuery = "CREATE TABLE `customers`.`auto_create_table`(`customerName` String,`occupation` String,`quantity` Int32,`amount_1` Float32,`amount` Float64,`employed` Bool,`blob_storage` String,`blob_storage_scale` Decimal,`json_output` JSON,`max_amount` Float64,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) " + ALL_COLUMNS_ORDER_BY;
        Assert.assertTrue(query, query.equalsIgnoreCase(expectedQuery));
        System.out.println(query);
    }

    private static Field[] keylessFields(boolean bOptional) {
        return new Field[]{
                new Field("a", 0, Schema.STRING_SCHEMA),
                new Field("b", 1, bOptional ? Schema.OPTIONAL_INT32_SCHEMA : Schema.INT32_SCHEMA),
        };
    }

    private static Map<String, String> keylessColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("a", ClickHouseDataType.String.name());
        m.put("b", ClickHouseDataType.Int32.name());
        return m;
    }

    /**
     * Spec 08.05 section 3.2: a source table without a primary key must never be
     * created with {@code ORDER BY tuple()} on ReplacingMergeTree -- every row
     * then shares the empty sorting key and merges collapse the whole table
     * to one row. The sorting key is every source column instead, and the
     * keyless banner is logged.
     */
    @Test
    public void testKeylessTableOrdersByAllColumns() {
        String query = new ClickHouseAutoCreateTable().createTableSyntax(null, "audit", "db",
                keylessFields(false), keylessColumns(), true, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertFalse(
                "ORDER BY tuple() collapses every row of a ReplacingMergeTree into one: " + query,
                query.contains("ORDER BY tuple()"));
        Assert.assertTrue("the sorting key must name every source column: " + query,
                query.contains("ORDER BY(`a`,`b`)"));
        Assert.assertFalse("no PRIMARY KEY clause is emitted for the fallback key: " + query,
                query.contains("PRIMARY KEY"));
        Assert.assertFalse("no nullable column, so allow_nullable_key must not be emitted: " + query,
                query.contains("allow_nullable_key"));
        Assert.assertTrue("engine columns stay out of the key: " + query,
                query.contains("Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY(`a`,`b`)"));
    }

    /** Spec 08.05 section 3.2.1: a Nullable column in the fallback key needs allow_nullable_key=1. */
    @Test
    public void testKeylessTableWithNullableColumnEnablesNullableKey() {
        String query = new ClickHouseAutoCreateTable().createTableSyntax(null, "audit", "db",
                keylessFields(true), keylessColumns(), true, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue(query, query.contains("`b` Nullable(Int32)"));
        Assert.assertTrue(query, query.contains("ORDER BY(`a`,`b`)"));
        Assert.assertTrue("a nullable sorting key is rejected by ClickHouse (Code 44) without the setting: " + query,
                query.endsWith(" SETTINGS allow_nullable_key=1"));
    }

    /** Spec 08.05 section 3.2.3: user settings are kept and allow_nullable_key appended once. */
    @Test
    public void testKeylessTableMergesUserSettings() {
        Map<String, String> props = new HashMap<>();
        props.put("databases.db.tables.audit.settings", "index_granularity=4096");
        String query = new ClickHouseAutoCreateTable().createTableSyntax(null, "audit", "db",
                keylessFields(true), keylessColumns(), true, false, null,
                new ClickHouseSinkConnectorConfig(props));

        Assert.assertTrue(query, query.endsWith(" SETTINGS index_granularity=4096,allow_nullable_key=1"));

        Map<String, String> already = new HashMap<>();
        already.put("databases.db.tables.audit.settings", "allow_nullable_key=1");
        String query2 = new ClickHouseAutoCreateTable().createTableSyntax(null, "audit", "db",
                keylessFields(true), keylessColumns(), true, false, null,
                new ClickHouseSinkConnectorConfig(already));
        Assert.assertTrue(query2, query2.endsWith(" SETTINGS allow_nullable_key=1"));
        Assert.assertEquals("the setting must not be duplicated: " + query2,
                query2.indexOf("allow_nullable_key"), query2.lastIndexOf("allow_nullable_key"));
    }

    /** The PK path is unchanged and never gets allow_nullable_key. */
    @Test
    public void testPrimaryKeyPathDoesNotEnableNullableKey() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("a");
        String query = new ClickHouseAutoCreateTable().createTableSyntax(primaryKeys, "audit", "db",
                keylessFields(true), keylessColumns(), true, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue(query, query.contains("PRIMARY KEY(a) ORDER BY(a)"));
        Assert.assertFalse(query, query.contains("allow_nullable_key"));
    }


    /**
     * Spec 08.05 section 3.1.1: a source table with its own {@code is_deleted}
     * column keeps it as a source column, and the engine column is renamed
     * {@code _is_deleted} (as the DDL translator does) instead of emitting the
     * name twice, which ClickHouse rejects.
     */
    @Test
    public void testSourceIsDeletedColumnRenamesEngineColumn() {
        Field[] fields = new Field[]{
                new Field("id", 0, Schema.INT32_SCHEMA),
                new Field("is_deleted", 1, Schema.OPTIONAL_INT16_SCHEMA),
        };
        Map<String, String> types = new ClickHouseTableOperationsBase()
                .getColumnNameToCHDataTypeMapping(fields, new ClickHouseSinkConnectorConfig(new HashMap<>()));
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");

        String query = new ClickHouseAutoCreateTable().createTableSyntax(primaryKey, "flags", "db",
                fields, types, true, false, null, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue("the source column keeps its name and nullability: " + query,
                query.contains("`is_deleted` Nullable(Int16)"));
        Assert.assertTrue("the engine column is renamed: " + query, query.contains("`_is_deleted` UInt8"));
        Assert.assertTrue(query, query.contains("Engine=ReplacingMergeTree(_version,_is_deleted)"));
        Assert.assertEquals("is_deleted must be declared exactly once as a column: " + query,
                1, query.split("`is_deleted`", -1).length - 1);
    }

    @Test
    public void testIsPrimaryKeyColumnPresent()    {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");
        primaryKeys.add("id");

        ArrayList<String> primaryKeys2 = new ArrayList<>();
        primaryKeys2.add("customerName2");
        primaryKeys2.add("id2");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        Map<String, String> columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("customerName", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("occupation", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("quantity", ClickHouseDataType.Int32.name());
        columnToDataTypesMap.put("amount_1", ClickHouseDataType.Float32.name());
        columnToDataTypesMap.put("id", ClickHouseDataType.Int8.name());

        Assert.assertTrue(act.isPrimaryKeyColumnPresent(primaryKeys, columnToDataTypesMap));
        Assert.assertFalse(act.isPrimaryKeyColumnPresent(primaryKeys2, columnToDataTypesMap));
    }

}
