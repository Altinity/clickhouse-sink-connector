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
        // Debezium 2.x embedded engine always writes offset keys with
        // "embeddedconnector" as the server name (its internal default for
        // topic.prefix). This constant MUST match what Debezium writes so
        // that reads and writes target the same row. The Python tooling
        // (postgres_dumper.py / postgres_type_mapper.py) also uses this
        // constant for the same reason.
        String topicPrefix = "embeddedconnector";
        return String.format("[\"%s\",{\"server\":\"%s\"}]",
                connectorName, topicPrefix);
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
        DBMetadata dbMetadata = new DBMetadata(props);
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
                                         Connection connection, Properties props)
            throws SQLException {

        String query = String.format(
                "delete from `%s` where JSONExtractRaw(JSONExtractRaw(history_data,"
                        + "'source'), 'server')='%s'",
                tableName, offsetKey);
        log.info("Deleting schema history table query: " + query);
        DBMetadata dbMetadata = new DBMetadata(props);
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

        // I14-scan-allowed: the connector-owned offset table (spec 09.03 section 3),
        // a handful of rows keyed by offset_key; read by the restart monitor.
        String query = String.format(
                "select max(record_insert_ts) from %s", tableName);
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

        DBMetadata dbMetadata = new DBMetadata(props);
        return dbMetadata.executeSystemQuery(connection, offsetValueQuery(props));
    }

    /**
     * The SQL that reads the stored offset value for this connector: the
     * NEWEST row for the key (spec 09.03 §3.2).
     *
     * <p>Debezium appends one row per flush. On a ReplacingMergeTree the older
     * rows survive until a merge, so a plain
     * {@code select offset_val ... where offset_key = ...} returns an arbitrary
     * one of several checkpoints -- and the REST position edit then rewrote the
     * table from a STALE base. The read therefore takes the newest row by
     * {@code (record_insert_ts, record_insert_seq)}, the same order Debezium's
     * own load query uses, under {@code FINAL}. A KeeperMap offset table has
     * exactly one row per key and rejects {@code FINAL}, so it is read with the
     * same ordering and no {@code FINAL}.</p>
     *
     * @param props Startup properties.
     * @return the query text.
     */
    String offsetValueQuery(Properties props) {
        String tableName = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
        String offsetKey = getOffsetKey(props);
        return String.format(
                "select offset_val from %s%s where offset_key='%s' "
                        + "order by record_insert_ts desc, record_insert_seq desc limit 1",
                tableName, isKeeperMapOffsetTable(props) ? "" : " FINAL", offsetKey);
    }

    /**
     * Whether the configured offset table DDL declares a KeeperMap engine.
     * Both key spellings of the DDL property are consulted (the current
     * {@code offset.storage.jdbc.table.ddl} and the pre-2.7.1
     * {@code offset.storage.jdbc.offset.table.ddl}).
     */
    static boolean isKeeperMapOffsetTable(Properties props) {
        String[] keys = {
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                        + JdbcOffsetBackingStoreConfig.PROP_TABLE_DDL.name(),
                "offset.storage.jdbc.offset.table.ddl"
        };
        for (String key : keys) {
            String ddl = props.getProperty(key);
            if (ddl != null && ddl.toUpperCase().contains("KEEPERMAP")) {
                return true;
            }
        }
        return false;
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
     * <p><b>Validation (spec 09.03 §3.4).</b> The edit used to overlay whatever
     * it was given: the position was stored as a string (Debezium fails at the
     * next start on anything non-numeric), a new file/position left the stored
     * {@code gtids} in place (Debezium resumes from a GTID set when one is
     * present, so the edit was silently ignored), and it left the stored
     * {@code row}/{@code event} skip counters in place (they belong to the OLD
     * position; kept, Debezium skips that many rows of the first event at the
     * NEW position -- silent loss). Now:</p>
     * <ul>
     *   <li>at least one of a file+position pair or a GTID set is required;</li>
     *   <li>file and position must be given together; the position must be a
     *       non-negative integer and is stored as a number;</li>
     *   <li>a file+position edit without a GTID set removes the stored
     *       {@code gtids}, and any file+position edit resets {@code row} and
     *       {@code event} to 0;</li>
     *   <li>a GTID-only edit replaces {@code gtids} and keeps the stored
     *       file/position.</li>
     * </ul>
     *
     * @param record         The original record.
     * @param binLogFile     The new binlog file name.
     * @param binLogPosition The new binlog position.
     * @param gtids          The new GTIDs string.
     * @return The updated record as a JSON string.
     * @throws ParseException If JSON parsing fails.
     * @throws IllegalArgumentException if the edit is incomplete or malformed.
     */
    public String updateBinLogInformation(String record, String binLogFile,
                                          String binLogPosition,
                                          String gtids)
            throws ParseException {

        String file = blankToNull(binLogFile);
        String pos = blankToNull(binLogPosition);
        String gtidSet = blankToNull(gtids);

        if (file == null && pos == null && gtidSet == null) {
            throw new IllegalArgumentException(
                    "an offset edit must give a " + BINLOG_FILE + " and " + BINLOG_POS
                            + " pair, or a " + GTID + " set, or both; nothing was given");
        }
        if ((file == null) != (pos == null)) {
            throw new IllegalArgumentException(
                    BINLOG_FILE + " and " + BINLOG_POS + " must be given together (got file="
                            + file + ", position=" + pos + ")");
        }
        Long position = null;
        if (pos != null) {
            try {
                position = Long.parseLong(pos);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        BINLOG_POS + " must be a non-negative integer, got '" + pos + "'");
            }
            if (position < 0) {
                throw new IllegalArgumentException(
                        BINLOG_POS + " must be a non-negative integer, got " + position);
            }
        }

        JSONObject jsonObject = new JSONObject();
        if (record != null && !record.isEmpty()) {
            jsonObject = (JSONObject) new JSONParser().parse(record);
        } else {
            jsonObject.put("ts_sec", System.currentTimeMillis() / 1000);
            jsonObject.put("transaction_id", null);
        }

        if (file != null) {
            jsonObject.put("file", file);
            jsonObject.put("pos", position);
            // The skip counters describe progress INSIDE the old position's
            // event/transaction. At the new position they would make Debezium
            // skip that many rows / events silently.
            jsonObject.put("row", 0L);
            jsonObject.put("event", 0L);
            if (gtidSet == null) {
                // Debezium prefers the GTID set when the offset carries one; a
                // stale set would make it ignore the file/position just set.
                jsonObject.remove("gtids");
            }
        }

        if (gtidSet != null) {
            jsonObject.put("gtids", gtidSet);
        }

        return jsonObject.toJSONString();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
     * <p>The LSN is accepted either as a decimal number or in PostgreSQL's
     * {@code X/Y} text form, which denotes {@code (X << 32) | Y} with both
     * halves in hex. The earlier parser kept only {@code Y}, positioning the
     * connector one 4 GiB WAL segment group too early for any {@code X > 0}
     * (spec 09.03 §3.4).</p>
     *
     * @param record The original record.
     * @param lsn    The new LSN value.
     * @return The updated record as a JSON string.
     * @throws ParseException If JSON parsing fails.
     * @throws IllegalArgumentException if the LSN is missing or malformed.
     */
    public String updateLsnInformation(String record, String lsn)
            throws ParseException {

        String value = blankToNull(lsn);
        if (value == null) {
            throw new IllegalArgumentException(LSN + " is required (decimal, or PostgreSQL X/Y)");
        }
        long lsnLong;
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                            LSN + " in X/Y form must have exactly two hex parts, got '" + value + "'");
                }
                long high = Long.parseLong(parts[0], 16);
                long low = Long.parseLong(parts[1], 16);
                if (high < 0 || low < 0 || low > 0xFFFFFFFFL) {
                    throw new IllegalArgumentException(LSN + " out of range: '" + value + "'");
                }
                lsnLong = (high << 32) | low;
            } else {
                lsnLong = Long.parseLong(value);
                if (lsnLong < 0) {
                    throw new IllegalArgumentException(LSN + " must be non-negative, got " + lsnLong);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    LSN + " must be a decimal number or PostgreSQL X/Y hex form, got '" + value + "'");
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
        // Use a deterministic UUID derived from the offsetKey so that all
        // updates for the same connector produce the same `id` value.
        // This allows ReplacingMergeTree (ORDER BY id) to collapse
        // duplicate rows via FINAL, keeping only the latest one.
        String deterministicId = UUID.nameUUIDFromBytes(
                offsetKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try (PreparedStatement sql = connection.prepareStatement(insertQuery)) {
            sql.setString(1, deterministicId);
            sql.setString(2, offsetKey);
            sql.setString(3, offsetVal);
            sql.setTimestamp(4, new Timestamp(currentTs));
            sql.setInt(5, DEFAULT_RECORD_INSERT_SEQ);
            sql.executeUpdate();
        }
    }
}
