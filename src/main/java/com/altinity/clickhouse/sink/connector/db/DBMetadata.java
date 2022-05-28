package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.Statement;

public class DBMetadata {

    private static final Logger log = LoggerFactory.getLogger(DBMetadata.class);


    public enum TABLE_ENGINE {
        COLLAPSING_MERGE_TREE("CollapsingMergeTree"),
        REPLACING_MERGE_TREE("ReplacingMergeTree"),

        MERGE_TREE("MergeTree"),

        DEFAULT("default");

        private String engine;

        TABLE_ENGINE(String engine) {
            this.engine = engine;
        }
    }
    /**
     * Function to return Engine type for table.
     * This function calls the "create table" SQL
     * to get the schema of the table.
     * @param tableName
     * @return
     */
    public TABLE_ENGINE getTableEngine(ClickHouseConnection conn, String tableName) {
        TABLE_ENGINE result = null;

        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }
            try(Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("show create table %s", tableName);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if(rs.next()) {
                    String response =  rs.getString(1);
                    if(response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
                    } else if(response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.REPLACING_MERGE_TREE;
                    } else if(response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.MERGE_TREE;
                    }else {
                        result = TABLE_ENGINE.DEFAULT;
                    }
                }
                rs.close();
                stmt.close();
                log.info("ResultSet" + rs);
            }
        } catch(Exception e) {
            log.error("getTableEngine exception", e);
        }

        return result;
    }

    /**
     * Function to get table engine using system tables.
     * @param conn ClickHouse Connection
     * @param tableName Table Name.
     * @return TABLE_ENGINE type
     */
    public TABLE_ENGINE getTableEngineUsingSystemTables(final ClickHouseConnection conn, final String tableName) {
        TABLE_ENGINE result = null;

        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }
            try(Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("select engine from system.tables where name='%s'", tableName);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if(rs.next()) {
                    String response =  rs.getString(1);
                    if(response.equalsIgnoreCase(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
                    } else if(response.equalsIgnoreCase(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.REPLACING_MERGE_TREE;
                    } else if(response.equalsIgnoreCase(TABLE_ENGINE.MERGE_TREE.engine)) {
                        result = TABLE_ENGINE.MERGE_TREE;
                    } else {
                        result = TABLE_ENGINE.DEFAULT;
                    }
                }
                rs.close();
                stmt.close();
                log.info("ResultSet" + rs);
            }
        } catch(Exception e) {
            log.error("getTableEngine exception", e);
        }

        return result;
    }
}
