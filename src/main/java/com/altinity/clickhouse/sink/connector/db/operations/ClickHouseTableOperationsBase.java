package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.client.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class ClickHouseTableOperationsBase {


    private static final String ENABLE_SETTINGS = "settings";
    private static final String ALLOW_EXPERIMENTAL_OBJECT_TYPE = "allow_experimental_object_type=1";

    public static final String SCALE = "scale";
    public static final String PRECISION = "connect.decimal.precision";

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

                    if(params != null && params.containsKey(SCALE) && params.containsKey(PRECISION)) {
                        columnToDataTypesMap.put(colName, "Decimal(" + params.get(PRECISION) + "," + params.get(SCALE) + ")");
                    } else {
                        columnToDataTypesMap.put(colName, "Decimal(10,2)");
                    }
                } else if(dataType == ClickHouseDataType.DateTime64){
                    // Timestamp (with milliseconds scale) , DATETIME, DATETIME(0 -3) -> DateTime64(3)
                    if(f.schema().type() == Schema.INT64_SCHEMA.type() && f.schema().name().equalsIgnoreCase(Timestamp.SCHEMA_NAME)) {
                        columnToDataTypesMap.put(colName, "DateTime64(3)");
                    } else if(f.schema().type() == Schema.INT64_SCHEMA.type() && f.schema().name().equalsIgnoreCase(MicroTimestamp.SCHEMA_NAME)) {
                        // MicroTimestamp (with microseconds precision) , DATETIME(3 -6) -> DateTime64(6)
                        columnToDataTypesMap.put(colName, "DateTime64(6)");
                    } else {
                        columnToDataTypesMap.put(colName, dataType.name());
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

        //https://github.com/ClickHouse/clickhouse-jdbc/issues/127

        Statement stmt = conn.createStatement();
         //   stmt.execute("SET allow_experimental_object_type = 1");

        stmt.executeQuery(query);
        stmt.close();

    }

}
