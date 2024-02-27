package com.altinity.clickhouse.debezium.embedded.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


public class BatchMultipleThreadsProcessingTest {


    @Test
    @DisplayName("Integration test to test the batch multiple threads processing.")
    public void testBatchMultipleThreadsProcessing() {
        // Create the concurrentLinkedQueue of ClickHouseStruct
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        // Add the ClickHouseStruct to the concurrentLinkedQueue

        // Create the ClickHouseBatchRunnable object
        //ClickHouseBatchRunnable clickHouseBatchRunnable = new ClickHouseBatchRunnable();
        // Set the concurrentLinkedQueue to the ClickHouseBatchRunnable object

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
