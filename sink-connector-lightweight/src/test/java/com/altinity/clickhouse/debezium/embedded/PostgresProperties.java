package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Properties;

public class PostgresProperties {

    public static Properties getDefaultProperties(PostgreSQLContainer postgreSQLContainer, ClickHouseContainer clickHouseContainer) throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config_postgres.yml");
        defaultProps.putAll(fileProps);

        defaultProps.setProperty("database.hostname", postgreSQLContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(postgreSQLContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user",  postgreSQLContainer.getUsername());
        defaultProps.setProperty("database.password", postgreSQLContainer.getPassword());

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
        //defaultProps.setProperty("clickhouse.server.database", "employees");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        // Test-data intent (spec 07.02 section 5). init_postgres.sql, the
        // fixture every Postgres IT built on these properties loads, seeds
        // tm.amount with an 89-digit negative numeric
        // (-57896044618658100000...000, about -5.8e88) on purpose: it was
        // added to exercise the BOUNDED (saturating) decimal path, and it is
        // far outside the Decimal128 range. The product default is the loud
        // clamp (clamp.out.of.range=false, spec 07.03 section 3.3): under it
        // that row throws DebeziumConverter.ValueOutOfRangeException in the
        // writer, which is terminal for the batch (spec 10.01 section 3.1) and
        // stops the engine. These ITs were written for the saturating
        // behaviour, so they opt in to it here; the default (loud) path is
        // pinned by DebeziumConverterRangePolicyTest and
        // PreparedStatementFieldMapperOutOfRangeTest. Do NOT change the
        // product default to make this fixture pass.
        defaultProps.setProperty(ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE.toString(), "true");

        return defaultProps;
    }

}
