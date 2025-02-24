package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import io.debezium.metadata.ConnectorDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public enum ConnectorType {


    MYSQL("mysql"),
    KAFKA("kafka"),
    POSTGRES("postgres");

    private final String value;

    ConnectorType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ConnectorType fromString(String value) {
        ConnectorType connectorType = ConnectorType.MYSQL;

        String displayName = ConnectorDescriptor.getIdForConnectorClass(value);
        if(displayName != null) {
            //connectorType =ConnectorType.valueOf(displayName);
            if(displayName.contains(MYSQL.getValue())) {
                connectorType = ConnectorType.MYSQL;
            } else if(displayName.contains(POSTGRES.getValue())) {
                connectorType = ConnectorType.POSTGRES;
            }
        }
        return connectorType;
    }

    public static final String SINK_CONNECTOR_CLASS="sink.connector.ClickHouseSinkConnector";
    public static final String CONNECTOR_CLASS="connector.class";

    public static ConnectorType getConnectorType(ClickHouseSinkConnectorConfig config, Logger logger) {
        ConnectorType connectorType = ConnectorType.MYSQL;

        try {
            String connectorClass = config.getString(CONNECTOR_CLASS);
            // For Kafka. connector.class -> com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector
            if(connectorClass.contains(SINK_CONNECTOR_CLASS)) {
                // Skip kafka check.
                return ConnectorType.KAFKA;
            }
            connectorType = ConnectorType.fromString(config.getString(CONNECTOR_CLASS));
        } catch (Exception e) {
            logger.error("Error getting connector type", e);
            //log.error("Error while getting connector type", e);
        }
        return connectorType;
    }

}