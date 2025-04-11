package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
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
}
