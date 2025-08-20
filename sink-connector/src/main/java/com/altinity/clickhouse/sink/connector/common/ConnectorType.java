package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.metadata.ConnectorDescriptor;
import org.apache.logging.log4j.Logger;

/**
 * Enum representing different types of connectors that can be used
 * in conjunction with the ClickHouse sink connector. Currently,
 * supported types include MySQL, Kafka, and PostgreSQL.
 */
public enum ConnectorType {

    /**
     * Represents the MySQL connector type.
     */
    MYSQL("mysql"),

    /**
     * Represents the Kafka connector type.
     */
    KAFKA("kafka"),

    /**
     * Represents the PostgreSQL connector type.
     */
    POSTGRES("postgres");

    /**
     * The internal string value associated with each connector type.
     */
    private final String value;

    /**
     * Constructs an enum value with the specified string representation.
     *
     * @param value The string value representing the connector type.
     */
    ConnectorType(String value) {
        this.value = value;
    }

    /**
     * Retrieves the string value associated with this connector type.
     *
     * @return The string value, e.g. "mysql" or "kafka".
     */
    public String getValue() {
        return value;
    }

    /**
     * Attempts to determine the enum constant based on a connector class name.
     * This method relies on Debezium's {@link ConnectorDescriptor} to derive
     * a display name. If the display name indicates MySQL or PostgreSQL,
     * the corresponding connector type is returned. Otherwise, it defaults
     * to {@link ConnectorType#MYSQL}.
     *
     * @param value The fully qualified connector class name.
     * @return The inferred connector type (MYSQL, POSTGRES, or default MYSQL
     *         if unable to determine).
     */
    public static ConnectorType fromString(String value) {
        ConnectorType connectorType = ConnectorType.MYSQL;

        String displayName = ConnectorDescriptor.getIdForConnectorClass(value);
        if (displayName != null) {
            //connectorType =ConnectorType.valueOf(displayName);
            if (displayName.contains(MYSQL.getValue())) {
                connectorType = ConnectorType.MYSQL;
            } else if (displayName.contains(POSTGRES.getValue())) {
                connectorType = ConnectorType.POSTGRES;
            }
        }
        return connectorType;
    }

    /**
     * A string constant representing the fully qualified class name
     * of the ClickHouse sink connector for Kafka.
     */
    public static final String SINK_CONNECTOR_CLASS =
            "sink.connector.ClickHouseSinkConnector";

    /**
     * A string constant for the standard "connector.class" property key.
     */
    public static final String CONNECTOR_CLASS = "connector.class";

    /**
     * Determines the connector type by inspecting the connector.class
     * property from the provided {@link ClickHouseSinkConnectorConfig}.
     * <p>
     * If the class name matches the ClickHouse sink connector, KAFKA is
     * returned. Otherwise, it uses {@link #fromString(String)} to derive
     * the correct connector type from Debezium's
     * {@link ConnectorDescriptor}.
     * </p>
     *
     * @param config The ClickHouse sink connector configuration.
     * @param logger A logger instance used to report errors.
     * @return The resolved {@link ConnectorType}.
     */
    public static ConnectorType getConnectorType(
            ClickHouseSinkConnectorConfig config,
            Logger logger
    ) {
        ConnectorType connectorType = ConnectorType.MYSQL;

        try {
            String connectorClass = config.getString(CONNECTOR_CLASS);
            // For Kafka. connector.class ->
            // com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
            if (connectorClass.contains(SINK_CONNECTOR_CLASS)) {
                // Skip kafka check.
                return ConnectorType.KAFKA;
            }
            connectorType = ConnectorType.fromString(
                    config.getString(CONNECTOR_CLASS)
            );
        } catch (Exception e) {
            logger.error("Error getting connector type", e);
            connectorType = ConnectorType.KAFKA;
            //log.error("Error while getting connector type", e);
        }
        return connectorType;
    }
}
