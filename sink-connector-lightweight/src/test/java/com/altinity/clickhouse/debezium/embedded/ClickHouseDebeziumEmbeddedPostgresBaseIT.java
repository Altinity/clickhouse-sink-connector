package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public abstract class ClickHouseDebeziumEmbeddedPostgresBaseIT {
//
//    public Map<String, String> getDefaultProperties(PostgreSQLContainer postgreSQLContainer, ClickHouseContainer clickHouseContainer) throws Exception {
//
//        // Start the debezium embedded application.
//
//        Properties defaultProps = new Properties();
//        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");
//
//        defaultProps.putAll(defaultProperties);
//        Properties fileProps = new ConfigLoader().load("config.yml");
//        defaultProps.putAll(fileProps);
//
//        defaultProps.setProperty("database.hostname", postgreSQLContainer.getHost());
//        defaultProps.setProperty("database.port", String.valueOf(postgreSQLContainer.getFirstMappedPort()));
//        defaultProps.setProperty("database.user", "root");
//        defaultProps.setProperty("database.password", "adminpass");
//
//        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
//        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
//        defaultProps.setProperty("clickhouse.server.database", "employees");
//
//        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
//                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));
//
//        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
//                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));
//
//        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
//                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));
//
//        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
//                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));
//
//        return defaultProps.to;
//    }

}
