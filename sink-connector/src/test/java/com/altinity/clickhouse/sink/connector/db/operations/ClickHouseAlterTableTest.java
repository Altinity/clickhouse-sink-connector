package com.altinity.clickhouse.sink.connector.db.operations;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseAlterTableTest extends com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTableTest {

    @Test
    public void createAlterTableSyntaxTest() {

        ClickHouseAlterTable cat = new ClickHouseAlterTable();

        // Add Column
        String alterTableAddColumnQuery = cat.createAlterTableSyntax("employees",
                this.getExpectedColumnToDataTypesMap(), ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);

        String expectedAddColumnQuery = "ALTER TABLE `employees` add column `amount` Float64,add column `occupation` String,add column `quantity` Int32,add column `blob_storage_scale` Decimal,add column `json_output` JSON,add column `max_amount` Float64,add column `amount_1` Float32,add column `customerName` String,add column `blob_storage` String,add column `employed` Bool";
        Assert.assertTrue(alterTableAddColumnQuery.equalsIgnoreCase(expectedAddColumnQuery));

        // DESTRUCTIVE: unit-test syntax string only; no database or table is modified.
        String alterTableDeleteColumnQuery = cat.createAlterTableSyntax("employees",
                this.getExpectedColumnToDataTypesMap(), ClickHouseAlterTable.ALTER_TABLE_OPERATION.REMOVE);

        String expectedDeleteColumnQuery = "ALTER TABLE `employees` drop column `amount`,drop column `occupation`,drop column `quantity`,drop column `blob_storage_scale`,drop column `json_output`,drop column `max_amount`,drop column `amount_1`,drop column `customerName`,drop column `blob_storage`,drop column `employed`";
        Assert.assertTrue(alterTableDeleteColumnQuery.equalsIgnoreCase(expectedDeleteColumnQuery));
    }

    @Test
    public void alterTableSkipsExactAndCaseInsensitiveMatchesWithoutQueryingClickHouse() throws Exception {
        ClickHouseAlterTable cat = new ClickHouseAlterTable();
        Map<String, String> existing = new HashMap<>();
        existing.put("id", "Int32");
        existing.put("customerName", "String");

        List<Field> fields = Arrays.asList(
                new Field("id", 0, Schema.INT32_SCHEMA),
                new Field("CUSTOMERNAME", 1, Schema.OPTIONAL_STRING_SCHEMA)
        );

        // Passing null Connection and null config proves no ALTER TABLE is built or executed
        // when every field matches either exactly or case-insensitively.
        cat.alterTable(fields, "employees", null, existing, null);
        cat.alterTable(Collections.singletonList(new Field("id", 0, Schema.INT32_SCHEMA)),
                "employees", null, existing, null);
    }
}
