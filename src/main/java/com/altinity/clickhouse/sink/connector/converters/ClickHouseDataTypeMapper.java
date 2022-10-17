package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import io.debezium.data.Enum;
import io.debezium.data.EnumSet;
import io.debezium.data.Json;
import io.debezium.data.geometry.Geometry;
import io.debezium.time.*;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;

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

         // DATE
        dataTypesMap.put(new MutablePair<>(Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME), ClickHouseDataType.Date32);

        // TIME
        dataTypesMap.put(new MutablePair<>(Schema.INT32_SCHEMA.type(), Time.SCHEMA_NAME), ClickHouseDataType.String);

        // debezium.time.MicroTime -> String (Time does not exist in CH)
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), MicroTime.SCHEMA_NAME), ClickHouseDataType.String);

        // Timestamp -> DateTime
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), Timestamp.SCHEMA_NAME), ClickHouseDataType.DateTime64);
        // Datetime with microseconds precision
        dataTypesMap.put(new MutablePair(Schema.INT64_SCHEMA.type(), MicroTimestamp.SCHEMA_NAME), ClickHouseDataType.DateTime64);

        // BLOB -> String
        dataTypesMap.put(new MutablePair(Schema.Type.BYTES, null), ClickHouseDataType.String);

        // BYTES, BIT
        dataTypesMap.put(new MutablePair(Schema.Type.BYTES, io.debezium.data.Bits.LOGICAL_NAME), ClickHouseDataType.String);

        // Boolean -> Boolean
        dataTypesMap.put(new MutablePair<>(Schema.Type.BOOLEAN, null), ClickHouseDataType.Bool);

        // Timestamp -> ZonedTimeStamp -> DateTime
        dataTypesMap.put(new MutablePair<>(Schema.Type.STRING, ZonedTimestamp.SCHEMA_NAME), ClickHouseDataType.DateTime64);

        dataTypesMap.put(new MutablePair<>(Schema.Type.STRING, Enum.LOGICAL_NAME), ClickHouseDataType.String);

        dataTypesMap.put(new MutablePair<>(Schema.Type.STRING, Json.LOGICAL_NAME), ClickHouseDataType.String);

        dataTypesMap.put(new MutablePair<>(Schema.INT32_SCHEMA.type(), Year.SCHEMA_NAME), ClickHouseDataType.Int32);

        // EnumSet -> String
        dataTypesMap.put(new MutablePair<>(Schema.STRING_SCHEMA.type(), EnumSet.LOGICAL_NAME), ClickHouseDataType.String);

        // Geometry -> Geometry
        dataTypesMap.put(new MutablePair<>(Schema.Type.STRUCT, Geometry.LOGICAL_NAME), ClickHouseDataType.String);
    }

    public static ClickHouseDataType getClickHouseDataType(Schema.Type kafkaConnectType, String schemaName) {

        ClickHouseDataType matchingDataType = null;
        for (Map.Entry<MutablePair<Schema.Type, String>, ClickHouseDataType> entry : dataTypesMap.entrySet()) {
            //   return dataTypesMap.get(kafkaConnectType);

            MutablePair mp = entry.getKey();

            if((schemaName == null && mp.right == null && kafkaConnectType == mp.left)  ||
                    (kafkaConnectType == mp.left && (schemaName != null && schemaName.equalsIgnoreCase((String) mp.right)))) {
                // Founding matching type.
                matchingDataType = entry.getValue();
            }

        }

        return matchingDataType;
    }
}
