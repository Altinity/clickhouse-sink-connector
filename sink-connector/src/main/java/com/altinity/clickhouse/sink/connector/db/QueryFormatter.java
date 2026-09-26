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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypeMap,
                includeKafkaMetaData, includeRawData, rawDataColumn, dbName, null);
    }

    /**
     * Overload accepting the configured ReplacingMergeTree delete column name.
     *
     * <p>The delete column is configurable
     * ({@code replacingmergetree.delete.column}), so it cannot be recognised
     * from a constant. It is never present in the source record but is
     * populated by the connector, so it must not be filtered out of the
     * INSERT column list.</p>
     *
     * @param tableName               the name of the ClickHouse table.
     * @param fields                  the list of fields from the source schema.
     * @param columnNameToDataTypeMap a map of column names to their corresponding data types.
     * @param includeKafkaMetaData    flag indicating whether Kafka metadata columns should be included.
     * @param includeRawData          flag indicating whether raw data should be included in the query.
     * @param rawDataColumn           the name of the raw data column.
     * @param dbName                  the name of the database.
     * @param deleteColumn            the configured ReplacingMergeTree delete column, may be null.
     * @return a MutablePair containing the generated INSERT query and a map of column names to their indices.
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryUsingInputFunction(
            String tableName, List<Field> fields,
            Map<String, String> columnNameToDataTypeMap,
            boolean includeKafkaMetaData,
            boolean includeRawData,
            String rawDataColumn, String dbName, String deleteColumn) {
        return getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypeMap,
                includeKafkaMetaData, includeRawData, rawDataColumn, dbName, deleteColumn, null);
    }

    /**
     * Overload taking the record's FULL schema fields separately from the
     * value-filtered {@code fields} list.
     *
     * <p>{@code fields} is the caller's "modified fields" list, which
     * {@code ClickHouseStruct#setAfterStruct} builds by keeping only the fields
     * whose value is {@code != null}. A column that is genuinely NULL in the
     * source row is therefore MISSING from that list even though the record
     * carries the column. Deciding INSERT membership from it drops NULL-valued
     * columns out of the statement entirely, so ClickHouse applies the column
     * DEFAULT (0 / '' / 1970-01-01) instead of NULL. MySQL NULL and ClickHouse 0
     * then differ, silently, with row counts still matching -- which is exactly
     * what a value-level checksum job reports and a count-based one misses.
     *
     * <p>{@code schemaFields} is the record's unfiltered schema
     * ({@code struct.schema().fields()}), which carries the column regardless of
     * its value. That is the correct authority for membership: a column absent
     * from the SCHEMA is genuinely not in this record -- the pre-ALTER case from
     * #1389, where omission is required so the ALTER's DEFAULT applies -- while a
     * column present in the schema must always be bound, NULL or not.</p>
     *
     * <p>When {@code schemaFields} is null the method falls back to
     * {@code fields}, preserving the previous behaviour for callers that have no
     * schema to offer.</p>
     *
     * @param tableName               the name of the ClickHouse table.
     * @param fields                  the value-filtered modified-fields list.
     * @param columnNameToDataTypeMap a map of column names to their corresponding data types.
     * @param includeKafkaMetaData    flag indicating whether Kafka metadata columns should be included.
     * @param includeRawData          flag indicating whether raw data should be included in the query.
     * @param rawDataColumn           the name of the raw data column.
     * @param dbName                  the name of the database.
     * @param deleteColumn            the configured ReplacingMergeTree delete column, may be null.
     * @param schemaFields            the record's full schema fields, may be null.
     * @return a MutablePair containing the generated INSERT query and a map of column names to their indices.
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryUsingInputFunction(
            String tableName, List<Field> fields,
            Map<String, String> columnNameToDataTypeMap,
            boolean includeKafkaMetaData,
            boolean includeRawData,
            String rawDataColumn, String dbName, String deleteColumn,
            List<Field> schemaFields) {
        return getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypeMap,
                includeKafkaMetaData, includeRawData, rawDataColumn, dbName, deleteColumn,
                schemaFields, null, null);
    }

    /**
     * Overload taking the target table's RESOLVED engine columns.
     *
     * <p>The version column of a ReplacingMergeTree and the sign column of a
     * CollapsingMergeTree are read from the table's engine clause
     * ({@code ReplacingMergeTree(ver)}, {@code CollapsingMergeTree(sgn)}), so
     * they can carry any name. Recognising them only by the connector's
     * default constants ({@code _version}, {@code _sign}) left a
     * differently named column out of the INSERT: it is never in the source
     * record, so it was "omitted as a pre-ALTER column" and ClickHouse stored
     * the type default -- {@code ver = 0} for every row (a redelivered older
     * row then wins every merge) and {@code sgn = 0} (no row ever collapses).
     * The resolved names are treated exactly like the constants: always
     * retained, always bind parameters (Spec 04.02 §3.1).</p>
     *
     * @param versionColumn the resolved ReplacingMergeTree version column, may be null.
     * @param signColumn    the resolved CollapsingMergeTree sign column, may be null.
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryUsingInputFunction(
            String tableName, List<Field> fields,
            Map<String, String> columnNameToDataTypeMap,
            boolean includeKafkaMetaData,
            boolean includeRawData,
            String rawDataColumn, String dbName, String deleteColumn,
            List<Field> schemaFields, String versionColumn, String signColumn) {

        // Membership is decided by the record's SCHEMA, never by the
        // value-filtered modified-fields list -- see the javadoc above.
        List<Field> membershipFields = (schemaFields != null) ? schemaFields : fields;

        // Create column data structures
        ColumnData columnData = createColumns(tableName, membershipFields, columnNameToDataTypeMap,
                includeKafkaMetaData, includeRawData, rawDataColumn, dbName, deleteColumn,
                versionColumn, signColumn);

        if (columnData == null) {
            return null;
        }

        // Construct the full insert query
        String tableWithBackTicks = "`" + tableName + "`";
        String placeholders = generatePlaceholders(columnData.colNameToIndexMap.size());
        String insertQuery = String.format("INSERT INTO %s(%s) VALUES (%s)",
                tableWithBackTicks, columnData.colNamesDelimited, placeholders);

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
     * Handles formats like "DateTime64(3)", "DateTime64(3, 'UTC')" or "Nullable(DateTime64(3))".
     *
     * @param dataType the DateTime64 data type string
     * @return the precision value as a string, defaults to "3" if not found
     */
    private String extractDateTime64Precision(String dataType) {
        // Find the opening parenthesis
        int start = dataType.lastIndexOf('(');
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
        return createColumns(tableName, fields, columnNameToDataTypeMap, includeKafkaMetaData,
                includeRawData, rawDataColumn, dbName, null, null, null);
    }

    /**
     * Returns true for columns the connector populates itself rather than
     * copying from the source record: {@code _version}, {@code is_deleted},
     * {@code _sign}, the replication-history validity columns, the
     * configured ReplacingMergeTree delete column, and the table's RESOLVED
     * version and sign columns (read from its engine clause, so they may
     * carry any name). These are never present in the incoming record's
     * schema and must always remain in the INSERT column list.
     *
     * @param colName       the ClickHouse column name to test.
     * @param deleteColumn  the configured delete column name, may be null.
     * @param versionColumn the resolved version column name, may be null.
     * @param signColumn    the resolved sign column name, may be null.
     * @return true if the connector populates this column itself.
     */
    private boolean isConnectorManagedColumn(String colName, String deleteColumn,
                                             String versionColumn, String signColumn) {
        return colName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)
                || colName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)
                || colName.equalsIgnoreCase(ClickHouseDbConstants.SIGN_COLUMN)
                || colName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)
                || colName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)
                // _operation is populated by the connector, never by the source
                // record, exactly like _version and is_deleted. Omitting it made
                // createColumns drop it from every replication-history INSERT, so
                // the SCD Type 2 rows carried an empty _operation and a DELETE
                // could not be distinguished from an insert -- deleted rows stayed
                // visible in ClickHouse forever while row counts looked plausible.
                || colName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)
                || matchesResolvedColumn(colName, deleteColumn)
                // The engine clause decides the real names. A version column
                // called `ver` or a sign column called `sgn` that is recognised
                // only by the constants above was dropped from the INSERT and
                // stored as the type default for every row (Spec 04.02 §3.1).
                || matchesResolvedColumn(colName, versionColumn)
                || matchesResolvedColumn(colName, signColumn);
    }

    private static boolean matchesResolvedColumn(String colName, String resolved) {
        return resolved != null && !resolved.isEmpty() && colName.equalsIgnoreCase(resolved);
    }

    private ColumnData createColumns(String tableName, List<Field> fields, Map<String, String> columnNameToDataTypeMap,
                                     boolean includeKafkaMetaData, boolean includeRawData, String rawDataColumn,
                                     String dbName, String deleteColumn, String versionColumn, String signColumn) {

        if (fields == null) {
            log.error("getInsertQueryUsingInputFunction, fields empty");
            return null;
        }

        Map<String, Integer> colNameToIndexMap = new HashMap<>();
        int index = 1;

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        // Names the incoming record actually carries. A record buffered before an
        // ALTER TABLE ... ADD/CHANGE COLUMN does not carry the columns that the
        // ALTER introduced, yet columnNameToDataTypeMap reflects the ClickHouse
        // table AFTER the ALTER. Including such a column in the INSERT column list
        // binds it to NULL and silently overwrites the real source value (row
        // counts still match, so count-based checksums report the table clean).
        // Omitting it from the column list instead lets ClickHouse apply the
        // column's DEFAULT, which is what the ALTER established.
        Set<String> recordFieldNames = new HashSet<>();
        for (Field field : fields) {
            if (field != null && field.name() != null) {
                recordFieldNames.add(field.name().toLowerCase());
            }
        }

        // Loop over each column to generate the insert query and map data types
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String sourceColumnName = entry.getKey();
            String sourceColumnNameWithBackTicks = "`" + entry.getKey() + "`";
            String dataType = ClickHouseUtils.escape(entry.getValue(), '\'');

            // Skip ClickHouse columns absent from this record's schema. Columns the
            // connector itself populates (Kafka metadata, raw data, _version,
            // is_deleted/_sign, replication-history) are never present in the source
            // record and must stay in the column list.
            if (!recordFieldNames.contains(sourceColumnName.toLowerCase())
                    && !isKafkaMetaDataColumn(sourceColumnName)
                    && !sourceColumnName.equalsIgnoreCase(rawDataColumn)
                    && !isConnectorManagedColumn(sourceColumnName, deleteColumn, versionColumn, signColumn)) {
                log.debug(String.format(
                        "Table Name: %s, Database: %s, Column(%s) omitted from INSERT: "
                                + "not present in this record's schema (pre-ALTER record); "
                                + "the ClickHouse DEFAULT applies instead of NULL",
                        tableName, dbName, sourceColumnNameWithBackTicks));
                continue;
            }

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
        String placeholders = generatePlaceholders(columnNameToDataTypeMap.size());
        return String.format("INSERT INTO %s(%s) VALUES (%s)",
                tableWithBackTicks, colNamesDelimited, placeholders);
    }

    /**
     * Generates a comma-separated list of JDBC parameter placeholders.
     *
     * @param count number of placeholders to generate
     * @return placeholder string (e.g. "?,?,?")
     */
    private static String generatePlaceholders(int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /** A one-column primary key in the map form the composite predicate takes. */
    private static Map<String, Object> singlePrimaryKey(String columnName, Object value) {
        Map<String, Object> primaryKey = new java.util.LinkedHashMap<>();
        primaryKey.put(columnName, value);
        return primaryKey;
    }

    /**
     * The predicate that selects exactly one history row by its whole primary key:
     * {@code `c1`=v1 AND `c2`=v2 ...}, each value formatted for its column type
     * (spec 02.01 section 3.5 a). A record without a primary key cannot be closed
     * and is refused loudly rather than closing every row of the table.
     *
     * @throws IllegalStateException if {@code primaryKey} is null or empty
     */
    String formatPrimaryKeyPredicate(String tableName, Map<String, String> columnNameToDataTypeMap,
                                     Map<String, Object> primaryKey) {
        if (primaryKey == null || primaryKey.isEmpty()) {
            throw new IllegalStateException("History mode cannot close the previous row of " + tableName
                    + ": the record carries no primary key column (spec 02.01 section 3.5 a)");
        }
        StringBuilder predicate = new StringBuilder();
        for (Map.Entry<String, Object> column : primaryKey.entrySet()) {
            if (predicate.length() > 0) {
                predicate.append(" AND ");
            }
            predicate.append("`").append(column.getKey()).append("`=")
                    .append(formatValueForSql(column.getValue(), columnNameToDataTypeMap.get(column.getKey())));
        }
        return predicate.toString();
    }

    /**
     * Builds a 2-SELECT UNION ALL query for replication history DELETE (SCD2 delete pattern,
     * Spec 12.03 section 3.3).
     * 1. First SELECT: Close the current active row (_valid_to = delete_timestamp, is_deleted = 0).
     * 2. Second SELECT: Insert delete marker row (_valid_from = delete_timestamp, _valid_to = open_end,
     *    is_deleted = 1, _operation = 'D').
     * Both SELECTs read from the table; no parameter binding is needed.
     *
     * <p>Both rows carry the ONE version {@code version} of the event (Spec 12.03
     * section 3.5, Gap G-12.03-4): the close row lands at sorting key
     * {@code (pk, ts)} and the marker at {@code (pk, sentinel)}, so they never
     * compete with each other; the marker beats the open row it hides because that
     * row came from an EARLIER event and versions are monotonic (Invariant I2).
     * {@code version + 1} -- the previous marker version -- collided with the NEXT
     * event's version on the lightweight sequence path.</p>
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
        return getInsertQueryForDelete(tableName, columnNameToDataTypeMap,
                singlePrimaryKey(primaryKeyColumnName, primaryKeyValue), validToMax, binlogRecordTimestamp,
                version, serverTimeZone);
    }

    /**
     * Composite-key form of {@link #getInsertQueryForDelete(String, Map, String, Object, String, String, long, String)}:
     * the previous history row is selected by EVERY primary-key column
     * ({@code primaryKey}: column name to value, in key order), so a table whose
     * primary key has several columns closes exactly the row of that composite key
     * (spec 02.01 section 3.5 a).
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryForDelete(String tableName,
                                          Map<String, String> columnNameToDataTypeMap,
                                          Map<String, Object> primaryKey,
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
                // The event's own version, not version + 1 (see the javadoc above).
                selectExpr = String.format("%d", version);
            } else {
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForSecondSelect.append(selectExpr).append(",");
        }

        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesDelimitedForFirstSelect);
        removeTrailingComma(colNamesDelimitedForSecondSelect);

        String primaryKeyPredicate = formatPrimaryKeyPredicate(tableName, columnNameToDataTypeMap, primaryKey);
        String tableWithBackTicks = "`" + tableName + "`";
        String isDeletedCondition = columnNameToDataTypeMap.containsKey(
                ClickHouseDbConstants.IS_DELETED_COLUMN) ? " AND `is_deleted` = 0" : "";

        String whereClause = String.format("WHERE %s AND `_valid_to` = toDateTime('%s', '%s')%s",
                primaryKeyPredicate, validToMax, serverTimeZone, isDeletedCondition);

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

    /**
     * Single-column-key form of
     * {@link #getInsertQueryForUpdate(String, Map, Map, String, String, long, ClickHouseConverter.CDC_OPERATION, String, boolean)}
     * for a same-key UPDATE ({@code keyChanged = false}).
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryForUpdate(String tableName,
                                                                             Map<String, String> columnNameToDataTypeMap,
                                          String primaryKeyColumnName,
                                          Object primaryKeyValue,
                                          String validToMax,
                                          String binlogRecordTimestamp,
                                          long version,
                                          ClickHouseConverter.CDC_OPERATION cdcOperation,
                                          String serverTimeZone) {
        return getInsertQueryForUpdate(tableName, columnNameToDataTypeMap,
                singlePrimaryKey(primaryKeyColumnName, primaryKeyValue), validToMax, binlogRecordTimestamp,
                version, cdcOperation, serverTimeZone, false);
    }

    /**
     * Builds the replication history UPDATE statement (SCD2, Spec 12.03 section 3.2):
     * one {@code INSERT ... SELECT ... UNION ALL ...} that
     * <ol>
     *   <li>CLOSES the visible open row at the BEFORE-image key ({@code primaryKey}):
     *       a copy of that row with {@code _valid_to = binlogRecordTimestamp},
     *       {@code is_deleted = 0}, {@code _version = version}, read
     *       {@code FROM t FINAL WHERE <key> AND _valid_to = sentinel [AND is_deleted = 0]};</li>
     *   <li>inserts the AFTER image at {@code (afterKey, sentinel)} from bound
     *       parameters ({@code _valid_from = ?}, {@code _valid_to = ?}, data columns),
     *       with {@code is_deleted = 0}, {@code _operation = '<letter>'} and
     *       {@code _version = version} as SQL literals;</li>
     *   <li>ONLY when {@code keyChanged}: a DELETE MARKER at the OLD key's open
     *       sorting key {@code (primaryKey, sentinel)} -- a copy of the visible open
     *       row at the before key with {@code _valid_from = binlogRecordTimestamp},
     *       {@code _valid_to = sentinel}, {@code is_deleted = 1},
     *       {@code _operation = 'U'}, {@code _version = version}, same WHERE as (1) --
     *       so an UPDATE that changes a primary-key column retires the old key
     *       instead of leaving two current rows for one source row (Gap G-12.03-3).</li>
     * </ol>
     *
     * <p>The former third SELECT (a deleted copy of the before image at
     * {@code (pk, ts)} carrying the close row's version) is REMOVED: it tied with
     * the close row on {@code (pk, ts, _version)} and, depending on physical
     * insertion order inside the one INSERT, could hide the closed history version
     * under {@code FINAL} (Gap G-12.03-2).</p>
     *
     * <p>VERSIONS (Spec 12.03 section 3.5, Gap G-12.03-4): every row of one event
     * carries the ONE version {@code version} -- the record's standard version.
     * The close row {@code (pk, ts)} and the open-key rows {@code (pk, sentinel)}
     * are different sorting keys, so they never compete with each other; the after
     * row beats the previous open row because that row came from an EARLIER event
     * and versions are monotonic (Invariant I2). {@code version + 1} -- the previous
     * after-row version -- collided with the next event's version on the
     * lightweight sequence path.</p>
     *
     * <p>Every table-reading SELECT selects the previous history row by EVERY
     * primary-key column ({@code primaryKey}: column name to value, in key order);
     * closing on the first column alone closed every row that shared it
     * (spec 02.01 section 3.5 a).</p>
     *
     * @param primaryKey the BEFORE-image primary key: the row being closed
     * @param keyChanged whether the after image carries a different primary key
     * @return query and the column-to-parameter-index map of SELECT (2); the
     *         table-reading SELECTs bind nothing
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryForUpdate(String tableName,
                                          Map<String, String> columnNameToDataTypeMap,
                                          Map<String, Object> primaryKey,
                                          String validToMax,
                                          String binlogRecordTimestamp,
                                          long version,
                                          ClickHouseConverter.CDC_OPERATION cdcOperation,
                                          String serverTimeZone,
                                          boolean keyChanged) {

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesDelimitedForCloseSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForAfterSelect = new StringBuilder();
        StringBuilder colNamesDelimitedForOldKeyMarkerSelect = new StringBuilder();

        // Map to track which columns need parameter binding
        Map<String, Integer> colNameToIndexMap = new HashMap<>();
        int parameterIndex = 1;

        // Loop over each column to build column list
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = "`" + entry.getKey() + "`";
            colNamesDelimited.append(columnName).append(",");
        }

        // SELECT (1): Close the existing record (set _valid_to to binlog timestamp)
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
                // The event's version: (pk, ts) is a sorting key of its own, so
                // nothing else of this event competes with the close row.
                selectExpr = String.format("%d as `%s`", version, columnName);
            } else {
                // Keep original value from existing row (including _operation, _valid_from, data columns)
                selectExpr = "`" + columnName + "`";
            }
            colNamesDelimitedForCloseSelect.append(selectExpr).append(",");
        }

        // SELECT (2): Insert new "after" image (NO FROM clause - uses parameter binding for data columns)
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
                // HARDCODE the event's version (NOT version + 1, see the javadoc):
                // it exceeds the previous open row's version because that row is
                // from an earlier event (Invariant I2).
                selectExpr = String.format("%d as `%s`", version, columnName);
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
            colNamesDelimitedForAfterSelect.append(selectExpr).append(",");
        }

        // SELECT (3), key change only: a DELETE MARKER at the OLD key's open sorting
        // key (before key, sentinel). Copies the visible open row at the before key
        // and marks it deleted, so the old key disappears from the current-state
        // view under FINAL (Gap G-12.03-3). NO parameter binding.
        if (keyChanged) {
            for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
                String columnName = entry.getKey();
                String selectExpr;

                if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN)) {
                    // The marker starts where the closed version ends
                    selectExpr = String.format("toDateTime('%s', '%s') as `%s`", binlogRecordTimestamp, serverTimeZone, columnName);
                } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.DELETED_TIME_COLUMN)) {
                    // Open sorting key of the old primary key
                    selectExpr = String.format("toDateTime('%s', '%s') as `%s`", validToMax, serverTimeZone, columnName);
                } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.IS_DELETED_COLUMN)) {
                    // Mark as deleted (is_deleted = 1)
                    selectExpr = String.format("1 as `%s`", columnName);
                } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.OPERATION_COLUMN)) {
                    // The UPDATE retired the old key
                    selectExpr = String.format("'%s' as `%s`", cdcOperation.getOperation(), columnName);
                } else if (columnName.equalsIgnoreCase(ClickHouseDbConstants.VERSION_COLUMN)) {
                    // The event's version: beats the previous open row at the old key (I2)
                    selectExpr = String.format("%d as `%s`", version, columnName);
                } else {
                    // Keep original value from table (the primary-key columns keep the OLD key)
                    selectExpr = "`" + columnName + "`";
                }
                colNamesDelimitedForOldKeyMarkerSelect.append(selectExpr).append(",");
            }
        }

        removeTrailingComma(colNamesDelimited);
        removeTrailingComma(colNamesDelimitedForCloseSelect);
        removeTrailingComma(colNamesDelimitedForAfterSelect);
        removeTrailingComma(colNamesDelimitedForOldKeyMarkerSelect);

        // The predicate over the WHOLE (before-image) primary key, each value formatted for its type.
        String primaryKeyPredicate = formatPrimaryKeyPredicate(tableName, columnNameToDataTypeMap, primaryKey);

        String tableWithBackTicks = "`" + tableName + "`";

        // Build is_deleted condition only if the column exists in the table
        String isDeletedCondition = columnNameToDataTypeMap.containsKey(
                ClickHouseDbConstants.IS_DELETED_COLUMN) ? " AND `is_deleted` = 0" : "";

        // The open-row predicate at the before key, shared by every table-reading SELECT
        String openRowClause = String.format("FROM %s FINAL WHERE %s AND `_valid_to` = toDateTime('%s', '%s')%s",
                tableWithBackTicks, primaryKeyPredicate, validToMax, serverTimeZone, isDeletedCondition);

        // Build the query:
        // 1. Close existing record (from table with WHERE) - uses FINAL to get merged view
        // 2. Insert new "after" values (NO FROM clause - uses parameter binding)
        // 3. Key change only: delete marker at the old key (FROM table, same WHERE)
        StringBuilder query = new StringBuilder();
        query.append(String.format("INSERT INTO %s(%s) ", tableWithBackTicks, colNamesDelimited));
        query.append(String.format("SELECT %s %s ", colNamesDelimitedForCloseSelect, openRowClause));
        query.append("UNION ALL ");
        query.append(String.format("SELECT %s", colNamesDelimitedForAfterSelect));  // NO FROM clause - uses parameters
        if (keyChanged) {
            query.append(" UNION ALL ");
            query.append(String.format("SELECT %s %s", colNamesDelimitedForOldKeyMarkerSelect, openRowClause));
        }

        MutablePair<String, Map<String, Integer>> response = new MutablePair<>();
        response.left = query.toString();
        response.right = colNameToIndexMap;
        return response;
    }

    /**
     * Builds the replication history statement for a replicated truncation (Spec 12.03
     * section 3.4, Gap G-12.03-6). Instead of erasing the SCD2 table -- closed
     * versions included -- ONE {@code INSERT ... SELECT} closes EVERY visible open
     * row at the event time and appends a delete marker at each row's open sorting
     * key, so the history window survives and the current-state view becomes empty,
     * exactly as the source table did:
     *
     * <pre>
     * INSERT INTO t
     * SELECT * REPLACE (toDateTime('ts', 'tz') AS `_valid_to`, V AS `_version`)
     *   FROM t FINAL WHERE `_valid_to` = toDateTime('S', 'tz') [AND `is_deleted` = 0]
     * UNION ALL
     * SELECT * REPLACE (toDateTime('ts', 'tz') AS `_valid_from`, [1 AS `is_deleted`,] 'T' AS `_operation`, V AS `_version`)
     *   FROM t FINAL WHERE &lt;same predicate&gt;
     * </pre>
     *
     * <p>Column-list agnostic on purpose: {@code SELECT * REPLACE} keeps the table's
     * column order, so the positional INSERT is safe and the caller needs no column
     * map. There is no primary-key predicate: every open row is closed. When the
     * table has no {@code is_deleted} column the {@code 1 AS is_deleted} replacement
     * and the predicate conjunct are omitted. The statement only INSERTS: nothing is
     * destroyed. Every row carries the ONE version {@code version} of the event
     * (Spec 12.03 section 3.5): close rows land at {@code (pk, ts)}, markers at
     * {@code (pk, sentinel)} where they beat the earlier-event open rows (I2).</p>
     *
     * @param qualifiedTable       {@code database.table}
     * @param hasIsDeletedColumn   whether the table carries {@code is_deleted}
     * @param validToMax           the open-row sentinel, rendered for the server timezone
     * @param binlogRecordTimestamp the event time, rendered for the server timezone
     * @param version              the event's version, shared by every emitted row
     * @param operationCode        the single-letter operation code stored in {@code _operation} ({@code 'T'})
     * @param serverTimeZone       the resolved server timezone id
     * @return the statement; it binds no parameters
     */
    public String getInsertQueryForBulkClose(String qualifiedTable, boolean hasIsDeletedColumn,
                                             String validToMax, String binlogRecordTimestamp,
                                             long version, String operationCode, String serverTimeZone) {
        String table = quoteQualifiedTable(qualifiedTable);
        String eventTime = String.format("toDateTime('%s', '%s')", binlogRecordTimestamp, serverTimeZone);
        String openRowPredicate = String.format("WHERE `_valid_to` = toDateTime('%s', '%s')%s",
                validToMax, serverTimeZone, hasIsDeletedColumn ? " AND `is_deleted` = 0" : "");

        // Close row: the open version, ended at the event time
        String closeReplace = String.format("%s AS `%s`, %d AS `%s`",
                eventTime, ClickHouseDbConstants.DELETED_TIME_COLUMN, version, ClickHouseDbConstants.VERSION_COLUMN);
        // Delete marker: at the open sorting key, deleted, starting at the event time
        String markerReplace = String.format("%s AS `%s`, %s'%s' AS `%s`, %d AS `%s`",
                eventTime, ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN,
                hasIsDeletedColumn ? "1 AS `" + ClickHouseDbConstants.IS_DELETED_COLUMN + "`, " : "",
                operationCode, ClickHouseDbConstants.OPERATION_COLUMN,
                version, ClickHouseDbConstants.VERSION_COLUMN);

        return String.format(
                "INSERT INTO %s SELECT * REPLACE (%s) FROM %s FINAL %s UNION ALL SELECT * REPLACE (%s) FROM %s FINAL %s",
                table, closeReplace, table, openRowPredicate, markerReplace, table, openRowPredicate);
    }

    /** {@code database.table} as {@code `database`.`table`}; a bare name as {@code `table`}. */
    private static String quoteQualifiedTable(String qualifiedTable) {
        int dot = qualifiedTable.indexOf('.');
        if (dot < 0) {
            return "`" + qualifiedTable + "`";
        }
        return "`" + qualifiedTable.substring(0, dot) + "`.`" + qualifiedTable.substring(dot + 1) + "`";
    }
}
