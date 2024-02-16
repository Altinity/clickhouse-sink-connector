package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Testcontainers

public class DbKafkaOffsetWriterTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql");

    @Test
    @Tag("IntegrationTest")
    public void testInsertTopicOffsetMetadata() throws SQLException {


        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), conn);

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
        Map<TopicPartition, Long> result = new HashMap<>();
        GroupInsertQueryWithBatchRecords groupInsertQueryWithBatchRecords = new GroupInsertQueryWithBatchRecords();

        boolean resultStatus =groupInsertQueryWithBatchRecords.groupQueryWithRecords(com.altinity.clickhouse.sink.connector.db.DbWriterTest.getSampleRecords()
                , queryToRecordsMap, result, new ClickHouseSinkConnectorConfig(new HashMap<>()), tableName, database, writer.getConnection(), writer.getColumnsDataTypesForTable(tableName));

        dbKafkaOffsetWriter.insertTopicOffsetMetadata(result);
        Map<TopicPartition, Long> offsetsMap = dbKafkaOffsetWriter.getStoredOffsets();

        Assert.assertTrue(offsetsMap.isEmpty() == false);
    }
}
