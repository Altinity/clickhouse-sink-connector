package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
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
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Map<TopicPartition, Long> offsetsMap = dbKafkaOffsetWriter.getStoredOffsets();

        Assert.assertTrue(offsetsMap.isEmpty() == false);
    }
}
