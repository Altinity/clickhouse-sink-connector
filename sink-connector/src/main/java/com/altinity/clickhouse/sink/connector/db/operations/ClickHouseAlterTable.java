package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
        REMOVE("remove");

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
        return createAlterTableSyntax(null, tableName, colNameToDataTypesMap, operation);
    }

    /**
     * Creates the SQL syntax for an ALTER TABLE operation with database prefix.
     *
     * @param databaseName the name of the database (may be null for backwards compatibility)
     * @param tableName    the name of the table to alter
     * @param colNameToDataTypesMap a map of column names to data types
     * @param operation    the ALTER_TABLE_OPERATION to perform (ADD or REMOVE)
     * @return a SQL string for altering the table
     */
    public String createAlterTableSyntax(String databaseName, String tableName,
                                         Map<String, String> colNameToDataTypesMap,
                                         ALTER_TABLE_OPERATION operation) {

        if (colNameToDataTypesMap == null || colNameToDataTypesMap.isEmpty()) {
            log.warn("createAlterTableSyntax called with empty column map for table {}", tableName);
            return "";
        }

        StringBuilder alterTableSyntax = new StringBuilder();
        alterTableSyntax.append(ClickHouseDbConstants.ALTER_TABLE).append(" ");
        if (databaseName != null && !databaseName.isEmpty()) {
            alterTableSyntax.append("`").append(databaseName).append("`.`").append(tableName).append("`");
        } else {
            alterTableSyntax.append("`").append(tableName).append("`");
        }
        alterTableSyntax.append(" ");

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
}
