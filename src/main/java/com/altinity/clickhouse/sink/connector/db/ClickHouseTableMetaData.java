package com.altinity.clickhouse.sink.connector.db;

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
 * Class to perform all operations related
 * to adding metadata to Clickhouse tables.
 */
public class ClickHouseTableMetaData {

    /**
     * Function to add kafka metadata columns.
     * @param colName
     * @param record
     * @param index
     * @param ps
     * @return
     * @throws SQLException
     */
    public static boolean addKafkaMetaData(String colName, ClickHouseStruct record, int index, PreparedStatement ps) throws SQLException {
        boolean columnUpdated = true;

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
        } else {
            columnUpdated = false;
        }

        return columnUpdated;
    }

    /**
     * Function to convert the kafka record to JSON.
     * @param record
     * @return
     */
    public static String convertRecordToJSON(ClickHouseStruct record) throws Exception {

        Struct s = record.getStruct();
        List<Field> fields = s.schema().fields();

        HashMap<String, Object> result = new HashMap<String, Object>();
        for(Field f: fields) {
            if(f != null && s.get(f) != null) {
                result.put(f.name(), s.get(f));
            }
        }

        ObjectMapper mapper = new ObjectMapper();

        return mapper.writeValueAsString(result);
    }
}
