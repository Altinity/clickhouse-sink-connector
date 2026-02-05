package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Handles DROP TABLE and RENAME TABLE operations for ClickHouse.
 *
 * <p>This class provides methods to safely drop or rename tables with
 * configurable behavior to prevent accidental data loss.
 */
public class ClickHouseDropTable {

    /**
     * Logger instance for the ClickHouseDropTable class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseDropTable.class.getName());

    /**
     * Drops a table from ClickHouse based on configured behavior.
     *
     * @param tableName the name of the table to drop
     * @param databaseName the database containing the table
     * @param connection the database connection
     * @param config the connector configuration
     * @throws SQLException if the operation fails
     */
    public void dropTable(String tableName, String databaseName,
                         Connection connection,
                         ClickHouseSinkConnectorConfig config) throws SQLException {
        
        String dropBehavior = config.getString(
            ClickHouseSinkConnectorConfigVariables.DROP_TABLE_BEHAVIOR.toString());
        if (dropBehavior == null || dropBehavior.isEmpty()) {
            dropBehavior = "RENAME"; // Default to safer option
        }
        
        String fullTableName = databaseName != null ? databaseName + "." + tableName : tableName;
        
        log.info("DROP TABLE detected: table={}, behavior={}", fullTableName, dropBehavior);
        
        switch (dropBehavior.toUpperCase()) {
            case "DROP":
                executeDropTable(fullTableName, connection, config);
                break;
                
            case "RENAME":
                // Safer: rename to _deleted_<name>_<timestamp>
                String newName = "_deleted_" + tableName + "_" + System.currentTimeMillis();
                executeRenameTable(fullTableName, newName, databaseName, connection, config);
                log.info("Renamed dropped table {} to {} for safety", tableName, newName);
                break;
                
            case "IGNORE":
                log.warn("DROP TABLE ignored for {}, table remains in ClickHouse", fullTableName);
                break;
                
            case "FAIL":
                throw new RuntimeException(
                    "DROP TABLE not allowed by configuration for " + fullTableName
                );
                
            default:
                throw new IllegalArgumentException("Invalid drop.table.behavior: " + dropBehavior);
        }
    }

    /**
     * Executes the actual DROP TABLE SQL command.
     */
    private void executeDropTable(String tableName, Connection connection,
                                  ClickHouseSinkConnectorConfig config) 
            throws SQLException {
        String sql = String.format("DROP TABLE IF EXISTS %s", tableName);
        
        log.info("Executing DROP TABLE: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully dropped table {}", tableName);
        } catch (SQLException e) {
            log.error("Failed to drop table {}", tableName, e);
            throw e;
        }
    }

    /**
     * Renames a table in ClickHouse.
     *
     * @param oldTableName the current table name (can include database prefix)
     * @param newTableName the new table name (without database prefix)
     * @param databaseName the database containing the table
     * @param connection the database connection
     * @param config the connector configuration
     * @throws SQLException if the operation fails
     */
    public void renameTable(String oldTableName, String newTableName, String databaseName,
                           Connection connection,
                           ClickHouseSinkConnectorConfig config) throws SQLException {
        executeRenameTable(oldTableName, newTableName, databaseName, connection, config);
    }

    /**
     * Executes the actual RENAME TABLE SQL command.
     */
    private void executeRenameTable(String oldTableName, String newTableName,
                                    String databaseName,
                                    Connection connection,
                                    ClickHouseSinkConnectorConfig config) 
            throws SQLException {
        // Build full table names
        String fullNewTableName = databaseName != null ? databaseName + "." + newTableName : newTableName;
        
        String sql = String.format("RENAME TABLE %s TO %s", 
            oldTableName, fullNewTableName);
        
        log.info("Executing RENAME TABLE: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully renamed table {} to {}", oldTableName, fullNewTableName);
        } catch (SQLException e) {
            log.error("Failed to rename table {} to {}", oldTableName, fullNewTableName, e);
            throw e;
        }
    }

    /**
     * Checks if a table exists in ClickHouse.
     *
     * @param tableName the table name
     * @param databaseName the database name
     * @param connection the database connection
     * @param config the connector configuration
     * @return true if the table exists
     */
    public boolean tableExists(String tableName, String databaseName,
                               Connection connection,
                               ClickHouseSinkConnectorConfig config) {
        try {
            DBMetadata metadata = new DBMetadata(config);
            String sql = String.format(
                "SELECT 1 FROM system.tables WHERE database = '%s' AND name = '%s'",
                databaseName, tableName
            );
            
            java.sql.ResultSet rs = connection.createStatement().executeQuery(sql);
            boolean exists = rs.next();
            rs.close();
            return exists;
        } catch (SQLException e) {
            log.error("Error checking if table exists: {}.{}", databaseName, tableName, e);
            return false;
        }
    }

    /**
     * Truncates a table in ClickHouse.
     *
     * @param tableName the table name
     * @param databaseName the database name
     * @param connection the database connection
     * @param config the connector configuration
     * @throws SQLException if the operation fails
     */
    public void truncateTable(String tableName, String databaseName,
                             Connection connection,
                             ClickHouseSinkConnectorConfig config) throws SQLException {
        String fullTableName = databaseName != null ? databaseName + "." + tableName : tableName;
        String sql = String.format("TRUNCATE TABLE %s", fullTableName);
        
        log.info("Executing TRUNCATE TABLE: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully truncated table {}", fullTableName);
        } catch (SQLException e) {
            log.error("Failed to truncate table {}", fullTableName, e);
            throw e;
        }
    }
}
