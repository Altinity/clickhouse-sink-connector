package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserFactory;
import com.altinity.clickhouse.debezium.embedded.postgres.schema.PostgresSchemaChangeDetector;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Encapsulates PostgreSQL-specific configuration and state that was previously
 * held directly in {@link DebeziumChangeEventCapture}.
 *
 * <p>By extracting these fields and their initialisation logic into a dedicated
 * class, {@code DebeziumChangeEventCapture} is kept focused on CDC orchestration
 * while PostgreSQL-specific concerns (schema prefix, database suffix, schema
 * change detection) are managed here.
 */
public class PostgresConnectorConfig {

    private static final Logger log = LogManager.getLogger(PostgresConnectorConfig.class);

    /**
     * Schema drift detector for PostgreSQL connectors.
     * {@code null} when the connector is not PostgreSQL (MySQL/MariaDB).
     */
    private PostgresSchemaChangeDetector postgresSchemaChangeDetector;

    /**
     * When {@code true}, ClickHouse table names include the PostgreSQL schema
     * as a prefix: {@code __<schema>__<table>}.
     */
    private final boolean schemaPrefixEnabled;

    /**
     * When {@code true}, the resolved {@code clickhouse.common.schema.template}
     * is appended to the ClickHouse database name as a suffix.
     */
    private final boolean databaseSchemaSuffix;

    /**
     * Shared template string with a {@code {{ schema }}} placeholder.
     * Used by both table-prefix and database-suffix features when enabled.
     * Empty string means disabled / use hardcoded format.
     */
    private final String commonSchemaTemplate;

    /**
     * Static prefix prepended to every ClickHouse database name.
     * Only alphanumeric characters and underscores are allowed.
     * Empty string (default) means disabled.
     */
    private final String commonDatabasePrefix;

    /**
     * Creates a new {@code PostgresConnectorConfig} by reading the relevant
     * properties from the supplied connector configuration.
     *
     * @param props connector properties
     * @throws IllegalArgumentException if {@code commonDatabasePrefix} contains
     *                                  invalid characters
     */
    public PostgresConnectorConfig(Properties props) {
        this.schemaPrefixEnabled = Boolean.parseBoolean(
                props.getProperty(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_SCHEMA_PREFIX.toString(),
                        "false"));

        this.databaseSchemaSuffix = Boolean.parseBoolean(
                props.getProperty(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_SCHEMA_SUFFIX.toString(),
                        "false"));

        this.commonSchemaTemplate = props.getProperty(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_SCHEMA_TEMPLATE.toString(),
                "");

        this.commonDatabasePrefix = props.getProperty(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_DATABASE_PREFIX.toString(),
                "");

        if (!Utils.isValidDatabasePrefix(this.commonDatabasePrefix)) {
            throw new IllegalArgumentException(
                    "clickhouse.common.database.prefix contains invalid characters. "
                    + "Only alphanumeric and underscore allowed: "
                    + this.commonDatabasePrefix);
        }
    }

    // -----------------------------------------------------------------------
    // Schema change detector lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initialises the {@link PostgresSchemaChangeDetector} if the connector
     * class is a PostgreSQL connector.
     *
     * @param props  connector properties (used to detect connector type)
     * @param config the sink connector configuration forwarded to the detector
     * @param writer the ClickHouse writer used by the detector
     */
    public void initSchemaChangeDetector(Properties props,
                                          ClickHouseSinkConnectorConfig config,
                                          BaseDbWriter writer) {
        String connectorClass = props != null
                ? props.getProperty(
                        ClickHouseSinkConnectorConfigVariables.CONNECTOR_CLASS.toString(), "")
                : "";
        if (DDLParserFactory.isPostgresConnector(connectorClass)) {
            this.postgresSchemaChangeDetector =
                    new PostgresSchemaChangeDetector(writer, config);
            log.info("PostgresSchemaChangeDetector initialised for connector class '{}'",
                    connectorClass);

            // Startup alias column reconciliation
            try {
                String pgDbName = props != null
                        ? props.getProperty("database.dbname", "")
                        : "";
                if (pgDbName != null && !pgDbName.isEmpty()) {
                    String chDatabase = pgDbName;
                    chDatabase = Utils.applyDatabasePrefix(chDatabase, this.commonDatabasePrefix);
                    if (this.databaseSchemaSuffix
                            && this.commonSchemaTemplate != null
                            && !this.commonSchemaTemplate.isEmpty()) {
                        chDatabase = Utils.applyDatabaseSchemaSuffix(
                                chDatabase, this.commonSchemaTemplate, "public");
                    }
                    log.info("Startup alias reconciliation: pgDb='{}', chDb='{}'",
                            pgDbName, chDatabase);
                    this.postgresSchemaChangeDetector.ensureAllAliasColumns(pgDbName, chDatabase);
                } else {
                    log.debug("database.dbname not set – skipping startup alias reconciliation");
                }
            } catch (Exception e) {
                log.warn("Startup alias column reconciliation failed – continuing. Cause: {}",
                        e.getMessage(), e);
            }
        } else {
            this.postgresSchemaChangeDetector = null;
            log.debug("PostgresSchemaChangeDetector not activated (connector class: '{}')",
                    connectorClass);
        }
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    /** Returns the schema change detector, or {@code null} if not a PG connector. */
    public PostgresSchemaChangeDetector getSchemaChangeDetector() {
        return postgresSchemaChangeDetector;
    }

    /** Whether schema-prefix mode is enabled for table names. */
    public boolean isSchemaPrefixEnabled() {
        return schemaPrefixEnabled;
    }

    /** Whether database-schema-suffix mode is enabled. */
    public boolean isDatabaseSchemaSuffix() {
        return databaseSchemaSuffix;
    }

    /** The shared schema template string (may be empty). */
    public String getCommonSchemaTemplate() {
        return commonSchemaTemplate;
    }

    /** The static database prefix (may be empty). */
    public String getCommonDatabasePrefix() {
        return commonDatabasePrefix;
    }
}
