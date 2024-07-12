package com.altinity.clickhouse.sink.connector.db;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.CHECK_DB_EXISTS_SQL;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

public class DBMetadata {

    private static final Logger log = LogManager.getLogger(DBMetadata.class);


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
            result = getTableEngineUsingShowTable(conn, databaseName, tableName);
        }

        return result;
    }

    /**
     * Function to check if database exists by querying the information schema tables.
     * @param conn
     * @param databaseName
     * @return
     */
    public boolean checkIfDatabaseExists(ClickHouseConnection conn, String databaseName) throws SQLException {

        boolean result = false;
        try (Statement stmt = conn.createStatement()) {
            String showSchemaQuery = String.format(CHECK_DB_EXISTS_SQL, databaseName);
            ResultSet rs = stmt.executeQuery(showSchemaQuery);
            if (rs != null && rs.next()) {
                String response = rs.getString(1);
                if(response.equalsIgnoreCase(databaseName)) {
                    result = true;
                }
            }

            rs.close();
        } catch(SQLException se) {
            log.error("checkIfDatabaseExists exception", se);
            // ToDO: For some reason, this query throws SQLException when DB is not available.

            //throw se;
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
    public MutablePair<TABLE_ENGINE, String> getTableEngineUsingShowTable(ClickHouseConnection conn, String databaseName,
                                                                          String tableName) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return new MutablePair<>(null, null);
            }
            try(Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("show create table %s.`%s`", databaseName, tableName);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if(rs != null && rs.next()) {
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
                log.info("getTableEngineUsingShowTable ResultSet" + rs);
            }
        } catch(Exception e) {
            log.info("getTableEngineUsingShowTable exception", e);
        }

        return result;
    }

    public static final String COLLAPSING_MERGE_TREE_SIGN_PREFIX = "CollapsingMergeTree(";
    public static final String REPLACING_MERGE_TREE_VER_PREFIX = "ReplacingMergeTree(";

    public static final String REPLACING_MERGE_TREE_VERSION_WITH_IS_DELETED = "23.2";
    public static final String REPLICATED_REPLACING_MERGE_TREE_VER_PREFIX = "ReplicatedReplacingMergeTree(";
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

        String versionColumn = "ver";

        if(createDML.contains(TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine())) {
            String parameters = StringUtils.substringBetween(createDML, REPLICATED_REPLACING_MERGE_TREE_VER_PREFIX, ")");
            if(parameters != null) {
                String[] parameterArray = parameters.split(",");
                if(parameterArray != null && parameterArray.length == 3) {
                    versionColumn = parameterArray[2].trim();
                } else if(parameterArray != null && parameterArray.length == 4) {
                    versionColumn = parameterArray[2].trim() + "," + parameterArray[3].trim();
                }
            }
        }
        else if(createDML.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())) {
            if(createDML != null && createDML.indexOf("(") != -1 && createDML.indexOf(")") != -1) {
                String subString = StringUtils.substringBetween(createDML, REPLACING_MERGE_TREE_VER_PREFIX, ")");
                if(subString != null) {
                    versionColumn = subString.trim();
                }
            }
        } else {
            log.error("Error: Trying to retrieve ver from table that is not ReplacingMergeTree");
        }

        return versionColumn;
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
                if(rs.wasNull() == false && rs.next()) {
                    String response =  rs.getString(1);
                    result = getEngineFromResponse(response);
                } else {
                    log.debug("Error: Table not found in system tables:" + tableName + " Database:" + database);
                }
                rs.close();
                stmt.close();
                log.info("getTableEngineUsingSystemTables ResultSet" + rs);
            }
        } catch(Exception e) {
            log.debug("getTableEngineUsingSystemTables exception", e);
        }

        return result;
    }

    public MutablePair<TABLE_ENGINE, String> getEngineFromResponse(String response) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        if(response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
            result.right = getSignColumnForCollapsingMergeTree(response);
        }
        else if(response.contains(TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE;
            result.right = getVersionColumnForReplacingMergeTree(response);
        }
        else if(response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.REPLACING_MERGE_TREE;
            result.right = getVersionColumnForReplacingMergeTree(response);
        } else if(response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.MERGE_TREE;
        }  else {
            result.left = TABLE_ENGINE.DEFAULT;
        }

        return result;
    }


    /**
     * Function to check if Replacing mergetree is supported
     * based on ClickHouse version.
     * @return true, if RMT is supported, false otherwise
     * @throws SQLException
     */
    public boolean checkIfNewReplacingMergeTree(String currentClickHouseVersion) throws SQLException {

        boolean result = true;

        DefaultArtifactVersion supportedVersion = new DefaultArtifactVersion(REPLACING_MERGE_TREE_VERSION_WITH_IS_DELETED);
        DefaultArtifactVersion currentVersion = new DefaultArtifactVersion(currentClickHouseVersion);

        if (currentVersion.compareTo(supportedVersion) < 0) {
            result = false;
        }

        return result;
    }




    /**
     * Function that uses the DatabaseMetaData JDBC functionality
     * to get the column name and column data type as key/value pair.
     */
    public Map<String, String> getColumnsDataTypesForTable(String tableName,
                                                           ClickHouseConnection conn,
                                                           String database) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }

            ResultSet columns = conn.getMetaData().getColumns(database, null,
                    tableName, null);
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");

                String isGeneratedColumn = columns.getString("IS_GENERATEDCOLUMN");
                String columnDefinition = columns.getString("COLUMN_DEF");
                String sqlDataType = columns.getString("SQL_DATA_TYPE");
                String dataType = columns.getString("DATA_TYPE");
               // String typeName = columns.getString("TYPE_NAME");
//                String columnSize = columns.getString("COLUMN_SIZE");
//                String isNullable = columns.getString("IS_NULLABLE");
//                String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");

                // Skip generated columns.
                if(isGeneratedColumn != null && isGeneratedColumn.equalsIgnoreCase("YES")) {
                    continue;
                }
                result.put(columnName, typeName);
            }
        } catch (SQLException sq) {
            log.error("Exception retrieving Column Metadata", sq);
        }
        return result;
    }
    /**
     * Function to get the ClickHouse server timezone(Defaults to UTC)
     */
    public ZoneId getServerTimeZone(ClickHouseConnection conn) {
        ZoneId result = ZoneId.of("UTC");
        if(conn != null) {
            TimeZone serverTimeZone = conn.getServerTimeZone();
            if(serverTimeZone != null) {
                result = serverTimeZone.toZoneId();
            }
        }

        return result;
    }
}
