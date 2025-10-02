package com.altinity.clickhouse.sink.connector.db.operations;

import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.Json;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for ClickHouseAutoCreateTable tests providing common test data and methods.
 * This class contains shared functionality used across different test classes.
 */
public abstract class ClickHouseAutoCreateTableBase {

    /**
     * Creates an array of test fields with various data types for testing table creation.
     * 
     * @return Array of Field objects representing different column types
     */
    protected Field[] createFields() {
        ArrayList<Field> fields = new ArrayList<>();
        fields.add(new Field("customerName", 0, Schema.STRING_SCHEMA));
        fields.add(new Field("occupation", 1, Schema.STRING_SCHEMA));
        fields.add(new Field("quantity", 2, Schema.INT32_SCHEMA));
        fields.add(new Field("amount_1", 3, Schema.FLOAT32_SCHEMA));
        fields.add(new Field("amount", 4, Schema.FLOAT64_SCHEMA));
        fields.add(new Field("employed", 5, Schema.BOOLEAN_SCHEMA));
        fields.add(new Field("blob_storage", 6, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).build()));

        Schema decimalSchema = SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).parameter("scale", "10")
                .parameter("connect.decimal.precision", "30")
                .name(Decimal.LOGICAL_NAME).build();

        fields.add(new Field("blob_storage_scale", 7, decimalSchema));
        fields.add(new Field("json_output", 8, Json.schema()));
        fields.add(new Field("max_amount", 9, Schema.FLOAT64_SCHEMA));

        Field[] result = new Field[fields.size()];
        fields.toArray(result);
        return result;
    }

    /**
     * Returns the expected mapping of column names to their ClickHouse data types.
     * This map is used to verify that table creation produces the correct column types.
     * 
     * @return Map of column names to ClickHouse data type names
     */
    protected static Map<String, String> getExpectedColumnToDataTypesMap() {

        Map<String, String> columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("customerName", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("occupation", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("quantity", ClickHouseDataType.Int32.name());
        columnToDataTypesMap.put("amount_1", ClickHouseDataType.Float32.name());
        columnToDataTypesMap.put("amount", ClickHouseDataType.Float64.name());
        columnToDataTypesMap.put("employed", ClickHouseDataType.Bool.name());
        columnToDataTypesMap.put("blob_storage", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("blob_storage_scale", ClickHouseDataType.Decimal.name());
        columnToDataTypesMap.put("json_output", ClickHouseDataType.JSON.name());
        columnToDataTypesMap.put("max_amount", ClickHouseDataType.Float64.name());

        return columnToDataTypesMap;
    }
}