package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class TableMetaDataWriterTest {

    public static final Logger log = LogManager.getLogger(TableMetaDataWriter.class);
    @Test
    public void testConvertRecordToJSON() {
        Schema kafkaConnectSchema = SchemaBuilder
                .struct()
                .field("first_name", Schema.STRING_SCHEMA)
                .field("last_name", Schema.STRING_SCHEMA)
                .field("quantity", Schema.INT32_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .field("employed", Schema.BOOLEAN_SCHEMA)
                .build();

        Struct kafkaConnectStruct = new Struct(kafkaConnectSchema);
        kafkaConnectStruct.put("first_name", "John");
        kafkaConnectStruct.put("last_name", "Doe");
        kafkaConnectStruct.put("quantity", 100);
        kafkaConnectStruct.put("amount", 23.223);
        kafkaConnectStruct.put("employed", true);

        ClickHouseStruct s = new ClickHouseStruct(1, "test-topic", kafkaConnectStruct, 12,
                122323L, null, kafkaConnectStruct, null, ClickHouseConverter.CDC_OPERATION.UPDATE);

        String jsonString = null;
        try {
            jsonString = TableMetaDataWriter.convertRecordToJSON(s.getAfterStruct());
        } catch(Exception e) {
            log.error("Exception converting record to JSON" + e);
        }

        String expectedString = "{\"amount\":23.223,\"quantity\":100,\"last_name\":\"Doe\",\"first_name\":\"John\",\"employed\":true}";

        Assert.assertNotNull(jsonString);
        Assert.assertTrue(jsonString.equalsIgnoreCase(expectedString));
    }
}
