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
 * Class that wraps the Kafka Connect Struct
 * with extra kafka related metadata.
 * (Mostly from SinkRecord.
 */
public class ClickHouseStruct {

    @Getter
    @Setter
    private long kafkaOffset;
    @Getter
    @Setter
    private String topic;
    @Getter
    @Setter
    private Integer kafkaPartition;
    @Getter
    @Setter
    private Long timestamp;

    @Getter
    @Setter
    private String key;

    @Getter
    @Setter
    private ArrayList<String> primaryKey;

    // Database processed timestamp
    @Getter
    @Setter
    private long ts_ms;

    @Getter
    @Setter
    private long debezium_ts_ms;

    @Getter
    @Setter
    private boolean snapshot;

    @Getter
    @Setter
    private Long serverId;

    @Getter
    @Setter
    private String file = "";

    @Getter
    @Setter
    private Long pos = 0L;

    @Getter
    @Setter
    private int row;

    @Getter
    @Setter
    private int thread;

    @Getter
    @Setter
    private long gtid = -1;

    @Getter
    @Setter
    private String database;
  
    @Getter
    @Setter
    private long sequenceNumber = -1;

    @Getter
    @Setter
    // The insert position is described by a Log Sequence Number (LSN) that is a byte offset into the logs,
    // increasing monotonically with each new record. LSN values are returned as the datatype pg_lsn.
    // Values can be compared to calculate the volume of WAL data that separates them,
    // so they are used to measure the progress of replication and recovery.
    private long lsn = -1;

    // Inheritance doesn't work because of different package
    // error, composition.
    @Getter
    @Setter
    Struct beforeStruct;

    @Getter
    @Setter
    Struct afterStruct;

    @Getter
    @Setter
    List<Field> beforeModifiedFields;

    @Getter
    @Setter
    List<Field> afterModifiedFields;


    @Getter
    @Setter
    ClickHouseConverter.CDC_OPERATION cdcOperation;

    @Getter
    @Setter
    DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer;

    @Getter
    @Setter
    ChangeEvent<SourceRecord, SourceRecord> sourceRecord;

    @Getter
    @Setter
    boolean lastRecordInBatch;

    private static final Logger log = LogManager.getLogger(ClickHouseStruct.class);

    public ClickHouseStruct(long kafkaOffset, String topic, Struct key, Integer kafkaPartition,
                            Long timestamp, Struct beforeStruct, Struct afterStruct, Map<String, Object> metadata,
                            ClickHouseConverter.CDC_OPERATION operation,
                            ChangeEvent<SourceRecord, SourceRecord> sourceRecord,
                            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer, boolean lastRecordInBatch) {
        this(kafkaOffset, topic, key, kafkaPartition, timestamp, beforeStruct, afterStruct, metadata, operation);
        this.setCommitter(committer);
        this.setSourceRecord(sourceRecord);
        this.setLastRecordInBatch(lastRecordInBatch);
    }

    public ClickHouseStruct(long kafkaOffset, String topic, Struct key, Integer kafkaPartition,
                            Long timestamp, Struct beforeStruct, Struct afterStruct, Map<String, Object> metadata,
                            ClickHouseConverter.CDC_OPERATION operation) {

        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        if(key != null) {
            this.key = key.toString();
            Schema pkSchema = key.schema();
            if(pkSchema != null) {
                List<Field> fields = pkSchema.fields();
                if(fields != null && fields.isEmpty() == false) {
                    this.primaryKey = new ArrayList<>();
                    for(Field f: fields) {
                        if(f.name() != null) {
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

    public void setBeforeStruct(Struct s) {
        this.beforeStruct = s;

        if(s != null) {
            List<Field> schemaFields = s.schema().fields();
            this.beforeModifiedFields = new ArrayList<Field>();
            for (Field f : schemaFields) {
                // Identify the list of columns that were modified.
                // Schema.fields() will give the list of columns in the schema.
                if (s.get(f) != null) {
                    this.beforeModifiedFields.add(f);
                }
            }
        }
    }

    public void setAfterStruct(Struct s) {
        this.afterStruct = s;

        if(s != null) {
            List<Field> schemaFields = s.schema().fields();
            this.afterModifiedFields = new ArrayList<Field>();
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
     * Function to get additional kafka metadata
     * not stored in SinkRecord.
     * @param convertedValue
     */
    public void setAdditionalMetaData(Map<String, Object> convertedValue) {

        if (convertedValue == null || false == convertedValue.containsKey(SOURCE)) {
            return;
        }
        Struct source = (Struct) convertedValue.get(SOURCE);

        List<Field> fields = source.schema().fields();
        HashSet<String> fieldNames = new HashSet<String>();
        for(Field f: fields) {
            fieldNames.add(f.name());
        }
        try {
            if (fieldNames.contains(TS_MS) && source.get(TS_MS) != null && source.get(TS_MS) instanceof Long) {
                //  indicates the time that the change was made in the database.
                this.setTs_ms((Long) source.get(TS_MS));
            }
            if(convertedValue.get(TS_MS) != null) {
                this.setDebezium_ts_ms((Long) convertedValue.get(TS_MS));
            }
            if (fieldNames.contains(SNAPSHOT) && source.get(SNAPSHOT) != null && source.get(SNAPSHOT) instanceof String) {
                this.setSnapshot(Boolean.parseBoolean((String) source.get(SNAPSHOT)));
            }
            if (fieldNames.contains(SERVER_ID) && source.get(SERVER_ID) != null && source.get(SERVER_ID) instanceof Long) {
                this.setServerId((Long) source.get(SERVER_ID));
            }
            if (fieldNames.contains(BINLOG_FILE) && source.get(BINLOG_FILE) != null && source.get(BINLOG_FILE) instanceof String) {
                this.setFile((String) source.get(BINLOG_FILE));
            }
            if (fieldNames.contains(BINLOG_POS) && source.get(BINLOG_POS) != null && source.get(BINLOG_POS) instanceof Long) {
                this.setPos((Long) source.get(BINLOG_POS));
            }
            if (fieldNames.contains(ROW) && source.get(ROW) != null && source.get(ROW) instanceof Integer) {
                this.setRow((Integer) source.get(ROW));
            }
            if (fieldNames.contains(SERVER_THREAD) && source.get(SERVER_THREAD) != null && source.get(SERVER_THREAD) instanceof Integer) {
                this.setThread((Integer) convertedValue.get(SERVER_THREAD));
            }
            if(fieldNames.contains(GTID) && source.get(GTID) != null && source.get(GTID) instanceof String) {
                String[] gtidArray = ((String) source.get(GTID)).split(":");
                if(gtidArray.length == 2) {
                    this.setGtid(Long.parseLong(gtidArray[1]));
                }
            }
            if(fieldNames.contains(LSN) && source.get(LSN) != null && source.get(LSN) instanceof Long) {
                this.setLsn((Long) source.get(LSN));
            }
            if(fieldNames.contains(DATABASE) && source.get(DATABASE) != null && source.get(DATABASE) instanceof String) {
                this.setDatabase((String) source.get(DATABASE));
            }
        } catch (Exception e) {
            log.error("setAdditionalMetadata exception", e);
        }
    }

    public long getReplicationLag() {
        if(this.getTs_ms() > 0) {
            return System.currentTimeMillis() - this.getTs_ms();
        }
        return 0;
    }
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
                .append(" server_thread").append(thread).toString();
    }
}
