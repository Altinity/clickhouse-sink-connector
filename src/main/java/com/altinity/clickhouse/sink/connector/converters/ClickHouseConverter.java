package com.kafka.connect.clickhouse.converters;


import com.altinity.clickhouse.sink.connector.converters.AbstractConverter;
import com.altinity.clickhouse.sink.connector.metadata.KafkaSchemaRecordType;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.sink.SinkRecord;

import org.apache.kafka.connect.data.Struct;

import org.apache.kafka.connect.json.JsonConverter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseConverter implements AbstractConverter {

    /**
     *
     */
    public enum CDC_OPERATION {
        // Sql updates
        UPDATE("U"),
        // Inserts
        CREATE("C"),

        DELETE("D");

        private String operation;
        CDC_OPERATION(String op) {
            this.operation = op;
        }
    }
    @Override
    public Map<String, Object> convertKey(SinkRecord record) {

        /**
         * Struct{before=Struct{id=1,message=Hello from MySQL},
         * after=Struct{id=1,message=Mysql update},source=Struct{version=1.8.1.Final,connector=mysql,
         * name=local_mysql3,ts_ms=1648575279000,snapshot=false,db=test,table=test_hello2,server_id=1,file=binlog.000002,pos=4414,row=0},op=u,ts_ms=1648575279856}
         */
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

    /**
     * Primary functionality of parsing a CDC event in a SinkRecord.
     * This checks the operation flag( if its 'C' or 'U')
     * @param record
     */
    public Struct convert(SinkRecord record) {

        Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);

        Struct afterRecord = null;
        if(convertedValue.containsKey("op")) {
            // Operation (u, c)
            String operation = (String) convertedValue.get("op");
            if (operation.equalsIgnoreCase(CDC_OPERATION.CREATE.operation)) {
                // Inserts.
            } else if(operation.equalsIgnoreCase(CDC_OPERATION.UPDATE.operation)) {
                // Updates.
            } else if(operation.equalsIgnoreCase(CDC_OPERATION.DELETE.operation)) {
                // Deletes.
            }
        }
        if(convertedValue.containsKey("after")) {
            afterRecord = (Struct) convertedValue.get("after");
            List<Field> fields = afterRecord.schema().fields();

            List<String> cols = new ArrayList<String>();
            List<Object> values = new ArrayList<Object>();
            List<Schema.Type> types = new ArrayList<Schema.Type>();

            for(Field f: fields) {
                System.out.println("Key" + f.name());
                cols.add(f.name());

                System.out.println("Value"+ afterRecord.get(f));
                values.add(afterRecord.get(f));
            }

        }

        //ToDO: Remove the following code after evaluating
        try {
            byte[] rawJsonPayload = new JsonConverter().fromConnectData(record.topic(), record.valueSchema(), record.value());
            String stringPayload = new String(rawJsonPayload, StandardCharsets.UTF_8);
            System.out.println("STRING PAYLOAD" + stringPayload);
        } catch(Exception e) {

        }

        return afterRecord;
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
