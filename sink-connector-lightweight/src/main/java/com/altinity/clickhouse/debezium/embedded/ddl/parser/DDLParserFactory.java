package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory class that creates the appropriate {@link DDLParserService} implementation
 * based on the source connector type (MySQL/MariaDB or PostgreSQL).
 *
 * <p>Usage example:
 * <pre>
 *   DDLParserService parser = DDLParserFactory.getParser(connectorClass, writer, config, dbName);
 *   parser.parseSql(ddl, tableName, queryBuffer, isDropOrTruncate);
 * </pre>
 * </p>
 */
public class DDLParserFactory {

    private static final Logger log = LogManager.getLogger(DDLParserFactory.class);

    /**
     * Fully-qualified class name used by the Debezium PostgreSQL connector.
     */
    public static final String POSTGRES_CONNECTOR_CLASS = "io.debezium.connector.postgresql.PostgresConnector";

    /**
     * Fully-qualified class name used by the Debezium MySQL connector.
     */
    public static final String MYSQL_CONNECTOR_CLASS = "io.debezium.connector.mysql.MySqlConnector";

    /**
     * Fully-qualified class name used by the Debezium MariaDB connector.
     */
    public static final String MARIADB_CONNECTOR_CLASS = "io.debezium.connector.mariadb.MariaDbConnector";

    /** Private constructor – this is a static factory class. */
    private DDLParserFactory() {
    }

    /**
     * Returns the appropriate {@link DDLParserService} for the given connector class name.
     *
     * <p>If the connector class name contains "postgres" (case-insensitive) a
     * {@link PostgreSQLDDLParserService} is returned; otherwise a
     * {@link MySQLDDLParserService} is returned (covering MySQL and MariaDB).</p>
     *
     * @param connectorClass the fully-qualified Debezium connector class name
     *                       (e.g. value of the {@code connector.class} property).
     * @param writer         the {@link BaseDbWriter} used for DDL execution.
     * @param config         the ClickHouse sink connector configuration.
     * @param databaseName   the destination ClickHouse database name.
     * @return the appropriate {@link DDLParserService} implementation.
     */
    public static DDLParserService getParser(String connectorClass,
                                             BaseDbWriter writer,
                                             ClickHouseSinkConnectorConfig config,
                                             String databaseName) {
        if (isPostgresConnector(connectorClass)) {
            log.info("DDLParserFactory: selecting PostgreSQLDDLParserService for connector class '{}'", connectorClass);
            return new PostgreSQLDDLParserService(writer, config, databaseName);
        } else {
            log.info("DDLParserFactory: selecting MySQLDDLParserService for connector class '{}'", connectorClass);
            return new MySQLDDLParserService(writer, config, databaseName);
        }
    }

    /**
     * Convenience overload that derives the connector class from the
     * {@code connector.class} key in the provided {@link java.util.Properties}.
     *
     * @param props        the connector properties (must contain {@code connector.class}).
     * @param writer       the {@link BaseDbWriter} used for DDL execution.
     * @param config       the ClickHouse sink connector configuration.
     * @param databaseName the destination ClickHouse database name.
     * @return the appropriate {@link DDLParserService} implementation.
     */
    public static DDLParserService getParser(java.util.Properties props,
                                             BaseDbWriter writer,
                                             ClickHouseSinkConnectorConfig config,
                                             String databaseName) {
        String connectorClass = props != null ? props.getProperty("connector.class", "") : "";
        return getParser(connectorClass, writer, config, databaseName);
    }

    /**
     * Returns {@code true} if the given connector class name identifies a
     * PostgreSQL connector.
     *
     * @param connectorClass the connector class name.
     * @return {@code true} for PostgreSQL connectors.
     */
    public static boolean isPostgresConnector(String connectorClass) {
        if (connectorClass == null || connectorClass.isEmpty()) {
            return false;
        }
        return connectorClass.toLowerCase().contains("postgres");
    }
}
