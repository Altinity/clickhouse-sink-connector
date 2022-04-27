package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;

import java.util.ArrayList;
import java.util.List;

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

    // Inheritance doesn't work because of different package
    // error, composition.
    @Getter
    Struct struct;

    @Getter
    @Setter
    List<Field> modifiedFields;


    public ClickHouseStruct(long kafkaOffset, String topic, Struct key, Integer kafkaPartition, Long timestamp) {

        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        this.key = key.toString();
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
}
