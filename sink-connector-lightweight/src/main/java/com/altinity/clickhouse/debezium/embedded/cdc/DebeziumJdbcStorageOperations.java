package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.relational.history.SchemaHistory;
import io.debezium.storage.jdbc.history.JdbcSchemaHistoryConfig;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class that encapsulates all operations on Debezium JDBC Storage,
 * including offset storage and schema history management.
 */
public class DebeziumJdbcStorageOperations {

    /**
     * Logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            DebeziumJdbcStorageOperations.class);

    /**
     * Pattern used to extract the "[database.]viewName" target from a
     * CREATE [OR REPLACE] VIEW DDL statement so the view name is never
     * hard-coded.
     */
    private static final Pattern VIEW_NAME_PATTERN =
            Pattern.compile(
                    "CREATE\\s+(?:OR\\s+REPLACE\\s+)?VIEW\\s+([`\\w.]+)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Instance of DebeziumOffsetStorage for performing storage ops.
     */
    DebeziumOffsetStorage debeziumOffsetStorage;

    /**
     * Default constructor that initializes the DebeziumOffsetStorage.
     */
    public DebeziumJdbcStorageOperations() {
        this.debeziumOffsetStorage = new DebeziumOffsetStorage();
    }

    /**
     * Function to create database for Debezium storage.
     *
     * @param conn  The database connection.
     * @param props The connector properties.
     * @throws SQLException If a database error occurs.
     */
    void createDatabaseForDebeziumStorage(Connection conn,
                                          Properties props)
            throws SQLException {
        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String databaseName = tableNameDatabaseName.getRight();
        String createDbQuery = String.format("create database if not exists %s",
                databaseName);
        log.info("CREATING DEBEZIUM STORAGE Database: " + createDbQuery);
        new DBMetadata(props).executeSystemQuery(conn, createDbQuery);
    }

    /**
     * Creates the schema history table as per the provided query in
     * the properties.
     */
    void createSchemaHistoryTable(Connection conn, Properties props) {

        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String dbName = tableNameDatabaseName.getRight();
        String createSchemaHistoryTableQuery = props.getProperty(
                JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                        JdbcSchemaHistoryConfig.PROP_TABLE_DDL.name());
        if (createSchemaHistoryTableQuery == null ||
                createSchemaHistoryTableQuery.isEmpty()) {
            log.warn("Skipping creating schema history table as the query " +
                    "was not provided in configuration");
            return;
        }
        String formattedCreateSchemaHistoryTableQuery = String.format(
                createSchemaHistoryTableQuery, dbName, dbName + "." + tableName);
        try {
            new DBMetadata(props).executeSystemQuery(conn, formattedCreateSchemaHistoryTableQuery);
        } catch (Exception e) {
            log.error("Error creating schema history table", e);
        }
    }

    /**
     * Function to create view for show_replica_status.
     *
     * @param conn   The database connection.
     * @param config The ClickHouse sink connector config.
     * @param props  The connector properties.
     */
    void createViewForShowReplicaStatus(Connection conn,
                                        ClickHouseSinkConnectorConfig config,
                                        Properties props) {
        String view = props.getProperty(
                ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString());
        if (view == null || view.isEmpty() == true) {
            log.warn("Skipping creating view for replica_status as the query " +
                    "was not provided in configuration");
            return;
        }
        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String dbName = tableNameDatabaseName.getRight();

        String formattedView = String.format(view, dbName, dbName + "." + tableName);
        // Remove quotes.
        formattedView = formattedView.replace("\"", "");

        // Parse the target "[database.]viewName" straight out of the DDL so we never
        // hard-code the view name (or assume the wrong database).
        String viewDatabase = null;
        String viewName = null;
        Matcher m = VIEW_NAME_PATTERN.matcher(formattedView);
        if (m.find()) {
            String identifier = m.group(1).replace("`", "");
            int dot = identifier.lastIndexOf('.');
            if (dot >= 0) {
                viewDatabase = identifier.substring(0, dot);
                viewName = identifier.substring(dot + 1);
            } else {
                viewName = identifier;
            }
        }

        // Check if the view already exists before attempting to create it.
        if (viewName != null) {
            try {
                String checkSql = (viewDatabase != null)
                        ? String.format(
                            "SELECT count(*) FROM system.tables WHERE database = '%s' AND name = '%s'",
                            viewDatabase, viewName)
                        : String.format(
                            "SELECT count(*) FROM system.tables WHERE name = '%s'", viewName);
                ResultSet rs = new DBMetadata(props).executeQueryWithResultSet(checkSql, conn);
                if (rs != null && rs.next() && rs.getInt(1) > 0) {
                    log.info("View {} already exists, skipping creation.",
                            (viewDatabase != null ? viewDatabase + "." : "") + viewName);
                    rs.close();
                    return;
                }
                if (rs != null) {
                    rs.close();
                }
            } catch (Exception e) {
                log.warn("Could not check if view exists, will attempt to create it", e);
            }
        }

        try {
            new DBMetadata(props).executeSystemQuery(conn, formattedView);
        } catch (Exception e) {
            log.error("**** Error creating VIEW **** " + formattedView);
        }
    }

    /**
     * Function to get the database name and table name for the offset
     * storage table.
     *
     * @param props The connector properties.
     * @return A Pair with the table name as the left element and the database
     *         name as the right element.
     */
    private Pair<String, String> getDebeziumOffsetStorageDatabaseName(Properties props) {
        String propKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        String tableName = props.getProperty(propKey);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Required configuration property '" + propKey + "' is not set. "
                    + "Please add it to your config file (e.g. "
                    + "offset.storage.jdbc.table.name: \"altinity_sink_connector.replica_source_info\").");
        }
        return splitTableName(tableName);
    }

    /**
     * Function to delete offsets from Debezium storage.
     *
     * @param props The connector properties.
     * @throws SQLException If a database error occurs.
     */
    public void deleteOffsets(Connection conn, Properties props)
            throws SQLException {
        String offsetKey = this.debeziumOffsetStorage.getOffsetKey(props);
        this.debeziumOffsetStorage.deleteOffsetStorageRow(offsetKey, props, conn);
    }

    /**
     * Function to get the status of the error table.
     *
     * @param conn  The database connection.
     * @param props The connector properties.
     * @return
     * @throws SQLException If a database error occurs.
     */
    public String getErrorTableStatus(Connection conn, Properties props)
            throws SQLException {
        String response = "";

        String errorTableName = props.getProperty(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        if (errorTableName == null || errorTableName.isEmpty() == true) {
            log.warn("Skipping getting error table status as the query " +
                    "was not provided in configuration");
            return response;
        }
        String errorTableStatusQuery = String.format("select * from %s limit 1", errorTableName);
        DBMetadata metadata = new DBMetadata(props);
        ResultSet resultSet = metadata.executeQueryWithResultSet(
                errorTableStatusQuery, conn);
        if (resultSet != null) {
            ResultSetMetaData md = resultSet.getMetaData();
            int numCols = md.getColumnCount();
            List<String> colNames = IntStream.range(0, numCols)
                    .mapToObj(i -> {
                        try {
                            return md.getColumnName(i + 1);
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return "?";
                        }
                    })
                    .collect(Collectors.toList());
            JSONArray result = new JSONArray();
            // convert the result set to a json array.
            while (resultSet.next()) {
                JSONObject row = new JSONObject();
                colNames.forEach(cn -> {
                    try {
                        Object v = resultSet.getObject(cn);
                        row.put(cn, v);
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                result.add(row);
            }
            response = result.toString();
        }


        return response;

        // TODO: Return the status of the error table.

    }

    /**
     * Function to get the status of Debezium storage.
     *
     * @param conn   The database connection.
     * @param config The ClickHouse sink connector config.
     * @param props  The connector properties.
     * @return A JSON string representing the Debezium storage status.
     * @throws Exception If an error occurs while retrieving status.
     */
    public String getDebeziumStorageStatus(Connection conn,
                                           ClickHouseSinkConnectorConfig config,
                                           Properties props)
            throws Exception {
        String response = "";
        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();
        DBCredentials dbCredentials = parseDBConfiguration(config);
        String debeziumStorageStatusQuery = String.format(
                "select * from %s limit 1", databaseName + "." + tableName);
        DBMetadata metadata = new DBMetadata(config);
        ResultSet resultSet = metadata.executeQueryWithResultSet(
                debeziumStorageStatusQuery, conn);
        if (resultSet != null) {
            ResultSetMetaData md = resultSet.getMetaData();
            int numCols = md.getColumnCount();
            List<String> colNames = IntStream.range(0, numCols)
                    .mapToObj(i -> {
                        try {
                            return md.getColumnName(i + 1);
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return "?";
                        }
                    })
                    .collect(Collectors.toList());
            JSONArray result = new JSONArray();
            JSONObject replicationLag = new JSONObject();
            replicationLag.put("Seconds_Behind_Source",
                    ReplicationStatusSingleton.getInstance().getReplicationLag() / 1000);
            result.add(replicationLag);
            JSONObject replicationRunning = new JSONObject();
            replicationRunning.put("Replica_Running",
                    ReplicationStatusSingleton.getInstance().isReplicationRunning());
            result.add(replicationRunning);
            // Add Database name and table name.
            JSONObject dbName = new JSONObject();
            dbName.put("Database", dbCredentials.getDatabase());
            result.add(dbName);
            while (resultSet.next()) {
                JSONObject row = new JSONObject();
                colNames.forEach(cn -> {
                    try {
                        Object v = resultSet.getObject(cn);
                        if (v != null && v instanceof LocalDateTime) {
                            v = ((LocalDateTime) v).toString();
                        }
                        row.put(cn, v);
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                result.add(row);
            }
            response = result.toJSONString();
        }
        return response;
    }

    /**
     * Function to get the latest timestamp from Debezium storage.
     *
     * @param conn  The database connection.
     * @param props The connector properties.
     * @return The latest record timestamp in milliseconds.
     * @throws SQLException If a database error occurs.
     */
    public long getLatestRecordTimestamp(Connection conn, Properties props)
            throws SQLException {
        long result = -1;
        String latestRecordTs = this.debeziumOffsetStorage.getDebeziumLatestRecordTimestamp(props, conn);
        if (latestRecordTs == null || latestRecordTs.isEmpty()) {
            // The query returned no result, e.g. because ClickHouse was not
            // reachable. Without this guard SimpleDateFormat.parse() below
            // throws a NullPointerException, which the ParseException handler
            // does not catch.
            log.error("Latest record timestamp is not available");
            return result;
        }
        // Convert date string from "yyyy-MM-dd HH:mm:ss" format to milliseconds.
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = null;
        try {
            date = sdf.parse(latestRecordTs);
            ReplicationStatusSingleton.getInstance().setLastRecordTimestamp(date.getTime());
        } catch (java.text.ParseException e) {
            log.error("Error parsing date", e);
        }
        if (date != null) {
            result = date.getTime();
        }
        return result;
    }

    /**
     * Function to update the status of Debezium storage with binlog info.
     *
     * @param conn         The database connection.
     * @param config       The ClickHouse sink connector config.
     * @param props        The connector properties.
     * @param binlogFile   The binlog file.
     * @param binLogPosition The binlog position.
     * @param gtid         The GTID.
     * @throws SQLException   If a database error occurs.
     * @throws ParseException If a parsing error occurs.
     */
    public void updateDebeziumStorageStatus(Connection conn,
                                            ClickHouseSinkConnectorConfig config,
                                            Properties props,
                                            String binlogFile,
                                            String binLogPosition,
                                            String gtid)
            throws SQLException, ParseException {
        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String offsetValue = this.debeziumOffsetStorage.getDebeziumStorageStatusQuery(props, conn);
        String offsetKey = this.debeziumOffsetStorage.getOffsetKey(props);
        String updateOffsetValue = this.debeziumOffsetStorage.updateBinLogInformation(
                offsetValue, binlogFile, binLogPosition, gtid);
        this.debeziumOffsetStorage.deleteOffsetStorageRow(offsetKey, props, conn);
        this.debeziumOffsetStorage.updateDebeziumStorageRow(conn, tableName, offsetKey,
                updateOffsetValue, System.currentTimeMillis());
    }

    /**
     * Function to delete the Debezium schema history.
     *
     * @param conn   The database connection.
     * @param config The ClickHouse sink connector config.
     * @param props  The connector properties.
     * @throws SQLException If a database error occurs.
     */
    public void deleteSchemaHistory(Connection conn,
                                    ClickHouseSinkConnectorConfig config,
                                    Properties props)
            throws SQLException {
        Pair<String, String> tableNameDatabaseName =
                getDebeziumSchemaHistoryDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String databaseName = tableNameDatabaseName.getRight();
        String topicPrefix = props.getProperty(CommonConnectorConfig.TOPIC_PREFIX.name());
        new DebeziumOffsetStorage().deleteSchemaHistoryTable(topicPrefix, tableName, conn, props);
    }

    /**
     * Function to update the Debezium storage status (LSN).
     *
     * @param conn   The database connection.
     * @param config The ClickHouse sink connector config.
     * @param props  The connector properties.
     * @param lsn    The new LSN value.
     * @throws SQLException   If a database error occurs.
     * @throws ParseException If a parsing error occurs.
     */
    public void updateDebeziumStorageStatus(Connection conn,
                                            ClickHouseSinkConnectorConfig config,
                                            Properties props,
                                            String lsn)
            throws SQLException, ParseException {
        Pair<String, String> tableNameDatabaseName =
                getDebeziumOffsetStorageDatabaseName(props);
        String tableName = tableNameDatabaseName.getLeft();
        String offsetValue = this.debeziumOffsetStorage.getDebeziumStorageStatusQuery(props, conn);
        String offsetKey = this.debeziumOffsetStorage.getOffsetKey(props);
        String updateOffsetValue = this.debeziumOffsetStorage.updateLsnInformation(
                offsetValue, lsn);
        this.debeziumOffsetStorage.deleteOffsetStorageRow(offsetKey, props, conn);
        this.debeziumOffsetStorage.updateDebeziumStorageRow(conn, tableName, offsetKey,
                updateOffsetValue, System.currentTimeMillis());
    }

    /**
     * Function to get the Debezium schema history database name.
     *
     * @param props The connector properties.
     * @return A Pair where the left element is the table name and the right
     *         element is the database name.
     */
    private Pair<String, String> getDebeziumSchemaHistoryDatabaseName(Properties props) {
        String propKey = SchemaHistory.CONFIGURATION_FIELD_PREFIX_STRING +
                JdbcSchemaHistoryConfig.PROP_TABLE_NAME.name();
        String tableName = props.getProperty(propKey);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Required configuration property '" + propKey + "' is not set. "
                    + "Please add it to your config file.");
        }
        return splitTableName(tableName);
    }

    /**
     * Splits a fully qualified table name into its table and database parts.
     *
     * @param tableName The fully qualified table name.
     * @return A Pair with the table name as the left element and the database
     *         name as the right element. Defaults to "system" if not present.
     */
    private Pair<String, String> splitTableName(String tableName) {
        String databaseName = "system";
        if (tableName.contains(".")) {
            String[] dbTableNameArray = tableName.split("\\.");
            if (dbTableNameArray.length >= 2) {
                databaseName = dbTableNameArray[0].replace("\"", "");
                tableName = dbTableNameArray[1].replace("\"", "");
            }
        }
        return Pair.of(tableName, databaseName);
    }

    /**
     * Parses the database configuration from the ClickHouse sink connector config.
     *
     * @param config The ClickHouse sink connector config.
     * @return A DBCredentials object with the necessary database connection details.
     */
    DBCredentials parseDBConfiguration(ClickHouseSinkConnectorConfig config) {
        DBCredentials dbCredentials = new DBCredentials();
        dbCredentials.setHostName(config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString()));
        dbCredentials.setPort(config.getInt(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString()));
        dbCredentials.setUserName(config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString()));
        dbCredentials.setPassword(config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString()));
        dbCredentials.setDatabase("system");
        return dbCredentials;
    }
}
