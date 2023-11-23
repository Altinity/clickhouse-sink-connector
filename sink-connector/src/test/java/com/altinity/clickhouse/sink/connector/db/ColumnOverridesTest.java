package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.data.ClickHouseDataType;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class ColumnOverridesTest {

    @Test
    public void testMapping() {
        String dateTime64Type = "DateTime64(3)";
        String dataTime64OverrideType = ColumnOverrides.getColumnOverride(dateTime64Type);
        Assert.assertTrue(dataTime64OverrideType.equalsIgnoreCase("String"));

        String nullableDateTime64Type = "Nullable(DateTime64)";
        String nullableDataTime64OverrideType = ColumnOverrides.getColumnOverride(nullableDateTime64Type);
        Assert.assertTrue(nullableDataTime64OverrideType.equalsIgnoreCase("Nullable(String)"));

        Assert.assertTrue(ColumnOverrides.getColumnOverride(ClickHouseDataType.DateTime.name()).equalsIgnoreCase("String"));
        Assert.assertNull(ColumnOverrides.getColumnOverride(ClickHouseDataType.Decimal.name()));


        Assert.assertNull(ColumnOverrides.getColumnOverride(ClickHouseDataType.Int16.name()));
        Assert.assertTrue(ColumnOverrides.getColumnOverride(ClickHouseDataType.DateTime32.name()).equalsIgnoreCase(ClickHouseDataType.String.name()));
    }
}
