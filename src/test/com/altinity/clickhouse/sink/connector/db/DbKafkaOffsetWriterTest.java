package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DbKafkaOffsetWriterTest {

    @Test
    @Tag("IntegrationTest")
    public void testInsertTopicOffsetMetadata() {

        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(new WeakReference<>(writer),
                "topic_offset_metadata");

        ConcurrentLinkedQueue<ClickHouseStruct> records = new ConcurrentLinkedQueue<ClickHouseStruct>();

       // Map<TopicPartition, Long> result = writer.groupQueryWithRecords(new DbWriterTest().getSampleRecords(), queryToRecordsMap);

    }
}
