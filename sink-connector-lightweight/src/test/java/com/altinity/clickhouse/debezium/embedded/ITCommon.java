package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;

import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
public class ITCommon {
    // Docker image constants
    public static final String MYSQL_DOCKER_IMAGE = "docker.io/mysql:8.0.36";
    public static final String CLICKHOUSE_DOCKER_IMAGE = "clickhouse/clickhouse-server:latest";

    static public Connection connectToMySQL(MySQLContainer mySqlContainer) {
        Connection conn = null;
        try {

            String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort(),
                    mySqlContainer.getDatabaseName(), mySqlContainer.getUsername(), mySqlContainer.getPassword());
            conn = DriverManager.getConnection(connectionUrl);


        } catch (SQLException ex) {
            // handle any errors

        }

        return conn;
    }

    static public Connection connectToMySQL(String host, String port, String databaseName, String userName, String password) {
        Connection conn = null;
        try {

            String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", host, port,
                    databaseName, userName, password);
            conn = DriverManager.getConnection(connectionUrl);


        } catch (SQLException ex) {
            // handle any errors

        }

        return conn;
    }

    // Function to connect to Postgres.
    static public Connection connectToPostgreSQL(PostgreSQLContainer postgreSQLContainer) throws SQLException {
        Connection conn = null;

            String connectionUrl = String.format("jdbc:postgresql://%s:%s/%s?user=%s&password=%s", postgreSQLContainer.getHost(),
                    postgreSQLContainer.getFirstMappedPort(),
                    postgreSQLContainer.getDatabaseName(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
            conn = DriverManager.getConnection(connectionUrl);

        return conn;
    }

    static public Properties getDebeziumProperties(String mySQLHost, String mySQLPort, ClickHouseContainer clickHouseContainer) throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        defaultProps.setProperty("database.hostname", mySQLHost);
        defaultProps.setProperty("database.port", String.valueOf(mySQLPort));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;

    }

    static public Properties getDebeziumProperties( ClickHouseContainer clickHouseContainer) throws Exception {

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);


        defaultProps.setProperty("connector.class", "io.debezium.connector.mongodb.MongoDbConnector");

        // Construct mongodb connection string
        String mongoConnectionString = String.format("mongodb://%s:%s", "mongo",
                "27017");

        defaultProps.setProperty("mongodb.connection.string", mongoConnectionString +"/?replicaSet=rs0");
        //defaultProps.setProperty("mongodb.connection.string", mongoConnectionString );

        //defaultProps.setProperty("mongodb.connection.string", mongoConnectionString + "/?replicaSet=docker-rs");

        defaultProps.setProperty("capture.scope", "database");
        defaultProps.setProperty("mongodb.members.auto.discover", "true");
        defaultProps.setProperty("topic.prefix", "mongo-ch");
        defaultProps.setProperty("collection.include.list", "project.items");
        defaultProps.setProperty("snapshot.include.collection.list", "project.items");
        defaultProps.setProperty("database.include.list", "project");
        defaultProps.setProperty("key.converter", "org.apache.kafka.connect.json.JsonConverter");

        defaultProps.setProperty("value.converter", "org.apache.kafka.connect.storage.StringConverter");
        defaultProps.setProperty("value.converter.schemas.enable", "true");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;
    }
    static public Properties getDebeziumProperties(MySQLContainer mySqlContainer, ClickHouseContainer clickHouseContainer) throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
        //defaultProps.setProperty("ddl.retry", "true");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s/altinity_sink_connector",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s/altinity_sink_connector",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        defaultProps.setProperty("jdbc.schema.history.table.name", "altinity_sink_connector.replicate_schema_history");

        return defaultProps;

    }

    static public Properties getDebeziumPropertiesForSchemaOnly(MySQLContainer mySqlContainer, ClickHouseContainer clickHouseContainer) throws Exception {

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

        props.replace("snapshot.mode", "schema_only");
        props.replace("disable.drop.truncate", "true");
        props.setProperty("disable.ddl", "true");
        props.setProperty("replica.status.view", "CREATE VIEW IF NOT EXISTS %s.show_replica_status AS SELECT now() - fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS seconds_behind_source,  toDateTime(fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')), 'UTC') AS utc_time, fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS local_time FROM %s settings final=1");
        return props;
    }


    static public BaseDbWriter getDBWriter(ClickHouseContainer clickHouseContainer) {

         String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), 
         "employees");
        Connection connection = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, clickHouseContainer.getUsername(), 
        clickHouseContainer.getPassword(), BaseDbWriter.SYSTEM_DB, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);

        return writer;
    }

    static public BaseDbWriter getDBWriter(ClickHouseContainer clickHouseContainer, String databaseName) {

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                databaseName);
        Connection connection = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, clickHouseContainer.getUsername(),
                clickHouseContainer.getPassword(), BaseDbWriter.SYSTEM_DB, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                databaseName, clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);

        return writer;
    }



    /**
     * Function to execute query.
     * @param sql
     * @return
     * @throws SQLException
     */
    static public ResultSet executeQueryWithResultSet(String sql, Connection conn) throws SQLException {
        ResultSet rs = conn.prepareStatement(sql).executeQuery();
        return rs;

    }

    /**
     * Calculate checksum for a MySQL table
     * Uses MD5 hashing compatible with pt-checksum technique
     * 
     * @param host MySQL host
     * @param port MySQL port
     * @param user MySQL username
     * @param password MySQL password
     * @param database Database name
     * @param table Table name
     * @return MD5 checksum as hex string
     * @throws Exception if connection or query fails
     */
    static public String calculateMySQLTableChecksum(String host, int port, String user, String password, 
                                                     String database, String table) throws Exception {
        String url = String.format("jdbc:mysql://%s:%d/%s?charset=utf8mb4", host, port, database);
        
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // Get column metadata
            String sql = String.format(
                "SELECT COLUMN_NAME as column_name, column_type as data_type, IS_NULLABLE as is_nullable " +
                "FROM information_schema.columns WHERE table_schema='%s' AND table_name='%s' ORDER BY ordinal_position",
                database, table
            );
            
            StringBuilder select = new StringBuilder();
            List<String> nullables = new ArrayList<>();
            boolean firstColumn = true;
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    String columnName = "`" + rs.getString("column_name") + "`";
                    String dataType = rs.getString("data_type");
                    String isNullable = rs.getString("is_nullable");
                    
                    if (!firstColumn) {
                        select.append(",");
                    }
                    
                    if ("YES".equals(isNullable)) {
                        nullables.add(columnName);
                    }
                    
                    String selectColumn = columnName;
                    
                    if ("YES".equals(isNullable)) {
                        selectColumn = String.format("IFNULL(%s,'')", selectColumn);
                    }
                    
                    select.append(selectColumn);
                    firstColumn = false;
                }
            }
            
            if (!nullables.isEmpty()) {
                select.append(", CONCAT(");
                boolean first = true;
                for (String nullable : nullables) {
                    if (!first) {
                        select.append(',');
                    } else {
                        first = false;
                    }
                    select.append("ISNULL(").append(nullable).append(")");
                }
                select.append(")");
            }
            
            // Execute checksum query
            String checksumSql = String.format(
                "SELECT COUNT(*) AS cnt, " +
                "COALESCE(MAX(a),0) AS a, " +
                "COALESCE(MAX(b),0) AS b, " +
                "COALESCE(MAX(c),0) AS c, " +
                "COALESCE(MAX(d),0) AS d " +
                "FROM (" +
                "  SELECT @md5sum :=MD5(CONVERT(CONCAT_WS('#',%s) USING utf8mb4)) AS `hash`, " +
                "  @a:=@a+CAST(CONV(SUBSTRING(@md5sum, 1, 8), -16, 10) AS SIGNED) AS a, " +
                "  @b:=@b+CAST(CONV(SUBSTRING(@md5sum, 9, 8), -16, 10) AS SIGNED) AS b, " +
                "  @c:=@c+CAST(CONV(SUBSTRING(@md5sum, 17, 8), -16, 10) AS SIGNED) AS c, " +
                "  @d:=@d+CAST(CONV(SUBSTRING(@md5sum, 25, 8), -16, 10) AS SIGNED) AS d " +
                "  FROM %s.%s WHERE 1=1" +
                ") AS t",
                select, database, table
            );
            
            // Initialize variables
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET @md5sum := '', @a := CAST(0 AS SIGNED), @b:= CAST(0 AS SIGNED), " +
                           "@c:= CAST(0 AS SIGNED), @d:=CAST(0 AS SIGNED)");
            }
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checksumSql)) {
                
                if (rs.next()) {
                    long cnt = rs.getLong("cnt");
                    long a = rs.getLong("a");
                    long b = rs.getLong("b");
                    long c = rs.getLong("c");
                    long d = rs.getLong("d");
                    
                    // Calculate final MD5
                    String md5Input = cnt + "#" + a + "#" + b + "#" + c + "#" + d + "#";
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    byte[] digest = md5.digest(md5Input.getBytes(StandardCharsets.UTF_8));
                    
                    return bytesToHex(digest);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Calculate checksum for a ClickHouse table
     * Uses MD5 hashing compatible with pt-checksum technique
     * Automatically excludes internal columns (_sign, _version, is_deleted, _is_deleted)
     * 
     * @param host ClickHouse host
     * @param port ClickHouse port
     * @param user ClickHouse username
     * @param password ClickHouse password
     * @param database Database name
     * @param table Table name
     * @return MD5 checksum as hex string
     * @throws Exception if connection or query fails
     */
    static public String calculateClickHouseTableChecksum(String host, int port, String user, String password,
                                                         String database, String table) throws Exception {
        String url = String.format("jdbc:clickhouse://%s:%d/%s", host, port, database);
        
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        
        try (Connection conn = DriverManager.getConnection(url, props)) {
            // Check if _sign column exists to determine if we need FINAL and WHERE clause
            boolean hasSignColumn = false;
            String checkSignSql = String.format(
                "SELECT count(*) as cnt FROM system.columns WHERE database='%s' AND table='%s' AND name='_sign'",
                database, table
            );
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checkSignSql)) {
                if (rs.next()) {
                    hasSignColumn = rs.getInt("cnt") > 0;
                }
            }
            
            // Get column metadata
            String sql = String.format(
                "SELECT name, type, if(match(type,'Nullable'),1,0) is_nullable, numeric_scale " +
                "FROM system.columns WHERE database='%s' AND table='%s' " +
                "AND name NOT IN ('_sign', '_version', 'is_deleted', '_is_deleted') " +
                "ORDER BY position",
                database, table
            );
            
            StringBuilder select = new StringBuilder();
            List<String> nullables = new ArrayList<>();
            List<Map<String, Object>> columns = new ArrayList<>();
            
            // Collect all column metadata first
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    Map<String, Object> column = new HashMap<>();
                    column.put("name", rs.getString("name"));
                    column.put("type", rs.getString("type"));
                    column.put("is_nullable", rs.getInt("is_nullable") == 1);
                    columns.add(column);
                }
            }
            
            // Build select expression
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> column = columns.get(i);
                String columnName = "\"" + column.get("name") + "\"";
                boolean isNullable = (boolean) column.get("is_nullable");
                boolean isLastColumn = (i == columns.size() - 1);
                
                if (i > 0) {
                    select.append("||");
                }
                
                if (isNullable) {
                    nullables.add(columnName);
                    select.append(" CASE WHEN ").append(columnName).append(" IS NULL THEN '' ELSE ");
                }
                
                select.append("toString(").append(columnName).append(")");
                
                if (isNullable) {
                    select.append(" END");
                }
                
                // Only add '#' separator if not the last column
                if (!isLastColumn) {
                    select.append("||'#'");
                }
            }
            
            if (!nullables.isEmpty()) {
                select.append("||'#'");
                for (String nullable : nullables) {
                    select.append("|| CASE WHEN ").append(nullable).append(" IS NULL THEN '1' ELSE '0' END ");
                }
            }
            
            // Build the checksum query with conditional FINAL and WHERE clause
            String finalClause = hasSignColumn ? "FINAL" : "";
            String whereClause = hasSignColumn ? "WHERE _sign > 0" : "";
            String settingsClause = hasSignColumn ? "SETTINGS do_not_merge_across_partitions_select_final=1" : "";
            
            String checksumSql = String.format(
                "SELECT " +
                "  count(*) AS cnt, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 1, 8))))),0) AS a, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 9, 8))))),0) AS b, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))),0) AS c, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))),0) AS d " +
                "FROM ( " +
                "  SELECT hex(MD5(%s)) AS \"hash\" " +
                "  FROM %s.%s %s %s " +
                ") AS t %s",
                select, database, table, finalClause, whereClause, settingsClause
            );
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checksumSql)) {
                
                if (rs.next()) {
                    long cnt = rs.getLong("cnt");
                    long a = rs.getLong("a");
                    long b = rs.getLong("b");
                    long c = rs.getLong("c");
                    long d = rs.getLong("d");
                    
                    // Calculate final MD5
                    String md5Input = cnt + "#" + a + "#" + b + "#" + c + "#" + d + "#";
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    byte[] digest = md5.digest(md5Input.getBytes(StandardCharsets.UTF_8));
                    
                    return bytesToHex(digest);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Convert byte array to hex string
     * 
     * @param bytes byte array to convert
     * @return hex string representation
     */
    static private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Get row count for a MySQL table
     * 
     * @param host MySQL host
     * @param port MySQL port
     * @param user MySQL username
     * @param password MySQL password
     * @param database Database name
     * @param table Table name
     * @return Row count
     * @throws Exception if connection or query fails
     */
    static public long getMySQLTableRowCount(String host, int port, String user, String password,
                                             String database, String table) throws Exception {
        String url = String.format("jdbc:mysql://%s:%d/%s", host, port, database);
        
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) as cnt FROM %s.%s", database, table))) {
            
            if (rs.next()) {
                return rs.getLong("cnt");
            }
        }
        
        return 0;
    }
    
    /**
     * Get row count for a ClickHouse table
     * 
     * @param host ClickHouse host
     * @param port ClickHouse port
     * @param user ClickHouse username
     * @param password ClickHouse password
     * @param database Database name
     * @param table Table name
     * @return Row count
     * @throws Exception if connection or query fails
     */
    static public long getClickHouseTableRowCount(String host, int port, String user, String password,
                                                  String database, String table) throws Exception {
        String url = String.format("jdbc:clickhouse://%s:%d/%s", host, port, database);
        
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        
        try (Connection conn = DriverManager.getConnection(url, props)) {
            // Check if _sign column exists
            boolean hasSignColumn = false;
            String checkSignSql = String.format(
                "SELECT count(*) as cnt FROM system.columns WHERE database='%s' AND table='%s' AND name='_sign'",
                database, table
            );
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checkSignSql)) {
                if (rs.next()) {
                    hasSignColumn = rs.getInt("cnt") > 0;
                }
            }
            
            // Build query with conditional FINAL and WHERE clause
            String finalClause = hasSignColumn ? "FINAL" : "";
            String whereClause = hasSignColumn ? "WHERE _sign > 0" : "";
            String countSql = String.format("SELECT COUNT(*) as cnt FROM %s.%s %s %s", 
                                           database, table, finalClause, whereClause);
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(countSql)) {
                
                if (rs.next()) {
                    return rs.getLong("cnt");
                }
            }
        }
        
        return 0;
    }
}