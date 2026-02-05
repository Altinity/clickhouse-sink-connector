package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles ALTER TABLE operations for ClickHouse to support schema
 * evolution.
 *
 * <p>This class provides methods to generate SQL syntax for altering
 * tables and to execute these operations when adding missing columns.
 */
public class ClickHouseAlterTable
        extends ClickHouseTableOperationsBase {

    /**
     * Logger instance for the ClickHouseAlterTable class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseAlterTable.class.getName());

    /**
     * Enum for specifying the type of ALTER TABLE operation.
     */
    public enum ALTER_TABLE_OPERATION {
        /**
         * Operation to add a new column.
         */
        ADD("add"),
        /**
         * Operation to remove an existing column.
         */
        REMOVE("remove"),
        /**
         * Operation to rename a column.
         */
        RENAME("rename"),
        /**
         * Operation to modify a column type.
         */
        MODIFY("modify");

        String op;

        ALTER_TABLE_OPERATION(String op) {
            this.op = op;
        }
    }

    /**
     * Creates the SQL syntax for an ALTER TABLE operation.
     *
     * <p>The SQL is built based on the provided map of column names to
     * data types and the specified operation. For example:
     *
     * <pre>
     * ALTER TABLE table_name ADD COLUMN `col1` data_type1,
     * ADD COLUMN `col2` data_type2
     * </pre>
     *
     * @param tableName the name of the table to alter
     * @param colNameToDataTypesMap a map of column names to data types
     * @param operation the ALTER_TABLE_OPERATION to perform (ADD or REMOVE)
     * @return a SQL string for altering the table as
     * specified
     */
    public String createAlterTableSyntax(String tableName,
                                         Map<String, String> colNameToDataTypesMap,
                                         ALTER_TABLE_OPERATION operation) {

        StringBuilder alterTableSyntax = new StringBuilder();
        alterTableSyntax.append(ClickHouseDbConstants.ALTER_TABLE)
                .append(" ").append(tableName).append(" ");

        for (Map.Entry<String, String> entry
                : colNameToDataTypesMap.entrySet()) {
            if (operation.name().equalsIgnoreCase(
                    ALTER_TABLE_OPERATION.ADD.op)) {
                alterTableSyntax.append(
                                ClickHouseDbConstants.ALTER_TABLE_ADD_COLUMN)
                        .append(" ");
            } else {
                alterTableSyntax.append(
                                ClickHouseDbConstants.ALTER_TABLE_DELETE_COLUMN)
                        .append(" ");
            }
            alterTableSyntax.append("`").append(entry.getKey())
                    .append("`").append(" ").append(entry.getValue())
                    .append(",");
        }
        alterTableSyntax.deleteCharAt(
                alterTableSyntax.lastIndexOf(","));
        return alterTableSyntax.toString();
    }

    /**
     * Alters the ClickHouse table by adding columns that are missing.
     *
     * <p>This method compares the list of modified fields with the current
     * columns in ClickHouse. Missing fields are identified and an ALTER
     * TABLE query is generated to add them.
     *
     * @param modifiedFields a list of fields that have been
     *                       modified
     * @param tableName the name of the table to be altered
     * @param connection the database connection to use for executing the
     *                   ALTER TABLE query
     * @param columnNameToDataTypeMap a map of current column names
     *                                to data types
     */
    public void alterTable(List<Field> modifiedFields, String tableName,
                           Connection connection,
                           Map<String, String> columnNameToDataTypeMap,
                           ClickHouseSinkConnectorConfig config) throws SQLException {

        List<Field> missingFieldsInCH = new ArrayList<>();
        // Identify columns that are missing in ClickHouse.
        for (Field f : modifiedFields) {
            String colName = f.name();
            if (!columnNameToDataTypeMap.containsKey(colName)) {
                missingFieldsInCH.add(f);
            }
        }

        if (!missingFieldsInCH.isEmpty()) {
            log.info("***** ALTER TABLE ****");
            ClickHouseAlterTable cat = new ClickHouseAlterTable();
            Field[] missingFieldsArray =
                    new Field[missingFieldsInCH.size()];
            missingFieldsInCH.toArray(missingFieldsArray);
            Map<String, String> colNameToDataTypeMap2 =
                    cat.getColumnNameToCHDataTypeMapping(missingFieldsArray,config);

            if (!colNameToDataTypeMap2.isEmpty()) {
                String alterTableQuery = cat.createAlterTableSyntax(
                        tableName, colNameToDataTypeMap2,
                        ALTER_TABLE_OPERATION.ADD);
                log.info(" ***** ALTER TABLE QUERY **** "
                        + alterTableQuery);
                try {
                    DBMetadata metadata = new DBMetadata(config);
                    metadata.executeSystemQuery(connection,
                            alterTableQuery);
                } catch (Exception e) {
                    log.error(" **** ALTER TABLE EXCEPTION ", e);
                }
            }
        }
    }

    /**
     * Drops a column from the ClickHouse table.
     *
     * @param tableName the name of the table
     * @param columnName the name of the column to drop
     * @param connection the database connection
     * @param config the connector configuration
     * @throws SQLException if the operation fails
     */
    public void dropColumn(String tableName, String columnName,
                          Connection connection,
                          ClickHouseSinkConnectorConfig config) throws SQLException {
        
        String dropBehavior = config.getString(
            ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString());
        if (dropBehavior == null || dropBehavior.isEmpty()) {
            dropBehavior = "RENAME"; // Default
        }
        
        log.info("DROP COLUMN detected: table={}, column={}, behavior={}",
            tableName, columnName, dropBehavior);
        
        switch (dropBehavior.toUpperCase()) {
            case "DROP":
                executeDropColumn(tableName, columnName, connection, config);
                break;
                
            case "RENAME":
                // Safer: rename to _deleted_<name>_<timestamp>
                String newName = "_deleted_" + columnName + "_" + System.currentTimeMillis();
                executeRenameColumn(tableName, columnName, newName, connection, config);
                log.info("Renamed dropped column {} to {} for safety", columnName, newName);
                break;
                
            case "IGNORE":
                log.warn("DROP COLUMN ignored for {}.{}, column remains in ClickHouse",
                    tableName, columnName);
                break;
                
            case "FAIL":
                throw new RuntimeException(
                    "DROP COLUMN not allowed by configuration for " + tableName + "." + columnName
                );
                
            default:
                throw new IllegalArgumentException("Invalid drop.column.behavior: " + dropBehavior);
        }
    }

    /**
     * Executes the actual DROP COLUMN SQL command.
     */
    private void executeDropColumn(String tableName, String columnName, Connection connection,
                                   ClickHouseSinkConnectorConfig config)
            throws SQLException {
        String sql = String.format("ALTER TABLE %s DROP COLUMN `%s`", tableName, columnName);
        
        log.info("Executing DROP COLUMN: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully dropped column {}.{}", tableName, columnName);
        } catch (SQLException e) {
            log.error("Failed to drop column {}.{}", tableName, columnName, e);
            throw e;
        }
    }

    /**
     * Renames a column in the ClickHouse table.
     *
     * @param tableName the name of the table
     * @param oldColumnName the current column name
     * @param newColumnName the new column name
     * @param connection the database connection
     * @throws SQLException if the operation fails
     */
    public void renameColumn(String tableName, String oldColumnName, String newColumnName,
                            Connection connection, ClickHouseSinkConnectorConfig config) throws SQLException {
        executeRenameColumn(tableName, oldColumnName, newColumnName, connection, config);
    }

    /**
     * Executes the actual RENAME COLUMN SQL command.
     */
    private void executeRenameColumn(String tableName, String oldColumnName,
                                     String newColumnName, Connection connection,
                                     ClickHouseSinkConnectorConfig config)
            throws SQLException {
        String sql = String.format("ALTER TABLE %s RENAME COLUMN `%s` TO `%s`",
            tableName, oldColumnName, newColumnName);
        
        log.info("Executing RENAME COLUMN: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully renamed column {}.{} to {}", tableName, oldColumnName, newColumnName);
        } catch (SQLException e) {
            log.error("Failed to rename column {}.{} to {}", tableName, oldColumnName, newColumnName, e);
            throw e;
        }
    }

    /**
     * Modifies a column type with safety checks for type compatibility.
     *
     * @param tableName the name of the table
     * @param columnName the column to modify
     * @param oldType the current ClickHouse type
     * @param newType the new ClickHouse type
     * @param connection the database connection
     * @param config the connector configuration
     * @throws SQLException if the operation fails
     */
    public void modifyColumn(String tableName, String columnName,
                            String oldType, String newType,
                            Connection connection,
                            ClickHouseSinkConnectorConfig config) throws SQLException {
        
        String typeChangeBehavior = config.getString(
            ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString());
        if (typeChangeBehavior == null || typeChangeBehavior.isEmpty()) {
            typeChangeBehavior = "MODIFY"; // Default
        }
        
        log.info("MODIFY COLUMN detected: table={}, column={}, {} → {}, behavior={}",
            tableName, columnName, oldType, newType, typeChangeBehavior);
        
        switch (typeChangeBehavior.toUpperCase()) {
            case "MODIFY":
                boolean safeOnly = config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString());
                if (safeOnly && !isSafeTypeChange(oldType, newType)) {
                    String msg = String.format("Unsafe type change detected: %s.%s %s → %s",
                        tableName, columnName, oldType, newType);
                    log.error(msg);
                    throw new RuntimeException(msg);
                }
                executeModifyColumn(tableName, columnName, newType, connection, config);
                break;
                
            case "IGNORE":
                log.warn("Type change ignored for {}.{}, may cause data errors",
                    tableName, columnName);
                break;
                
            case "FAIL":
                throw new RuntimeException(
                    "Type change not allowed by configuration for " + tableName + "." + columnName
                );
                
            default:
                throw new IllegalArgumentException("Invalid type.change.behavior: " + typeChangeBehavior);
        }
    }

    /**
     * Executes the actual MODIFY COLUMN SQL command.
     */
    private void executeModifyColumn(String tableName, String columnName,
                                     String newType, Connection connection,
                                     ClickHouseSinkConnectorConfig config)
            throws SQLException {
        String sql = String.format("ALTER TABLE %s MODIFY COLUMN `%s` %s",
            tableName, columnName, newType);
        
        log.info("Executing MODIFY COLUMN: {}", sql);
        
        try {
            DBMetadata metadata = new DBMetadata(config);
            metadata.executeSystemQuery(connection, sql);
            log.info("Successfully modified column {}.{} to type {}", tableName, columnName, newType);
        } catch (SQLException e) {
            log.error("Failed to modify column {}.{} to type {}", tableName, columnName, newType, e);
            throw e;
        }
    }

    /**
     * Checks if a type change is safe (widening conversions only).
     *
     * @param oldType the current ClickHouse type
     * @param newType the new ClickHouse type
     * @return true if the type change is safe
     */
    private boolean isSafeTypeChange(String oldType, String newType) {
        // Normalize types (remove Nullable wrapper, etc.)
        String oldBase = normalizeType(oldType);
        String newBase = normalizeType(newType);
        
        // Same type is always safe (might be nullability change)
        if (oldBase.equals(newBase)) {
            return true;
        }
        
        // Safe widening conversions
        Map<String, List<String>> safeConversions = new HashMap<>();
        safeConversions.put("Int8", List.of("Int16", "Int32", "Int64"));
        safeConversions.put("Int16", List.of("Int32", "Int64"));
        safeConversions.put("Int32", List.of("Int64"));
        safeConversions.put("UInt8", List.of("UInt16", "UInt32", "UInt64"));
        safeConversions.put("UInt16", List.of("UInt32", "UInt64"));
        safeConversions.put("UInt32", List.of("UInt64"));
        safeConversions.put("Float32", List.of("Float64"));
        safeConversions.put("Date", List.of("DateTime", "DateTime64"));
        safeConversions.put("DateTime", List.of("DateTime64"));
        
        // Check if conversion is in safe list
        List<String> allowedTargets = safeConversions.get(oldBase);
        if (allowedTargets != null && allowedTargets.contains(newBase)) {
            return true;
        }
        
        // String size increases are safe (ClickHouse String is unbounded)
        if (oldBase.equals("String") && newBase.equals("String")) {
            return true;
        }
        
        log.warn("Potentially unsafe type change: {} → {}", oldType, newType);
        return false;
    }

    /**
     * Normalizes a ClickHouse type by removing Nullable wrapper and other modifiers.
     */
    private String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        
        // Remove Nullable() wrapper
        if (type.startsWith("Nullable(") && type.endsWith(")")) {
            type = type.substring(9, type.length() - 1);
        }
        
        // Remove LowCardinality() wrapper
        if (type.startsWith("LowCardinality(") && type.endsWith(")")) {
            type = type.substring(15, type.length() - 1);
        }
        
        return type.trim();
    }
}
