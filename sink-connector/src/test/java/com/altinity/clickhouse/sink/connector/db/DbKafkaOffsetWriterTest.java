package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Testcontainers

public class DbKafkaOffsetWriterTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8")
            .withInitScript("./init_clickhouse.sql");

    @AfterAll
    public static void cleanup() {
        HikariDbSource.close();
    }

    @Test
    @Tag("IntegrationTest")
    public void testInsertTopicOffsetMetadata() throws SQLException {

        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "employees";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        HashMap<String, String> rawConfig = new HashMap<>();
        rawConfig.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(rawConfig);

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        Connection conn = DbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                BaseDbWriter.SYSTEM_DB, config);

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                config, null, conn);

        DbKafkaOffsetWriter dbKafkaOffsetWriter = new DbKafkaOffsetWriter(dbHostName, port, database, "topic_offset_metadata", userName, password,
                config, conn);

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();
        Map<TopicPartition, Long> result = new HashMap<>();
        GroupInsertQueryWithBatchRecords groupInsertQueryWithBatchRecords = new GroupInsertQueryWithBatchRecords();

        DBMetadata metadata = new DBMetadata(config);
        boolean resultStatus = groupInsertQueryWithBatchRecords.groupQueryWithRecords(
                DbWriterTest.getSampleRecords(),
                queryToRecordsMap, result, config, tableName, database, writer.getConnection(),
                metadata.getColumnsDataTypesForTable(conn, tableName, database));

        dbKafkaOffsetWriter.insertTopicOffsetMetadata(result);
        Map<TopicPartition, Long> offsetsMap = dbKafkaOffsetWriter.getStoredOffsets();

        Assert.assertTrue(offsetsMap.isEmpty() == false);
    }
}
