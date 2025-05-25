package com.altinity.clickhouse.sink.connector.metadata;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;

/**
 * The TableMetaDataWriter class performs operations related to adding metadata
 * to ClickHouse tables. This includes adding Kafka metadata columns and raw
 * data to the prepared statements used for writing to ClickHouse.
 */
public class TableMetaDataWriter {

    /**
     * Adds Kafka metadata columns to the prepared statement.
     * <p>
     * This method checks the column name and adds the appropriate Kafka
     * metadata to the prepared statement, including offset, topic, partition,
     * timestamp, key, server ID, binlog file, and other related information.
     * </p>
     *
     * @param colName the column name to add to the prepared statement.
     * @param record the ClickHouseStruct containing the record data.
     * @param index the index of the parameter to be set in the prepared statement.
     * @param ps the PreparedStatement object to which the values will be set.
     * @return true if the column was updated, false otherwise.
     * @throws SQLException if an SQL error occurs while setting the value.
     */
    public static boolean addKafkaMetaData(String colName, ClickHouseStruct record, int index, PreparedStatement ps) throws SQLException {
        boolean columnUpdated = true;

        // Set Kafka metadata columns
        if (colName.equalsIgnoreCase(KafkaMetaData.OFFSET.getColumn())) {
            ps.setLong(index, record.getKafkaOffset());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.TOPIC.getColumn())) {
            ps.setString(index, record.getTopic());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.PARTITION.getColumn())) {
            ps.setInt(index, record.getKafkaPartition());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.TIMESTAMP_MS.getColumn())) {
            ps.setLong(index, record.getTimestamp());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.TIMESTAMP.getColumn())) {
            LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.getTimestamp()),
                    ZoneId.systemDefault());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            ps.setObject(index, date.format(formatter));

        } else if (colName.equalsIgnoreCase(KafkaMetaData.KEY.getColumn())) {
            if (record.getKey() != null) {
                ps.setString(index, record.getKey());
            }
        } else if (colName.equalsIgnoreCase(KafkaMetaData.TS_MS.getColumn())) {
            ps.setLong(index, record.getTs_ms());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.SERVER_ID.getColumn())) {
            ps.setLong(index, record.getServerId());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.GTID.getColumn())) {
            ps.setLong(index, record.getGtid());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.BINLOG_FILE.getColumn())) {
            ps.setString(index, record.getFile());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.BINLOG_POSITION.getColumn())) {
            ps.setLong(index, record.getPos());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.BINLOG_ROW.getColumn())) {
            ps.setInt(index, record.getRow());
        } else if (colName.equalsIgnoreCase(KafkaMetaData.SERVER_THREAD.getColumn())) {
            ps.setInt(index, record.getThread());
        } else {
            columnUpdated = false;
        }

        return columnUpdated;
    }

    /**
     * Adds raw data to the prepared statement.
     * <p>
     * This method converts the given Struct into a JSON representation and
     * adds it to the prepared statement at the specified index.
     * </p>
     *
     * @param s the Struct containing the raw data to be added.
     * @param index the index of the parameter to be set in the prepared statement.
     * @param ps the PreparedStatement object to which the raw data will be added.
     * @throws Exception if an error occurs during the conversion to JSON.
     */
    public static void addRawData(Struct s, int index, PreparedStatement ps) throws Exception {
        String jsonRecord = convertRecordToJSON(s);
        ps.setString(index, jsonRecord);
    }

    /**
     * Converts a Kafka record (Struct) to JSON.
     * <p>
     * This method iterates over the fields of the Struct and converts them
     * into a JSON string, using the Jackson ObjectMapper.
     * </p>
     *
     * @param s the Struct containing the Kafka record.
     * @return the JSON representation of the record.
     * @throws Exception if an error occurs during the conversion to JSON.
     */
    public static String convertRecordToJSON(Struct s) throws Exception {

        List<Field> fields = s.schema().fields();

        HashMap<String, Object> result = new HashMap<String, Object>();
        for (Field f: fields) {
            if (f != null && s.get(f) != null) {
                result.put(f.name(), s.get(f));
            }
        }

        ObjectMapper mapper = new ObjectMapper();

        return mapper.writeValueAsString(result);
    }
}
