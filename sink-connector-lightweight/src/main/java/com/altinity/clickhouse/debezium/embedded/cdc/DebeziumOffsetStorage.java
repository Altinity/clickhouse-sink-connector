package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
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
     * Quotes a possibly qualified table name one identifier part at a time.
     *
     * <p>Every value that reaches these statements is configuration- or
     * connector-supplied, so values are bound as JDBC parameters. A table
     * name cannot be bound as a parameter, so it is quoted instead, which is
     * what makes the surrounding statement safe to assemble by concatenation.
     *
     * <p>The configured offset table is normally qualified, for example
     * {@code altinity_sink_connector.replica_source_info}. Quoting the whole
     * string in one go would produce a single identifier containing a dot,
     * which names a different (non-existent) table in the connection's
     * default database, so each dot-separated part is quoted on its own to
     * yield {@code `altinity_sink_connector`.`replica_source_info`}.
     *
     * <p>Surrounding backticks or double quotes already present in the
     * configured value are stripped first, so an operator who quoted the name
     * in the config file does not end up with a doubly quoted identifier.
     * This mirrors {@code splitTableName} in
     * {@code DebeziumJdbcStorageOperations}, which strips double quotes for
     * the same reason.
     *
     * @param tableName the raw, possibly qualified table name, may be null.
     * @return the per-part quoted table name, or null if the input was null.
     */
    static String quoteTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        StringBuilder quoted = new StringBuilder();
        for (String part : tableName.split("\\.", -1)) {
            if (quoted.length() > 0) {
                quoted.append('.');
            }
            quoted.append(ClickHouseTableOperationsBase.quoteIdentifier(
                    stripQuotes(part)));
        }
        return quoted.toString();
    }

    /**
     * Removes a matching pair of surrounding backticks or double quotes.
     *
     * @param part a single identifier part.
     * @return the unquoted identifier part.
     */
    private static String stripQuotes(String part) {
        if (part.length() > 1
                && ((part.startsWith("`") && part.endsWith("`"))
                || (part.startsWith("\"") && part.endsWith("\"")))) {
            return part.substring(1, part.length() - 1);
        }
        return part;
    }

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
                "delete from %s where offset_key=?", quoteTableName(tableName));
        DBMetadata dbMetadata = new DBMetadata(props);
        dbMetadata.executeSystemQuery(connection, query, offsetKey);
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
                                         Connection connection, Properties props)
            throws SQLException {

        String query = String.format(
                "delete from %s where JSONExtractRaw(JSONExtractRaw(history_data,"
                        + "'source'), 'server')=?",
                quoteTableName(tableName));
        log.info("Deleting schema history table query: " + query
                + " for server: " + offsetKey);
        DBMetadata dbMetadata = new DBMetadata(props);
        dbMetadata.executeSystemQuery(connection, query, offsetKey);
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
                "select max(record_insert_ts) from %s",
                quoteTableName(tableName));
        DBMetadata dbMetadata = new DBMetadata(props);
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
                "select offset_val from %s where offset_key=?",
                quoteTableName(tableName));
        DBMetadata dbMetadata = new DBMetadata(props);
        return dbMetadata.executeSystemQuery(connection, query, offsetKey);
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
     * @return the id of the row that was inserted, so the caller can delete the
     *         rows it supersedes without deleting the row it just wrote.
     * @throws SQLException If a database error occurs.
     */
    public String updateDebeziumStorageRow(Connection connection,
                                           String tableName,
                                           String offsetKey,
                                           String offsetVal,
                                           long currentTs)
            throws SQLException {

        String rowId = UUID.randomUUID().toString();
        String insertQuery = String.format(
                JdbcOffsetBackingStoreConfig.DEFAULT_TABLE_INSERT,
                quoteTableName(tableName));
        try (PreparedStatement sql = connection.prepareStatement(insertQuery)) {
            sql.setString(1, rowId);
            sql.setString(2, offsetKey);
            sql.setString(3, offsetVal);
            sql.setTimestamp(4, new Timestamp(currentTs));
            sql.setInt(5, DEFAULT_RECORD_INSERT_SEQ);
            sql.executeUpdate();
        }
        return rowId;
    }

    /**
     * Deletes the offset rows a freshly written row supersedes, keeping the row
     * just inserted.
     *
     * <p>The offset table is a ReplacingMergeTree whose sorting key is
     * {@code id}, and {@code id} is a fresh UUID on every insert rather than
     * the offset key, so successive offset rows for one connector never
     * collapse into one. Superseded rows must therefore be deleted explicitly
     * or the FINAL read returns the whole history instead of the current
     * position.
     *
     * <p>This is deliberately called AFTER the insert. ClickHouse has no
     * transactions on this path -- the offset-storage JDBC URL sets
     * jdbc_ignore_unsupported_values=true precisely so setAutoCommit(false) is
     * silently ignored -- so the two statements cannot be made atomic.
     * Ordering them insert-then-delete means a crash in between leaves a
     * superseded row alongside the new one, which is harmless: the read is
     * ordered by record_insert_ts so the newer row wins, and the next update
     * clears the leftover. Deleting first would leave no row at all and lose
     * the offset.
     *
     * @param offsetKey  the offset key whose superseded rows should be removed.
     * @param keepId     the id of the row just inserted, which must survive.
     * @param props      connector properties, used to resolve the table name.
     * @param connection Database connection.
     * @throws SQLException If a database error occurs.
     */
    public void deleteSupersededOffsetRows(String offsetKey, String keepId,
                                           Properties props,
                                           Connection connection)
            throws SQLException {

        String tableName = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());

        String query = String.format(
                "delete from %s where offset_key=? and id!=?",
                quoteTableName(tableName));
        DBMetadata dbMetadata = new DBMetadata(props);
        dbMetadata.executeSystemQuery(connection, query, offsetKey, keepId);
    }
}
