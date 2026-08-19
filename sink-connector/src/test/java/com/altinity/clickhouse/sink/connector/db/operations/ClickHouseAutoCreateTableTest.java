package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;

import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
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
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE employees.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY(customerName)"));
        //Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY (customerName)"));
    }

    @Test
    public void testCreateTableEmptyPrimaryKey() {

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(null, "auto_create_table", "employees", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        // With no primary key the sorting key must still distinguish rows.
        // ORDER BY tuple() gives every row the same (empty) sorting key, so a
        // ReplacingMergeTree treats the whole table as one deduplication group
        // and collapses it to a single row.
        String expectedQuery = "CREATE TABLE employees.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY(`customerName`,`occupation`,`quantity`,`amount_1`,`amount`,`employed`,`blob_storage`,`blob_storage_scale`,`json_output`,`max_amount`)";
        Assert.assertEquals(expectedQuery, query);
    }

    /**
     * A source table with no PRIMARY KEY must not be created with an empty
     * sorting key: ReplacingMergeTree deduplicates on the sorting key, so
     * ORDER BY tuple() puts every row in one deduplication group and silently
     * collapses the table to a single row.
     */
    @Test
    public void testNoPrimaryKeyDoesNotUseEmptySortingKey() {
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(null, "no_pk_table", "employees", createFields(),
                this.columnToDataTypesMap, false, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertFalse(
                "ORDER BY tuple() collapses a ReplacingMergeTree to a single row",
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("expected a non-empty sorting key",
                query.contains("ORDER BY(`customerName`"));
    }

    /**
     * The connector's own metadata columns must never appear in the fallback
     * sorting key. {@code _version} in particular changes on every write, so
     * including it would give each version of a row a distinct sorting key and
     * defeat deduplication entirely.
     */
    @Test
    public void testFallbackSortingKeyExcludesMetadataColumns() {
        org.apache.kafka.connect.data.Field[] fields = {
                new org.apache.kafka.connect.data.Field("uk", 0,
                        org.apache.kafka.connect.data.Schema.INT32_SCHEMA),
                new org.apache.kafka.connect.data.Field("payload", 1,
                        org.apache.kafka.connect.data.Schema.STRING_SCHEMA),
                new org.apache.kafka.connect.data.Field("_version", 2,
                        org.apache.kafka.connect.data.Schema.INT64_SCHEMA),
                new org.apache.kafka.connect.data.Field("is_deleted", 3,
                        org.apache.kafka.connect.data.Schema.INT8_SCHEMA),
                new org.apache.kafka.connect.data.Field("_sign", 4,
                        org.apache.kafka.connect.data.Schema.INT8_SCHEMA),
                new org.apache.kafka.connect.data.Field("_valid_to", 5,
                        org.apache.kafka.connect.data.Schema.STRING_SCHEMA),
        };

        Assert.assertEquals("`uk`,`payload`",
                ClickHouseAutoCreateTable.orderByAllDataColumns(fields));
    }

    /**
     * The fallback sorting key must follow the source schema's declaration
     * order, which is stable, rather than the iteration order of the
     * column-to-data-type HashMap, which is not. An unstable sorting key would
     * differ between connector restarts.
     */
    @Test
    public void testFallbackSortingKeyIsInDeclarationOrder() {
        String first = ClickHouseAutoCreateTable.orderByAllDataColumns(createFields());
        for (int i = 0; i < 20; i++) {
            Assert.assertEquals("sorting key must be deterministic",
                    first, ClickHouseAutoCreateTable.orderByAllDataColumns(createFields()));
        }
        Assert.assertTrue("expected source declaration order",
                first.startsWith("`customerName`,`occupation`,`quantity`"));
    }

    /**
     * Degenerate input must fall back to the previous behaviour rather than
     * emitting an empty {@code ORDER BY()} clause, which is a syntax error.
     */
    @Test
    public void testFallbackSortingKeyWithOnlyMetadataColumns() {
        org.apache.kafka.connect.data.Field[] onlyMetadata = {
                new org.apache.kafka.connect.data.Field("_version", 0,
                        org.apache.kafka.connect.data.Schema.INT64_SCHEMA),
                new org.apache.kafka.connect.data.Field("is_deleted", 1,
                        org.apache.kafka.connect.data.Schema.INT8_SCHEMA),
        };

        Assert.assertEquals("", ClickHouseAutoCreateTable.orderByAllDataColumns(onlyMetadata));
        Assert.assertEquals("", ClickHouseAutoCreateTable.orderByAllDataColumns(null));
        Assert.assertEquals("", ClickHouseAutoCreateTable.orderByAllDataColumns(
                new org.apache.kafka.connect.data.Field[0]));
    }

    @Test
    public void testCreateTableMultiplePrimaryKeys() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");
        primaryKeys.add("customer_name");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", "customers", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        // customer_id / customer_name are not columns of this schema, so the
        // declared primary key is unusable and the fallback sorting key
        // applies — previously ORDER BY tuple(), which collapses the table.
        String expectedQuery = "CREATE TABLE customers.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY(`customerName`,`occupation`,`quantity`,`amount_1`,`amount`,`employed`,`blob_storage`,`blob_storage_scale`,`json_output`,`max_amount`)";
        Assert.assertEquals(expectedQuery, query);
        System.out.println(query);
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
