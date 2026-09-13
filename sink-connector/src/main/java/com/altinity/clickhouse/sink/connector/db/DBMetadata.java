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
     * Returns the columns that make up the table's sorting key, in key order.
     *
     * <p>Used to decide whether an UPDATE moves a row to a different sorting
     * key. When it does, the row's previous position must be tombstoned or the
     * stale row survives forever alongside the updated one.</p>
     *
     * @param conn ClickHouse Connection.
     * @param database The database name where the table is located.
     * @param tableName The name of the table.
     * @return The sorting-key columns in key order; empty when the table has an
     *         empty sorting key ({@code ORDER BY tuple()}), is not found, or
     *         cannot be queried.
     */
    public List<String> getSortingKeyColumns(final Connection conn, final String database,
                                             final String tableName) {
        List<String> sortingKeyColumns = new ArrayList<>();
        if (conn == null) {
            log.error("Error with DB connection, cannot read sorting key for {}.{}", database, tableName);
            return sortingKeyColumns;
        }
        // sorting_key is the rendered ORDER BY expression, which may contain
        // functions. Reading the column list from system.columns via
        // is_in_sorting_key avoids having to parse it.
        String query = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s' "
                        + "AND is_in_sorting_key = 1 ORDER BY position", database, tableName);
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                sortingKeyColumns.add(rs.getString("name"));
            }
        } catch (Exception e) {
            // Never fail the write path on this. An empty result makes the
            // caller skip the tombstone, i.e. fall back to the previous
            // behaviour rather than emitting a possibly wrong tombstone.
            log.error("Error retrieving sorting key columns for {}.{}", database, tableName, e);
        }
        return sortingKeyColumns;
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
     * Returns the declared type of a single column, or null when the column
     * does not exist in ClickHouse or cannot be read.
     *
     * <p>Needed to rewrite a column definition: {@code MODIFY COLUMN} must
     * restate the type, and restating it from anywhere other than
     * {@code system.columns} risks changing it by accident.</p>
     *
     * @param tableName    the ClickHouse table name.
     * @param databaseName the ClickHouse database name.
     * @param columnName   the column to look up; matched case-insensitively.
     * @param conn         the connection to read metadata with.
     * @return the column's declared type, or null.
     */
    public String getColumnType(String tableName, String databaseName,
                                String columnName, Connection conn) {
        if (tableName == null || databaseName == null || columnName == null
                || conn == null) {
            return null;
        }
        String query = String.format(
                "SELECT type FROM system.columns WHERE database = '%s' "
                        + "AND table = '%s' AND lower(name) = lower('%s')",
                databaseName, tableName, columnName);
        try (ResultSet rs = conn.createStatement().executeQuery(query)) {
            if (rs != null && rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception e) {
            log.warn("Could not read the declared type of {}.{}.{}",
                    databaseName, tableName, columnName, e);
        }
        return null;
    }

    /**
     * Returns the {@code default_expression} of a single column, or null when
     * the column has none or it cannot be read.
     *
     * <p>Read for the same reason as {@link #getColumnType}: converting a
     * MATERIALIZED column to DEFAULT has to restate the expression, and the
     * only faithful source for it is {@code system.columns}. Reconstructing
     * it from the source DDL would translate it a second time and could
     * produce a different expression than the one already in place.</p>
     *
     * <p>The names are bound as parameters rather than interpolated. They are
     * replicated identifiers, so a single quote in one would otherwise make
     * the query malformed -- and the failure would be invisible, because it
     * is caught below and reported as "no expression", which the caller reads
     * as "abandon the conversion".</p>
     *
     * @param tableName    the ClickHouse table name.
     * @param databaseName the ClickHouse database name.
     * @param columnName   the column to look up; matched case-insensitively.
     * @param conn         the connection to read metadata with.
     * @return the column's default expression, or null when absent/unreadable.
     */
    public String getColumnDefaultExpression(String tableName,
                                             String databaseName,
                                             String columnName,
                                             Connection conn) {
        if (tableName == null || databaseName == null || columnName == null
                || conn == null) {
            return null;
        }
        String query = "SELECT default_expression FROM system.columns WHERE "
                + "database = ? AND table = ? AND lower(name) = lower(?)";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, databaseName);
            ps.setString(2, tableName);
            ps.setString(3, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs != null && rs.next()) {
                    String expression = rs.getString(1);
                    if (expression != null && !expression.trim().isEmpty()) {
                        return expression;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read the default expression of {}.{}.{}",
                    databaseName, tableName, columnName, e);
        }
        return null;
    }

    /**
     * Converts a MATERIALIZED column into a DEFAULT column so the connector
     * can write the source's value into it, and returns whether the column is
     * now writable.
     *
     * <p>This is the enforcement half of the replication contract. The source
     * database is the authority on what the data is, and this connector is
     * what makes ClickHouse agree with it. A column defined MATERIALIZED on
     * the ClickHouse side breaks that: ClickHouse computes and stores its own
     * value, the source's value for the same column is never written, and the
     * two sides disagree silently -- no error, no failed batch, identical row
     * counts. Reporting that is not enough. The ClickHouse definition is the
     * thing that is wrong, so the connector corrects it.</p>
     *
     * <p><b>The conversion is to DEFAULT, not to an ordinary column.</b>
     * The two differ in what happens when the connector does NOT send the
     * column, which is the common case for a table whose source declares it
     * generated: DEFAULT re-derives the value from the same expression,
     * exactly as MATERIALIZED did, whereas a bare column would store a type
     * zero. DEFAULT additionally accepts an explicit value, so the binlog
     * value lands as sent. That is the whole property being bought here --
     * the source becomes authoritative without the replica losing its
     * ability to derive the column on its own.</p>
     *
     * <p>Restating the column without any default clause does NOT achieve
     * this, and is not merely a weaker form of it. ClickHouse reads an
     * omitted default clause as "leave the existing default alone", so
     * {@code MODIFY COLUMN <col> <type>} against a MATERIALIZED column is
     * accepted, returns no error, and leaves {@code default_kind} exactly as
     * it was. Verified on 24.8.14, 25.8.12 and 26.1.6:</p>
     *
     * <pre>
     *   ALTER TABLE t MODIFY COLUMN c Nullable(Int64)
     *       -&gt; default_kind: MATERIALIZED   (unchanged, no error)
     *   ALTER TABLE t MODIFY COLUMN c Nullable(Int64) DEFAULT ifNull(base,0)*2
     *       -&gt; default_kind: DEFAULT
     * </pre>
     *
     * <p>The expression is read back from {@code system.columns} and restated
     * verbatim rather than reconstructed, so the replica keeps deriving
     * precisely what it derived before. CODEC, COMMENT and column TTL are
     * carried across by ClickHouse itself and do not need restating --
     * verified on the same builds by comparing {@code create_table_query}
     * either side of the conversion.</p>
     *
     * <p>When the expression cannot be read the conversion is abandoned
     * rather than attempted without it. Emitting {@code MODIFY COLUMN <col>
     * <type>} in that situation is the silent no-op above, and any statement
     * that did take effect would strip the replica's ability to derive the
     * column at all -- a worse state than the divergence being corrected.</p>
     *
     * <p>It is a metadata-only change: existing parts are not rewritten, so
     * it is cheap and does not block.</p>
     *
     * <p><b>It fixes the write path forward, not history.</b> Rows written
     * while the column was MATERIALIZED still hold ClickHouse's computed
     * values; reconciling those is a backfill, and the caller is told so.</p>
     *
     * @param tableName    the ClickHouse table name.
     * @param databaseName the ClickHouse database name.
     * @param columnName   the column being converted to DEFAULT.
     * @param columnType   the column's declared type, restated verbatim.
     * @param conn         the connection to issue the DDL on.
     * @return true when the column was successfully made writable.
     */
    public boolean makeColumnWritable(String tableName, String databaseName,
                                      String columnName, String columnType,
                                      Connection conn) {
        if (tableName == null || databaseName == null || columnName == null
                || columnType == null || columnType.isEmpty() || conn == null) {
            return false;
        }

        // The existing expression, restated verbatim. Without it the DDL
        // below degenerates into the silent no-op described above, so the
        // conversion is abandoned rather than issued blind.
        String defaultExpression = getColumnDefaultExpression(
                tableName, databaseName, columnName, conn);
        if (defaultExpression == null) {
            log.warn("Could not read the expression behind {}.{}.{}, so it "
                            + "cannot be converted to DEFAULT. Restating the "
                            + "column without one would not remove "
                            + "MATERIALIZED anyway -- ClickHouse treats an "
                            + "omitted default clause as 'leave it alone'. "
                            + "Redefine the column by hand as "
                            + "DEFAULT <expression> so the replicated value "
                            + "is stored.",
                    databaseName, tableName, columnName);
            return false;
        }

        // Backticks, not plain identifiers: replicated table and column names
        // routinely contain characters ClickHouse would otherwise parse.
        String ddl = String.format(
                "ALTER TABLE `%s`.`%s` MODIFY COLUMN `%s` %s DEFAULT %s",
                databaseName, tableName, columnName, columnType,
                defaultExpression);
        try {
            log.info("Enforcing source conformance on {}.{}: {}",
                    databaseName, tableName, ddl);
            executeSystemQuery(conn, ddl);
        } catch (Exception e) {
            log.warn("Could not make {}.{}.{} writable; the source value for "
                            + "this column cannot be stored until the "
                            + "ClickHouse definition is corrected.",
                    databaseName, tableName, columnName, e);
            return false;
        }

        // Confirm the enforcement actually took effect rather than trusting
        // the absence of an exception. executeSystemQuery retries a failing
        // statement and, once the retry budget is spent, RETURNS NORMALLY --
        // so a rejected ALTER (missing privilege, unsupported change) is
        // indistinguishable from a successful one at the call site. Reporting
        // success there would be the worst possible outcome: the caller would
        // believe the replica had been corrected and stop warning, while the
        // source value continued to be silently discarded.
        //
        // Asserted positively: the column must now read back as DEFAULT.
        // A negative check ("no longer MATERIALIZED") would also accept an
        // ordinary column with no default at all, which is the outcome this
        // change exists to avoid -- the replica would stop deriving the
        // column and store a type zero whenever the connector omits it.
        String kindAfter = getColumnDefaultKind(tableName, databaseName,
                columnName, conn);
        if (!"DEFAULT".equalsIgnoreCase(kindAfter)) {
            log.warn("Conversion did not take effect on {}.{}.{}: its "
                            + "default_kind is '{}' after the ALTER, not "
                            + "DEFAULT. The source value for this column is "
                            + "still not being stored -- redefine the column "
                            + "by hand as DEFAULT <expression>, or grant the "
                            + "connector ALTER TABLE on this table.",
                    databaseName, tableName, columnName,
                    kindAfter == null ? "unreadable" : kindAfter);
            return false;
        }
        return true;
    }

    /**
     * Returns the {@code default_kind} of a single column, or null when the
     * column does not exist in ClickHouse.
     *
     * <p>The connector replicates a source database into ClickHouse, so the
     * source is the authority on what the data is. That makes ALIAS and
     * MATERIALIZED two very different situations, even though
     * {@link #getColumnsDataTypesForTable} excludes both from the writable
     * column map:</p>
     *
     * <ul>
     *   <li><b>ALIAS</b> is not stored at all. There is nothing to diverge,
     *       so a source column that is ALIAS here is simply ignored.</li>
     *   <li><b>MATERIALIZED</b> IS stored, and ClickHouse computes it. If the
     *       source also supplies that column, the stored value is whatever
     *       ClickHouse derived rather than what the source sent -- the
     *       replica silently disagrees with its source, with no error and
     *       matching row counts.</li>
     * </ul>
     *
     * <p>Distinguishing the two is what lets the caller stay silent about the
     * former and report the latter.</p>
     *
     * @param tableName    the ClickHouse table name.
     * @param databaseName the ClickHouse database name.
     * @param columnName   the column to look up; matched case-insensitively.
     * @param conn         the connection to read metadata with.
     * @return the column's {@code default_kind} (possibly an empty string for
     *         an ordinary column), or null when it cannot be determined.
     */
    public String getColumnDefaultKind(String tableName, String databaseName,
                                       String columnName, Connection conn) {
        if (tableName == null || databaseName == null || columnName == null
                || conn == null) {
            return null;
        }
        String query = String.format(
                "SELECT default_kind FROM system.columns WHERE database = '%s' "
                        + "AND table = '%s' AND lower(name) = lower('%s')",
                databaseName, tableName, columnName);
        try (ResultSet rs = conn.createStatement().executeQuery(query)) {
            if (rs != null && rs.next()) {
                String kind = rs.getString(1);
                return kind == null ? "" : kind;
            }
        } catch (Exception e) {
            log.warn("Could not read default_kind for {}.{}.{}", databaseName,
                    tableName, columnName, e);
        }
        return null;
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
    /**
     * ClickHouse server error codes that describe a permanent, deterministic
     * condition. Retrying them cannot change the outcome, so they must not
     * consume the retry budget.
     *
     * <p>57 = TABLE_ALREADY_EXISTS, 82 = DATABASE_ALREADY_EXISTS,
     * 44 = ILLEGAL_COLUMN, 47 = UNKNOWN_IDENTIFIER, 60 = UNKNOWN_TABLE,
     * 62 = SYNTAX_ERROR, 81 = UNKNOWN_DATABASE, already-exists variants for
     * dictionaries (487) and columns (44/15).</p>
     */
    private static final Set<Integer> NON_RETRYABLE_ERROR_CODES = Collections.unmodifiableSet(
            // 497 ACCESS_DENIED is deterministic in exactly the same way as
            // the rest: a privilege the connector does not hold will not be
            // granted by sleeping. It matters most for the enforcement path
            // (ALTER TABLE ... MODIFY COLUMN), where retrying a denied ALTER
            // blocks the CDC/DDL thread for the whole retry budget and still
            // fails.
            new HashSet<>(Arrays.asList(15, 44, 47, 57, 60, 62, 81, 82, 487, 497)));

    /**
     * Decides whether a failed statement can plausibly succeed on a retry.
     *
     * <p>Before this classification every SQLException was retried with a
     * linear backoff. A deterministic failure such as
     * {@code Code: 57 TABLE_ALREADY_EXISTS} therefore blocked the calling
     * thread for the entire {@code errors.max.retries} budget. In history
     * mode that thread is the CDC/DDL thread, so the change events arriving
     * during the sleep were dropped -- silent data loss whose only symptom is
     * a burst of "Error executing query: Retrying" lines.</p>
     *
     * @param sqle the exception raised by the driver
     * @return true when a retry may succeed, false for a permanent condition
     */
    static boolean isRetryable(SQLException sqle) {
        if (sqle == null) {
            return false;
        }
        for (Throwable t = sqle; t != null; t = t.getCause()) {
            Integer code = parseClickHouseErrorCode(t.getMessage());
            if (code != null && NON_RETRYABLE_ERROR_CODES.contains(code)) {
                return false;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return true;
    }

    /**
     * Extracts the numeric error code from a ClickHouse error message of the
     * form {@code "Code: 57. DB::Exception: ..."}.
     *
     * @param message the exception message, may be null
     * @return the code, or null when the message carries none
     */
    static Integer parseClickHouseErrorCode(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher m = CLICKHOUSE_ERROR_CODE.matcher(message);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * Matches the {@code Code: NN} that ClickHouse puts at the START of an
     * error message.
     *
     * <p>Anchored deliberately. An unanchored search would also match the
     * text inside a failing statement -- a transient read timeout whose
     * message quotes a query containing {@code COMMENT 'Code: 57'} would be
     * misread as permanent and lose its retries, which is the exact failure
     * this classification exists to prevent. Drivers wrap the server message,
     * so a bounded prefix is allowed before the code, but it must not appear
     * arbitrarily deep in user data.</p>
     */
    private static final java.util.regex.Pattern CLICKHOUSE_ERROR_CODE =
            java.util.regex.Pattern.compile("^[^:]{0,64}?\\bCode:\\s*(\\d+)");

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
                if (!isRetryable(sqle)) {
                    // Permanent, deterministic failure. Retrying it would
                    // block this thread for the whole retry budget while the
                    // outcome cannot change; in history mode that thread is
                    // the CDC/DDL thread and the events arriving during the
                    // sleep are lost. Surface it to the caller instead.
                    log.error("Non-retryable error executing query, giving up: {}", sql, sqle);
                    throw sqle;
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
                ps = conn.prepareStatement("TRUNCATE TABLE `" + databaseName + "`.`" + tableName + "`");
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
