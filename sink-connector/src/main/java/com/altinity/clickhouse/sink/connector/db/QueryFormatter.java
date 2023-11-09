package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class with all functions related
 * to creating Raw queries for Clickhouse JDBC library
 */
public class QueryFormatter {

    private static final Logger log = LoggerFactory.getLogger(QueryFormatter.class);

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
     * There could be a possibility that the column count will not match
     * between Source and Clickhouse.
     * - We will drop records if the columns are not present in clickhouse.
     * @param tableName
     * @param fields
     * @return
     */
    public MutablePair<String, Map<String, Integer>> getInsertQueryUsingInputFunction(String tableName, List<Field> fields,
                                                                                      Map<String, String> columnNameToDataTypeMap,
                                                                                      boolean includeKafkaMetaData,
                                                                                      boolean includeRawData,
                                                                                      String rawDataColumn) {


        Map<String, Integer> colNameToIndexMap = new HashMap<String, Integer>();
        int index = 1;

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        if(fields == null) {
            log.error("getInsertQueryUsingInputFunction, fields empty");
            return null;
        }

        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {

            //for(Field f: fields) {
            String sourceColumnName = entry.getKey();
            String sourceColumnNameWithBackTicks = new StringBuffer().append("`").append(entry.getKey()).append("`").toString();
            //String sourceColumnName = f.name();
            // Get Field Name and lookup in the Clickhouse column to datatype map.
            String dataType = ClickHouseUtils.escape(columnNameToDataTypeMap.get(entry.getKey()), '\'');

            if(dataType != null) {
                // Is the column a kafka metadata column.
                if(isKafkaMetaDataColumn(sourceColumnName)) {
                    if(includeKafkaMetaData) {
                        //log.info("Kafka metadata enabled and column added to clickhouse: "  + sourceColumnName );
                        colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                        colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                        colNameToIndexMap.put(sourceColumnName, index++);
                    }
                } else if(sourceColumnName.equalsIgnoreCase(rawDataColumn)) {
                    if(includeRawData) {
                        //log.info("RAW DATA enabled and column added to clickhouse: "  + sourceColumnName );
                        colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                        colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                        colNameToIndexMap.put(sourceColumnName, index++);
                    }

                }else {
                    colNamesDelimited.append(sourceColumnNameWithBackTicks).append(",");
                    colNamesToDataTypes.append(sourceColumnNameWithBackTicks).append(" ").append(dataType).append(",");
                    colNameToIndexMap.put(sourceColumnName, index++);
                }
            } else {
                log.error(String.format("Table Name: %s, Column(%s) ignored", tableName, sourceColumnNameWithBackTicks));
            }
        }

        //Remove terminating comma
        int colNamesIndex = colNamesDelimited.lastIndexOf(",");
        if(colNamesIndex != -1 )
            colNamesDelimited.deleteCharAt(colNamesIndex);

        int colNamesToDataTypesIndex = colNamesToDataTypes.lastIndexOf(",");
        if(colNamesToDataTypesIndex != -1)
            colNamesToDataTypes.deleteCharAt(colNamesToDataTypesIndex);

        String insertQuery = String.format("insert into %s(%s) select %s from input('%s')", tableName, colNamesDelimited, colNamesDelimited, colNamesToDataTypes);
        MutablePair<String, Map<String, Integer>> response = new MutablePair<String, Map<String, Integer>>();

        response.left = insertQuery;
        response.right = colNameToIndexMap;

        return response;
    }
    /**
     * Function to construct an INSERT query using input functions.
     *
     * @param tableName Table Name
     * @return Insert query using Input function.
     */
    public String getInsertQueryUsingInputFunction(String tableName,  Map<String, String> columnNameToDataTypeMap) {
        // "insert into mytable select col1, col2 from input('col1 String, col2 DateTime64(3), col3 Int32')"))

        StringBuilder colNamesDelimited = new StringBuilder();
        StringBuilder colNamesToDataTypes = new StringBuilder();

        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String columnName = new StringBuffer().append("`").append(entry.getKey()).append("`").toString();

            colNamesDelimited.append(columnName).append(",");
            colNamesToDataTypes.append(columnName).append(" ").append(entry.getValue()).append(",");
        }

        if(colNamesDelimited.length() != 0) {
            //Remove terminating comma
            int indexOfComma = colNamesDelimited.lastIndexOf(",");
            if (indexOfComma != -1) {
                colNamesDelimited.deleteCharAt(indexOfComma);

            }
        }

        if(colNamesToDataTypes.length() != 0) {
            int indexOfComma = colNamesToDataTypes.lastIndexOf(",");

            if(indexOfComma != -1) {
                colNamesToDataTypes.deleteCharAt(indexOfComma);
            }
        }
        return String.format("insert into %s select %s from input('%s')", tableName, colNamesDelimited, colNamesToDataTypes);
    }
}
