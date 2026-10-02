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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickHouseConverter is responsible for converting Kafka Sink or
 * Source records into a map structure for subsequent processing.
 *
 * <p>This converter interprets CDC operations (create, read, update,
 * delete, truncate), checks the before/after states of the record,
 * and produces a {@link ClickHouseStruct} when applicable.
 */
public class ClickHouseConverter implements AbstractConverter {

    /**
     * Logger instance for the ClickHouseConverter class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseConverter.class);

    /**
     * Enum to store the OP Types.
     * Refer:
     * https://debezium.io/documentation/reference/stable/connectors/mysql.html
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

        /**
         * Gets the operation string associated with this enum constant.
         *
         * @return the operation string.
         */
        public String getOperation() {
            return operation;
        }

        /**
         * Constructs a CDC_OPERATION with the specified op string.
         *
         * @param op the operation string (e.g., "r" for read).
         */
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

    /**
     * Retrieves the CDC_OPERATION for a given SinkRecord by checking the
     * op field in the converted record.
     *
     * @param record the SinkRecord to analyze
     * @return the corresponding CDC_OPERATION, or null if conversion fails
     */
    public CDC_OPERATION getOperation(final SinkRecord record) {
        CDC_OPERATION cdcOperation = null;
        log.debug("convert()");
        Map<String, Object> convertedValue = convertValue(record);
        if (convertedValue == null) {
            log.debug("Error converting Kafka Sink Record");
            return null;
        }
        // Check "operation" represented by this record.
        if (convertedValue.containsKey(SinkRecordColumns.OPERATION)) {
            // Operation (u, c)
            String operation = (String) convertedValue.get(
                    SinkRecordColumns.OPERATION);
            if (operation.equalsIgnoreCase(
                    CDC_OPERATION.CREATE.operation)
                    || operation.equalsIgnoreCase(
                    CDC_OPERATION.READ.operation)) {
                // Inserts.
                cdcOperation = CDC_OPERATION.CREATE;
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.UPDATE.operation)) {
                // Updates.
                log.debug("UPDATE received");
                cdcOperation = CDC_OPERATION.UPDATE;
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.DELETE.operation)) {
                // Deletes.
                log.debug("DELETE received");
                cdcOperation = CDC_OPERATION.DELETE;
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.TRUNCATE.operation)) {
                // Truncates.
                log.debug("TRUNCATE received");
                cdcOperation = CDC_OPERATION.TRUNCATE;
            }
        }
        return cdcOperation;
    }

    /**
     * Parses a CDC event in a SinkRecord. This checks the operation flag
     * and retrieves the after structure for downstream processing.
     *
     * @param record the SinkRecord to parse
     * @return a ClickHouseStruct representing the parsed data
     */
    public ClickHouseStruct convert(SinkRecord record) {
        log.debug("convert()");
        //Map<String, Object> convertedKey = convertKey(record);
        Map<String, Object> convertedValue = convertValue(record);
        ClickHouseStruct chStruct = null;
        if (convertedValue == null) {
            log.debug("Error converting Kafka Sink Record");
            return chStruct;
        }
        // Check "operation" represented by this record.
        if (convertedValue.containsKey(SinkRecordColumns.OPERATION)) {
            // Operation (u, c)
            String operation = (String) convertedValue.get(
                    SinkRecordColumns.OPERATION);
            if (operation.equalsIgnoreCase(
                    CDC_OPERATION.CREATE.operation)
                    || operation.equalsIgnoreCase(
                    CDC_OPERATION.READ.operation)) {
                // Inserts.
                log.debug("CREATE received");
                chStruct = readBeforeOrAfterSection(
                        convertedValue, record,
                        SinkRecordColumns.AFTER,
                        CDC_OPERATION.CREATE);
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.UPDATE.operation)) {
                // Updates.
                log.debug("UPDATE received");
                chStruct = readBeforeOrAfterSection(
                        convertedValue, record,
                        SinkRecordColumns.AFTER,
                        CDC_OPERATION.UPDATE);
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.DELETE.operation)) {
                // Deletes.
                log.debug("DELETE received");
                chStruct = readBeforeOrAfterSection(
                        convertedValue, record,
                        SinkRecordColumns.BEFORE,
                        CDC_OPERATION.DELETE);
            } else if (operation.equalsIgnoreCase(
                    CDC_OPERATION.TRUNCATE.operation)) {
                log.debug("TRUNCATE received");
                chStruct = readBeforeOrAfterSection(
                        convertedValue, record,
                        SinkRecordColumns.BEFORE,
                        CDC_OPERATION.TRUNCATE);
            }
        }
        return chStruct;
    }

    /**
     * Reads the before or after section from the convertedValue map.
     *
     * @param convertedValue the map of converted key/value pairs
     * @param record the original SinkRecord
     * @param sectionKey the key indicating which section (before/after)
     * @param operation the CDC operation (e.g., CREATE, UPDATE)
     * @return a ClickHouseStruct populated with the relevant fields
     */
    private ClickHouseStruct readBeforeOrAfterSection(
            Map<String, Object> convertedValue,
            SinkRecord record,
            String sectionKey,
            CDC_OPERATION operation) {

        ClickHouseStruct chStruct = null;
        if (convertedValue.containsKey(sectionKey)) {
            Object beforeSection = convertedValue.get(
                    SinkRecordColumns.BEFORE);
            Object afterSection = convertedValue.get(
                    SinkRecordColumns.AFTER);
            chStruct = new ClickHouseStruct(record.kafkaOffset(),
                    record.topic(), (Struct) record.key(),
                    record.kafkaPartition(), record.timestamp(),
                    (Struct) beforeSection, (Struct) afterSection,
                    convertedValue, operation);
        } else if (operation.getOperation().equalsIgnoreCase(
                CDC_OPERATION.TRUNCATE.operation)) {
            // Truncate does not have before/after.
            chStruct = new ClickHouseStruct(record.kafkaOffset(),
                    record.topic(), null, record.kafkaPartition(),
                    record.timestamp(), null, null, convertedValue,
                    operation);
        }
        return chStruct;
    }

    @Override
    public Map<String, Object> convertKey(SinkRecord record) {
        return this.convertRecord(record,
                KafkaSchemaRecordType.KEY);
    }

    @Override
    public Map<String, Object> convertValue(SinkRecord record) {
        return this.convertRecord(record,
                KafkaSchemaRecordType.VALUE);
    }

    @Override
    public Map<String, Object> convertValue(SourceRecord record) {
        KafkaSchemaRecordType what = KafkaSchemaRecordType.VALUE;
        Schema schema = (what == KafkaSchemaRecordType.KEY)
                ? record.keySchema() : record.valueSchema();
        Object obj = (what == KafkaSchemaRecordType.KEY)
                ? record.key() : record.value();
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
     * Converts a SinkRecord to a map based on whether it's a key or value.
     *
     * @param record the SinkRecord
     * @param what enum indicating KEY or VALUE
     * @return a map of fields from the SinkRecord
     */
    public Map<String, Object> convertRecord(
            SinkRecord record,
            KafkaSchemaRecordType what) {
        Schema schema = (what == KafkaSchemaRecordType.KEY)
                ? record.keySchema() : record.valueSchema();
        Object obj = (what == KafkaSchemaRecordType.KEY)
                ? record.key() : record.value();
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
     * Converts a struct object into a map of field names and values.
     *
     * @param object the object to be converted
     * @param schema the schema associated with the object
     * @return a map representation of the struct
     */
    private Map<String, Object> convertStruct(
            Object object, Schema schema) {
        // Object to be converted assumed to be a struct
        Struct struct = (Struct) object;
        // Result record would be a map
        Map<String, Object> record = new HashMap<>();
        // Fields of the struct
        List<Field> fields = schema.fields();
        // Convert all fields of the struct into a map
        for (Field field : fields) {
            // Ignore empty structures
            boolean isEmptyStruct =
                    (field.schema().type() == Schema.Type.STRUCT)
                            && (field.schema().fields().isEmpty());
            if (!isEmptyStruct) {
                // Not empty struct
                Object convertedObject = convertObject(
                        struct.get(field.name()),
                        field.schema());
                if (convertedObject != null) {
                    record.put(field.name(), convertedObject);
                }
            }
        }
        return record;
    }

    /**
     * Converts an object based on the schema type.
     *
     * @param object the object to convert
     * @param schema the schema describing the object
     * @return the converted object, or null if not supported
     */
    private Object convertObject(Object object, Schema schema) {
        if (object == null) {
            if (schema.isOptional()) {
                // short circuit converting the object
                return null;
            }
            // else, field is not optional
            // (leaving the original comments intact)
        }
        Schema.Type type = schema.type();
        switch (type) {
            case ARRAY:
                log.debug("ARRAY type");
                return object;
            case MAP:
                log.debug("MAP type");
                return object;
            case STRUCT:
                return object;
            case BYTES:
                log.debug("BYTES type");
                return object;
            case FLOAT64:
                log.debug("FLOAT64 type");
                return object;
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
                // (leaving original commented code)
                break;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Static helpers for Debezium SourceRecord schema extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts the field name → Debezium {@link Schema} mapping from a Debezium
     * SourceRecord value schema.
     *
     * <p>In a Debezium envelope the value schema looks like:
     * <pre>
     * Envelope {
     *   before: Struct { id INT, name STRING, … }
     *   after:  Struct { id INT, name STRING, new_col FLOAT64, … }   ← preferred
     *   source: Struct { … }
     *   op:     STRING
     * }
     * </pre>
     *
     * <p>For DELETE events {@code after} is {@code null}, so {@code before} is used
     * as the fallback.  For all other event types {@code after} is used.
     *
     * @param record the Debezium {@link SourceRecord}
     * @return ordered map of field name → field {@link Schema}, or {@code null}
     *         if the schema cannot be extracted
     */
    public static Map<String, Schema> extractDebeziumSchema(SourceRecord record) {
        try {
            Schema valueSchema = record.valueSchema();
            if (valueSchema == null) {
                return null;
            }

            // Prefer "after" (INSERT / UPDATE); fall back to "before" (DELETE).
            Schema rowSchema = null;

            Field afterField = valueSchema.field("after");
            if (afterField != null && afterField.schema() != null
                    && afterField.schema().type() == Schema.Type.STRUCT) {
                // For DELETE, the "after" value in the Struct will be null, so check the value too.
                Struct valueStruct = record.value() instanceof Struct ? (Struct) record.value() : null;
                Object afterValue = (valueStruct != null) ? safeGet(valueStruct, "after") : null;
                if (afterValue != null) {
                    rowSchema = afterField.schema();
                }
            }

            if (rowSchema == null) {
                Field beforeField = valueSchema.field("before");
                if (beforeField != null && beforeField.schema() != null
                        && beforeField.schema().type() == Schema.Type.STRUCT) {
                    rowSchema = beforeField.schema();
                }
            }

            if (rowSchema == null) {
                return null;
            }

            List<Field> fields = rowSchema.fields();
            if (fields == null || fields.isEmpty()) {
                return null;
            }

            Map<String, Schema> result = new LinkedHashMap<>(fields.size());
            for (Field field : fields) {
                result.put(field.name(), field.schema());
            }
            return result;

        } catch (Exception e) {
            log.debug("Could not extract Debezium schema from SourceRecord: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Safely retrieves a field value from a {@link Struct}, returning {@code null}
     * if the field does not exist or the access throws.
     *
     * @param struct    the Struct to read from
     * @param fieldName the field name to retrieve
     * @return the field value, or {@code null} if not available
     */
    public static Object safeGet(Struct struct, String fieldName) {
        try {
            return struct.get(fieldName);
        } catch (Exception e) {
            return null;
        }
    }
}
