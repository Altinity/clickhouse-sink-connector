package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebeziumOffsetManagementTest {

    // Test function to validate the isWithinRange function
    @Test
    public void testIsWithinRange() {

        // Min and Max values for this batch - 3 and 433
        List<ClickHouseStruct> clickHouseStructs = new ArrayList<>();
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setDebezium_ts_ms(21L);

        ClickHouseStruct ch4 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 433L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setDebezium_ts_ms(433L);

        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, 22L ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setDebezium_ts_ms(22L);

        ClickHouseStruct ch6 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 3L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch6.setDebezium_ts_ms(3L);

        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 33L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setDebezium_ts_ms(33L);
        clickHouseStructs.add(ch1);
        clickHouseStructs.add(ch2);
        clickHouseStructs.add(ch3);

        clickHouseStructs.add(ch4);
        clickHouseStructs.add(ch6);

        // Batch 2 - Min and Max values for this batch - 1001 and 2001
        List<ClickHouseStruct> clickHouseStructs1 = new ArrayList<>();
        ClickHouseStruct ch5 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch5.setDebezium_ts_ms(1001L);

        ClickHouseStruct ch7 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch7.setDebezium_ts_ms(2001L);
        clickHouseStructs1.add(ch5);
        clickHouseStructs1.add(ch7);

        DebeziumOffsetManagement.addToBatchTimestamps(clickHouseStructs);
        DebeziumOffsetManagement.addToBatchTimestamps(clickHouseStructs1);

        // Batch 3 - Min and Max values for this batch - 501 and 1000
        List<ClickHouseStruct> clickHouseStructs2 = new ArrayList<>();
        ClickHouseStruct ch8 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch8.setDebezium_ts_ms(501L);

        ClickHouseStruct ch9 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch9.setDebezium_ts_ms(1000L);
        clickHouseStructs2.add(ch8);
        clickHouseStructs2.add(ch9);

        boolean result = DebeziumOffsetManagement.checkIfThereAreInflightRequests(clickHouseStructs2);
        Assert.assertTrue(result);

        // Batch 4 - Min and Max values for this batch - 1 and 2
        List<ClickHouseStruct> clickHouseStructs3 = new ArrayList<>();
        ClickHouseStruct ch10 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch10.setDebezium_ts_ms(1L);

        ClickHouseStruct ch11 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch11.setDebezium_ts_ms(2L);
        clickHouseStructs3.add(ch10);
        clickHouseStructs3.add(ch11);

        boolean result1 = DebeziumOffsetManagement.checkIfThereAreInflightRequests(clickHouseStructs3);
        Assert.assertFalse(result1);


    }

    @Test
    public void testCalculateMinMaxTimestampFromBatch() {
        // Test to validate DebeziumOffsetManagement calculateMinMaxTimestampFromBatch function
        // Create batch timestamps map.
        Map<Pair<Long, Long>, List<ClickHouseStruct>> batchTimestamps = new HashMap();
        List<ClickHouseStruct> clickHouseStructs = new ArrayList<>();
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setDebezium_ts_ms(21L);

        ClickHouseStruct ch4 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 433L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setDebezium_ts_ms(433L);

        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, 22L ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setDebezium_ts_ms(22L);

        ClickHouseStruct ch6 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 3L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch6.setDebezium_ts_ms(3L);

        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 33L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setDebezium_ts_ms(33L);

        clickHouseStructs.add(ch1);
        clickHouseStructs.add(ch2);
        clickHouseStructs.add(ch3);

        clickHouseStructs.add(ch4);
        clickHouseStructs.add(ch6);


        Pair<Long, Long> result = DebeziumOffsetManagement.calculateMinMaxTimestampFromBatch(clickHouseStructs);
        Assert.assertTrue(result.getLeft() == 3L);
        Assert.assertTrue(result.getRight() == 433L);

    }
    public static Struct getKafkaStruct() {
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


        return kafkaConnectStruct;
    }
}
