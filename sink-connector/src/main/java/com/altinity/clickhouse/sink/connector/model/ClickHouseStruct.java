package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.altinity.clickhouse.sink.connector.model.SinkRecordColumns.*;

/**
 * Class that wraps the Kafka Connect {@link Struct} with extra Kafka-related
 * metadata. (Mostly from SinkRecord.)
 * <p>
 * This class holds various fields that capture both before and after states
 * of a database record, along with metadata such as offsets, timestamps,
 * thread, and GTID information.
 * </p>
 */
public class ClickHouseStruct {

    /**
     * Logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseStruct.class);

    /**
     * Default value for positions set to zero.
     */
    private static final long DEFAULT_POS_VALUE = 0L;

    /**
     * Default sentinel value for uninitialized fields set to -1.
     */
    private static final long UNINITIALIZED_VALUE = -1L;

    /**
     * Expected length of the GTID array.
     */
    private static final int EXPECTED_GTID_ARRAY_LENGTH = 2;

    /**
     * Index to parse the second segment from the GTID array.
     */
    private static final int GTID_SEGMENT_INDEX = 1;

    /**
     * Offset in Kafka. Matches the SinkRecord offset.
     */
    @Getter
    @Setter
    private long kafkaOffset;

    /**
     * Topic name for the record.
     */
    @Getter
    @Setter
    private String topic;

    /**
     * Partition number within the topic.
     */
    @Getter
    @Setter
    private Integer kafkaPartition;

    /**
     * Timestamp of the record, usually from the SinkRecord.
     */
    @Getter
    @Setter
    private Long timestamp;

    /**
     * Key for the record, usually from SinkRecord key.
     */
    @Getter
    @Setter
    private String key;

    /**
     * A list of primary key field names extracted from the key {@link Struct}.
     */
    @Getter
    @Setter
    private ArrayList<String> primaryKey;

    /**
     * Timestamp (in ms) when the change occurred in the database.
     */
    @Getter
    @Setter
    private long ts_ms;

    /**
     * Timestamp (in ms) when Debezium processed the record.
     */
    @Getter
    @Setter
    private long debezium_ts_ms;

    /**
     * Whether the record is part of a snapshot or not.
     */
    @Getter
    @Setter
    private boolean snapshot;

    /**
     * Server ID of the database server that produced this record.
     */
    @Getter
    @Setter
    private Long serverId;

    /**
     * Binlog file name (for MySQL) or WAL file (for other databases).
     */
    @Getter
    @Setter
    private String file = "";

    /**
     * Binlog position in the file.
     */
    @Getter
    @Setter
    private Long pos = DEFAULT_POS_VALUE;

    /**
     * Row number within the binlog file event.
     */
    @Getter
    @Setter
    private int row;

    /**
     * Database server thread identifier.
     */
    @Getter
    @Setter
    private int thread;

    /**
     * Global Transaction ID value, if available.
     */
    @Getter
    @Setter
    private long gtid = UNINITIALIZED_VALUE;

    /**
     * Name of the database the record belongs to.
     */
    @Getter
    @Setter
    private String database;

    /**
     * Sequence number for the record, if applicable.
     */
    @Getter
    @Setter
    private long sequenceNumber = UNINITIALIZED_VALUE;

    /**
     * Log Sequence Number (LSN) offset for some databases, if applicable.
     */
    @Getter
    @Setter
    private long lsn = UNINITIALIZED_VALUE;

    // Inheritance doesn't work because of different package
    // error, composition.

    /**
     * Struct representing the state of the record before the change.
     */
    @Getter
    @Setter
    Struct beforeStruct;

    /**
     * Struct representing the state of the record after the change.
     */
    @Getter
    @Setter
    Struct afterStruct;

    /**
     * List of {@link Field} objects in the {@code beforeStruct} that
     * were modified.
     */
    @Getter
    @Setter
    List<Field> beforeModifiedFields;

    /**
     * List of {@link Field} objects in the {@code afterStruct} that
     * were modified.
     */
    @Getter
    @Setter
    List<Field> afterModifiedFields;

    /**
     * Represents the CDC operation (CREATE, UPDATE, DELETE, etc.).
     */
    @Getter
    @Setter
    ClickHouseConverter.CDC_OPERATION cdcOperation;

    /**
     * Debezium record committer instance.
     */
    @Getter
    @Setter
    DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
            committer;

    /**
     * The underlying change event from Debezium.
     */
    @Getter
    @Setter
    ChangeEvent<SourceRecord, SourceRecord> sourceRecord;

    /**
     * Indicator if this is the last record in a batch.
     */
    @Getter
    @Setter
    boolean lastRecordInBatch;

    /**
     * Constructs a ClickHouseStruct with commit info.
     *
     * @param kafkaOffset  Offset in Kafka.
     * @param topic        Topic name.
     * @param key          Struct representing the key.
     * @param kafkaPartition Partition number within the topic.
     * @param timestamp    Timestamp of the record.
     * @param beforeStruct Struct state before the change.
     * @param afterStruct  Struct state after the change.
     * @param metadata     Map with additional metadata fields.
     * @param operation    The CDC operation.
     * @param sourceRecord Debezium change event record.
     * @param committer    Debezium record committer.
     * @param lastRecordInBatch Boolean indicating if this is the last record
     *                          in the batch.
     */
    public ClickHouseStruct(
            long kafkaOffset,
            String topic,
            Struct key,
            Integer kafkaPartition,
            Long timestamp,
            Struct beforeStruct,
            Struct afterStruct,
            Map<String, Object> metadata,
            ClickHouseConverter.CDC_OPERATION operation,
            ChangeEvent<SourceRecord, SourceRecord> sourceRecord,
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                    committer,
            boolean lastRecordInBatch
    ) {
        this(
                kafkaOffset,
                topic,
                key,
                kafkaPartition,
                timestamp,
                beforeStruct,
                afterStruct,
                metadata,
                operation
        );
        this.setCommitter(committer);
        this.setSourceRecord(sourceRecord);
        this.setLastRecordInBatch(lastRecordInBatch);
    }

    /**
     * Constructs a ClickHouseStruct without commit info.
     *
     * @param kafkaOffset   Offset in Kafka.
     * @param topic         Topic name.
     * @param key           Struct representing the key.
     * @param kafkaPartition Partition number within the topic.
     * @param timestamp     Timestamp of the record.
     * @param beforeStruct  Struct state before the change.
     * @param afterStruct   Struct state after the change.
     * @param metadata      Map with additional metadata fields.
     * @param operation     The CDC operation.
     */
    public ClickHouseStruct(
            long kafkaOffset,
            String topic,
            Struct key,
            Integer kafkaPartition,
            Long timestamp,
            Struct beforeStruct,
            Struct afterStruct,
            Map<String, Object> metadata,
            ClickHouseConverter.CDC_OPERATION operation
    ) {
        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        if (key != null) {
            this.key = key.toString();
            Schema pkSchema = key.schema();
            if (pkSchema != null) {
                List<Field> fields = pkSchema.fields();
                if (fields != null && !fields.isEmpty()) {
                    this.primaryKey = new ArrayList<>();
                    for (Field f : fields) {
                        if (f.name() != null) {
                            this.primaryKey.add(f.name());
                        }
                    }
                }
            }
        }
        setBeforeStruct(beforeStruct);
        setAfterStruct(afterStruct);
        this.setAdditionalMetaData(metadata);
        this.cdcOperation = operation;
    }

    /**
     * Sets the {@code beforeStruct} and identifies the fields
     * that were modified (i.e. not null).
     *
     * @param s The struct representing the state before the change.
     */
    public void setBeforeStruct(Struct s) {
        this.beforeStruct = s;
        if (s != null) {
            List<Field> schemaFields = s.schema().fields();
            this.beforeModifiedFields = new ArrayList<>();
            for (Field f : schemaFields) {
                // Identify the list of columns that were modified.
                // Schema.fields() will give the list of columns in the schema.
                if (s.get(f) != null) {
                    this.beforeModifiedFields.add(f);
                }
            }
        }
    }

    /**
     * Sets the {@code afterStruct} and identifies the fields
     * that were modified (i.e. not null).
     *
     * @param s The struct representing the state after the change.
     */
    public void setAfterStruct(Struct s) {
        this.afterStruct = s;
        if (s != null) {
            List<Field> schemaFields = s.schema().fields();
            this.afterModifiedFields = new ArrayList<>();
            for (Field f : schemaFields) {
                // Identify the list of columns that were modified.
                // Schema.fields() will give the list of columns in the schema.
                if (s.get(f) != null) {
                    this.afterModifiedFields.add(f);
                }
            }
        }
    }

    /**
     * Function to get additional kafka metadata not stored in SinkRecord.
     *
     * @param convertedValue Map of metadata fields extracted from
     *                       the Debezium record.
     */
    public void setAdditionalMetaData(Map<String, Object> convertedValue) {
        if (convertedValue == null
                || false == convertedValue.containsKey(SOURCE)) {
            return;
        }
        Struct source = (Struct) convertedValue.get(SOURCE);
        List<Field> fields = source.schema().fields();
        HashSet<String> fieldNames = new HashSet<>();
        for (Field f : fields) {
            fieldNames.add(f.name());
        }
        try {
            if (fieldNames.contains(TS_MS)
                    && source.get(TS_MS) != null
                    && source.get(TS_MS) instanceof Long) {
                // indicates the time that the change was made in the database.
                this.setTs_ms((Long) source.get(TS_MS));
            }
            if (convertedValue.get(TS_MS) != null) {
                this.setDebezium_ts_ms((Long) convertedValue.get(TS_MS));
            }
            if (fieldNames.contains(SNAPSHOT)
                    && source.get(SNAPSHOT) != null
                    && source.get(SNAPSHOT) instanceof String) {
                this.setSnapshot(Boolean.parseBoolean(
                        (String) source.get(SNAPSHOT)));
            }
            if (fieldNames.contains(SERVER_ID)
                    && source.get(SERVER_ID) != null
                    && source.get(SERVER_ID) instanceof Long) {
                this.setServerId((Long) source.get(SERVER_ID));
            }
            if (fieldNames.contains(BINLOG_FILE)
                    && source.get(BINLOG_FILE) != null
                    && source.get(BINLOG_FILE) instanceof String) {
                this.setFile((String) source.get(BINLOG_FILE));
            }
            if (fieldNames.contains(BINLOG_POS)
                    && source.get(BINLOG_POS) != null
                    && source.get(BINLOG_POS) instanceof Long) {
                this.setPos((Long) source.get(BINLOG_POS));
            }
            if (fieldNames.contains(ROW)
                    && source.get(ROW) != null
                    && source.get(ROW) instanceof Integer) {
                this.setRow((Integer) source.get(ROW));
            }
            if (fieldNames.contains(SERVER_THREAD)
                    && source.get(SERVER_THREAD) != null
                    && source.get(SERVER_THREAD) instanceof Integer) {
                this.setThread((Integer) convertedValue.get(SERVER_THREAD));
            }
            if (fieldNames.contains(GTID)
                    && source.get(GTID) != null
                    && source.get(GTID) instanceof String) {
                String[] gtidArray = ((String) source.get(GTID)).split(":");
                if (gtidArray.length == EXPECTED_GTID_ARRAY_LENGTH) {
                    this.setGtid(Long.parseLong(
                            gtidArray[GTID_SEGMENT_INDEX]));
                }
            }
            if (fieldNames.contains(LSN)
                    && source.get(LSN) != null
                    && source.get(LSN) instanceof Long) {
                this.setLsn((Long) source.get(LSN));
            }
            if (fieldNames.contains(DATABASE)
                    && source.get(DATABASE) != null
                    && source.get(DATABASE) instanceof String) {
                this.setDatabase((String) source.get(DATABASE));
            }
        } catch (Exception e) {
            log.error("setAdditionalMetadata exception", e);
        }
    }

    /**
     * Calculates replication lag by comparing the current system time
     * with the {@code ts_ms} field from the source event.
     *
     * @return The replication lag in milliseconds, or 0 if {@code ts_ms}
     *         is not set.
     */
    public long getReplicationLag() {
        if (this.getTs_ms() > 0) {
            return System.currentTimeMillis() - this.getTs_ms();
        }
        return 0;
    }

    /**
     * Returns a string representation of the {@link ClickHouseStruct},
     * including Kafka offset, topic, partition, binlog file,
     * and other relevant metadata.
     *
     * @return String describing the current instance.
     */
    @Override
    public String toString() {
        return new StringBuffer()
                .append(" offset:").append(kafkaOffset)
                .append(" topic:").append(topic)
                .append(" partition:").append(kafkaPartition)
                .append(" key:").append(key)
                .append(" ts_ms:").append(ts_ms)
                .append(" snapshot:").append(snapshot)
                .append(" server_id").append(serverId)
                .append(" binlog_file").append(file)
                .append(" binlog_pos").append(pos)
                .append(" row").append(row)
                .append(" server_thread").append(thread)
                .toString();
    }

    /**
     * Converts a Kafka Connect Struct to a Map for JSON serialization.
     *
     * @param struct The Kafka Connect Struct to convert
     * @return A Map representation of the Struct, or null if struct is null
     */
    private Map<String, Object> structToMap(Struct struct) {
        if (struct == null) {
            return null;
        }
        
        Map<String, Object> map = new HashMap<>();
        for (Field field : struct.schema().fields()) {
            try {
                Object value = struct.get(field);
                if (value instanceof Struct) {
                    map.put(field.name(), structToMap((Struct) value));
                } else {
                    map.put(field.name(), value);
                }
            } catch (Exception e) {
                log.debug("Could not get value for field: " + field.name(), e);
            }
        }
        return map;
    }

    /**
     * Serializes the sourceRecord to JSON format.
     *
     * @return JSON string representation of the sourceRecord, or null if sourceRecord is null
     */
    public String sourceRecordToJson() {
        if (sourceRecord == null) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode jsonNode = mapper.createObjectNode();
            
            SourceRecord record = sourceRecord.value();
            if (record != null) {
                // Add key information
                if (record.key() != null) {
                    if (record.key() instanceof Struct) {
                        jsonNode.set("key", mapper.valueToTree(structToMap((Struct) record.key())));
                    } else {
                        jsonNode.put("key", record.key().toString());
                    }
                }
                
                // Add value information
                if (record.value() != null) {
                    if (record.value() instanceof Struct) {
                        jsonNode.set("value", mapper.valueToTree(structToMap((Struct) record.value())));
                    } else {
                        jsonNode.put("value", record.value().toString());
                    }
                }
                
                // Add topic, partition, and offset
                jsonNode.put("topic", record.topic());
                if (record.kafkaPartition() != null) {
                    jsonNode.put("partition", record.kafkaPartition());
                }
                if (record.sourceOffset() != null) {
                    jsonNode.set("sourceOffset", mapper.valueToTree(record.sourceOffset()));
                }
                if (record.sourcePartition() != null) {
                    jsonNode.set("sourcePartition", mapper.valueToTree(record.sourcePartition()));
                }
                
                // Add timestamp if available
                if (record.timestamp() != null) {
                    jsonNode.put("timestamp", record.timestamp());
                }
            }
            
            return mapper.writeValueAsString(jsonNode);
        } catch (Exception e) {
            log.error("Error serializing sourceRecord to JSON", e);
            return null;
        }
    }

    /**
     * Serializes the beforeModifiedFields to JSON format.
     *
     * @return JSON string representation of the beforeModifiedFields, or null if beforeModifiedFields is null
     */
    public String beforeModifiedFieldsToJson() {
        if (beforeModifiedFields == null) {
            return "";
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode jsonArray = mapper.createArrayNode();
            
            for (Field field : beforeModifiedFields) {
                ObjectNode fieldNode = mapper.createObjectNode();
                fieldNode.put("name", field.name());
                fieldNode.put("index", field.index());
                
                // Add schema information
                if (field.schema() != null) {
                    ObjectNode schemaNode = mapper.createObjectNode();
                    schemaNode.put("type", field.schema().type().toString());
                    if (field.schema().name() != null) {
                        schemaNode.put("name", field.schema().name());
                    }
                    schemaNode.put("optional", field.schema().isOptional());
                    fieldNode.set("schema", schemaNode);
                }
                
                // Add field value from beforeStruct if available
                if (beforeStruct != null) {
                    try {
                        Object value = beforeStruct.get(field);
                        if (value != null) {
                            if (value instanceof Struct) {
                                fieldNode.set("value", mapper.valueToTree(structToMap((Struct) value)));
                            } else {
                                fieldNode.set("value", mapper.valueToTree(value));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not get value for field: " + field.name(), e);
                    }
                }
                
                jsonArray.add(fieldNode);
            }
            
            return mapper.writeValueAsString(jsonArray);
        } catch (Exception e) {
            log.error("Error serializing beforeModifiedFields to JSON", e);
            return null;
        }
    }

    /**
     * Serializes the afterModifiedFields to JSON format.
     *
     * @return JSON string representation of the afterModifiedFields, or null if afterModifiedFields is null
     */
    public String afterModifiedFieldsToJson() {
        if (afterModifiedFields == null) {
            return "";
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode jsonArray = mapper.createArrayNode();
            
            for (Field field : afterModifiedFields) {
                ObjectNode fieldNode = mapper.createObjectNode();
                fieldNode.put("name", field.name());
                fieldNode.put("index", field.index());
                
                // Add schema information
                if (field.schema() != null) {
                    ObjectNode schemaNode = mapper.createObjectNode();
                    schemaNode.put("type", field.schema().type().toString());
                    if (field.schema().name() != null) {
                        schemaNode.put("name", field.schema().name());
                    }
                    schemaNode.put("optional", field.schema().isOptional());
                    fieldNode.set("schema", schemaNode);
                }
                
                // Add field value from afterStruct if available
                if (afterStruct != null) {
                    try {
                        Object value = afterStruct.get(field);
                        if (value != null) {
                            if (value instanceof Struct) {
                                fieldNode.set("value", mapper.valueToTree(structToMap((Struct) value)));
                            } else {
                                fieldNode.set("value", mapper.valueToTree(value));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not get value for field: " + field.name(), e);
                    }
                }
                
                jsonArray.add(fieldNode);
            }
            
            return mapper.writeValueAsString(jsonArray);
        } catch (Exception e) {
            log.error("Error serializing afterModifiedFields to JSON", e);
            return null;
        }
    }
}
