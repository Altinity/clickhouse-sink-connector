package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DbKafkaOffsetWriterTest {

    @Test
    @Tag("IntegrationTest")
    public void testInsertTopicOffsetMetadata() throws SQLException {

        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));
                ;

        ConcurrentLinkedQueue<ClickHouseStruct> records = new ConcurrentLinkedQueue<ClickHouseStruct>();

        Map<String, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<String, List<ClickHouseStruct>>();

        Map<TopicPartition, Long> result = writer.groupQueryWithRecords(new DbWriterTest().getSampleRecords(), queryToRecordsMap);

        dbKafkaOffsetWriter.insertTopicOffsetMetadata(result);

    }

    @Test
    @Tag("IntegrationTest")
    public void testGetStoredOffsets() throws SQLException {

        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Map<TopicPartition, Long> offsetsMap = dbKafkaOffsetWriter.getStoredOffsets();

        Assert.assertTrue(offsetsMap.isEmpty() == false);
    }
}
