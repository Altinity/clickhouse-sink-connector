package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.Statement;

public class DBMetadata {

    private static final Logger log = LoggerFactory.getLogger(DBMetadata.class);


    public enum TABLE_ENGINE {
        COLLAPSING_MERGE_TREE("CollapsingMergeTree"),
        REPLACING_MERGE_TREE("ReplacingMergeTree"),
        REPLICATED_REPLACING_MERGE_TREE("ReplicatedReplacingMergeTree"),

        MERGE_TREE("MergeTree"),

        DEFAULT("default");

        private final String engine;

        public String getEngine() {
            return engine;
        }

        TABLE_ENGINE(String engine) {
            this.engine = engine;
        }
    }

    /**
     * Wrapper function to get table engine.
     * @param conn
     * @param tableName
     * @return
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngine(ClickHouseConnection conn, String databaseName, String tableName) {

        MutablePair<TABLE_ENGINE, String> result;
        result = getTableEngineUsingSystemTables(conn, databaseName, tableName);

        if(result.left == null) {
            result = getTableEngineUsingShowTable(conn, tableName);
        }

        return result;
    }
    /**
     * Function to return Engine type for table.
     * This function calls the "create table" SQL
     * to get the schema of the table.
     * @param tableName
     * @return
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngineUsingShowTable(ClickHouseConnection conn, String tableName) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return new MutablePair<>(null, null);
            }
            try(Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("show create table %s", tableName);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if(rs.next()) {
                    String response =  rs.getString(1);
                    if(response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
                        result.right = getSignColumnForCollapsingMergeTree(response);
                    } else if(response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.REPLACING_MERGE_TREE;
                        result.right = getVersionColumnForReplacingMergeTree(response);
                    } else if(response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.MERGE_TREE;
                    }else {
                        result.left = TABLE_ENGINE.DEFAULT;
                    }
                }
                rs.close();
                stmt.close();
                log.info("ResultSet" + rs);
            }
        } catch(Exception e) {
            log.error("getTableEngineUsingShowTable exception", e);
        }

        return result;
    }

    public static final String COLLAPSING_MERGE_TREE_SIGN_PREFIX = "CollapsingMergeTree(";
    public static final String REPLACING_MERGE_TREE_VER_PREFIX = "ReplacingMergeTree(";

    /**
     * Function to extract the sign column for CollapsingMergeTree
     * @param createDML
     * @return Sign column
     */
    public String getSignColumnForCollapsingMergeTree(String createDML) {

        String signColumn = "sign";

        if(createDML.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
            signColumn = StringUtils.substringBetween(createDML, COLLAPSING_MERGE_TREE_SIGN_PREFIX, ")");
        } else {
            log.error("Error: Trying to retrieve sign from table that is not CollapsingMergeTree");
        }

        return signColumn;
    }

    /**
     * Function to extract the version column for ReplacingMergeTree
     * @param createDML
     * @return Sign column
     */
    public String getVersionColumnForReplacingMergeTree(String createDML) {

        String signColumn = "sign";

        if(createDML.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())) {
            signColumn = StringUtils.substringBetween(createDML, REPLACING_MERGE_TREE_VER_PREFIX, ")");
        } else {
            log.error("Error: Trying to retrieve ver from table that is not ReplacingMergeTree");
        }

        return signColumn;
    }
    /**
     * Function to get table engine using system tables.
     * @param conn ClickHouse Connection
     * @param tableName Table Name.
     * @return TABLE_ENGINE type
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngineUsingSystemTables(final ClickHouseConnection conn, final String database,
                                                        final String tableName) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();


        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }
            try(Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("select engine_full from system.tables where name='%s' and database='%s'",
                        tableName, database);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if(rs.next()) {
                    String response =  rs.getString(1);
                    if(response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
                        result.right = getSignColumnForCollapsingMergeTree(response);
                    } else if(response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.REPLACING_MERGE_TREE;
                        result.right = getVersionColumnForReplacingMergeTree(response);
                    } else if(response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.MERGE_TREE;
                    } else {
                        result.left = TABLE_ENGINE.DEFAULT;
                    }
                }
                rs.close();
                stmt.close();
                log.info("ResultSet" + rs);
            }
        } catch(Exception e) {
            log.error("getTableEngineUsingSystemTables exception", e);
        }

        return result;
    }
}
