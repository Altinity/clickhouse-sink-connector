package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import io.debezium.time.MicroTime;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Decimal;
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

        // Integer
        dataTypesMap.put(new MutablePair(Schema.INT16_SCHEMA.type(), null), ClickHouseDataType.Int16);
        dataTypesMap.put(new MutablePair(Schema.INT8_SCHEMA.type(), null), ClickHouseDataType.Int8);
        dataTypesMap.put(new MutablePair(Schema.INT32_SCHEMA.type(), null), ClickHouseDataType.Int32);
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), null), ClickHouseDataType.Int64);

        // Float
         dataTypesMap.put(new MutablePair(Schema.FLOAT32_SCHEMA.type(), null), ClickHouseDataType.Float32);
         dataTypesMap.put(new MutablePair(Schema.FLOAT64_SCHEMA.type(), null), ClickHouseDataType.Float64);

         // String
         dataTypesMap.put(new MutablePair(Schema.STRING_SCHEMA.type(), null), ClickHouseDataType.String);

         // BLOB -> String
         dataTypesMap.put(new MutablePair(Schema.BYTES_SCHEMA.type(), Decimal.LOGICAL_NAME), ClickHouseDataType.Decimal);


        // debezium.time.MicroTime -> String (Time does not exist in CH)
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), MicroTime.SCHEMA_NAME), ClickHouseDataType.String);

        // Timestamp -> DateTime
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), Timestamp.SCHEMA_NAME), ClickHouseDataType.DateTime64);

        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), MicroTimestamp.SCHEMA_NAME), ClickHouseDataType.String);

        // BLOB -> String
        dataTypesMap.put(new MutablePair(Schema.Type.BYTES, null), ClickHouseDataType.String);

    }

    static ClickHouseDataType getClickHouseDataType(Schema.Type kafkaConnectType, String schemaName) {

        ClickHouseDataType matchingDataType = null;
        for (Map.Entry<MutablePair<Schema.Type, String>, ClickHouseDataType> entry : dataTypesMap.entrySet()) {
            //   return dataTypesMap.get(kafkaConnectType);

            MutablePair mp = entry.getKey();

            if (kafkaConnectType == mp.left && schemaName == mp.right) {
                // Founding matching type.
                matchingDataType = entry.getValue();
            }

        }

        return matchingDataType;
    }
}
