package com.altinity.clickhouse.debezium.embedded.config;

/**
 * Enumeration of environment variables for database configuration.
 * <p>
 * This enum defines the keys for various environment variables used
 * to configure the database connection for Debezium.
 * </p>
 */
public enum EnvironmentVariables {

    /**
     * The environment variable for the database hostname.
     */
    SOURCE_HOST("database.hostname"),

    /**
     * The environment variable for the database port.
     */
    SOURCE_PORT("database.port"),

    /**
     * The environment variable for the database user.
     */
    SOURCE_USER("database.user"),

    /**
     * The environment variable for the database password.
     */
    SOURCE_PASSWORD("database.password"),

    /**
     * The environment variable for the database whitelist.
     */
    SOURCE_DATABASE("database.whitelist"),

    /**
     * The environment variable for the Debezium MySQL connector.
     */
    SOURCE_CONNECTOR("io.debezium.connector.mysql.MySqlConnector");

    /**
     * The label associated with the environment variable.
     */
    private final String label;

    /**
     * Constructs an EnvironmentVariables constant with the given label.
     *
     * @param s the label for the environment variable
     */
    EnvironmentVariables(String s) {
        this.label = s;
    }
}
