package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
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
     * Shared ObjectMapper for JSON serialization. Thread-safe for read/serialization operations.
     */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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

    /**
     * Timestamp in seconds from sourceOffset (ts_sec).
     */
    @Getter
    @Setter
    private long tsSec = UNINITIALIZED_VALUE;

    /**
     * Version number for ReplacingMergeTree engines.
     * This is calculated based on gtid, sequenceNumber, or lsn.
     */
    @Getter
    @Setter
    private long version = UNINITIALIZED_VALUE;

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
        parseSourceOffset(sourceRecord);
    }

    public ClickHouseStruct(){

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
        if (s != null && s.schema() != null) {
            // Include ALL fields in modifiedFields, not just non-null ones.
            // The previous code skipped fields with null values, which caused
            // UPDATE statements that set columns to NULL to silently lose those
            // changes — the NULL column would not appear in the modified fields
            // list and ClickHouse would keep the old value.
            this.beforeModifiedFields = new ArrayList<>(s.schema().fields());
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
        if (s != null && s.schema() != null) {
            // Include ALL fields in modifiedFields — see setBeforeStruct comment.
            this.afterModifiedFields = new ArrayList<>(s.schema().fields());
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
                // Debezium emits the snapshot marker as an enum string, not a plain boolean:
                // "true", "first", "first_in_data_collection", "last",
                // "last_in_data_collection" and "incremental" all denote a snapshot record,
                // while "false" denotes streaming. Boolean.parseBoolean would incorrectly
                // classify "first"/"last"/etc. as streaming records.
                this.setSnapshot(!"false".equalsIgnoreCase((String) source.get(SNAPSHOT)));
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
                // Fix: read from source, not convertedValue (was data corruption bug)
                this.setThread((Integer) source.get(SERVER_THREAD));
            }
            if (fieldNames.contains(GTID)
                    && source.get(GTID) != null
                    && source.get(GTID) instanceof String) {
                String gtidStr = (String) source.get(GTID);
                try {
                    this.setGtid(parseGtidMax(gtidStr));
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse GTID value: " + gtidStr, e);
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
                .append(" ts_sec:").append(tsSec)
                .append(" snapshot:").append(snapshot)
                .append(" server_id:").append(serverId)
                .append(" binlog_file:").append(file)
                .append(" binlog_pos:").append(pos)
                .append(" row:").append(row)
                .append(" server_thread:").append(thread)
                .toString();
    }


    /**
     * Gets the Debezium timestamp from the change event.   
     * @param changeEvent The change event.
     * @return The Debezium timestamp, or 0 if the change event is null or the value is null.
     */
    public static Long getDebeziumTsFromChangeEvent(ChangeEvent<SourceRecord, SourceRecord> changeEvent) {
        if (changeEvent == null || changeEvent.value() == null) {
            return 0L;
        }
        SourceRecord srd = changeEvent.value();
        if (!(srd.value() instanceof Struct)) {
            return 0L;
        }
        Struct kafkaStruct = (Struct) srd.value();
        if(kafkaStruct == null) {
            return 0L;
        }
        // Guard against null or wrong type for TS_MS field
        Object tsMs = kafkaStruct.get(SinkRecordColumns.TS_MS);
        if (tsMs instanceof Long) {
            return (Long) tsMs;
        }
        return 0L;
    }

    /**
     * Gets the SOURCE commit timestamp ({@code source.ts_ms}) from the change event.
     *
     * <p>Unlike {@link #getDebeziumTsFromChangeEvent} (which returns the envelope/processing
     * timestamp assigned when the connector reads the event), this returns the time the change
     * was committed at the source database. That value is identical every time Debezium
     * re-delivers an event, so anchoring the ReplacingMergeTree {@code _version} sequence to it
     * keeps the effective ordering stable across at-least-once redelivery: a re-delivered
     * DELETE keeps its original (lower) version and can no longer out-rank a later,
     * un-redelivered re-INSERT (issue #1346).</p>
     *
     * @param changeEvent The change event.
     * @return the source commit timestamp in ms; falls back to the envelope {@code ts_ms}
     *         (and finally 0) when the nested {@code source} struct or field is unavailable
     *         (e.g. some snapshot or heartbeat records).
     */
    public static Long getSourceTsFromChangeEvent(ChangeEvent<SourceRecord, SourceRecord> changeEvent) {
        if (changeEvent == null || changeEvent.value() == null) {
            return 0L;
        }
        SourceRecord srd = changeEvent.value();
        Struct kafkaStruct = (Struct) srd.value();
        if (kafkaStruct == null) {
            return 0L;
        }
        if (kafkaStruct.schema() != null
                && kafkaStruct.schema().field(SinkRecordColumns.SOURCE) != null) {
            Object sourceObj = kafkaStruct.get(SinkRecordColumns.SOURCE);
            if (sourceObj instanceof Struct) {
                Struct source = (Struct) sourceObj;
                // Snapshot records do not carry a binlog commit timestamp - their
                // source.ts_ms is the snapshot read time, whose clock semantics differ
                // between connector versions/configurations. Keep the envelope
                // (processing) timestamp for snapshots - identical to the historical
                // behavior, and snapshots are not subject to the #1346 redelivery
                // inversion (they are re-read, not re-delivered from an offset rewind).
                boolean isSnapshot = false;
                if (source.schema() != null
                        && source.schema().field(SinkRecordColumns.SNAPSHOT) != null) {
                    Object snap = source.get(SinkRecordColumns.SNAPSHOT);
                    if (snap instanceof String) {
                        isSnapshot = !"false".equalsIgnoreCase((String) snap);
                    }
                }
                if (!isSnapshot
                        && source.schema() != null
                        && source.schema().field(SinkRecordColumns.TS_MS) != null) {
                    Object sourceTs = source.get(SinkRecordColumns.TS_MS);
                    if (sourceTs instanceof Long && (Long) sourceTs > 0) {
                        return (Long) sourceTs;
                    }
                }
            }
        }
        // Fall back to the envelope timestamp when the source struct/field is absent.
        Object envelopeTs = kafkaStruct.get(SinkRecordColumns.TS_MS);
        return envelopeTs instanceof Long ? (Long) envelopeTs : 0L;
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
        
        Map<String, Object> map = new LinkedHashMap<>();
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
            ObjectNode jsonNode = JSON_MAPPER.createObjectNode();
            SourceRecord record = sourceRecord.value();
            if (record != null) {
                // Add key information
                if (record.key() != null) {
                    if (record.key() instanceof Struct) {
                        jsonNode.set("key", JSON_MAPPER.valueToTree(structToMap((Struct) record.key())));
                    } else {
                        jsonNode.put("key", record.key().toString());
                    }
                }
                // Add value information
                if (record.value() != null) {
                    if (record.value() instanceof Struct) {
                        jsonNode.set("value", JSON_MAPPER.valueToTree(structToMap((Struct) record.value())));
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
                    jsonNode.set("sourceOffset", JSON_MAPPER.valueToTree(record.sourceOffset()));
                }
                if (record.sourcePartition() != null) {
                    jsonNode.set("sourcePartition", JSON_MAPPER.valueToTree(record.sourcePartition()));
                }
                if (record.timestamp() != null) {
                    jsonNode.put("timestamp", record.timestamp());
                }
            }
            return JSON_MAPPER.writeValueAsString(jsonNode);
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
            ArrayNode jsonArray = JSON_MAPPER.createArrayNode();
            for (Field field : beforeModifiedFields) {
                ObjectNode fieldNode = JSON_MAPPER.createObjectNode();
                fieldNode.put("name", field.name());
                fieldNode.put("index", field.index());
                if (field.schema() != null) {
                    ObjectNode schemaNode = JSON_MAPPER.createObjectNode();
                    schemaNode.put("type", field.schema().type().toString());
                    if (field.schema().name() != null) {
                        schemaNode.put("name", field.schema().name());
                    }
                    schemaNode.put("optional", field.schema().isOptional());
                    fieldNode.set("schema", schemaNode);
                }
                if (beforeStruct != null) {
                    try {
                        Object value = beforeStruct.get(field);
                        if (value != null) {
                            if (value instanceof Struct) {
                                fieldNode.set("value", JSON_MAPPER.valueToTree(structToMap((Struct) value)));
                            } else {
                                fieldNode.set("value", JSON_MAPPER.valueToTree(value));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not get value for field: " + field.name(), e);
                    }
                }
                jsonArray.add(fieldNode);
            }
            return JSON_MAPPER.writeValueAsString(jsonArray);
        } catch (Exception e) {
            log.error("Error serializing beforeModifiedFields to JSON", e);
            return "";
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
            ArrayNode jsonArray = JSON_MAPPER.createArrayNode();
            for (Field field : afterModifiedFields) {
                ObjectNode fieldNode = JSON_MAPPER.createObjectNode();
                fieldNode.put("name", field.name());
                fieldNode.put("index", field.index());
                if (field.schema() != null) {
                    ObjectNode schemaNode = JSON_MAPPER.createObjectNode();
                    schemaNode.put("type", field.schema().type().toString());
                    if (field.schema().name() != null) {
                        schemaNode.put("name", field.schema().name());
                    }
                    schemaNode.put("optional", field.schema().isOptional());
                    fieldNode.set("schema", schemaNode);
                }
                if (afterStruct != null) {
                    try {
                        Object value = afterStruct.get(field);
                        if (value != null) {
                            if (value instanceof Struct) {
                                fieldNode.set("value", JSON_MAPPER.valueToTree(structToMap((Struct) value)));
                            } else {
                                fieldNode.set("value", JSON_MAPPER.valueToTree(value));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not get value for field: " + field.name(), e);
                    }
                }
                jsonArray.add(fieldNode);
            }
            return JSON_MAPPER.writeValueAsString(jsonArray);
        } catch (Exception e) {
            log.error("Error serializing afterModifiedFields to JSON", e);
            return "";
        }
    }

    /**
     * Parses the sourceOffset map to extract ts_sec value.
     * This method is called during construction with the sourceRecord.
     *
     * @param sourceRecord The change event containing the source record
     */
    private void parseSourceOffset(ChangeEvent<SourceRecord, SourceRecord> sourceRecord) {
        if (sourceRecord == null || sourceRecord.value() == null) {
            return;
        }
        
        try {
            SourceRecord record = sourceRecord.value();
            Map<String, ?> sourceOffset = record.sourceOffset();
            
            if (sourceOffset != null && sourceOffset.containsKey("ts_sec")) {
                Object tsSecValue = sourceOffset.get("ts_sec");
                if (tsSecValue instanceof Long) {
                    this.tsSec = (Long) tsSecValue;
                } else if (tsSecValue instanceof Integer) {
                    this.tsSec = ((Integer) tsSecValue).longValue();
                } else if (tsSecValue instanceof String) {
                    this.tsSec = Long.parseLong((String) tsSecValue);
                }
                log.debug("Parsed ts_sec from sourceOffset: {}", this.tsSec);
            }
        } catch (Exception e) {
            log.error("Error parsing ts_sec from sourceOffset", e);
        }
    }

    /**
     * Parses a MySQL GTID string and returns the maximum transaction ID.
     * Handles formats:
     *   - Simple: uuid:N
     *   - Range: uuid:1-5
     *   - Multi-range: uuid:1-5:7-10
     *   - Multi-source: uuid1:1-5,uuid2:1-3
     *
     * @param gtidStr the raw GTID string from Debezium
     * @return the maximum transaction ID across all sources and ranges
     * @throws NumberFormatException if the GTID contains unparseable numbers
     */
    static long parseGtidMax(String gtidStr) {
        long maxGtid = -1;
        // Split by comma for multi-source GTIDs
        String[] sources = gtidStr.split(",");
        for (String src : sources) {
            // Split by colon: first part is UUID, rest are ranges
            String[] parts = src.trim().split(":");
            for (int i = 1; i < parts.length; i++) {
                String range = parts[i].trim();
                long val;
                if (range.contains("-")) {
                    // Range format (e.g., "1-5"): take the end value
                    String endStr = range.substring(
                            range.lastIndexOf('-') + 1);
                    val = Long.parseLong(endStr);
                } else {
                    val = Long.parseLong(range);
                }
                maxGtid = Math.max(maxGtid, val);
            }
        }
        return maxGtid;
    }

    /**
     * Calculates and sets the version based on gtid, sequenceNumber, or lsn.
     * Uses SnowFlakeId algorithm if useSnowflakeId is true and gtid is available.
     *
     * @param useSnowflakeId Whether to use SnowFlakeId algorithm for version generation
     */
    public void calculateVersion(boolean useSnowflakeId) {
        if (this.gtid != UNINITIALIZED_VALUE) {
            if (useSnowflakeId) {
                this.version = SnowFlakeId.generate(this.ts_ms, this.gtid, false);
            } else {
                this.version = this.gtid;
            }
        } else if (this.sequenceNumber != UNINITIALIZED_VALUE) {
            this.version = this.sequenceNumber;
        } else if (this.lsn != UNINITIALIZED_VALUE) {
            this.version = this.lsn;
        }
    }
}
