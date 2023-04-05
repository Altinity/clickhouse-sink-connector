package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.Test;

public class ClickHouseConverterTest {

    @Test
    public void testConvert() {
        ClickHouseConverter converter = new ClickHouseConverter();

        SinkRecord record = spoofSinkRecord("test", "key", "k", "value", "v",
                TimestampType.NO_TIMESTAMP_TYPE, null);

        converter.convert(record);

    }

    /**
     * Utility method for spoofing SinkRecords that should be passed to SinkTask.put()
     * @param topic The topic of the record.
     * @param keyField The field name for the record key; may be null.
     * @param key The content of the record key; may be null.
     * @param valueField The field name for the record value; may be null
     * @param value The content of the record value; may be null
     * @param timestampType The type of timestamp embedded in the message
     * @param timestamp The timestamp in milliseconds
     * @return The spoofed SinkRecord.
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
                basicValueSchema, basicValue, 0, timestamp, timestampType);
    }
}
