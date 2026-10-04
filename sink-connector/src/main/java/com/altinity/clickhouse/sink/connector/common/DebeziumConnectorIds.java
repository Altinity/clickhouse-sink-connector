package com.altinity.clickhouse.sink.connector.common;

/**
 * Connector-class to connector-id mapping the connector used to take from
 * Debezium's {@code io.debezium.metadata.ConnectorDescriptor.getIdForConnectorClass},
 * which Debezium 3.7 removed (spec 02.06 section 3.4). Same cases, same ids,
 * same exception for an unknown class as the 3.3.2 implementation.
 */
public final class DebeziumConnectorIds {

    private DebeziumConnectorIds() {
    }

    /**
     * @param className fully qualified Debezium connector class name.
     * @return the connector id ({@code mysql}, {@code postgres}, ...).
     * @throws RuntimeException for a class that is not a known Debezium connector.
     */
    public static String idForConnectorClass(String className) {
        switch (className) {
            case "io.debezium.connector.mongodb.MongoDbConnector":
                return "mongodb";
            case "io.debezium.connector.mysql.MySqlConnector":
                return "mysql";
            case "io.debezium.connector.oracle.OracleConnector":
                return "oracle";
            case "io.debezium.connector.postgresql.PostgresConnector":
                return "postgres";
            case "io.debezium.connector.sqlserver.SqlServerConnector":
                return "sqlserver";
            case "io.debezium.connector.mariadb.MariaDbConnector":
                return "mariadb";
            default:
                throw new RuntimeException("Unsupported connector type with className: \"" + className + "\"");
        }
    }
}
