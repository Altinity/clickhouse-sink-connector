package com.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class ClickHouseAlterTableTest extends com.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTableTest {

    @Test
    public void createAlterTableSyntaxTest() {

        ClickHouseAlterTable cat = new ClickHouseAlterTable();
        String alterTableQuery = cat.createAlterTableSyntax("employees",
                this.getExpectedColumnToDataTypesMap(), ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);

        String expectedQuery = "ALTER TABLE employees add column `amount` Float64,add column `occupation` String,add column `quantity` Int32,add column `blob_storage_scale` Decimal,add column `json_output` JSON,add column `amount_1` Float32,add column `customerName` String,add column `blob_storage` String,add column `employed` Bool";

        Assert.assertTrue(alterTableQuery.equalsIgnoreCase(expectedQuery));
        System.out.println("Alter table query" + alterTableQuery);
    }
}
