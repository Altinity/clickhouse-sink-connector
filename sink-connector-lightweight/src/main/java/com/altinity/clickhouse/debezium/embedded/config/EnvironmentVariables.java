package com.altinity.clickhouse.debezium.embedded.config;

public enum EnvironmentVariables {

    SOURCE_HOST("database.hostname"),
    SOURCE_PORT("database.port"),

    SOURCE_USER("database.user"),
    SOURCE_PASSWORD("database.password"),
    SOURCE_DATABASE("database.whitelist"),

    SOURCE_CONNECTOR("io.debezium.connector.mysql.MySqlConnector");

    private final String label;

    EnvironmentVariables(String s) {
        this.label = s;
    }
}
