package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;


public class ClickHouseAutoCreateTable {

    public void createClickHouseTableSyntax(Field[] fields) {

        for(Field f: fields) {
            String colName = f.name();

            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();

            // Input: 
        }
    }
}
