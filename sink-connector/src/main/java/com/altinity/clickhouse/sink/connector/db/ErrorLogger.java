package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Static class to handle error logging in ClickHouse.
 * Creates and manages the replica_source_error table for storing error information.
 */
public class ErrorLogger {
    private static final Logger log = LogManager.getLogger(ErrorLogger.class);

    // Default table name if not specified in config
    private static final String DEFAULT_ERROR_TABLE = "replica_source_error";

    /**
     * Resolves the configured error table name, falling back to the default when unset.
     *
     * @param config the connector configuration
     * @return the error table name
     */
    public static String getErrorTableName(ClickHouseSinkConnectorConfig config) {
        if (config == null) {
            return DEFAULT_ERROR_TABLE;
        }
        String errorTableName = config.getString(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        if (errorTableName == null || errorTableName.isEmpty()
                || ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString().equals(errorTableName)) {
            return DEFAULT_ERROR_TABLE;
        }
        return errorTableName;
    }

    /**
     * Creates the error table if it doesn't exist.
     *
     * @param connection The ClickHouse connection
     * @param config     The connector configuration
     * @throws SQLException If there's an error creating the table
     */
    public static void createErrorTable(Connection connection, ClickHouseSinkConnectorConfig config) throws SQLException {
        if (connection == null) {
            throw new SQLException("Connection cannot be null");
        }

        if (config == null) {
            throw new SQLException("Config cannot be null");
        }
        // Read error table name from config
        String errorTableName = getErrorTableName(config);

        String createTableQuery = String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
                "error_timestamp DateTime64(3) DEFAULT now()," +
                "error String," +
                "offset_key String," +
                "binlog_file String," +
                "binlog_position UInt64," +
                "gtid String," +
                "server String," +
                "source_database String," +
                "database_query String" +
            ") ENGINE = MergeTree() " +
            "ORDER BY (error_timestamp, server)", 
            BaseDbWriter.SYSTEM_DB, 
            errorTableName
        );

        DBMetadata dbMetadata = new DBMetadata(config);
        dbMetadata.executeSystemQuery(connection, createTableQuery);
    }

    /**
     * Inserts an error record into the error table.
     *
     * @param connection The ClickHouse connection
     * @param error The error message
     * @param record The source record that caused the error
     * @param sourceDatabase The source database name
     * @param query The database query that caused the error
     * @param offsetKey The offset key from the connector properties
     * @throws SQLException If there's an error inserting the record
     */
    public static void logError(Connection connection,
                              String error, 
                              SourceRecord record,
                              String sourceDatabase,
                              String query, 
                              String offsetKey,
                              String errorTableName) throws SQLException {
        if (connection == null) {
            throw new SQLException("Connection cannot be null");
        }

        if (error == null || error.isEmpty()) {
            error = "Unknown error";
        }

        if (errorTableName == null || errorTableName.isEmpty()) {
            errorTableName = DEFAULT_ERROR_TABLE;
        }

        String insertQuery = String.format(
            "INSERT INTO %s.%s (" +
                "error, " +
                "offset_key, " +
                "binlog_file, " +
                "binlog_position, " +
                "gtid, " +
                "server, " +
                "source_database, " +
                "database_query" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", 
            BaseDbWriter.SYSTEM_DB, 
            errorTableName
        );

        try (var statement = connection.prepareStatement(insertQuery)) {
            // Set error message
            statement.setString(1, error);

            // Set offset key
            statement.setString(2, offsetKey != null ? offsetKey : "");

            // Set binlog file
            String binlogFile = "";
            if (record != null && record.sourceOffset() != null) {
                Object fileObj = record.sourceOffset().get("file");
                binlogFile = fileObj != null ? fileObj.toString() : "";
            }
            statement.setString(3, binlogFile);

            // Set binlog position
            long binlogPosition = 0;
            if (record != null && record.sourceOffset() != null) {
                Object posObj = record.sourceOffset().get("pos");
                if (posObj != null) {
                    try {
                        binlogPosition = Long.parseLong(posObj.toString());
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse binlog position: " + posObj);
                    }
                }
            }
            statement.setLong(4, binlogPosition);

            // Set GTID
            String gtid = "";
            if (record != null && record.sourceOffset() != null) {
                Object gtidObj = record.sourceOffset().get("gtids");
                gtid = gtidObj != null ? gtidObj.toString() : "";
            }
            statement.setString(5, gtid);

            // Set server (topic)
            String server = record != null ? record.topic() : "";
            statement.setString(6, server);

            // Set source database
            statement.setString(7, sourceDatabase != null ? sourceDatabase : "");

            // Set database query
            statement.setString(8, query != null ? query : "");

            statement.execute();
        } catch (SQLException e) {
            log.error("Failed to log error to ClickHouse", e);
            throw e;
        }
    }
} 
