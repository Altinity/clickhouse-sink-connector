package com.altinity.clickhouse.sink.connector.converters;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ClickHouseAutoCreateTableTest {

    Map<String, String> columnToDataTypesMap;

    @Before
    public void initialize() {
        this.columnToDataTypesMap = new HashMap<>();

        this.columnToDataTypesMap.put("address", "String");
        this.columnToDataTypesMap.put("first_name", "String");
        this.columnToDataTypesMap.put("amount", "UINT32");

    }
    @Test
    public void testCreateTableSyntax() {
        String primaryKey = "customer_id";

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKey, this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE(`amount` UINT32,`address` String,`first_name` String) ENGINE = MergeTree PRIMARY KEY customer_id ORDER BY customer_id";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }
}
