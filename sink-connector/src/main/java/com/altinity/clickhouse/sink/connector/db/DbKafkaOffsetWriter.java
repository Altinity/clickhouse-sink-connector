package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.common.TopicPartition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class DbKafkaOffsetWriter extends BaseDbWriter {

    String query;

    Map<String, String> columnNamesToDataTypesMap;

    private static final Logger log = LogManager.getLogger(DbKafkaOffsetWriter.class);

    public DbKafkaOffsetWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            ClickHouseConnection connection
    ) {

        super(hostName, port, database, userName, password, config, connection);

        createOffsetTable();
        this.columnNamesToDataTypesMap = new DBMetadata().getColumnsDataTypesForTable(tableName, this.getConnection(),
                database);
        this.query = new QueryFormatter().getInsertQueryUsingInputFunction(tableName, columnNamesToDataTypesMap);

    }

    /**
     * Function to create kafka offset table.
     */
    public void createOffsetTable() {
        try {
            PreparedStatement ps = this.getConnection().prepareStatement(ClickHouseDbConstants.OFFSET_TABLE_CREATE_SQL);
            ps.execute();
        } catch(SQLException se) {
            log.error("Error creating Kafka offset table");
        }
    }


    /**
     * @param topicPartitionToOffsetMap
     * @throws SQLException
     */
    public void insertTopicOffsetMetadata(Map<TopicPartition, Long> topicPartitionToOffsetMap) throws SQLException {

        try (PreparedStatement ps = this.getConnection().prepareStatement(this.query)) {


            for (Map.Entry<TopicPartition, Long> entry : topicPartitionToOffsetMap.entrySet()) {


                TopicPartition tp = entry.getKey();
                String topicName = tp.topic();
                int partition = tp.partition();

                long offset = entry.getValue();

                int index = 1;
                for (Map.Entry<String, String> colNamesEntry : this.columnNamesToDataTypesMap.entrySet()) {
                    String columnName = colNamesEntry.getKey();

                    if (columnName.equalsIgnoreCase(KafkaMetaData.TOPIC.getColumn())) {
                        ps.setString(index, topicName);
                    } else if (columnName.equalsIgnoreCase(KafkaMetaData.PARTITION.getColumn())) {
                        ps.setInt(index, partition);
                    } else if (columnName.equalsIgnoreCase(KafkaMetaData.OFFSET.getColumn())) {
                        ps.setLong(index, offset);
                    }

                    index++;
                }


                ps.addBatch();
            }
            ps.executeBatch();

        } catch (Exception e) {
            log.error("Error persisting offsets to CH", e);
        }
    }

    public Map<TopicPartition, Long> getStoredOffsets() throws SQLException {
        Map<TopicPartition, Long> result = new HashMap<>();

        Statement stmt = this.getConnection().createStatement();
        ResultSet rs = stmt.executeQuery("select * from topic_offset_metadata");

        while (rs.next()) {
            String topicName = rs.getString(KafkaMetaData.TOPIC.getColumn());
            int partition = rs.getInt(KafkaMetaData.PARTITION.getColumn());
            long offset = rs.getLong(KafkaMetaData.OFFSET.getColumn());

            TopicPartition tp = new TopicPartition(topicName, partition);

            result.put(tp, offset);
        }

        return result;
    }
}
