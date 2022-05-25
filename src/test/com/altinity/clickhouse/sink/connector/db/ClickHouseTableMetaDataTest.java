package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.metadata.ClickHouseTableMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClickHouseTableMetaDataTest {

    public static final Logger log = LoggerFactory.getLogger(ClickHouseTableMetaData.class);
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
                122323L);
        s.setStruct(kafkaConnectStruct);

        String jsonString = null;
        try {
            jsonString = ClickHouseTableMetaData.convertRecordToJSON(s);
        } catch(Exception e) {
            log.error("Exception converting record to JSON" + e);
        }

        String expectedString = "{\"amount\":23.223,\"quantity\":100,\"last_name\":\"Doe\",\"first_name\":\"John\",\"employed\":true}";

        Assert.assertNotNull(jsonString);
        Assert.assertTrue(jsonString.equalsIgnoreCase(expectedString));
    }
}
