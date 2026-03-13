package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class responsible for generating raw queries for the ClickHouse JDBC library.
 * <p>
 * This class contains methods for constructing SQL INSERT queries using input
 * functions, ensuring proper handling of column names, data types, and Kafka
 * metadata. It also validates and formats columns to be inserted into ClickHouse
 * based on the given schema.
 * </p>
 */
public class QueryFormatter {

    /**
     * Logger instance used for logging messages in the QueryFormatter class.
     * <p>
     * This logger is initialized using LogManager to log events related to
     * query formatting and database operations, allowing for tracking and
     * troubleshooting.
     * </p>
     */
    private static final Logger log = LogManager.getLogger(QueryFormatter.class);

    /**
     * Checks if a column is related to Kafka metadata.
     * <p>
     * Kafka metadata columns (such as topic, partition, offset, etc.) are special
     * columns that need to be handled differently when generating insert queries.
     * </p>
     *
     * @param colName the name of the column to check.
     * @return true if the column is Kafka metadata, false otherwise.
     */
    private boolean isKafkaMetaDataColumn(String colName) {
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (metaDataColName.equalsIgnoreCase(colName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates an INSERT SQL query using input functions for the specified table and fields.
     * <p>
     * This method constructs an INSERT query for inserting data into ClickHouse. It
     * ensures that Kafka metadata and raw data columns are included if specified in
     * the configuration. It also ensures the columns in the source schema match
     * the ones expected by ClickHouse.
     * </p>
     *
     * @param tableName               the name of the ClickHouse table.
     * @param fields                  the list of fields from the source schema.
     * @param columnNameToDataTypeMap a map of column names to their corresponding data types.
     * @param includeKafkaMetaData    flag indicating whether Kafka metadata columns should be included.
     * @param includeRawData          flag indicating whether raw data should be included in the query.
     * @param rawDataColumn           the name of the raw data column.
     * @param dbName                  the name of the database.
     * @return a MutablePair containing the generated INSERT query and a map of column names to their indices.
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryUsingInputFunction(
            String tableName, List<Field> fields,
            Map<String, String> columnNameToDataTypeMap,
            boolean includeKafkaMetaData,
            boolean includeRawData,
            String rawDataColumn, String dbName) {

        // Create column data structures
        ColumnData columnData = createColumns(tableName, fields, columnNameToDataTypeMap,
                includeKafkaMetaData, includeRawData, rawDataColumn, dbName);

        if (columnData == null) {
            return null;
        }

        // Construct the full insert query
        String tableWithBackTicks = "`" + tableName + "`";
        String insertQuery = String.format("insert into %s(%s) select %s from input('%s')",
                tableWithBackTicks, columnData.colNamesDelimited, columnData.colNamesDelimited, columnData.colNamesToDataTypes);

        // Return the query and column index map
        MutablePair<String, Map<String, Integer>> response = new MutablePair<>();
        response.left = insertQuery;
        response.right = columnData.colNameToIndexMap;

        return response;
    }


    /**
     * Helper class to hold the results of column creation.
     */
    private static class ColumnData {
        Map<String, Integer> colNameToIndexMap;
        StringBuilder colNamesDelimited;
        StringBuilder colNamesToDataTypes;

        ColumnData(Map<String, Integer> colNameToIndexMap, StringBuilder colNamesDelimited, StringBuilder colNamesToDataTypes) {
            this.colNameToIndexMap = colNameToIndexMap;
            this.colNamesDelimited = colNamesDelimited;
            this.colNamesToDataTypes = colNamesToDataTypes;
        }
    }

    /**
     * Checks if a column is a temporal tracking column used for history.
     * These columns should use DEFAULT values from the table schema.
     *
     * @param colName the name of the column to check.
     * @return true if the column is a temporal tracking column, false otherwise.
     */
    private boolean isTemporalTrackingColumn(String colName) {
        return colName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN) ||
               colName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN) ||
               colName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN) ||
               colName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN) ||
               colName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN);
    }

    /**
     * Formats a parameter placeholder for use in SQL based on the ClickHouse data type.
     * DECIMAL types are wrapped with CAST function, DateTime/DateTime64 types are wrapped
     * with toDateTime/toDateTime64 functions to ensure proper type handling.
     *
     * @param dataType the ClickHouse data type
     * @return the formatted parameter placeholder (e.g., "?", "CAST(?, 'Decimal(10, 2)')", "toDateTime(?)")
     */
    private String formatParameterPlaceholder(String dataType) {
        if (dataType == null) {
            return "?";
        }
        String upperDataType = dataType.toUpperCase();
        // Check DateTime64 before DateTime since "DateTime64" contains "DateTime"
        // Check Date32 before Date since "Date32" contains "Date"
        if (upperDataType.contains("DATETIME64")) {
            // Extract precision and optional timezone from DateTime64(precision)
            // or DateTime64(precision, 'timezone')
            String precision = extractDateTime64Precision(dataType);
            String tz = extractDateTime64Timezone(dataType);
            if (tz != null) {
                return "toDateTime64(?, " + precision + ", '" + tz + "')";
            }
            return "toDateTime64(?, " + precision + ")";
        } else if (upperDataType.contains("DATETIME")) {
            return "toDateTime(?)";
        } else if (upperDataType.contains("DATE32")) {
            return "toDate32(?)";
        } else if (upperDataType.contains("DATE")) {
            return "toDate(?)";
        } else if (upperDataType.contains("DECIMAL")) {
            return "CAST(?, '" + dataType + "')";
        }
        return "?";
    }

    /**
     * Extracts the precision (scale) from a DateTime64 data type.
     * Handles formats like "DateTime64(3)" or "DateTime64(3, 'UTC')".
     *
     * @param dataType the DateTime64 data type string
     * @return the precision value as a string, defaults to "3" if not found
     */
    private String extractDateTime64Precision(String dataType) {
        // Find the opening parenthesis
        int start = dataType.indexOf('(');
        if (start == -1) {
            return "3"; // Default precision
        }
        // Find the first comma or closing parenthesis
        int end = dataType.indexOf(',', start);
        if (end == -1) {
            end = dataType.indexOf(')', start);
        }
        if (end == -1 || end <= start + 1) {
            return "3"; // Default precision
        }
        return dataType.substring(start + 1, end).trim();
    }

    /**
     * Extracts the timezone from a DateTime64 data type string.
     * Handles formats like "DateTime64(3, 'UTC')" or "Nullable(DateTime64(6, 'UTC'))".
     *
     * @param dataType the DateTime64 data type string
     * @return the timezone string (e.g. "UTC"), or null if no timezone is specified
     */
    private String extractDateTime64Timezone(String dataType) {
        // Look for single-quoted timezone: DateTime64(6, 'UTC')
        int singleQuoteStart = dataType.indexOf('\'');
        if (singleQuoteStart == -1) {
            return null;
        }
        int singleQuoteEnd = dataType.indexOf('\'', singleQuoteStart + 1);
        if (singleQuoteEnd == -1) {
            return null;
        }
        return dataType.substring(singleQuoteStart + 1, singleQuoteEnd);
    }

    /**
     * Formats a value for use in SQL based on the ClickHouse data type.
     * String types are quoted, numeric types are not.
     *
     * @param value the value to format
     * @param dataType the ClickHouse data type
     * @return the formatted value as a string
     */
    private String formatValueForSql(Object value, String dataType) {
        if (value == null) {
            return "NULL";
        }
        
        // Check if the data type is a string type
        String upperDataType = dataType.toUpperCase();
        if (upperDataType.startsWith("STRING") || 
            upperDataType.startsWith("FIXEDSTRING") ||
            upperDataType.startsWith("ENUM") ||
            upperDataType.startsWith("UUID")) {
            // Quote string types
            return "'" + value.toString().replace("'", "''") + "'";
        }
        
        // Numeric and other types don't need quotes
        return value.toString();
    }

    /**
     * Formats a value as a SQL literal for use in static SELECT expressions (no parameters).
     * Handles NULL, Decimal (CAST), string types (quoted/escaped), Date/DateTime, and other types.
     *
     * @param value    the value (may be null)
     * @param dataType the ClickHouse data type
     * @return SQL literal expression (e.g. "NULL", "'x'", "CAST(57.46, 'Decimal(10, 2)')", "toDateTime('2026-02-09 11:26:47', 'America/Chicago')")
     */
    private String formatLiteralForSql(Object value, String dataType) {
        if (value == null) {
            return "NULL";
        }
        if (dataType == null) {
            return value.toString();
        }
        String upperDataType = dataType.toUpperCase();
        if (upperDataType.contains("DECIMAL")) {
            String numLiteral = (value instanceof java.math.BigDecimal)
                ? ((java.math.BigDecimal) value).toPlainString()
                : value.toString();
            numLiteral = numLiteral.replace("'", "''");
            return "CAST('" + numLiteral + "', '" + dataType + "')";
        }
        if (upperDataType.contains("DATETIME64") || upperDataType.contains("DATETIME")) {
            String ts = value.toString().replace("'", "''");
            return "toDateTime('" + ts + "')";
        }
        if (upperDataType.contains("DATE32") || upperDataType.contains("DATE")) {
            if (value instanceof Number) {
                int epochDays = ((Number) value).intValue();
                String dateStr = LocalDate.ofEpochDay(epochDays).format(DateTimeFormatter.ISO_LOCAL_DATE);
                return "toDate('" + dateStr + "')";
            }
            String d = value.toString().replace("'", "''");
            return "toDate('" + d + "')";
        }
        if (upperDataType.contains("STRING") || upperDataType.contains("FIXEDSTRING")
                || upperDataType.contains("ENUM") || upperDataType.contains("UUID")) {
            return "'" + value.toString().replace("'", "''") + "'";
        }
        return value.toString();
    }

    /**
     * Creates column data structures for generating INSERT queries.
     *
     * @param tableName               the name of the table (used for error logging).
     * @param fields                  the list of fields from the source schema.
     * @param columnNameToDataTypeMap a map of column names to their corresponding data types.
     * @param includeKafkaMetaData    flag indicating whether Kafka metadata columns should be included.
     * @param includeRawData          flag indicating whether raw data should be included.
     * @param rawDataColumn           the name of the raw data column.
     * @param dbName                  the name of the database (used for error logging).
     * @return a ColumnData object containing the column index map and delimited strings, or null if fields is null.
     */
    private ColumnData createColumns(String tableName, List<Field> fields, Map<String, String> columnNameToDataTypeMap,
                                     boolean includeKafkaMetaData, boolean includeRawData, String rawDataColumn, String dbName) {

        if (fields == null) {
            log.error("getInsertQueryUsingInputFunction, fields empty");
            return null;
        }

        Map<String, Integer> colNameToIndexMap = new HashMap<>();
        int index = 1;

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        // Loop over each column to generate the insert query and map data types
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String sourceColumnName = entry.getKey();
            String sourceColumnNameWithBackTicks = "`" + entry.getKey() + "`";
            String dataType = ClickHouseUtils.escape(entry.getValue(), '\'');

            // Override data type if necessary
            if (ColumnOverrides.getColumnOverride(dataType) != null) {
                dataType = ColumnOverrides.getColumnOverride(dataType);
            }

            if (dataType != null) {
                // Check if the column is Kafka metadata
                if (isKafkaMetaDataColumn(sourceColumnName)) {
                    if (includeKafkaMetaData) {
                        colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                        colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                        colNameToIndexMap.put(sourceColumnName, index++);
                    }
                } else if (sourceColumnName.equalsIgnoreCase(rawDataColumn)) {
                    if (includeRawData) {
                        colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                        colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                        colNameToIndexMap.put(sourceColumnName, index++);
                    }
                } else {
                    colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                    colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                    colNameToIndexMap.put(sourceColumnName, index++);
                }
            } else {
                log.error(String.format("Table Name: %s, Database: %s,  Column(%s) ignored",
                        tableName, dbName, sourceColumnNameWithBackTicks));
            }
        }

        // Remove the terminating commas
        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesToDataTypes);

        return new ColumnData(colNameToIndexMap, colNamesDelimited, colNamesToDataTypes);
    }

    /**
     * Removes the trailing comma from the StringBuilder if present.
     *
     * @param stringBuilder the StringBuilder to process.
     */
    private void removeTrailingComma(StringBuilder stringBuilder) {
        int lastIndex = stringBuilder.lastIndexOf(",");
        if (lastIndex != -1) {
            stringBuilder.deleteCharAt(lastIndex);
        }
    }

    /**
     * Generates an INSERT SQL query using input functions for the specified table and columns.
     * <p>
     * This method constructs an INSERT query based on the provided column names and data types.
     * </p>
     *
     * @param tableName               the name of the ClickHouse table.
     * @param columnNameToDataTypeMap a map of column names to their corresponding data types.
     * @return the generated INSERT SQL query.
     */
    public String getInsertQueryUsingInputFunction(String tableName, Map<String, String> columnNameToDataTypeMap) {
        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        // Loop over each column to generate the insert query
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = "`" + entry.getKey() + "`";
            colNamesDelimited.append(columnName).append(",");
            // Escape single quotes in data types (e.g., DateTime64(3, 'UTC') -> DateTime64(3, ''UTC''))
            String escapedDataType = ClickHouseUtils.escape(entry.getValue(), '\'');
            colNamesToDataTypes.append(columnName).append(" ").append(escapedDataType).append(",");
        }

        // Remove the terminating commas
        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesToDataTypes);

        // Construct the full insert query
        String tableWithBackTicks = "`" + tableName + "`";
        return String.format("insert into %s select %s from input('%s')", tableWithBackTicks, colNamesDelimited, colNamesToDataTypes);
    }

    /**
     * Builds a 2-SELECT UNION ALL query for replication history DELETE (SCD2 delete pattern).
     * 1. First SELECT: Close the current active row (_valid_to = delete_timestamp, is_deleted = 0).
     * 2. Second SELECT: Insert delete marker row (_valid_from = delete_timestamp, _valid_to = open_end,
     *    is_deleted = 1, _operation = 'D').
     * Both SELECTs read from the table; no parameter binding is needed.
     *
     * @return query and empty column index map (no parameter binding)
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryForDelete(String tableName,
                                          Map<String, String> columnNameToDataTypeMap,
                                          String primaryKeyColumnName,
                                          Object primaryKeyValue,
                                          String validToMax,
                                          String binlogRecordTimestamp,
                                          long version,
                                          String serverTimeZone) {
        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesDelimitedForFirstSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForSecondSelect = new StringBuilder();
        Map<String, Integer> colNameToIndexMap = new HashMap<>();

        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = "`" + entry.getKey() + "`";
            colNamesDelimited.append(columnName).append(",");
        }

        // First SELECT: Close the current active row (no AS aliases; column names from INSERT list)
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();
            String selectExpr;
            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                selectExpr = String.format("toDateTime('%s', '%s')", binlogRecordTimestamp, serverTimeZone);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                selectExpr = "0";
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                selectExpr = String.format("%d", version);
            } else {
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForFirstSelect.append(selectExpr).append(",");
        }

        // Second SELECT: Insert delete marker row (no AS aliases; column names from INSERT list)
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();
            String selectExpr;
            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)) {
                selectExpr = String.format("toDateTime('%s', '%s')", binlogRecordTimestamp, serverTimeZone);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                selectExpr = String.format("toDateTime('%s', '%s')", validToMax, serverTimeZone);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                selectExpr = "'D'";
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                selectExpr = "1";
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                selectExpr = String.format("%d", version + 1);
            } else {
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForSecondSelect.append(selectExpr).append(",");
        }

        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesDelimitedForFirstSelect);
        removeTrailingComma(colNamesDelimitedForSecondSelect);

        String primaryKeyDataType = columnNameToDataTypeMap.get(primaryKeyColumnName);
        String formattedPrimaryKeyValue = formatValueForSql(primaryKeyValue, primaryKeyDataType);
        String tableWithBackTicks = "`" + tableName + "`";
        String isDeletedCondition = columnNameToDataTypeMap.containsKey(
                ClickHouseDbConstants.IS_DELETED_COLUMN) ? " AND `is_deleted` = 0" : "";

        String whereClause = String.format("WHERE `%s`=%s AND `_valid_to` = toDateTime('%s', '%s')%s",
                primaryKeyColumnName, formattedPrimaryKeyValue, validToMax, serverTimeZone, isDeletedCondition);

        String query = String.format(
            "INSERT INTO %s(%s) SELECT %s FROM %s FINAL %s UNION ALL SELECT %s FROM %s FINAL %s",
            tableWithBackTicks,
            colNamesDelimited,
            colNamesDelimitedForFirstSelect,
            tableWithBackTicks,
            whereClause,
            colNamesDelimitedForSecondSelect,
            tableWithBackTicks,
            whereClause
        );

        MutablePair<String, Map<String, Integer>> response = new MutablePair<>();
        response.left = query;
        response.right = colNameToIndexMap;
        return response;
    }

    public MutablePair<String, Map<String, Integer>> getInsertQueryForUpdate(String tableName,
                                                                             Map<String, String> columnNameToDataTypeMap,
                                          String primaryKeyColumnName,
                                          Object primaryKeyValue,
                                          String validToMax,
                                          String binlogRecordTimestamp,
                                          long version,
                                          ClickHouseConverter.CDC_OPERATION cdcOperation,
                                          String serverTimeZone) {

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesDelimitedForFirstSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForSecondSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForThirdSelect = new StringBuilder();
        
        // Map to track which columns need parameter binding
        Map<String, Integer> colNameToIndexMap = new HashMap<>();
        int parameterIndex = 1;

        // Loop over each column to build column list
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = "`" + entry.getKey() + "`";
            colNamesDelimited.append(columnName).append(",");
        }

        // First SELECT: Close the existing record (set _valid_to to binlog timestamp)
        // Keep original values from table, only modify _valid_to and _version
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();
            String selectExpr;
            
            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                // CLOSE the record by setting _valid_to to binlog timestamp (not now())
                // Using binlog timestamp ensures _valid_to matches the next record's _valid_from
                selectExpr = String.format("toDateTime('%s', '%s')", binlogRecordTimestamp, serverTimeZone);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                // Keep is_deleted = 0 (this is historical, not deleted)
                selectExpr = String.format("0 as `%s`", columnName);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                // Update version for ReplacingMergeTree to recognize this as newer
                selectExpr = String.format("%d as `%s`", version, columnName);
            } else {
                // Keep original value from existing row (including _operation, _valid_from, data columns)
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForFirstSelect.append(selectExpr).append(",");
        }

        // Second SELECT: Insert new "after" image (NO FROM clause - uses parameter binding for data columns)
        // Metadata columns (_version, is_deleted, _operation) are HARDCODED to ensure correct values
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();
            String dataType = entry.getValue();
            String selectExpr;

            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)) {
                // New record starts at binlog timestamp
                selectExpr = "toDateTime(?, '" + serverTimeZone + "') as `" + columnName + "`";
                colNameToIndexMap.put(columnName, parameterIndex++);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                // New record is open-ended (valid until max time)
                selectExpr = "toDateTime(?, '" + serverTimeZone + "') as `" + columnName + "`";
                colNameToIndexMap.put(columnName, parameterIndex++);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                // HARDCODE version+1 to ensure correct version regardless of fieldMapper
                selectExpr = String.format("%d as `%s`", version + 1, columnName);
                // NO parameter binding - hardcoded in SQL
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                // HARDCODE is_deleted = 0 for new active record
                selectExpr = String.format("0 as `%s`", columnName);
                // NO parameter binding - hardcoded in SQL
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                // HARDCODE operation type
                selectExpr = String.format("'%s' as `%s`", cdcOperation.getOperation(), columnName);
                // NO parameter binding - hardcoded in SQL
            } else {
                // Data columns use parameter binding (with CAST for DECIMAL types)
                selectExpr = formatParameterPlaceholder(dataType) + " as `" + columnName + "`";
                colNameToIndexMap.put(columnName, parameterIndex++);
            }
            colNamesDelimitedForSecondSelect.append(selectExpr).append(",");
        }

        // Third SELECT: Read before-image FROM TABLE with modified temporal columns
        // This preserves the ORIGINAL _valid_from from the table and sets _valid_to to now()
        // NO parameter binding - all values come from the table or are hardcoded
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();
            String selectExpr;

            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)) {
                // Keep the ORIGINAL _valid_from from the table
                selectExpr = "`" + columnName + "`";
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                // Set _valid_to to binlog timestamp - when this record was superseded
                selectExpr = String.format("toDateTime('%s', '%s') as `%s`", binlogRecordTimestamp, serverTimeZone, columnName);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                // Mark as deleted (is_deleted = 1)
                selectExpr = String.format("1 as `%s`", columnName);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                // Set operation type
                selectExpr = String.format("'%s' as `%s`", cdcOperation.getOperation(), columnName);
            } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                // Use same version as first SELECT
                selectExpr = String.format("%d as `%s`", version, columnName);
            } else {
                // Keep original value from table
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForThirdSelect.append(selectExpr).append(",");
        }
        // NO colNameToIndexMap entries for third SELECT - no parameter binding needed

        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesDelimitedForFirstSelect);
        removeTrailingComma(colNamesDelimitedForSecondSelect);
        removeTrailingComma(colNamesDelimitedForThirdSelect);

        // Get the primary key data type and format the value appropriately
        String primaryKeyDataType = columnNameToDataTypeMap.get(primaryKeyColumnName);
        String formattedPrimaryKeyValue = formatValueForSql(primaryKeyValue, primaryKeyDataType);

        String tableWithBackTicks = "`" + tableName + "`";
        
        // Build is_deleted condition only if the column exists in the table
        String isDeletedCondition = columnNameToDataTypeMap.containsKey(
                ClickHouseDbConstants.IS_DELETED_COLUMN) ? " AND `is_deleted` = 0" : "";

        // Build the query with three SELECTs:
        // 1. Close existing record (from table with WHERE) - uses FINAL to get merged view
        // 2. Insert new "after" values (NO FROM clause - uses parameter binding)
        // 3. Insert "before" image (FROM TABLE - preserves original _valid_from)
        String query = String.format(
            "INSERT INTO %s(%s) " +
            "SELECT %s FROM %s FINAL WHERE `%s`=%s AND `_valid_to` = toDateTime('%s', '%s')%s " +
            "UNION ALL " +
            "SELECT %s " +  // NO FROM clause for second SELECT - uses parameters
            "UNION ALL " +
            "SELECT %s FROM %s FINAL WHERE `%s`=%s AND `_valid_to` = toDateTime('%s', '%s')%s",
            tableWithBackTicks, 
            colNamesDelimited, 
            colNamesDelimitedForFirstSelect, 
            tableWithBackTicks, 
            primaryKeyColumnName, 
            formattedPrimaryKeyValue, 
            validToMax,
            serverTimeZone,
            isDeletedCondition,
            colNamesDelimitedForSecondSelect,
            colNamesDelimitedForThirdSelect,
            tableWithBackTicks,
            primaryKeyColumnName,
            formattedPrimaryKeyValue,
            validToMax,
            serverTimeZone,
            isDeletedCondition
        );

        MutablePair<String, Map<String, Integer>> response = new MutablePair<>();
        response.left = query;
        response.right = colNameToIndexMap;
        return response;
    }
}