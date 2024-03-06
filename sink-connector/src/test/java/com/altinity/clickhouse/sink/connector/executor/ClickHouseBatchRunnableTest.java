package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.Before;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ClickHouseBatchRunnableTest {


    LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
    Map<String, String> topic2TableMap = new HashMap<>();

    @Before
    public void initTest() {


        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis() ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);


        ClickHouseStruct ch4 = new ClickHouseStruct(1020, "SERVER5432.test.products", getKafkaStruct(), 3, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch5 = new ClickHouseStruct(1400, "SERVER5432.test.products", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch6 = new ClickHouseStruct(1010, "SERVER5432.test.products", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);

        ConcurrentLinkedQueue<List<ClickHouseStruct>> customersQueue = new ConcurrentLinkedQueue<>();
        List<ClickHouseStruct> recordBatch1 = new ArrayList<>();
        recordBatch1.add(ch1);
        recordBatch1.add(ch2);
        recordBatch1.add(ch3);
        customersQueue.add(recordBatch1);

        ConcurrentLinkedQueue<List<ClickHouseStruct>> productsQueue = new ConcurrentLinkedQueue<>();
        List<ClickHouseStruct> recordBatch2 = new ArrayList<>();
        recordBatch2.add(ch1);
        recordBatch2.add(ch2);
        recordBatch2.add(ch3);
        productsQueue.add(recordBatch2);

        records.add(recordBatch1);
        records.add(recordBatch2);

        this.topic2TableMap.put("SERVER5432.test.customers", "customers");
        this.topic2TableMap.put("SERVER5432.test.products", "products");
        this.topic2TableMap.put("SERVER5432.test.employees", "employees");

    }


    public Struct getKafkaStruct() {
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

    @Test
    public void testGetTableNameFromTopic() {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<String, String>());
        ClickHouseBatchRunnable run = new ClickHouseBatchRunnable(this.records, config, this.topic2TableMap);

        String tableName = run.getTableFromTopic("SERVER5432.test.customers");

        Assert.assertTrue(tableName.equalsIgnoreCase("customers"));

    }
}
