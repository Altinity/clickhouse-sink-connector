package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private long ts_ms;

    @Getter
    @Setter
    private boolean snapshot;

    @Getter
    @Setter
    private Long serverId;

    @Getter
    @Setter
    private String file;

    @Getter
    @Setter
    private Long pos;

    @Getter
    @Setter
    private int row;

    @Getter
    @Setter
    private int thread;

    @Getter
    @Setter
    private int gtid;


    // Inheritance doesn't work because of different package
    // error, composition.
    @Getter
    Struct struct;

    @Getter
    @Setter
    List<Field> modifiedFields;

    private static final Logger log = LoggerFactory.getLogger(ClickHouseStruct.class);

    public ClickHouseStruct(long kafkaOffset, String topic, Struct key, Integer kafkaPartition, Long timestamp) {

        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        if(key != null) {
            this.key = key.toString();
        }
    }

    public void setStruct(Struct s) {
        this.struct = s;

        if(s != null) {
            List<Field> schemaFields = s.schema().fields();
            this.modifiedFields = new ArrayList<Field>();
            for (Field f : schemaFields) {
                // Identify the list of columns that were modified.
                // Schema.fields() will give the list of columns in the schema.
                if (s.get(f) != null) {
                    this.modifiedFields.add(f);
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

        if (false == convertedValue.containsKey(SOURCE)) {
            return;
        }
        Struct source = (Struct) convertedValue.get(SOURCE);

        try {
            if (source.get(TS_MS) != null && source.get(TS_MS) instanceof Long) {
                this.setTs_ms((Long) source.get(TS_MS));
            }
            if (source.get(SNAPSHOT) != null && source.get(SNAPSHOT) instanceof String) {
                this.setSnapshot(Boolean.parseBoolean((String) source.get(SNAPSHOT)));
            }
            if (source.get(SERVER_ID) != null && source.get(SERVER_ID) instanceof Long) {
                this.setServerId((Long) source.get(SERVER_ID));
            }
            if (source.get(BINLOG_FILE) != null && source.get(BINLOG_FILE) instanceof String) {
                this.setFile((String) source.get(BINLOG_FILE));
            }
            if (source.get(BINLOG_POS) != null && source.get(BINLOG_POS) instanceof Long) {
                this.setPos((Long) source.get(BINLOG_POS));
            }
            if (source.get(ROW) != null && source.get(ROW) instanceof Integer) {
                this.setRow((Integer) source.get(ROW));
            }
            if (source.get(SERVER_THREAD) != null && source.get(SERVER_THREAD) instanceof Integer) {
                this.setThread((Integer) convertedValue.get(SERVER_THREAD));
            }
        } catch (Exception e) {
            log.error("setAdditionalMetadata exception", e);
        }
    }
    @Override
    public String toString() {
        //
        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        this.key = key.toString();
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
