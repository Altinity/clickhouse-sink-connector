package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.kafka.connect.data.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles schema differences between PostgreSQL (reflected via Debezium SourceRecord
 * schema metadata) and ClickHouse (reflected via {@code system.columns}).
 *
 * <p>Supported reconciliation actions:
 * <ol>
 *   <li>ADD COLUMN – new column present in Debezium schema but absent from ClickHouse</li>
 *   <li>Type widening – not yet implemented (logged as a warning, not applied)</li>
 *   <li>DROP COLUMN – not applied automatically; requires explicit configuration</li>
 * </ol>
 *
 * <p>All DDL errors are caught, logged at WARN level, and the connector continues
 * processing so that replication is never halted by a schema-reconciliation failure.
 */
public class PostgresSchemaReconciler {

    private static final Logger log = LogManager.getLogger(PostgresSchemaReconciler.class);

    /** The writer whose JDBC connection is used to execute DDL on ClickHouse. */
    private final BaseDbWriter writer;

    /** ClickHouse sink connector configuration (used for DBMetadata constructor). */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Constructs a PostgresSchemaReconciler.
     *
     * @param writer the {@link BaseDbWriter} connected to ClickHouse
     * @param config the connector configuration
     */
    public PostgresSchemaReconciler(BaseDbWriter writer, ClickHouseSinkConnectorConfig config) {
        this.writer = writer;
        this.config = config;
    }

    /**
     * Generates and executes {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS} statements
     * for every entry in {@code newColumns}.
     *
     * <p>Each column's ClickHouse type is determined by
     * {@link #mapDebeziumTypeToClickHouse(Schema)}.
     *
     * @param database   ClickHouse target database name
     * @param table      ClickHouse target table name
     * @param newColumns map of {@code columnName → Debezium Schema} for columns to add
     */
    public void addMissingColumns(String database, String table, Map<String, Schema> newColumns) {
        if (newColumns == null || newColumns.isEmpty()) {
            return;
        }

        Connection conn = writer.getConnection();
        DBMetadata dbMetadata = new DBMetadata(config);

        for (Map.Entry<String, Schema> entry : newColumns.entrySet()) {
            String columnName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            String chType = mapDebeziumTypeToClickHouse(fieldSchema);

            String ddl = String.format(
                    "%s `%s`.`%s` %s IF NOT EXISTS `%s` %s",
                    ClickHouseDbConstants.ALTER_TABLE, database, table,
                    ClickHouseDbConstants.ALTER_TABLE_ADD_COLUMN, columnName, chType);

            try {
                log.info("Schema drift reconciliation – executing DDL: {}", ddl);
                dbMetadata.executeSystemQuery(conn, ddl);
                log.info("Schema drift reconciliation – DDL executed successfully for column '{}' in {}.{}",
                        columnName, database, table);
            } catch (Exception e) {
                log.warn("Schema drift reconciliation – failed to add column '{}' to {}.{}: {}. " +
                                "Replication will continue; the event may be written without this column.",
                        columnName, database, table, e.getMessage());
            }
        }
    }

    /**
     * Maps a Debezium {@link Schema} (Kafka Connect type) to the equivalent
     * ClickHouse column type string.
     *
     * <p>Delegates to the centralized
     * {@link ClickHouseDataTypeMapper#mapDebeziumSchemaToDDL(Schema)} so that
     * all DDL type-mapping logic lives in one place alongside the existing
     * enum-based mapping in {@code ClickHouseDataTypeMapper}.
     *
     * @param fieldSchema the Debezium/Kafka-Connect field schema
     * @return the ClickHouse column type string (always wrapped in {@code Nullable(…)})
     * @see ClickHouseDataTypeMapper#mapDebeziumSchemaToDDL(Schema)
     */
    String mapDebeziumTypeToClickHouse(Schema fieldSchema) {
        return ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(fieldSchema);
    }

    // -----------------------------------------------------------------------
    // Alias column reconciliation
    // -----------------------------------------------------------------------

    /**
     * Ensures that all configured alias column overrides are present on the
     * specified ClickHouse table.  For each alias override that targets
     * {@code database.schema.table}, this method checks whether the alias
     * column already exists and, if not, adds it via
     * {@code ALTER TABLE … ADD COLUMN}.
     *
     * <p>This is designed to be called on startup or during schema drift
     * reconciliation so that alias overrides configured after a table was
     * initially created are retroactively applied.
     *
     * <p>The {@code schema} parameter may be a ClickHouse database name
     * (e.g. {@code litellm_prod_app__public}); the underlying
     * {@code schemaMatches()} logic will extract the PG schema suffix.
     *
     * @param database the source PostgreSQL database name (e.g. {@code "app"}).
     * @param schema   the schema / CH database name.
     * @param table    the ClickHouse table name.
     */
    public void ensureAliasColumns(String database, String schema, String table) {
        if (config == null) return;

        ColumnTypeOverrideConfig overrideConfig =
                ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
        List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                overrideConfig.getAliasOverrides(database, schema, table);

        if (aliasOverrides.isEmpty()) {
            return;
        }

        // Fetch current column names from ClickHouse
        Set<String> existingColumns = fetchColumnNames(database, table);
        if (existingColumns == null) {
            // Table doesn't exist or error fetching — skip silently
            log.debug("Cannot fetch columns for {}.{} — skipping alias reconciliation", database, table);
            return;
        }

        // Use the schema parameter for CH DDL operations (it may be the CH
        // database name like "litellm_prod_app__public").
        String chDatabase = schema;

        Connection conn = writer.getConnection();
        DBMetadata dbMetadata = new DBMetadata(config);

        for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
            String aliasColName = entry.getAliasColumnName();
            if (existingColumns.contains(aliasColName.toLowerCase())) {
                log.debug("Alias column '{}' already exists in {}.{} — skipping",
                        aliasColName, chDatabase, table);
                continue;
            }

            String ddl = String.format(
                    "%s `%s`.`%s` %s IF NOT EXISTS `%s` %s ALIAS %s",
                    ClickHouseDbConstants.ALTER_TABLE, chDatabase, table,
                    ClickHouseDbConstants.ALTER_TABLE_ADD_COLUMN,
                    aliasColName, entry.getAliasType(), entry.getExpression());

            try {
                log.info("Alias column reconciliation – executing DDL: {}", ddl);
                dbMetadata.executeSystemQuery(conn, ddl);
                log.info("Alias column reconciliation – DDL executed successfully: '{}' in {}.{}",
                        aliasColName, chDatabase, table);
            } catch (Exception e) {
                log.warn("Alias column reconciliation – failed to add alias column '{}' to {}.{}: {}. " +
                                "Replication will continue.",
                        aliasColName, chDatabase, table, e.getMessage());
            }
        }
    }

    /**
     * Ensures alias columns are present on ALL tables that have alias overrides
     * configured.  Iterates all alias override entries from the connector
     * configuration and applies any missing alias columns.
     *
     * <p>Designed to be called once at connector startup.
     *
     * @param database the source PostgreSQL database name (e.g. {@code "app"}).
     * @param schema   the schema / CH database name (e.g.
     *                 {@code "litellm_prod_app__public"}).
     */
    public void ensureAllAliasColumns(String database, String schema) {
        if (config == null) return;

        ColumnTypeOverrideConfig overrideConfig =
                ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());

        // Collect all unique table names from alias overrides
        Set<String> tables = overrideConfig.getAllAliasOverrideTables(database, schema);
        if (tables.isEmpty()) {
            log.debug("No alias overrides configured — skipping startup alias reconciliation");
            return;
        }

        log.info("Startup alias reconciliation: checking {} table(s) for missing alias columns", tables.size());
        for (String table : tables) {
            ensureAliasColumns(database, schema, table);
        }
    }

    /**
     * Fetches the set of column names (lowercased) from a ClickHouse table
     * via {@code system.columns}.
     *
     * @param database the ClickHouse database name.
     * @param table    the ClickHouse table name.
     * @return a set of lowercased column names, or {@code null} if the table
     *         doesn't exist or an error occurs.
     */
    private Set<String> fetchColumnNames(String database, String table) {
        try {
            Connection conn = writer.getConnection();
            String sql = String.format(
                    "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s'",
                    database.replace("'", "\\'"),
                    table.replace("'", "\\'"));

            Set<String> columns = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase());
                }
            }
            return columns.isEmpty() ? null : columns;
        } catch (Exception e) {
            log.warn("Failed to fetch columns for {}.{}: {}", database, table, e.getMessage());
            return null;
        }
    }
}
