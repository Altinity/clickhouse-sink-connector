package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class DbKafkaOffsetWriter {

    WeakReference<DbWriter> writer;
    String query;

    Map<String, String> columnNamesToDataTypesMap;

    private static final Logger log = LoggerFactory.getLogger(DbKafkaOffsetWriter.class);


    public DbKafkaOffsetWriter(WeakReference<DbWriter> writer, String tableName) {
        this.writer = writer;
        this.columnNamesToDataTypesMap = this.writer.get().getColumnsDataTypesForTable(tableName);

        this.query = new QueryFormatter().getInsertQueryUsingInputFunction(tableName, columnNamesToDataTypesMap);
    }

    /**
     * @param topicPartitionToOffsetMap
     * @throws SQLException
     */
    public void insertTopicOffsetMetadata(Map<TopicPartition, Long> topicPartitionToOffsetMap) throws SQLException {

        try (PreparedStatement ps = this.writer.get().getConnection().prepareStatement(this.query)) {


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
        Map<TopicPartition, Long> result = new HashMap<TopicPartition, Long>();

        Statement stmt = this.writer.get().getConnection().createStatement();
        ResultSet rs = stmt.executeQuery("select * from mv_topic_offset_metadata_view");

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
