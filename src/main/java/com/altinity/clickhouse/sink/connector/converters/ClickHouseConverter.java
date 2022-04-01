package com.altinity.clickhouse.sink.connector.converters;


import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.metadata.KafkaSchemaRecordType;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseConverter implements AbstractConverter {
    @Override
    public Map<String, Object> convertKey(SinkRecord record) {

        KafkaSchemaRecordType recordType = KafkaSchemaRecordType.KEY;
        Schema kafkaConnectSchema = recordType == KafkaSchemaRecordType.KEY ? record.keySchema() : record.valueSchema();
        Object kafkaConnectStruct = recordType == KafkaSchemaRecordType.KEY ? record.key() : record.value();
        Map<String, Object> result = null;

        if (kafkaConnectSchema == null) {
            if (kafkaConnectStruct instanceof Map) {
                // Schemaless record.
                //return (Map<String, Object>) convertSchemalessRecord(kafkaConnectStruct);
                System.out.println("SCHEMA LESS RECORD");
            }
//                    throw new ConversionConnectException("Only Map objects supported in absence of schema for " +
//                            "record conversion to BigQuery format.");
        }
        if (kafkaConnectSchema.type() != Schema.Type.STRUCT) {
//                    throw new
//                            ConversionConnectException("Top-level Kafka Connect schema must be of type 'struct'");
        } else {
            // Convert STRUCT
            System.out.println("RECIEVED STRUCT");
            result = convertStruct(kafkaConnectStruct, kafkaConnectSchema);
        }

        return result;
    }

    @Override
    public Map<String, Object> convertValue(SinkRecord record) {

        KafkaSchemaRecordType recordType = KafkaSchemaRecordType.VALUE;
        Schema kafkaConnectSchema = recordType == KafkaSchemaRecordType.KEY ? record.keySchema() : record.valueSchema();
        Object kafkaConnectStruct = recordType == KafkaSchemaRecordType.KEY ? record.key() : record.value();
        Map<String, Object> result = null;

        if (kafkaConnectSchema == null) {
            if (kafkaConnectStruct instanceof Map) {
                // Schemaless record.
                //return (Map<String, Object>) convertSchemalessRecord(kafkaConnectStruct);
                System.out.println("SCHEMA LESS RECORD");
            }
//                    throw new ConversionConnectException("Only Map objects supported in absence of schema for " +
//                            "record conversion to BigQuery format.");
        }
        if (kafkaConnectSchema.type() != Schema.Type.STRUCT) {
//                    throw new
//                            ConversionConnectException("Top-level Kafka Connect schema must be of type 'struct'");
        } else {
            // Convert STRUCT
            System.out.println("RECIEVED STRUCT");
            result = convertStruct(kafkaConnectStruct, kafkaConnectSchema);
        }

        return result;
    }

    public void convert(SinkRecord record) {

        Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);

        System.out.println("Converted Key");
        System.out.println("Converted Value");

        if (convertedValue.containsKey("after")) {
            Struct afterValue = (Struct) convertedValue.get("after");
            List<Field> fields = afterValue.schema().fields();
            System.out.println("DONE");

            List<String> cols = new ArrayList<String>();
            List<Object> values = new ArrayList<Object>();
            for (Field f : fields) {

                System.out.println("Key" + f.name());
                cols.add(f.name());

                System.out.println("Value" + afterValue.get(f));
                values.add(afterValue.get(f));
            }

            DbWriter writer = new DbWriter();
            //writer.insert(record.topic(), String.join(' ', cols.), String.join(' ', values));

        }

        try {
            byte[] rawJsonPayload = new JsonConverter().fromConnectData(record.topic(), record.valueSchema(), record.value());
            String stringPayload = new String(rawJsonPayload, StandardCharsets.UTF_8);
            System.out.println("STRING PAYLOAD" + stringPayload);
        } catch (Exception e) {

        }
    }

    private Map<String, Object> convertStruct(Object kafkaConnectObject, Schema kafkaConnectSchema) {
        Map<String, Object> bigQueryRecord = new HashMap<>();
        List<Field> kafkaConnectSchemaFields = kafkaConnectSchema.fields();
        Struct kafkaConnectStruct = (Struct) kafkaConnectObject;
        for (Field kafkaConnectField : kafkaConnectSchemaFields) {
            // ignore empty structures
            boolean isEmptyStruct = kafkaConnectField.schema().type() == Schema.Type.STRUCT &&
                    kafkaConnectField.schema().fields().isEmpty();
            if (!isEmptyStruct) {
                // Not empty struct
                Object convertedObject = convertObject(
                        kafkaConnectStruct.get(kafkaConnectField.name()),
                        kafkaConnectField.schema()
                );
                if (convertedObject != null) {
                    bigQueryRecord.put(kafkaConnectField.name(), convertedObject);
                }
            }
        }
        return bigQueryRecord;
    }

    private Object convertObject(Object kafkaConnectObject, Schema kafkaConnectSchema) {
        if (kafkaConnectObject == null) {
            if (kafkaConnectSchema.isOptional()) {
                // short circuit converting the object
                return null;
            } else {
                // Name is not optional
//                throw new ConversionConnectException(
//                        kafkaConnectSchema.name() + " is not optional, but converting object had null value");
            }
        }
//        if (LogicalConverterRegistry.isRegisteredLogicalType(kafkaConnectSchema.name())) {
//            return convertLogical(kafkaConnectObject, kafkaConnectSchema);
//        }
        Schema.Type kafkaConnectSchemaType = kafkaConnectSchema.type();
        switch (kafkaConnectSchemaType) {
            case ARRAY:
                System.out.println("ARRAY type");
                //return convertArray(kafkaConnectObject, kafkaConnectSchema);
            case MAP:
                System.out.println("MAP type");
                //return convertMap(kafkaConnectObject, kafkaConnectSchema);
            case STRUCT:
                System.out.println("STRUCT type");
                //return convertStruct(kafkaConnectObject, kafkaConnectSchema);
            case BYTES:
                System.out.println("BYTES type");
                //return convertBytes(kafkaConnectObject);
            case FLOAT64:
                System.out.println("FLOAT64 type");
                //return convertDouble((Double)kafkaConnectObject);
            case BOOLEAN:
            case FLOAT32:
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case STRING:
                return kafkaConnectObject;
            default:
                System.out.println("Not supported type");
                // Throw error - unrecognized type.
                //throw new ConversionConnectException("Unrecognized schema type: " + kafkaConnectSchemaType);
        }

        return null;

    }
}
