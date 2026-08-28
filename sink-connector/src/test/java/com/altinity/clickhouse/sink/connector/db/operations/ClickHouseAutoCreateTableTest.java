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
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE `employees`.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY(customerName)"));
        //Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY (customerName)"));
    }

    @Test
    public void testCreateTableEmptyPrimaryKey() {

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(null, "auto_create_table", "employees", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        String expectedQuery = "CREATE TABLE `employees`.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY tuple()";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testCreateTableMultiplePrimaryKeys() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");
        primaryKeys.add("customer_name");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", "customers", createFields(),
                this.columnToDataTypesMap, false, false, null,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        String expectedQuery = "CREATE TABLE `customers`.`auto_create_table`(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY tuple()";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
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
