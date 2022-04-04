package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.metadata.KafkaSchemaRecordType;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseConverter implements AbstractConverter {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseConverter.class);

    /**
     * Convert SinkRecord
     *
     * @param record
     */
    public void convert(SinkRecord record) {
        log.info("convert()");

        Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);

        if (convertedValue.containsKey("after")) {
            Struct afterValue = (Struct) convertedValue.get("after");
            List<Field> fields = afterValue.schema().fields();

            List<String> cols = new ArrayList<String>();
            List<Object> values = new ArrayList<Object>();
            for (Field f : fields) {
                log.info("Key" + f.name());
                log.info("Value" + afterValue.get(f));

                cols.add(f.name());
                values.add(afterValue.get(f));
            }

            DbWriter writer = new DbWriter();
            //writer.insert(record.topic(), String.join(' ', cols.), String.join(' ', values));
        }

        try {
            byte[] rawJsonPayload = new JsonConverter().fromConnectData(record.topic(), record.valueSchema(), record.value());
            String stringPayload = new String(rawJsonPayload, StandardCharsets.UTF_8);
            log.info("STRING PAYLOAD" + stringPayload);
        } catch (Exception e) {
        }
    }

    @Override
    public Map<String, Object> convertKey(SinkRecord record) {
        return this.convertRecord(record, KafkaSchemaRecordType.KEY);
    }

    @Override
    public Map<String, Object> convertValue(SinkRecord record) {
        return this.convertRecord(record, KafkaSchemaRecordType.VALUE);
    }

    /**
     *
     * @param record
     * @param what
     * @return
     */
    public Map<String, Object> convertRecord(SinkRecord record, KafkaSchemaRecordType what) {
        Schema schema = what == KafkaSchemaRecordType.KEY ? record.keySchema() : record.valueSchema();
        Object obj    = what == KafkaSchemaRecordType.KEY ? record.key() : record.value();
        Map<String, Object> result = null;

        if (schema == null) {
            if (obj instanceof Map) {
                // Schemaless record.
                //return (Map<String, Object>) convertSchemalessRecord(kafkaConnectStruct);
                log.info("SCHEMA LESS RECORD");
            }
//                    throw new ConversionConnectException("Only Map objects supported in absence of schema for " +
//                            "record conversion to BigQuery format.");
        }

        if (schema.type() != Schema.Type.STRUCT) {
//                    throw new
//                            ConversionConnectException("Top-level Kafka Connect schema must be of type 'struct'");
        } else {
            // Convert STRUCT
            log.info("RECEIVED STRUCT");
            result = convertStruct(obj, schema);
        }

        return result;
    }

    /**
     *
     * @param object
     * @param schema
     * @return
     */
    private Map<String, Object> convertStruct(Object object, Schema schema) {
        Map<String, Object> record = new HashMap<>();
        List<Field> fields = schema.fields();
        Struct struct = (Struct) object;
        for (Field field : fields) {
            // ignore empty structures
            boolean isEmptyStruct = (field.schema().type() == Schema.Type.STRUCT) && (field.schema().fields().isEmpty());
            if (!isEmptyStruct) {
                // Not empty struct
                Object convertedObject = convertObject(struct.get(field.name()),field.schema());
                if (convertedObject != null) {
                    record.put(field.name(), convertedObject);
                }
            }
        }
        return record;
    }

    /**
     *
     * @param object
     * @param schema
     * @return
     */
    private Object convertObject(Object object, Schema schema) {
        if (object == null) {
            if (schema.isOptional()) {
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
        Schema.Type type = schema.type();
        switch (type) {
            case ARRAY:
                log.info("ARRAY type");
                //return convertArray(kafkaConnectObject, kafkaConnectSchema);
            case MAP:
                log.info("MAP type");
                //return convertMap(kafkaConnectObject, kafkaConnectSchema);
            case STRUCT:
                log.info("STRUCT type");
                //return convertStruct(kafkaConnectObject, kafkaConnectSchema);
            case BYTES:
                log.info("BYTES type");
                //return convertBytes(kafkaConnectObject);
            case FLOAT64:
                log.info("FLOAT64 type");
                //return convertDouble((Double)kafkaConnectObject);
            case BOOLEAN:
            case FLOAT32:
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case STRING:
                return object;
            default:
                log.warn("Not supported type");
                // Throw error - unrecognized type.
                //throw new ConversionConnectException("Unrecognized schema type: " + kafkaConnectSchemaType);
        }

        return null;
    }
}
