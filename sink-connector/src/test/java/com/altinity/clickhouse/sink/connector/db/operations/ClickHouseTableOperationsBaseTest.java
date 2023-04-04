package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class ClickHouseTableOperationsBaseTest {

    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseTableOperationsBase base = new ClickHouseTableOperationsBase();

        Field[] fields = new Field[4];

        fields[0] =(new Field("totalAmount", 1, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).parameter(ClickHouseTableOperationsBase.SCALE, "2")
                .parameter(ClickHouseTableOperationsBase.PRECISION, "4").build()));
        fields[1] = (new Field("amount", 2, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).build()));

        // DateTime64(3)
        fields[2] = (new Field("date_milli", 3, SchemaBuilder.type(Schema.Type.INT64).
                name(Timestamp.SCHEMA_NAME).build()));
        // DateTime64(6)
        fields[3] = (new Field("date_micro", 4, SchemaBuilder.type(Schema.Type.INT64).
                name(MicroTimestamp.SCHEMA_NAME).build()));

        Map<String, String> result = base.getColumnNameToCHDataTypeMapping(fields);

        Assert.assertTrue(result.get("totalAmount").equalsIgnoreCase("Decimal(4,2)"));
        Assert.assertTrue(result.get("amount").equalsIgnoreCase("Decimal(10,2)"));

        Assert.assertTrue(result.get("date_milli").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(result.get("date_micro").equalsIgnoreCase("DateTime64(6)"));

    }
}
