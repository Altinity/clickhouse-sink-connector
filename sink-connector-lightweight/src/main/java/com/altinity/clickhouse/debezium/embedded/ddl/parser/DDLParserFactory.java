package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import io.debezium.metadata.ConnectorDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory class that creates the appropriate {@link DDLParserService} implementation
 * based on the source connector type (MySQL/MariaDB or PostgreSQL).
 *
 * <p>Connector identification is delegated to Debezium's
 * {@link ConnectorDescriptor#getIdForConnectorClass(String)} so that adding
 * new connector types does not require maintaining hardcoded fully-qualified
 * class names here.</p>
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

    /** Connector-id value returned by Debezium for PostgreSQL connectors. */
    private static final String POSTGRES_ID = "postgres";

    /** Private constructor – this is a static factory class. */
    private DDLParserFactory() {
    }

    /**
     * Returns the appropriate {@link DDLParserService} for the given connector class name.
     *
     * <p>Uses {@link ConnectorDescriptor#getIdForConnectorClass(String)} to resolve
     * the connector identity.  If the resolved id contains "postgres" a
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
        String connectorClass = props != null
                ? props.getProperty(ClickHouseSinkConnectorConfigVariables.CONNECTOR_CLASS.toString(), "")
                : "";
        return getParser(connectorClass, writer, config, databaseName);
    }

    /**
     * Returns {@code true} if the given connector class name identifies a
     * PostgreSQL connector.
     *
     * <p>Delegates to {@link ConnectorDescriptor#getIdForConnectorClass(String)}
     * for reliable identification rather than relying on substring matching
     * against hardcoded class names.</p>
     *
     * @param connectorClass the fully-qualified connector class name.
     * @return {@code true} for PostgreSQL connectors.
     */
    public static boolean isPostgresConnector(String connectorClass) {
        if (connectorClass == null || connectorClass.isEmpty()) {
            return false;
        }
        try {
            String id = ConnectorDescriptor.getIdForConnectorClass(connectorClass);
            if (id != null) {
                return id.toLowerCase().contains(POSTGRES_ID);
            }
        } catch (RuntimeException e) {
            // ConnectorDescriptor throws RuntimeException for unrecognized connector classes;
            // fall through to substring-based matching.
        }
        // Fallback: if Debezium cannot resolve the class, use simple substring match
        return connectorClass.toLowerCase().contains(POSTGRES_ID);
    }
}
