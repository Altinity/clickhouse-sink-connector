package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * Function that maps the debezium/kafka connect
 * data types to ClickHouse Data Types.
 *
 */
public class ClickHouseDataTypeMapper {
    static Map<Schema.Type, ClickHouseDataType> dataTypesMap;
    static {
        dataTypesMap = new HashMap<>();

        dataTypesMap.put(Schema.INT16_SCHEMA.type(), ClickHouseDataType.Int16);
        dataTypesMap.put(Schema.INT8_SCHEMA.type(), ClickHouseDataType.Int8);
        dataTypesMap.put(Schema.INT32_SCHEMA.type(), ClickHouseDataType.Int32);
        dataTypesMap.put(Schema.INT64_SCHEMA.type(), ClickHouseDataType.Int64);

        dataTypesMap.put(Schema.FLOAT32_SCHEMA.type(), ClickHouseDataType.Float32);
        dataTypesMap.put(Schema.FLOAT64_SCHEMA.type(), ClickHouseDataType.Float64);

        dataTypesMap.put(Schema.STRING_SCHEMA.type(), ClickHouseDataType.String);

        // BLOB -> String
        dataTypesMap.put(Schema.BYTES_SCHEMA.type(), ClickHouseDataType.String);




    }

    static ClickHouseDataType getClickHouseDataType(Schema.Type kafkaConnectType) {
        return dataTypesMap.get(kafkaConnectType);
    }
}
