package com.altinity.clickhouse.sink.connector.db;

import static com.altinity.clickhouse.sink.connector.db.BaseDbWriter.SYSTEM_DB;
import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.CHECK_DB_EXISTS_SQL;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.sql.*;
import java.time.ZoneId;
import java.util.*;

/**
 * This class handles metadata-related operations for interacting with ClickHouse databases,
 * such as retrieving the engine type for tables, checking if databases exist, and extracting
 * specific columns for different table engines.
 */
public class DBMetadata {

    /**
     * Logger instance for logging messages related to DBMetadata operations.
     */
    private static final Logger log = LogManager.getLogger(DBMetadata.class);

    /**
     * The maximum number of retry attempts for database operations.
     * Defaults to 10 retries. Can be configured via errors.max.retries.
     */
    static int MAX_RETRIES = 10;

    /**
     * Configuration for the ClickHouse sink connector.
     */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Constructor for DBMetadata.
     *
     * @param config The configuration for the ClickHouse sink connector.
     */
    public DBMetadata(ClickHouseSinkConnectorConfig config) {
        this.config = config;
    }

    public DBMetadata(Properties props) {
        // Create HashMap from Properties
        Map<String, String> propsMap = new HashMap<>();
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            propsMap.put(entry.getKey().toString(), entry.getValue().toString());
        }
        this.config = new ClickHouseSinkConnectorConfig(propsMap);
    }

    /**
     * Gets the configuration for the ClickHouse sink connector.
     *
     * @return The configuration object.
     */
    public ClickHouseSinkConnectorConfig getConfig() {
        return config;
    }

    /**
     * Sets the maximum number of retries for database operations.
     *
     * @param maxRetries The maximum number of retries to attempt for database operations.
     */
    public static void setMaxRetries(int maxRetries) {
        MAX_RETRIES = maxRetries;
    }

    /**
     * Enum representing the different table engine types used in ClickHouse.
     * Each engine type corresponds to a specific table engine available in ClickHouse.
     */
    public enum TABLE_ENGINE {
        /**
         * CollapsingMergeTree engine for ClickHouse tables.
         * Used for tables with collapsing versions of data.
         */
        COLLAPSING_MERGE_TREE("CollapsingMergeTree"),

        /**
         * ReplacingMergeTree engine for ClickHouse tables.
         * Used for tables with versions of data that can be replaced by newer versions.
         */
        REPLACING_MERGE_TREE("ReplacingMergeTree"),

        /**
         * ReplicatedReplacingMergeTree engine for ClickHouse tables.
         * A replicated version of the ReplacingMergeTree engine.
         */
        REPLICATED_REPLACING_MERGE_TREE("ReplicatedReplacingMergeTree"),

        /**
         * MergeTree engine for ClickHouse tables.
         * The most commonly used engine for tables with sorted data.
         */
        MERGE_TREE("MergeTree"),

        /**
         * Default engine for ClickHouse tables.
         * Represents a generic or unspecified engine type.
         */
        DEFAULT("default");

        private final String engine;

        /**
         * Gets the name of the table engine.
         *
         * @return The engine name as a string.
         */
        public String getEngine() {
            return engine;
        }

        /**
         * Constructor for the TABLE_ENGINE enum.
         *
         * @param engine The engine name associated with the enum constant.
         */
        TABLE_ENGINE(String engine) {
            this.engine = engine;
        }
    }

    /**
     * Wrapper function to get the engine type and specific column details for the table.
     * @param conn The database connection.
     * @param databaseName The name of the database.
     * @param tableName The name of the table.
     * @return A MutablePair containing the table engine type and the associated column information.
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngine(Connection conn, String databaseName, String tableName) throws SQLException {
        MutablePair<TABLE_ENGINE, String> result;
        try {
            result = getTableEngineUsingSystemTables(conn, databaseName, tableName);
        } catch(SQLException sq) {
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
    public boolean checkIfDatabaseExists(Connection conn, String databaseName) throws SQLException {

        int retryCount = 0;
        boolean result = false;

        while (!result && retryCount < MAX_RETRIES) {
            try {
                retryCount++;
                log.info("Retrying checkIfDatabaseExists, attempt {}", retryCount);
                try (Statement retryStmt = conn.createStatement()) {
                    String showSchemaQuery = String.format(CHECK_DB_EXISTS_SQL, databaseName);
                    ResultSet retryRs = retryStmt.executeQuery(showSchemaQuery);
                    if (retryRs != null && retryRs.next()) {
                        String response = retryRs.getString(1);
                        if (response.equalsIgnoreCase(databaseName)) {
                            result = true;
                        }
                    }
                    retryRs.close();
                }
            } catch (Exception retryException) {
                log.error("Retry attempt ({}/{}) failed", retryCount,MAX_RETRIES, retryException);
                // if config disable thread pool is false, then initiate new connection
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            databaseName, HikariDbSource.urlOf(conn));
                }
            }
        }

        return result;
    }

    /**
     * Function to return Engine type for table.
     * This function calls the "show create table" SQL
     * to get the schema of the table and determines its engine type.
     *
     * @param conn The database connection.
     * @param databaseName The name of the database.
     * @param tableName The name of the table.
     * @return A MutablePair containing the engine type of the table and
     *         additional column information if applicable.
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngineUsingShowTable(Connection conn, String databaseName,
                                                                          String tableName) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        // Retry logic for handling transient failures.
        int retryCount = 0;

        while (retryCount < MAX_RETRIES) {
            try (Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("show create table %s.`%s`", databaseName, tableName);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if (rs != null && rs.next()) {
                    String response = rs.getString(1);
                    // Determine table engine type based on the response.
                    if (response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
                        result.right = getSignColumnForCollapsingMergeTree(response);
                    } else if (response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.REPLACING_MERGE_TREE;
                        result.right = getVersionColumnForReplacingMergeTree(response);
                    } else if (response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
                        result.left = TABLE_ENGINE.MERGE_TREE;
                    } else {
                        result.left = TABLE_ENGINE.DEFAULT;
                    }
                }
                rs.close();
                stmt.close();
                log.info("getTableEngineUsingShowTable ResultSet: " + rs);
                break;
            } catch (Exception e) {
                try {
                    if (conn == null || conn.isClosed()) {
                        if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                            conn = HikariDbSource.initiateNewConnectionIfClosed(
                            databaseName, HikariDbSource.urlOf(conn));
                        }
                    }
                } catch (SQLException sqlException) {
                    log.error("Retry attempt ({}/{}) failed", retryCount, MAX_RETRIES,sqlException);
                }
                retryCount++;
                log.info("getTableEngineUsingShowTable exception", e);
            }
        }

        return result;
    }



    /**
     * Constant prefix for the sign column in the CollapsingMergeTree engine.
     * This prefix is used to identify the sign column in the table schema.
     */
    public static final String COLLAPSING_MERGE_TREE_SIGN_PREFIX = "CollapsingMergeTree(";

    /**
     * Constant prefix for the version column in the ReplacingMergeTree engine.
     * This prefix is used to identify the version column in the table schema.
     */
    public static final String REPLACING_MERGE_TREE_VER_PREFIX = "ReplacingMergeTree(";

    /**
     * Constant value for the version of the ReplacingMergeTree engine with an "is_deleted" column.
     * This represents the version where the "is_deleted" column is present.
     */
    public static final String REPLACING_MERGE_TREE_VERSION_WITH_IS_DELETED = "23.2";

    /**
     * Constant prefix for the version column in the ReplicatedReplacingMergeTree engine.
     * This prefix is used to identify the version column in the table schema for the
     * ReplicatedReplacingMergeTree engine.
     * */
    public static final String REPLICATED_REPLACING_MERGE_TREE_VER_PREFIX = "ReplicatedReplacingMergeTree(";

    /**
     * Extracts the sign column name for the CollapsingMergeTree engine from the
     * CREATE DML statement.
     *
     * @param createDML The CREATE DML statement of the table.
     * @return The sign column name.
     */
    public String getSignColumnForCollapsingMergeTree(String createDML) {
        String signColumn = "sign";

        if (createDML.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
            signColumn = StringUtils.substringBetween(createDML, COLLAPSING_MERGE_TREE_SIGN_PREFIX, ")");
        } else {
            log.error("Error: Trying to retrieve sign from table that is not CollapsingMergeTree");
        }

        return signColumn;
    }

    /**
     * Extracts the version column name for the ReplacingMergeTree engine from the
     * CREATE DML statement.
     *
     * @param createDML The CREATE DML statement of the table.
     * @return The version column name.
     */
    public String getVersionColumnForReplacingMergeTree(String createDML) {
        String versionColumn = "ver";

        if (createDML.contains(TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine())) {
            String parameters = StringUtils.substringBetween(createDML, REPLICATED_REPLACING_MERGE_TREE_VER_PREFIX, ")");
            if (parameters != null) {
                String[] parameterArray = parameters.split(",");
                if (parameterArray.length == 3) {
                    versionColumn = parameterArray[2].trim();
                } else if (parameterArray.length == 4) {
                    versionColumn = parameterArray[2].trim() + "," + parameterArray[3].trim();
                }
            }
        } else if (createDML.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())) {
            if (createDML != null && createDML.indexOf("(") != -1 && createDML.indexOf(")") != -1) {
                String subString = StringUtils.substringBetween(createDML, REPLACING_MERGE_TREE_VER_PREFIX, ")");
                if (subString != null) {
                    versionColumn = subString.trim();
                }
            }
        } else {
            log.error("Error: Trying to retrieve ver from table that is not ReplacingMergeTree");
        }

        return versionColumn;
    }

    /**
     * Retrieves the table engine using system tables in ClickHouse.
     * This function queries the `system.tables` system table to determine the engine.
     *
     * @param conn ClickHouse Connection.
     * @param database The database name where the table is located.
     * @param tableName The name of the table.
     * @return A pair containing the engine type and associated column information.
     */
    public MutablePair<TABLE_ENGINE, String> getTableEngineUsingSystemTables(final Connection conn, final String database,
                                                                             final String tableName) throws SQLException {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }
            try (Statement stmt = conn.createStatement()) {
                String showSchemaQuery = String.format("select engine_full from system.tables where name='%s' and database='%s'",
                        tableName, database);
                ResultSet rs = stmt.executeQuery(showSchemaQuery);
                if (rs != null && rs.next()) {
                    String response = rs.getString(1);
                    result = getEngineFromResponse(response);
                } else {
                    log.debug("Error: Table not found in system tables: " + tableName + " Database: " + database);
                }
                rs.close();
                stmt.close();
            }
        } catch (Exception e) {
            log.debug("getTableEngineUsingSystemTables exception", e);
            throw e;
        }

        return result;
    }

    /**
     * Extracts the engine type from the response of the `SHOW CREATE TABLE` query.
     * This function parses the engine type from the table creation statement.
     *
     * @param response The response string containing the table schema.
     * @return A pair containing the engine type and associated column information.
     */
    public MutablePair<TABLE_ENGINE, String> getEngineFromResponse(String response) {
        MutablePair<TABLE_ENGINE, String> result = new MutablePair<>();

        if (response.contains(TABLE_ENGINE.COLLAPSING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.COLLAPSING_MERGE_TREE;
            result.right = getSignColumnForCollapsingMergeTree(response);
        } else if (response.contains(TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE;
            result.right = getVersionColumnForReplacingMergeTree(response);
        } else if (response.contains(TABLE_ENGINE.REPLACING_MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.REPLACING_MERGE_TREE;
            result.right = getVersionColumnForReplacingMergeTree(response);
        } else if (response.contains(TABLE_ENGINE.MERGE_TREE.engine)) {
            result.left = TABLE_ENGINE.MERGE_TREE;
        } else {
            result.left = TABLE_ENGINE.DEFAULT;
        }

        return result;
    }

    /**
     * Checks if the ReplacingMergeTree engine with the version column and "is_deleted"
     * column is supported based on the current ClickHouse version.
     *
     * @param currentClickHouseVersion The current version of ClickHouse.
     * @return true if the ReplacingMergeTree engine is supported, false otherwise.
     * @throws SQLException if there is an issue with the database connection.
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
     * Retrieves the ClickHouse version by executing a query to the `VERSION()` function.
     *
     * @param connection The database connection.
     * @return The version of the ClickHouse database.
     * @throws SQLException if there is an issue with the database connection.
     */
    public String getClickHouseVersion(Connection connection) throws SQLException {
        return this.executeSystemQuery(connection, "SELECT VERSION()");
    }

    /**
     * Retrieves the column names and their nullable status for a given table.
     * This function queries the `system.columns` table to get the column names and
     * whether they are nullable.
     *
     * @param tableName The name of the table.
     * @param conn The database connection.
     * @param database The database name.
     * @return A map containing the column names as keys and their nullable status as values.
     * @throws SQLException if there is an issue with the database connection.
     */
    public Map<String, Boolean> getColumnsIsNullableForTable(String tableName, Connection conn, String database) throws SQLException {
        Map<String, Boolean> columnsIsNullable = new HashMap<>();

        // Execute the query to get column name and nullable status.
        String query = String.format("SELECT name AS column_name, type LIKE 'Nullable(%%' AS is_nullable " +
                "FROM system.columns WHERE (table = '%s') AND (database = '%s')", tableName, database);

        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(query);
            while (rs.next()) {
                String columnName = rs.getString("column_name");
                boolean isNullable = rs.getBoolean("is_nullable");
                columnsIsNullable.put(columnName, isNullable);
            }
        }

        return columnsIsNullable;
    }

    /**
     * Retrieves the column names and their data types for a given table.
     * This function queries the database metadata to get the column data types.
     *
     * @param tableName The name of the table.
     * @param conn The database connection.
     * @param database The database name.
     * @param config The configuration for the ClickHouse sink connector.
     * @return A map containing the column names as keys and their data types as values.
     */
    public Map<String, String> getColumnsDataTypesForTable(String tableName, Connection conn, String database) {
        // Add retry logic.
        int retryCount = 0;
        Set<String> aliasColumns = new HashSet<>();
        try {
            aliasColumns = getAliasAndMaterializedColumnsForTableAndDatabase(tableName, database, conn);
        } catch (Exception e) {
            log.error("Error getting alias columns, retrying ({}/{})", retryCount,MAX_RETRIES,e);

            try {
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            database, HikariDbSource.urlOf(conn));
                }
            } catch (SQLException e1) {
                log.error("Error initiating new connection retrying ({}/{})", retryCount,MAX_RETRIES,e1);

            }
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        // Add retry logic.
        retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                String query = String.format(
                        "SELECT name, type, default_kind FROM system.columns WHERE database = '%s' AND table = '%s' ORDER BY position",
                        database, tableName);
                ResultSet columns = conn.createStatement().executeQuery(query);
                while (columns.next()) {
                    String columnName = columns.getString("name");
                    String typeName = columns.getString("type");
                    String defaultKind = columns.getString("default_kind");

                    if ("ALIAS".equals(defaultKind) || "MATERIALIZED".equals(defaultKind)) {
                        continue;
                    }
                    if (aliasColumns.contains(columnName)) {
                        log.debug("Skipping alias column: " + columnName);
                        continue;
                    }
                    result.put(columnName, typeName);
                }
                columns.close();
                break;
            } catch (SQLException sq) {
                log.error("Exception retrieving Column Metadata, retrying ({}/{}), use error.max.retries to configure",
                        retryCount,MAX_RETRIES, sq);
                try {
                    if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                        conn = HikariDbSource.initiateNewConnectionIfClosed(
                            database, HikariDbSource.urlOf(conn));
                    }
                } catch (SQLException e1) {
                    log.error("Error initiating new connection, retrying ({}/{})", retryCount,MAX_RETRIES,e1);
                }
                retryCount++;
            }
        }
        return result;
    }

    /**
     * Retrieves the server's timezone. Defaults to UTC if the query fails or no
     * timezone is provided.
     *
     * @param conn The database connection.
     * @return The server's timezone.
     */
    public ZoneId getServerTimeZone(Connection conn) {
        ZoneId result = ZoneId.of("UTC");
        if (conn != null) {
            try {
                // Perform a query to get the server timezone
                ResultSet rs = conn.prepareStatement("SELECT timezone()").executeQuery();
                if (rs.next()) {
                    String serverTimeZone = rs.getString(1);
                    result = ZoneId.of(serverTimeZone);
                }
                rs.close();
            } catch (Exception e) {
                log.error("Error retrieving server timezone", e);
            }
        }
        return result;
    }

    /**
     * Retrieves the set of column names that are aliases or materialized columns
     * for a given table and database.
     *
     * @param tableName The name of the table.
     * @param databaseName The name of the database.
     * @param conn The database connection.
     * @return A set of column names that are aliases or materialized.
     * @throws SQLException if an error occurs while querying the database.
     */
    public Set<String> getAliasAndMaterializedColumnsForTableAndDatabase(String tableName, String databaseName,
                                                                         Connection conn) throws SQLException {
        // Add retry logic.
        int retryCount = 0;
        Set<String> aliasColumns = new HashSet<>();
        while (retryCount < MAX_RETRIES) {
            try {
                String query = "SELECT name FROM system.columns WHERE (table = '%s') AND (database = '%s') and " +
                        "(default_kind='ALIAS' or default_kind='MATERIALIZED')";
                String formattedQuery = String.format(query, tableName, databaseName);

                // Execute query
                ResultSet rs = conn.createStatement().executeQuery(formattedQuery);

                // Get the list of columns from rs.
                if (rs != null) {
                    while (rs.next()) {
                        String response = rs.getString(1);
                        aliasColumns.add(response);
                    }
                }
                rs.close();
                break;
            } catch (Exception e) {
                log.error("Error getting alias columns, retrying ({}/{})", retryCount,MAX_RETRIES,e);
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            databaseName, HikariDbSource.urlOf(conn));
                }
                retryCount++;
            }
        }
        return aliasColumns;
    }

    /**
     * Executes a SQL query and returns the ResultSet.
     *
     * @param sql The SQL query to be executed.
     * @param conn The database connection.
     * @return The result set obtained from executing the query.
     * @throws SQLException if an error occurs while executing the query.
     */
    public ResultSet executeQueryWithResultSet(String sql, Connection conn) throws SQLException {
        // Add retry logic.
        int retryCount = 0;
        ResultSet rs = null;
        while (retryCount < MAX_RETRIES) {
            try {
                rs = conn.prepareStatement(sql).executeQuery();
                break;
            } catch(Exception e) {
                log.error("Error executing query, retrying ({}/{})", retryCount,MAX_RETRIES,e);
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            SYSTEM_DB, HikariDbSource.urlOf(conn));
                }
                retryCount++;
            }
        }
        return rs;
    }

    /**
     * Checks whether the connection pool is able to hand out a connection for
     * the system database.
     *
     * @return true if pooling is enabled and a pool exists.
     */
    private boolean canReconnectFromPool() {
        try {
            if (config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                return false;
            }
            return HikariDbSource.getInstance(SYSTEM_DB) != null;
        } catch (Exception e) {
            log.error("Error checking the connection pool state", e);
            return false;
        }
    }

    /**
     * Executes a system query that returns a string result.
     *
     * @param conn The database connection.
     * @param sql The SQL query to execute.
     * @return The result string from the query.
     * @throws SQLException if an error occurs while executing the query.
     */
    public String executeSystemQuery(Connection conn, String sql) throws SQLException {
        // Add retry logic.
        int retryCount = 0;
        String result = null;
        ResultSet rs = null;
        while (retryCount < MAX_RETRIES) {
            try {
                if (conn == null) {
                    // createConnection() returns null when ClickHouse is
                    // unreachable. Report it as a SQLException so that the
                    // retry below is applied and callers that already handle
                    // SQLException are not hit by a NullPointerException.
                    throw new SQLException(
                            "ClickHouse connection is not available for query: "
                                    + sql);
                }
                PreparedStatement ps = conn.prepareStatement(sql);
                // Use execute() so DDL/DML statements (e.g. CREATE DATABASE, CREATE TABLE,
                // INSERT, SYSTEM ...) that do not produce a ResultSet are supported by
                // strict JDBC drivers (clickhouse-jdbc >= 0.9.x, which rejects executeQuery()
                // for statements that do not return a ResultSet).
                if (ps.execute()) {
                    rs = ps.getResultSet();
                }
                break;
            } catch (SQLException sqle) {
                // A missing connection can only be recovered from the pool. If
                // no pool can supply one, the initial connection never
                // succeeded and retrying would just sleep out the whole retry
                // budget, so fail immediately instead.
                if (conn == null && !canReconnectFromPool()) {
                    log.error("ClickHouse connection is not available and no "
                            + "connection pool can provide one, giving up on "
                            + "query: {}", sql, sqle);
                    break;
                }
                try {
                    log.error("Error executing query: Retrying ({}/{})" ,retryCount,MAX_RETRIES, sqle);
                    Thread.sleep(1000 * retryCount);
                    // Get a new connection from pool.
                    if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                        conn = HikariDbSource.initiateNewConnectionIfClosed(
                            SYSTEM_DB, HikariDbSource.urlOf(conn));
                    }
                } catch (Exception e) {
                    log.error("Error initiating DB connection, retrying ({}/{})",retryCount,MAX_RETRIES, e);
                }
                retryCount++;
            }
        }

        if (rs != null) {
            while(rs.next()) {
                result = rs.getString(1);
            }
        }
        return result;
    }

    /**
     * Retrieves the column names and their data types for a given table.
     *
     * @param conn The database connection.
     * @param tableName The name of the table.
     * @param database The name of the database.
     * @return A map of column names to their data types.
     * @throws SQLException if an error occurs while querying the database.
     */
    public Map<String, String> getColumnsDataTypesForTable(Connection conn, String tableName, String database) {
        // Add retry logic.
        int retryCount = 0;
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        while (retryCount < MAX_RETRIES) {
            try {
                if (conn == null) {
                    log.error("Error with DB connection");
                    return result;
                }

                // Query system.columns directly instead of
                // DatabaseMetaData.getColumns(null, database, tableName, null).
                //
                // getColumns() treats its schemaPattern/tableNamePattern
                // arguments as JDBC LIKE patterns, in which '_' matches ANY
                // single character and '%' matches any sequence. A table whose
                // name contains an underscore therefore also matches sibling
                // tables: for `under_score_t`, getColumns() additionally
                // returned the columns of `underXscore_t`, silently merging
                // two schemas into one column map. Verified live against
                // ClickHouse 24.8.8 with clickhouse-jdbc 0.9.8 on BOTH driver
                // generations (V1 and V2), so this is a latent defect in the
                // original code rather than a driver-migration regression.
                // Underscores are extremely common in replicated MySQL table
                // names, and a wrong column map produces wrong INSERT column
                // lists — i.e. data corruption.
                //
                // system.columns uses exact equality, is driver-independent,
                // and matches how the sibling metadata methods in this class
                // already read column metadata.
                String query = String.format(
                        "SELECT name, type FROM system.columns WHERE database = '%s' AND table = '%s' ORDER BY position",
                        database, tableName);
                ResultSet columns = conn.createStatement().executeQuery(query);
                while (columns.next()) {
                    String columnName = columns.getString("name");
                    String typeName = columns.getString("type");

                    result.put(columnName, typeName);
                }
                columns.close();
                break;
            } catch (Exception sq) {
                log.error("Exception retrieving Column Metadata, retrying ({}/{}),use error.max.retries to configure",
                        retryCount,MAX_RETRIES,sq);
                try {
                    if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                        conn = HikariDbSource.initiateNewConnectionIfClosed(
                            database, HikariDbSource.urlOf(conn));
                    }
                } catch (SQLException e1) {
                    log.error("Error initiating new connection, retrying ({}/{})",retryCount,MAX_RETRIES,e1);
                }

                retryCount++;
            }
        }
        return result;
    }

    /**
     * Truncates a table in the specified database.
     *
     * @param conn The database connection.
     * @param databaseName The name of the database.
     * @param tableName The name of the table to be truncated.
     * @throws SQLException if an error occurs while executing the truncate operation.
     */
    public void truncateTable(Connection conn, String databaseName, String tableName) throws SQLException {
        int retryCount = 0;
        PreparedStatement ps = null;
        while(retryCount < MAX_RETRIES) {
            try {
                ps = conn.prepareStatement("TRUNCATE TABLE " + databaseName + "." + tableName);
                ps.execute();
                break;
            } catch (SQLException e) {
                log.error("*** Error: Truncate table statement error, retry attempt ({}/{}) failed" ,retryCount,MAX_RETRIES, e);
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            databaseName, HikariDbSource.urlOf(conn));
                }
                retryCount++;
            }
        }
    }

    /**
     * Retrieves a prepared statement from the database connection.
     *
     * @param conn The database connection.
     * @param sql The SQL query to prepare.
     * @return A prepared statement for the given SQL query.
     * @throws SQLException if an error occurs while preparing the statement.
     */
    public PreparedStatement getPreparedStatement(Connection conn, String sql) throws SQLException {
        int retryCount = 0;
        PreparedStatement ps = null;
        while (retryCount < MAX_RETRIES) {
            try {
                ps = conn.prepareStatement(sql);
                break;
            } catch (SQLException e) {
                log.error("Error getting prepared statement, retry attempt ({}/{}) failed",retryCount,MAX_RETRIES, e);
                if (!config.getBoolean(String.valueOf(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE))) {
                    conn = HikariDbSource.initiateNewConnectionIfClosed(
                            SYSTEM_DB, HikariDbSource.urlOf(conn));
                }
                retryCount++;
            }
        }
        return ps;
    }
}
