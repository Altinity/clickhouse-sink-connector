package com.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class ClickHouseAlterTableTest extends com.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTableTest {

    @Test
    public void createAlterTableSyntaxTest() {

        ClickHouseAlterTable cat = new ClickHouseAlterTable();

        // Add Column
        String alterTableAddColumnQuery = cat.createAlterTableSyntax("employees",
                this.getExpectedColumnToDataTypesMap(), ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);

        String expectedAddColumnQuery = "ALTER TABLE employees add column `amount` Float64,add column `occupation` String,add column `quantity` Int32,add column `blob_storage_scale` Decimal,add column `json_output` JSON,add column `amount_1` Float32,add column `customerName` String,add column `blob_storage` String,add column `employed` Bool";
        Assert.assertTrue(alterTableAddColumnQuery.equalsIgnoreCase(expectedAddColumnQuery));

        // Delete Column
        String alterTableDeleteColumnQuery = cat.createAlterTableSyntax("employees",
                this.getExpectedColumnToDataTypesMap(), ClickHouseAlterTable.ALTER_TABLE_OPERATION.REMOVE);

        String expectedDeleteColumnQuery = "ALTER TABLE employees delete column `amount` Float64,delete column `occupation` String,delete column `quantity` Int32,delete column `blob_storage_scale` Decimal,delete column `json_output` JSON,delete column `amount_1` Float32,delete column `customerName` String,delete column `blob_storage` String,delete column `employed` Bool";
        Assert.assertTrue(alterTableDeleteColumnQuery.equalsIgnoreCase(expectedDeleteColumnQuery));
    }
}
