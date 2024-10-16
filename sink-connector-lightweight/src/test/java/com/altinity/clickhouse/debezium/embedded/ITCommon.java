package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class ITCommon {
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

    static public Properties getDebeziumPropertiesForSchemaOnly(MySQLContainer mySqlContainer, ClickHouseContainer clickHouseContainer) throws Exception {

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

        props.replace("snapshot.mode", "schema_only");
        props.replace("disable.drop.truncate", "true");
        props.setProperty("disable.ddl", "true");
        props.setProperty("replica.status.view", "CREATE VIEW IF NOT EXISTS %s.show_replica_status AS SELECT now() - fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS seconds_behind_source,  toDateTime(fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')), 'UTC') AS utc_time, fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS local_time FROM %s settings final=1");
        return props;
    }
}