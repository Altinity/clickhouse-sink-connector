package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import org.apache.kafka.common.TopicPartition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * A class for writing and retrieving Kafka offset metadata to and
 * from ClickHouse. It creates the offset table if it doesn't exist
 * and provides methods to insert and retrieve offsets.
 */
public class DbKafkaOffsetWriter extends BaseDbWriter {

    /**
     * The SQL query used for inserting offset metadata into ClickHouse.
     */
    String query;

    /**
     * The name of the offset table (stored for use in getStoredOffsets).
     */
    String tableName;

    /**
     * A map of column names to their respective data types in the offset table.
     */
    Map<String, String> columnNamesToDataTypesMap;

    /**
     * Logger instance for this class to record logs and errors.
     */
    private static final Logger log = LogManager.getLogger(
            DbKafkaOffsetWriter.class
    );

    /**
     * Constructor that initializes the writer with database connection
     * details, and prepares the insert query for offset metadata.
     *
     * @param hostName   The ClickHouse server hostname.
     * @param port       The ClickHouse server port.
     * @param database   The ClickHouse database name.
     * @param tableName  The name of the offset table.
     * @param userName   Username for database access.
     * @param password   Password for database access.
     * @param config     The sink connector configuration.
     * @param connection An existing connection to ClickHouse.
     */
    public DbKafkaOffsetWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            Connection connection
    ) {

        super(hostName, port, database, userName, password, config,
                connection);

        this.tableName = tableName;
        createOffsetTable();
        this.columnNamesToDataTypesMap =
                new DBMetadata(config).getColumnsDataTypesForTable(
                        tableName,
                        this.getConnection(),
                        database
                );
        this.query = buildValuesInsertQuery(tableName, columnNamesToDataTypesMap);
    }

    /**
     * Builds a VALUES-based INSERT query. Avoids the input() function which
     * breaks when column types contain commas (e.g. SimpleAggregateFunction).
     */
    private static String buildValuesInsertQuery(
            String tableName,
            Map<String, String> columnNamesToDataTypesMap
    ) {
        StringBuilder colNames = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (Map.Entry<String, String> entry : columnNamesToDataTypesMap.entrySet()) {
            colNames.append("`").append(entry.getKey()).append("`,");
            placeholders.append("?,");
        }
        if (!colNames.isEmpty()) {
            colNames.setLength(colNames.length() - 1);
            placeholders.setLength(placeholders.length() - 1);
        }
        return String.format(
                "INSERT INTO `%s` (%s) VALUES (%s)",
                tableName, colNames, placeholders
        );
    }

    /**
     * Function to create the Kafka offset table if it does not exist.
     */
    public void createOffsetTable() {
        try (PreparedStatement ps = this.getConnection().prepareStatement(
                ClickHouseDbConstants.OFFSET_TABLE_CREATE_SQL)) {
            ps.execute();
        } catch (SQLException se) {
            log.error("Error creating Kafka offset table", se);
        }
    }

    /**
     * Inserts the given map of topic-partition to offset values into
     * ClickHouse.
     *
     * @param topicPartitionToOffsetMap A map of {@link TopicPartition}
     *                                  to offset values.
     * @throws SQLException If a database access error occurs.
     */
    public void insertTopicOffsetMetadata(
            Map<TopicPartition, Long> topicPartitionToOffsetMap
    ) throws SQLException {

        try (PreparedStatement ps = this.getConnection().prepareStatement(
                this.query)) {

            for (Map.Entry<TopicPartition, Long> entry
                    : topicPartitionToOffsetMap.entrySet()) {

                TopicPartition tp = entry.getKey();
                String topicName = tp.topic();
                int partition = tp.partition();
                long offset = entry.getValue();

                int index = 1;
                for (Map.Entry<String, String> colNamesEntry
                        : this.columnNamesToDataTypesMap.entrySet()) {

                    String columnName = colNamesEntry.getKey();

                    if (columnName.equalsIgnoreCase(
                            KafkaMetaData.TOPIC.getColumn())) {
                        ps.setString(index, topicName);
                    } else if (columnName.equalsIgnoreCase(
                            KafkaMetaData.PARTITION.getColumn())) {
                        ps.setInt(index, partition);
                    } else if (columnName.equalsIgnoreCase(
                            KafkaMetaData.OFFSET.getColumn())) {
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

    /**
     * Retrieves the stored offsets from ClickHouse, returning them
     * as a map of {@link TopicPartition} to offset values.
     *
     * @return A map of partitions to their last known offsets.
     * @throws SQLException If a database access error occurs.
     */
    public Map<TopicPartition, Long> getStoredOffsets() throws SQLException {
        Map<TopicPartition, Long> result = new HashMap<>();

        try (Statement stmt = this.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("select * from " + this.tableName)) {

            while (rs.next()) {
                String topicName = rs.getString(
                        KafkaMetaData.TOPIC.getColumn()
                );
                int partition = rs.getInt(
                        KafkaMetaData.PARTITION.getColumn()
                );
                long offset = rs.getLong(
                        KafkaMetaData.OFFSET.getColumn()
                );

                TopicPartition tp = new TopicPartition(topicName, partition);
                result.put(tp, offset);
            }
        }

        return result;
    }
}
