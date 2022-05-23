package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Class with all functions related
 * to creating Raw queries for Clickhouse JDBC library
 */
public class QueryFormatter {

    private static final Logger log = LoggerFactory.getLogger(QueryFormatter.class);

    /**
     * Formatter for SQL 'Insert' query with placeholders for values
     * insert into <table name> values(?, ?, ?)
     *
     * @param tableName Table Name
     * @param numFields Number of fields with placeholders
     * @return
     */
    public String getInsertQuery(String tableName, int numFields) {
        StringBuffer insertQuery = new StringBuffer()
                .append("insert into ")
                .append(tableName)
                .append(" values(");
        for (int i = 0; i < numFields; i++) {
            insertQuery.append("?");
            if (i == numFields - 1) {
                insertQuery.append(")");
            } else {
                insertQuery.append(",");
            }
        }
        return insertQuery.toString();
    }

    /**
     * There could be a possibility that the column count will not match
     * between Source and Clickhouse.
     * - We will drop records if the columns are not present in clickhouse.
     * @param tableName
     * @param fields
     * @return
     */
    public String getInsertQueryUsingInputFunction(String tableName, List<Field> fields,
                                                   Map<String, String> columnNameToDataTypeMap,
                                                   boolean includeKafkaMetaData,
                                                   boolean includeRawData,
                                                   String rawDataColumn) {


        StringBuffer colNamesDelimited = new StringBuffer();
        StringBuffer colNamesToDataTypes = new StringBuffer();

        for(Field f: fields) {
            String sourceColumnName = f.name();
            // Get Field Name and lookup in the Clickhouse column to datatype map.
            String dataType = columnNameToDataTypeMap.get(sourceColumnName);

            if(dataType != null) {
                colNamesDelimited.append(sourceColumnName).append(",");
                colNamesToDataTypes.append(sourceColumnName).append(" ").append(dataType).append(",");
            } else {
                log.error(String.format("Table Name: %s, Column(%s) ignored", tableName, sourceColumnName));
            }
        }
        if(includeKafkaMetaData) {
            for(KafkaMetaData metaDataColumn: KafkaMetaData.values()) {
                String metaDataColName = metaDataColumn.getColumn();
                if(columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    String dataType = columnNameToDataTypeMap.get(metaDataColName);

                    colNamesDelimited.append(metaDataColName).append(",");
                    colNamesToDataTypes.append(metaDataColName).append(" ").append(dataType).append(",");
                } else {
                    log.error("RAW DATA enabled but column not added to clickhouse: "  + rawDataColumn );
                }
            }
        }
        if(includeRawData) {
            if(columnNameToDataTypeMap.containsKey(rawDataColumn)) {
                // Also check if the data type is String.
                String dataType = columnNameToDataTypeMap.get(rawDataColumn);
                if(dataType.equalsIgnoreCase("String")) {
                    colNamesDelimited.append(rawDataColumn).append(",");
                    ;
                    colNamesToDataTypes.append(rawDataColumn).append(" ").append("String").append(",");
                }
//                else {
//                    log.error("RAW DATA column is not of String datatype: "  + rawDataColumn );
//
//                }
            }
            else {
                log.error("RAW DATA enabled but column not added to clickhouse: "  + rawDataColumn );
            }
        }

        //Remove terminating comma
        colNamesDelimited.deleteCharAt(colNamesDelimited.lastIndexOf(","));
        colNamesToDataTypes.deleteCharAt(colNamesToDataTypes.lastIndexOf(","));

        return String.format("insert into %s(%s) select %s from input('%s')", tableName, colNamesDelimited, colNamesDelimited, colNamesToDataTypes);
    }
    /**
     * Function to construct an INSERT query using input functions.
     *
     * @param tableName Table Name
     * @return Insert query using Input function.
     */
    public String getInsertQueryUsingInputFunction(String tableName,  Map<String, String> columnNameToDataTypeMap) {
        // "insert into mytable select col1, col2 from input('col1 String, col2 DateTime64(3), col3 Int32')"))

        StringBuffer colNamesDelimited = new StringBuffer();
        StringBuffer colNamesToDataTypes = new StringBuffer();

        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            colNamesDelimited.append(entry.getKey()).append(",");
            colNamesToDataTypes.append(entry.getKey()).append(" ").append(entry.getValue()).append(",");
        }

        //Remove terminating comma
        colNamesDelimited.deleteCharAt(colNamesDelimited.lastIndexOf(","));
        colNamesToDataTypes.deleteCharAt(colNamesToDataTypes.lastIndexOf(","));

        return String.format("insert into %s select %s from input('%s')", tableName, colNamesDelimited, colNamesToDataTypes);
    }
}
