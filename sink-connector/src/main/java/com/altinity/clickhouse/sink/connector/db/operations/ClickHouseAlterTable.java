package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class that handles logic related to alter table
 * in ClickHouse(Schema Evolution)
 */
public class ClickHouseAlterTable extends ClickHouseTableOperationsBase{
    private static final Logger log = LoggerFactory.getLogger(ClickHouseAlterTable.class.getName());

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

    /**
     *
     * @para
     * m modifiedFields
     */
    public void alterTable(List<Field> modifiedFields, String tableName, ClickHouseConnection connection, Map<String, String> columnNameToDataTypeMap) {
        List<Field> missingFieldsInCH = new ArrayList<Field>();
        // Identify the columns that need to be added/removed in ClickHouse.
        for(Field f: modifiedFields) {
            String colName = f.name();

            if(columnNameToDataTypeMap.containsKey(colName) == false) {
                missingFieldsInCH.add(f);
            }
        }

        if(!missingFieldsInCH.isEmpty()) {
            log.info("***** ALTER TABLE ****");
            ClickHouseAlterTable cat = new ClickHouseAlterTable();
            Field[] missingFieldsArray = new Field[missingFieldsInCH.size()];
            missingFieldsInCH.toArray(missingFieldsArray);
            Map<String, String> colNameToDataTypeMap = cat.getColumnNameToCHDataTypeMapping(missingFieldsArray);

            if(!colNameToDataTypeMap.isEmpty()) {
                String alterTableQuery = cat.createAlterTableSyntax(tableName, colNameToDataTypeMap, ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
                log.info(" ***** ALTER TABLE QUERY **** " + alterTableQuery);

                try {
                    cat.runQuery(alterTableQuery, connection);
                } catch(Exception e) {
                    log.error(" **** ALTER TABLE EXCEPTION ", e);
                }
            }
        }
    }
}
