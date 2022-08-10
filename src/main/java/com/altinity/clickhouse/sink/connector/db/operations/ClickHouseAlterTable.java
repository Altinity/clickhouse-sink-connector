package com.altinity.clickhouse.sink.connector.db.operations;

import java.util.Map;
import java.util.Set;

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
    public String createAlterTableSyntaxAddColumn(String tableName, Map<String, String> colNameToDataTypesMap, ALTER_TABLE_OPERATION operation) {
        // alter table <table_name>
        // add column `col_name_1` data_type_1,
        // add column `col_name_2` data_type_2

        StringBuilder alterTableSyntax = new StringBuilder();

        alterTableSyntax.append("ALTER TABLE").append(" ").append(tableName).append(" ");

        for(Map.Entry<String, String>  entry: colNameToDataTypesMap.entrySet()) {
            if(operation.name().equalsIgnoreCase(ALTER_TABLE_OPERATION.ADD.op)) {
                alterTableSyntax.append("add column ");
            }
            alterTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
        }
        alterTableSyntax.deleteCharAt(alterTableSyntax.lastIndexOf(","));


        return alterTableSyntax.toString();
    }

    public String createAlterTableSyntaxDropColumn(String tableName, Set<String> columnNames) {
        // alter table <table_name>
        // add column `col_name_1` data_type_1,
        // add column `col_name_2` data_type_2

        StringBuilder alterTableSyntax = new StringBuilder();

        alterTableSyntax.append("ALTER TABLE").append(" ").append(tableName).append(" ");

        for(String colName: columnNames) {
            alterTableSyntax.append("drop column").append(colName).append(",");
        }
        //.append("drop column ");

        //alterTableSyntax.append(String.join(",", columnNames));

        return alterTableSyntax.toString();
    }

}
