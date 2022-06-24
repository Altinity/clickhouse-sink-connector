package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;

import org.apache.commons.lang3.tuple.MutablePair;


import java.util.HashMap;
import java.util.Map;

/**
 * Function that maps the debezium/kafka connect
 * data types to ClickHouse Data Types.
 *
 */
public class ClickHouseDataTypeMapper {
    static Map<MutablePair<Schema.Type, String>, ClickHouseDataType> dataTypesMap;
    static {
        dataTypesMap = new HashMap<>();

        dataTypesMap.put(new MutablePair(Schema.INT16_SCHEMA.type(), null), ClickHouseDataType.Int16);
        dataTypesMap.put(new MutablePair(Schema.INT8_SCHEMA.type(), null), ClickHouseDataType.Int8);
        dataTypesMap.put(new MutablePair(Schema.INT32_SCHEMA.type(), null), ClickHouseDataType.Int32);
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), null), ClickHouseDataType.Int64);

        // dataTypesMap.put(Schema.FLOAT32_SCHEMA.type(), ClickHouseDataType.Float32);
        // dataTypesMap.put(Schema.FLOAT64_SCHEMA.type(), ClickHouseDataType.Float64);

        // dataTypesMap.put(Schema.STRING_SCHEMA.type(), ClickHouseDataType.String);

        // // BLOB -> String
        // dataTypesMap.put(Schema.BYTES_SCHEMA.type(), ClickHouseDataType.String);


    }
 
    static ClickHouseDataType getClickHouseDataType(Schema.Type kafkaConnectType, String schemaName) {
     
        ClickHouseDataType matchingDataType = null;
        for(Map.Entry<MutablePair<Schema.Type, String>, ClickHouseDataType> entry: dataTypesMap.entrySet()) {
            //   return dataTypesMap.get(kafkaConnectType);

            MutablePair mp = entry.getKey();

            if(kafkaConnectType == mp.left && schemaName == mp.right) {
                // Founding matching type.
                matchingDataType = entry.getValue();
            }

    }

    return matchingDataType;
}
