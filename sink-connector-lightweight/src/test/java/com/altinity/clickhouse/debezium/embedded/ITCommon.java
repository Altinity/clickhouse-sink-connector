package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;

import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.HashMap;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
public class ITCommon {
    // Docker image constants
    public static final String MYSQL_DOCKER_IMAGE = "docker.io/mysql:8.0.36";
    public static final String CLICKHOUSE_DOCKER_IMAGE = "clickhouse/clickhouse-server:24.8";

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

        props.replace("snapshot.mode", "no_data");
        props.replace("disable.drop.truncate", "true");
        props.setProperty("disable.ddl", "true");
        props.setProperty("replica.status.view", "CREATE OR REPLACE VIEW %s.show_replica_status (`seconds_behind_source` Int32, `duration_behind_source` String, `utc_time` DateTime('UTC'), `local_time` DateTime, `id` String, `offset_key` String, `offset_val` String, `record_insert_ts` DateTime, `record_insert_seq` UInt64) AS SELECT * FROM (SELECT now() - fromUnixTimestamp(if(JSONHas(offset_val, 'ts_sec'), JSONExtractUInt(offset_val, 'ts_sec'), intDiv(JSONExtractUInt(offset_val, 'ts_usec'), 1000000))) AS seconds_behind_source, formatReadableTimeDelta(seconds_behind_source) AS duration_behind_source, toDateTime(fromUnixTimestamp(if(JSONHas(offset_val, 'ts_sec'), JSONExtractUInt(offset_val, 'ts_sec'), intDiv(JSONExtractUInt(offset_val, 'ts_usec'), 1000000))), 'UTC') AS utc_time, fromUnixTimestamp(if(JSONHas(offset_val, 'ts_sec'), JSONExtractUInt(offset_val, 'ts_sec'), intDiv(JSONExtractUInt(offset_val, 'ts_usec'), 1000000))) AS local_time, * FROM %s FINAL) AS U ORDER BY offset_key ASC");
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
     * Wait until a ClickHouse query returns at least {@code expectedMinRows} rows,
     * polling every {@code pollIntervalMs} milliseconds until {@code timeoutMs} elapses.
     *
     * @param conn          ClickHouse JDBC connection
     * @param countQuery    SQL query that returns a single count column (e.g. "SELECT count(*) FROM t")
     * @param expectedMin   minimum count value to consider "ready"
     * @param timeoutMs     maximum time to wait in milliseconds
     * @param pollIntervalMs interval between polls in milliseconds
     * @return the last observed count, or -1 if the query never succeeded
     */
    static public long waitForRowCount(Connection conn, String countQuery, long expectedMin, long timeoutMs, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastCount = -1;
        int poll = 0;
        while (System.currentTimeMillis() < deadline) {
            try (ResultSet rs = conn.prepareStatement(countQuery).executeQuery()) {
                if (rs.next()) {
                    lastCount = rs.getLong(1);
                    if (lastCount >= expectedMin) {
                        return lastCount;
                    }
                }
            } catch (Exception e) {
                // Table/database may not exist yet — keep polling
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            poll++;
        }
        return lastCount;
    }

    /**
     * Convenience overload: polls every 5 s with a 120 s timeout.
     */
    static public long waitForRowCount(Connection conn, String countQuery, long expectedMin) {
        return waitForRowCount(conn, countQuery, expectedMin, 120_000, 5_000);
    }

    /**
     * Wait until a table exists in ClickHouse and has the expected number of columns.
     * Polls {@code system.columns} every 5 seconds.
     *
     * @param conn           ClickHouse JDBC connection
     * @param database       ClickHouse database name
     * @param table          table name
     * @param expectedCols   expected column count
     * @param timeoutMs      max wait in milliseconds
     * @return true if the column count matched within the timeout
     */
    static public boolean waitForTableColumns(Connection conn, String database, String table, int expectedCols, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String sql = String.format(
                        "SELECT count() FROM system.columns WHERE database='%s' AND table='%s'",
                        database, table);
                try (ResultSet rs = conn.prepareStatement(sql).executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= expectedCols) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // table may not exist yet
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /**
     * Wait until a ClickHouse query returns at least one row (any result),
     * polling every 5 seconds with the given timeout.
     *
     * @param conn       ClickHouse JDBC connection
     * @param query      any SELECT query
     * @param timeoutMs  maximum wait in milliseconds
     * @return true if the query returned at least one row within the timeout
     */
    static public boolean waitForData(Connection conn, String query, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (ResultSet rs = conn.prepareStatement(query).executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore — table/db may not exist yet
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }
}