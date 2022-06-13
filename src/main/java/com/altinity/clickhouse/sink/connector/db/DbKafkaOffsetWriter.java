package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import org.apache.kafka.common.TopicPartition;

import java.lang.ref.WeakReference;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

public class DbKafkaOffsetWriter {

    WeakReference<DbWriter> writer;
    String query;

    Map<String, String> columnNamesToDataTypesMap;



    public DbKafkaOffsetWriter(WeakReference<DbWriter> writer, String tableName) {
        this.writer = writer;
        this.columnNamesToDataTypesMap = this.writer.get().getColumnsDataTypesForTable(tableName);

        this.query = new QueryFormatter().getInsertQueryUsingInputFunction(tableName, columnNamesToDataTypesMap);
    }

    /**
     *
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
                for(Map.Entry<String, String> colNamesEntry: this.columnNamesToDataTypesMap.entrySet()) {
                    String columnName = colNamesEntry.getKey();

                    if(columnName.equalsIgnoreCase(KafkaMetaData.TOPIC.getColumn())) {
                        ps.setString(index,  topicName);
                    } else if(columnName.equalsIgnoreCase(KafkaMetaData.PARTITION.getColumn())) {
                        ps.setInt(index, partition);
                    } else if(columnName.equalsIgnoreCase(KafkaMetaData.OFFSET.getColumn())) {
                        ps.setLong(index, offset);
                    }

                    index++;
                }


                ps.addBatch();
            }
            ps.executeBatch();

        }
        catch(Exception e) {

        }
    }
}
