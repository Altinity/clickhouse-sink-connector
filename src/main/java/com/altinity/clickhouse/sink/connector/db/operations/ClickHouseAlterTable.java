package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;

import java.util.Map;

/**
 * Class that handles logic related to alter table
 * in ClickHouse(Schema Evolution)
 */
public class ClickHouseAlterTable extends ClickHouseTableOperationsBase{

    public enum ALTER_TABLE_OPERATION {
        ADD("add"),
        REMOVE("remove");

        String op;
        ALTER_TABLE_OPERATION(String op) {
            this.op = op;
        }
    }
    public String createAlterTableSyntax(String tableName, Map<String, String> colNameToDataTypesMap, ALTER_TABLE_OPERATION operation) {
        // alter table <table_name>
        // add column `col_name_1` data_type_1,
        // add column `col_name_2` data_type_2

        StringBuilder alterTableSyntax = new StringBuilder();

        alterTableSyntax.append(ClickHouseDbConstants.ALTER_TABLE).append(" ").append(tableName).append(" ");

        for(Map.Entry<String, String>  entry: colNameToDataTypesMap.entrySet()) {
            if(operation.name().equalsIgnoreCase(ALTER_TABLE_OPERATION.ADD.op)) {
                alterTableSyntax.append(ClickHouseDbConstants.ALTER_TABLE_ADD_COLUMN).append(" ");
            } else {
                alterTableSyntax.append(ClickHouseDbConstants.ALTER_TABLE_DELETE_COLUMN).append(" ");
            }
            alterTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
        }
        alterTableSyntax.deleteCharAt(alterTableSyntax.lastIndexOf(","));


        return alterTableSyntax.toString();
    }
}
