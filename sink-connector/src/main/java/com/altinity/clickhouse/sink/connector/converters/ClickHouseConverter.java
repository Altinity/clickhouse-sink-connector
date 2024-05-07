package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.KafkaSchemaRecordType;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SinkRecordColumns;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHouseConverter implements AbstractConverter {
    private static final Logger log = LogManager.getLogger(ClickHouseConverter.class);

    /**
     * Enum to store the OP Types.
     * Refer: https://debezium.io/documentation/reference/stable/connectors/mysql.html
     */
    public enum CDC_OPERATION {
        // Snapshot events come as r
        READ("r"),
        // Sql updates
        UPDATE("U"),
        // Inserts
        CREATE("C"),
        // Deletes
        DELETE("D"),
        // Truncates
        TRUNCATE("T");

        private final String operation;

        public String getOperation() {
            return operation;
        }

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
     * "Struct"{
     *    "after=Struct"{
     *       "productCode=synergize",
     *       "productName=Sandra Mil",
     *       "productLine=brand plug",
     *       "productScale=redefine i",
     *       "productVendor=Ford",
     *       "Hunt",
     *       "productDescription=Johnson-Fo",
     *       quantityInStock=54,
     *       buyPrice=0.31,
     *       MSRP=0.24
     *    },
     *    "source=Struct"{
     *       version=1.9.2.Final,
     *       "connector=mysql",
     *       name=SERVER5432,
     *       ts_ms=1652122799000,
     *       "snapshot=false",
     *       "db=test",
     *       "table=products",
     *       server_id=1,
     *       file=binlog.000002,
     *       pos=775,
     *       row=0,
     *       thread=13
     *    },
     *    "op=c",
     *    ts_ms=1652122799299
     * }
     */

    public CDC_OPERATION getOperation(final SinkRecord record) {
        CDC_OPERATION cdcOperation = null;
        log.debug("convert()");

        Map<String, Object> convertedValue = convertValue(record);

        if(convertedValue == null) {
            log.debug("Error converting Kafka Sink Record");
            return null;
        }
        // Check "operation" represented by this record.
        if (convertedValue.containsKey(SinkRecordColumns.OPERATION)) {
            // Operation (u, c)
            String operation = (String) convertedValue.get(SinkRecordColumns.OPERATION);
            if (operation.equalsIgnoreCase(CDC_OPERATION.CREATE.operation) ||
                    operation.equalsIgnoreCase(CDC_OPERATION.READ.operation)) {
                // Inserts.
                cdcOperation = CDC_OPERATION.CREATE;
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.UPDATE.operation)) {
                // Updates.
                log.debug("UPDATE received");
                cdcOperation = CDC_OPERATION.UPDATE;
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.DELETE.operation)) {
                // Deletes.
                log.debug("DELETE received");
                cdcOperation = CDC_OPERATION.DELETE;
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.TRUNCATE.operation)) {
                // Truncates.
                log.debug("TRUNCATE received");
                cdcOperation = CDC_OPERATION.TRUNCATE;
            }
        }

        return cdcOperation;
    }
    /**
     * Primary functionality of parsing a CDC event in a SinkRecord.
     * This checks the operation flag( if its 'C' or 'U')
     * and retreives the after structure for downstream processing.
     *
     * @param record
     */
    public ClickHouseStruct convert(SinkRecord record) {
        log.debug("convert()");

        //Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);
        ClickHouseStruct chStruct = null;

        if(convertedValue == null) {
            log.debug("Error converting Kafka Sink Record");
            return chStruct;
        }
        // Check "operation" represented by this record.
        if (convertedValue.containsKey(SinkRecordColumns.OPERATION)) {
            // Operation (u, c)
            String operation = (String) convertedValue.get(SinkRecordColumns.OPERATION);
            if (operation.equalsIgnoreCase(CDC_OPERATION.CREATE.operation) ||
                    operation.equalsIgnoreCase(CDC_OPERATION.READ.operation)) {
                // Inserts.
                log.debug("CREATE received");
                chStruct = readBeforeOrAfterSection(convertedValue, record, SinkRecordColumns.AFTER, CDC_OPERATION.CREATE);
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.UPDATE.operation)) {
                // Updates.
                log.debug("UPDATE received");
                chStruct = readBeforeOrAfterSection(convertedValue, record, SinkRecordColumns.AFTER, CDC_OPERATION.UPDATE);
            } else if (operation.equalsIgnoreCase(CDC_OPERATION.DELETE.operation)) {
                // Deletes.
                log.debug("DELETE received");
                chStruct = readBeforeOrAfterSection(convertedValue, record, SinkRecordColumns.BEFORE, CDC_OPERATION.DELETE);
            } else if(operation.equalsIgnoreCase(CDC_OPERATION.TRUNCATE.operation)) {
                log.debug("TRUNCATE received");
                chStruct = readBeforeOrAfterSection(convertedValue, record, SinkRecordColumns.BEFORE, CDC_OPERATION.TRUNCATE);
            }
        }

        return chStruct;
    }

    /**
     *
     * @param convertedValue
     * @param record
     * @param sectionKey
     * @param operation
     * @return
     */
    private ClickHouseStruct readBeforeOrAfterSection(Map<String, Object> convertedValue,
                                              SinkRecord record, String sectionKey, CDC_OPERATION operation) {

        ClickHouseStruct chStruct = null;
        if (convertedValue.containsKey(sectionKey)) {
            Object beforeSection = convertedValue.get(SinkRecordColumns.BEFORE);
            Object afterSection = convertedValue.get(SinkRecordColumns.AFTER);

            chStruct = new ClickHouseStruct(record.kafkaOffset(),
                    record.topic(), (Struct) record.key(), record.kafkaPartition(),
                    record.timestamp(), (Struct) beforeSection, (Struct) afterSection,
                    convertedValue, operation);

        } else if(operation.getOperation().equalsIgnoreCase(CDC_OPERATION.TRUNCATE.operation)) {
            // Truncate does not have before/after.
            chStruct = new ClickHouseStruct(record.kafkaOffset(), record.topic(), null, record.kafkaPartition(),
                    record.timestamp(), null, null, convertedValue, operation);
        }

        return chStruct;
    }

    @Override
    public Map<String, Object> convertKey(SinkRecord record) {
        return this.convertRecord(record, KafkaSchemaRecordType.KEY);
    }

    @Override
    public Map<String, Object> convertValue(SinkRecord record) {
        return this.convertRecord(record, KafkaSchemaRecordType.VALUE);
    }

    @Override
    public Map<String, Object> convertValue(SourceRecord record) {
        KafkaSchemaRecordType what = KafkaSchemaRecordType.VALUE;
        Schema schema = what == KafkaSchemaRecordType.KEY ? record.keySchema() : record.valueSchema();
        Object obj = what == KafkaSchemaRecordType.KEY ? record.key() : record.value();
        Map<String, Object> result = null;

        if (schema == null) {
            log.debug("Schema is empty");
            if (obj instanceof Map) {
                log.info("SCHEMA LESS RECORD");
            }
        } else {
            if (schema.type() != Schema.Type.STRUCT) {
                log.error("NON STRUCT records ignored");
            } else {
                // Convert STRUCT
                result = convertStruct(obj, schema);
            }
        }
        return result;
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
            log.debug("Schema is empty");
            if (obj instanceof Map) {
                log.info("SCHEMA LESS RECORD");
            }
        } else {
            if (schema.type() != Schema.Type.STRUCT) {
                log.error("NON STRUCT records ignored");
            } else {
                // Convert STRUCT
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
            }
            //else {
            // Name is not optional
//                throw new ConversionConnectException(
//                        kafkaConnectSchema.name() + " is not optional, but converting object had null value");
            // }
        }
//        if (LogicalConverterRegistry.isRegisteredLogicalType(kafkaConnectSchema.name())) {
//            return convertLogical(kafkaConnectObject, kafkaConnectSchema);
//        }
        Schema.Type type = schema.type();
        switch (type) {
            case ARRAY:
                log.debug("ARRAY type");
                return object;
                //return convertArray(kafkaConnectObject, kafkaConnectSchema);
            case MAP:
                log.debug("MAP type");
                return object;
                //return convertMap(kafkaConnectObject, kafkaConnectSchema);
            case STRUCT:
                return object;
                //log.debug("STRUCT type");
                //return convertStruct(kafkaConnectObject, kafkaConnectSchema);
            case BYTES:
                log.debug("BYTES type");
                return object;
                //return convertBytes(kafkaConnectObject);
            case FLOAT64:
                log.debug("FLOAT64 type");
                return object;
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
                break;
                // Throw error - unrecognized type.
                //throw new ConversionConnectException("Unrecognized schema type: " + kafkaConnectSchemaType);
        }

        return null;
    }
}
