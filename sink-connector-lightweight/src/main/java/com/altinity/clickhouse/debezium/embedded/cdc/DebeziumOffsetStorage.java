package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.logging.Logger;
import com.clickhouse.logging.LoggerFactory;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.UUID;

/**
 * Utility class for managing Debezium offset storage and schema history.
 * <p>
 * This class contains methods for managing Debezium offset storage and
 * schema history in a database.
 * </p>
 */
public class DebeziumOffsetStorage {

    // MySQL parameters
    public static final String BINLOG_POS = "binlog_position";
    public static final String BINLOG_FILE = "binlog_file";
    public static final String GTID = "gtid";

    // PostgreSQL parameters
    public static final String LSN_PROCESSED = "lsn_proc";
    public static final String LSN = "lsn";

    // Source Host parameters
    public static final String SOURCE_HOST = "source_host";
    public static final String SOURCE_PORT = "source_port";
    public static final String SOURCE_USER = "source_user";
    public static final String SOURCE_PASSWORD = "source_password";

    /**
     * Default record insertion sequence to avoid magic numbers.
     */
    public static final int DEFAULT_RECORD_INSERT_SEQ = 1;

    private static final Logger log =
            LoggerFactory.getLogger(DebeziumOffsetStorage.class);

    /**
     * Generates an offset key based on the provided properties.
     *
     * @param props Startup properties.
     * @return The generated offset key string.
     */
    public String getOffsetKey(Properties props) {
        String connectorName = props.getProperty("name");
        return String.format("[\"%s\",{\"server\":\"embeddedconnector\"}]",
                connectorName);
    }

    /**
     * Deletes the row with the specified offsetKey from the offset storage.
     *
     * @param offsetKey The offset key.
     * @param props     Startup properties.
     * @param connection Database connection.
     * @throws SQLException If a database error occurs.
     */
    public void deleteOffsetStorageRow(String offsetKey, Properties props,
                                       Connection connection)
            throws SQLException {

        String tableName = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());

        String query = String.format(
                "delete from %s where offset_key='%s'", tableName, offsetKey);
        DBMetadata dbMetadata = new DBMetadata();
        dbMetadata.executeSystemQuery(connection, query);
    }

    /**
     * Deletes records from the schema history table that match the offsetKey.
     *
     * @param offsetKey  The offset key.
     * @param tableName  The schema history table name.
     * @param connection Database connection.
     * @throws SQLException If a database error occurs.
     */
    public void deleteSchemaHistoryTable(String offsetKey, String tableName,
                                         Connection connection)
            throws SQLException {

        String query = String.format(
                "delete from `%s` where JSONExtractRaw(JSONExtractRaw(history_data,"
                        + "'source'), 'server')='%s'",
                tableName, offsetKey);
        log.info("Deleting schema history table query: " + query);
        DBMetadata dbMetadata = new DBMetadata();
        dbMetadata.executeSystemQuery(connection, query);
    }

    /**
     * Retrieves the latest record timestamp from the offset storage.
     *
     * @param props      Startup properties.
     * @param connection Database connection.
     * @return The latest record timestamp as a string.
     * @throws SQLException If a database error occurs.
     */
    public String getDebeziumLatestRecordTimestamp(Properties props,
                                                   Connection connection)
            throws SQLException {

        String tableName = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());

        String query = String.format(
                "select max(record_insert_ts) from %s", tableName);
        DBMetadata dbMetadata = new DBMetadata();
        return dbMetadata.executeSystemQuery(connection, query);
    }

    /**
     * Retrieves the Debezium storage status query result.
     *
     * @param props      Startup properties.
     * @param connection Database connection.
     * @return The result of the storage status query.
     * @throws SQLException If a database error occurs.
     */
    public String getDebeziumStorageStatusQuery(Properties props,
                                                Connection connection)
            throws SQLException {

        String tableName = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
        String offsetKey = getOffsetKey(props);
        String query = String.format(
                "select offset_val from %s where offset_key='%s'",
                tableName, offsetKey);
        DBMetadata dbMetadata = new DBMetadata();
        return dbMetadata.executeSystemQuery(connection, query);
    }

    /**
     * Updates the binlog information in the provided record.
     * <p>
     * Example:
     * {"transaction_id":null,"ts_sec":1687278006,"file":
     * "mysql-bin.000003","pos":1156385,"gtids":
     * "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442",
     * "row":1,"server_id":266,"event":2}
     * </p>
     *
     * @param record         The original record.
     * @param binLogFile     The new binlog file name.
     * @param binLogPosition The new binlog position.
     * @param gtids          The new GTIDs string.
     * @return The updated record as a JSON string.
     * @throws ParseException If JSON parsing fails.
     */
    public String updateBinLogInformation(String record, String binLogFile,
                                          String binLogPosition,
                                          String gtids)
            throws ParseException {

        JSONObject jsonObject = new JSONObject();
        if (record != null && !record.isEmpty()) {
            jsonObject = (JSONObject) new JSONParser().parse(record);
        } else {
            jsonObject.put("ts_sec", System.currentTimeMillis() / 1000);
            jsonObject.put("transaction_id", null);
        }

        if (binLogFile != null && !binLogFile.isEmpty()) {
            jsonObject.put("file", binLogFile);
        }

        if (binLogPosition != null && !binLogPosition.isEmpty()) {
            jsonObject.put("pos", binLogPosition);
        }

        if (gtids != null && !gtids.isEmpty()) {
            jsonObject.put("gtids", gtids);
        }

        return jsonObject.toJSONString();
    }

    /**
     * Updates the LSN information in the provided record.
     * <p>
     * Table example:
     * <pre>
     * ┌─id─────────────────────────────┬─offset_key─────────────────────────────┐
     * │ 03750062-c862-48c5-9f37-451c0d33511b │
     * │ ["\"engine\"",{"server":"embeddedconnector"}]            │
     * ├──────────────────────────────────┼────────────────────────────────────────┤
     * │ offset_val: {"transaction_id":null,"lsn_proc":27485360,
     * "messageType":"UPDATE","lsn":27485360,"txId":743,
     * "ts_usec":1687876724804733}                             │
     * └──────────────────────────────────┴────────────────────────────────────────┘
     * </pre>
     *
     * @param record The original record.
     * @param lsn    The new LSN value.
     * @return The updated record as a JSON string.
     * @throws ParseException If JSON parsing fails.
     */
    public String updateLsnInformation(String record, String lsn)
            throws ParseException {

        Long lsnLong;
        // If lsn is a string like "1/AF00", extract the hex part after "/".
        if (lsn.contains("/")) {
            lsn = lsn.split("/")[1];
            // Convert lsn from hex to long.
            lsnLong = Long.parseLong(lsn, 16);
        } else {
            // Convert lsn to long.
            lsnLong = Long.parseLong(lsn);
        }
        JSONObject jsonObject = new JSONObject();
        if (record != null && !record.isEmpty()) {
            jsonObject = (JSONObject) new JSONParser().parse(record);
        }

        jsonObject.put(LSN_PROCESSED, lsnLong);
        jsonObject.put(LSN, lsnLong);

        return jsonObject.toJSONString();
    }

    /**
     * Updates the Debezium storage row with the provided information.
     *
     * @param connection Database connection.
     * @param tableName  The table name.
     * @param offsetKey  The offset key.
     * @param offsetVal  The offset value as a JSON string.
     * @param currentTs  The current timestamp in milliseconds.
     * @throws SQLException If a database error occurs.
     */
    public void updateDebeziumStorageRow(Connection connection,
                                         String tableName,
                                         String offsetKey,
                                         String offsetVal,
                                         long currentTs)
            throws SQLException {

        String insertQuery = String.format(
                JdbcOffsetBackingStoreConfig.DEFAULT_TABLE_INSERT, tableName);
        try (PreparedStatement sql = connection.prepareStatement(insertQuery)) {
            sql.setString(1, UUID.randomUUID().toString());
            sql.setString(2, offsetKey);
            sql.setString(3, offsetVal);
            sql.setTimestamp(4, new Timestamp(currentTs));
            sql.setInt(5, DEFAULT_RECORD_INSERT_SEQ);
            sql.executeUpdate();
        }
    }
}
