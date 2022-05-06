package com.altinity.clickhouse.sink.connector;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;

import java.util.concurrent.atomic.AtomicLong;

public class ClickHouseSinkTaskTest {
    private static AtomicLong spoofedRecordOffset = new AtomicLong();

    /**
     * Function to create spoofed sink record.
     * @param topic
     * @param keyField
     * @param key
     * @param valueField
     * @param value
     * @param timestampType
     * @param timestamp
     * @return
     */
    public static SinkRecord spoofSinkRecord(String topic, String keyField, String key,
                                             String valueField, String value,
                                             TimestampType timestampType, Long timestamp) {
        Schema basicKeySchema = null;
        Struct basicKey = null;
        if (keyField != null) {
            basicKeySchema = SchemaBuilder
                    .struct()
                    .field(keyField, Schema.STRING_SCHEMA)
                    .build();
            basicKey = new Struct(basicKeySchema);
            basicKey.put(keyField, key);
        }

        Schema basicValueSchema = null;
        Struct basicValue = null;
        if (valueField != null) {
            basicValueSchema = SchemaBuilder
                    .struct()
                    .field(valueField, Schema.STRING_SCHEMA)
                    .build();
            basicValue = new Struct(basicValueSchema);
            basicValue.put(valueField, value);
        }

        return new SinkRecord(topic, 0, basicKeySchema, basicKey,
                basicValueSchema, basicValue, spoofedRecordOffset.getAndIncrement(), timestamp, timestampType);
    }

}
