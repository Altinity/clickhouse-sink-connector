package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkTask;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.metadata.KafkaSchemaRecordType;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.sink.SinkRecord;

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
     * Enum to store the OP Types.
     * Refer: https://debezium.io/documentation/reference/stable/connectors/mysql.html
     */
    public enum CDC_OPERATION {
        // Sql updates
        UPDATE("U"),
        // Inserts
        CREATE("C"),
        // Deletes
        DELETE("D");

        private String operation;

        CDC_OPERATION(String op) {
            this.operation = op;
        }
    }

    /**
     * SinkRecord
     *
     * SinkRecord{
     *     kafkaOffset=300023,
     *     timestampType=CreateTime
     * }
     * ConnectRecord{
     *     topic='SERVER5432.test.employees',
     *     kafkaPartition=0,
     *     key=Struct{
     *         emp_no=499999
     *     },
     *     keySchema=Schema{
     *         SERVER5432.test.employees.Key:STRUCT
     *     },
     *     value=Struct{
     *         after=Struct{
     *             emp_no=499999,
     *             birth_date=-4263,
     *             first_name=Sachin,
     *             last_name=Tsukuda,
     *             gender=M,
     *             hire_date=10195
     *         },
     *         source=Struct{
     *             version=1.9.0.CR1,
     *             connector=mysql,
     *             name=SERVER5432,
     *             ts_ms=1649152583000,
     *             snapshot=false,
     *             db=test,
     *             table=employees,
     *             server_id=1,
     *             file=binlog.000002,
     *             pos=8249512,
     *             row=104,
     *             thread=13
     *         },
     *         op=c,
     *         ts_ms=1649152741745
     *     },
     *     valueSchema=Schema{
     *         SERVER5432.test.employees.Envelope:STRUCT
     *     },
     *     timestamp=1649152746408,
     *     headers=ConnectHeaders(headers=)
     * }
     *
     * Value struct
     * CREATE
     * value=Struct{
     *         after=Struct{
     *             emp_no=499999,
     *             birth_date=-4263,
     *             first_name=Sachin,
     *             last_name=Tsukuda,
     *             gender=M,
     *             hire_date=10195
     *         },
     *         source=Struct{
     *             version=1.9.0.CR1,
     *             connector=mysql,
     *             name=SERVER5432,
     *             ts_ms=1649152583000,
     *             snapshot=false,
     *             db=test,
     *             table=employees,
     *             server_id=1,
     *             file=binlog.000002,
     *             pos=8249512,
     *             row=104,
     *             thread=13
     *         },
     *         op=c,
     *         ts_ms=1649152741745
     * },
     *
     * UPDATE
     * value=Struct{
     *      before=Struct{
     *          id=1,
     *          message=Hello from MySQL
     *      },
     *      after=Struct{
     *          id=1,
     *          message=Mysql update
     *      },
     *      source=Struct{
     *          version=1.8.1.Final,
     *          connector=mysql,
     *          name=local_mysql3,
     *          ts_ms=1648575279000,
     *          snapshot=false,
     *          db=test,
     *          table=test_hello2,
     *          server_id=1,
     *          file=binlog.000002,
     *          pos=4414,
     *          row=0
     *      },
     *      op=u,
     *      ts_ms=1648575279856
     * }
     */

    /**
     * Primary functionality of parsing a CDC event in a SinkRecord.
     * This checks the operation flag( if its 'C' or 'U')
     * @param record
     */
    public Struct convert(SinkRecord record) {
        log.info("convert()");

        Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);
        Struct afterRecord = null;

        // Check "operation" represented by this record.
        if (convertedValue.containsKey("op")) {
            // Operation (u, c)
            String operation = (String) convertedValue.get("op");
            if (operation.equalsIgnoreCase(CDC_OPERATION.CREATE.operation)) {
                // Inserts.
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.UPDATE.operation)) {
                // Updates.
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.DELETE.operation)) {
                // Deletes.
            }
        }

        // Check "after" value represented by this record.
        if (convertedValue.containsKey("after")) {
            afterRecord = (Struct) convertedValue.get("after");
            List<Field> fields = afterRecord.schema().fields();

            List<String> cols = new ArrayList<String>();
            List<Object> values = new ArrayList<Object>();
            List<Schema.Type> types = new ArrayList<Schema.Type>();

            for (Field field : fields) {
                log.info("Key" + field.name());
                log.info("Value" + afterRecord.get(field));

                cols.add(field.name());
                values.add(afterRecord.get(field));
            }
        }

        //ToDO: Remove the following code after evaluating
        try {
            byte[] rawJsonPayload = new JsonConverter().fromConnectData(record.topic(), record.valueSchema(), record.value());
            String stringPayload = new String(rawJsonPayload, StandardCharsets.UTF_8);
            log.info("STRING PAYLOAD" + stringPayload);
        } catch (Exception e) {
        }

        return afterRecord;
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
     * @param record
     * @param what
     * @return
     */
    public Map<String, Object> convertRecord(SinkRecord record, KafkaSchemaRecordType what) {
        Schema schema = what == KafkaSchemaRecordType.KEY ? record.keySchema() : record.valueSchema();
        Object obj = what == KafkaSchemaRecordType.KEY ? record.key() : record.value();
        Map<String, Object> result = null;

        if (schema == null) {
            if (obj instanceof Map) {
                log.info("SCHEMA LESS RECORD");
            }
        } else {

            if (schema.type() != Schema.Type.STRUCT) {
//                    throw new
//                            ConversionConnectException("Top-level Kafka Connect schema must be of type 'struct'");
            } else {
                // Convert STRUCT
                log.info("RECEIVED STRUCT");
                result = convertStruct(obj, schema);
            }
        }
        return result;
    }

    /**
     * @param object
     * @param schema
     * @return
     */
    private Map<String, Object> convertStruct(Object object, Schema schema) {
        // Object to be converted assumed to be a struct
        Struct struct = (Struct) object;
        // Result record would be a map
        Map<String, Object> record = new HashMap<>();
        // Fields of the struct
        List<Field> fields = schema.fields();
        // Convert all fields of the struct into a map
        for (Field field : fields) {
            // Ignore empty structures
            boolean isEmptyStruct = (field.schema().type() == Schema.Type.STRUCT) && (field.schema().fields().isEmpty());
            if (!isEmptyStruct) {
                // Not empty struct
                Object convertedObject = convertObject(struct.get(field.name()), field.schema());
                if (convertedObject != null) {
                    record.put(field.name(), convertedObject);
                }
            }
        }
        return record;
    }

    /**
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
