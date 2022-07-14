package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.client.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class ClickHouseTableOperationsBase {


    public ClickHouseTableOperationsBase() {

    }
    private static final Logger log = LoggerFactory.getLogger(ClickHouseTableOperationsBase.class.getName());


    /**
     * Function to create clickhouse create table
     * syntax.
     * @param fields
     */
    public Map<String, String> getColumnNameToCHDataTypeMapping(Field[] fields) {

        ClickHouseDataTypeMapper mapper = new ClickHouseDataTypeMapper();

        Map<String, String> columnToDataTypesMap = new HashMap<>();

        for(Field f: fields) {
            String colName = f.name();

            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();

            // Input:
            ClickHouseDataType dataType = mapper.getClickHouseDataType(type, schemaName);
            if(dataType != null) {
                if(dataType == ClickHouseDataType.Decimal) {
                    //Get Scale, precision from parameters.
                    Map<String, String> params = f.schema().parameters();

                    String SCALE = "scale";
                    String PRECISION = "connect.decimal.precision";

                    if(params != null && params.containsKey(SCALE) && params.containsKey(PRECISION)) {
                        columnToDataTypesMap.put(colName, "Decimal(" + params.get(PRECISION) + "," + params.get(SCALE) + ")");
                    } else {
                        columnToDataTypesMap.put(colName, "Decimal(10, 2)");
                    }
                } else {
                    columnToDataTypesMap.put(colName, dataType.name());
                }
            }else {
                log.error(" **** DATA TYPE MAPPING not found: " + "TYPE:" + type.getName() + "SCHEMA NAME:" + schemaName);
            }
//
//            if(columnToDataTypesMap.isEmpty() == false) {
//                String createTableQuery = this.createTableSyntax(primaryKey, tableName, columnToDataTypesMap);
//            }
        }

        return columnToDataTypesMap;
    }

    /**
     * Function to execute the create table query using the ClickHouse JDBC connection
     * @param query
     * @param conn
     */
    public void runQuery(String query, ClickHouseConnection conn) throws SQLException {

        if(conn == null) {
            log.error("ClickHouse connection not created");
            return;
        }

        Statement stmt = conn.createStatement();
        stmt.executeQuery(query);

    }

}
