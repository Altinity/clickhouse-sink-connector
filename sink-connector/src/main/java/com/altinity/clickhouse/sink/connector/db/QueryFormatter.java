package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.batch.CdcOperation;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
            colNamesToDataTypes.append(columnName).append(" ").append(entry.getValue()).append(",");
        }

        // Remove the terminating commas
        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesToDataTypes);

        // Construct the full insert query
        String tableWithBackTicks = "`" + tableName + "`";
        return String.format("insert into %s select %s from input('%s')", tableWithBackTicks, colNamesDelimited, colNamesToDataTypes);
    }

    public String getInsertQueryForUpdate(String tableName, List<Field> fields,
                                          Map<String, String> columnNameToDataTypeMap,
                                          String primaryKeyColumnName,
                                          Object primaryKeyValue,
                                          String validToMax,
                                          String binlogRecordTimestamp,
                                          long version,
                                          ClickHouseConverter.CDC_OPERATION cdcOperation) {

        StringBuilder colNamesDelimitedForFirstSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForSecondSelect = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {

            String columnName = entry.getKey();
            // If column name is VALID_TO, replace it with toDateTime('2100-01-01 00:00:00')
            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                columnName = String.format("toDateTime('%s') as %s", validToMax, ClickHouseDbConstants.DELETED_TIME_COLUMN);
            }
            // If column name is IS_DELETED, replace it with 0
            if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                columnName = String.format("0 as %s", ClickHouseDbConstants.IS_DELETED_COLUMN);
            }
            // If column name is OPERATION, replace it with 'update'
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                columnName = String.format("'%s' as %s", cdcOperation.getOperation(), ClickHouseDbConstants.OPERATION_COLUMN);
            }
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                columnName = String.format("'%s' as %s", version, ClickHouseDbConstants.VERSION_COLUMN);
            }

            columnName = "`" + columnName + "`";
            colNamesDelimitedForFirstSelect.append(columnName).append(",");

            colNamesToDataTypes.append(columnName).append(" ").append(entry.getValue()).append(",");
        }
        for(Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = entry.getKey();

        
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)) {
            columnName = String.format("toDateTime('%s') as %s", binlogRecordTimestamp, ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN);
        
            }
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                columnName = String.format("toDateTime('%s') as %s", validToMax, ClickHouseDbConstants.DELETED_TIME_COLUMN);
            }
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                columnName = String.format("0 as %s", ClickHouseDbConstants.IS_DELETED_COLUMN);
            }
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                columnName = String.format("'%s' as %s", cdcOperation.getOperation(), ClickHouseDbConstants.OPERATION_COLUMN);
            }
            if(columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                columnName = String.format("'%s' as %s", version, ClickHouseDbConstants.VERSION_COLUMN);
            }
            columnName = "`" + columnName + "`";
            colNamesDelimitedForSecondSelect.append(columnName).append(",");

        }

        removeTrailingComma(colNamesDelimitedForFirstSelect);
        removeTrailingComma(colNamesDelimitedForSecondSelect);
        removeTrailingComma(colNamesToDataTypes);

        String tableWithBackTicks = "`" + tableName + "`";
        return String.format("INSERT INTO %s SELECT %s FROM %s WHERE %s=%s AND valid_to = %s AND is_deleted = %s UNION ALL SELECT %s",
                tableWithBackTicks, colNamesDelimitedForFirstSelect, tableWithBackTicks, primaryKeyColumnName, primaryKeyValue, validToMax, 0, colNamesDelimitedForSecondSelect);

    }
}