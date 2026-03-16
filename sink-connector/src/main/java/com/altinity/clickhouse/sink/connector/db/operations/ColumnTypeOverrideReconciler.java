package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig.AliasOverrideEntry;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig.DirectOverrideEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles the column type override configuration against an existing
 * ClickHouse table, ensuring that:
 *
 * <ul>
 *   <li><b>ALIAS overrides</b> are automatically applied via
 *       {@code ALTER TABLE ... ADD/MODIFY COLUMN} when the table is missing
 *       the alias column or the alias definition has drifted.</li>
 *   <li><b>Direct overrides</b> cause the connector to halt with a detailed
 *       error message when the configured type does not match the actual
 *       column type in ClickHouse, guiding the operator through the
 *       available fix options.</li>
 * </ul>
 *
 * <p>This class is designed to be called <em>after</em> the table has been
 * confirmed to exist (either pre-existing or just created) and
 * <em>before</em> any data insertion begins.
 */
public class ColumnTypeOverrideReconciler {

    private static final Logger log = LogManager.getLogger(ColumnTypeOverrideReconciler.class);

    /**
     * Reconciles the override configuration against the existing table
     * schema in ClickHouse.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Retrieve current column metadata from {@code system.columns}.</li>
     *   <li>Check all direct overrides — throw
     *       {@link ColumnTypeOverrideMismatchException} on the first
     *       mismatch.</li>
     *   <li>Reconcile alias overrides — add missing alias columns, modify
     *       existing ones whose type or expression has drifted.</li>
     * </ol>
     *
     * @param conn           an open JDBC connection to the ClickHouse
     *                       database.
     * @param database       the ClickHouse database name.
     * @param tableName      the ClickHouse table name.
     * @param schemaName     the source schema name used for config key
     *                       lookups (e.g. {@code "public"}).
     * @param overrideConfig the parsed column type override configuration.
     * @throws ColumnTypeOverrideMismatchException if a direct override does
     *         not match the existing column type.
     * @throws Exception if a SQL error occurs during reconciliation.
     */
    public void reconcile(
            Connection conn,
            String database,
            String tableName,
            String schemaName,
            ColumnTypeOverrideConfig overrideConfig
    ) throws Exception {
        if (overrideConfig == null || !overrideConfig.hasOverrides()) {
            return;
        }

        // 1. Get existing columns from system.columns
        Map<String, ColumnInfo> existingColumns =
                getExistingColumns(conn, database, tableName);
        if (existingColumns.isEmpty()) {
            // Table doesn't exist or has no columns — nothing to reconcile
            return;
        }

        // 2. Check direct overrides for mismatches
        checkDirectOverrides(database, tableName, schemaName,
                overrideConfig, existingColumns);

        // 3. Reconcile alias overrides (add / modify)
        reconcileAliasOverrides(conn, database, tableName, schemaName,
                overrideConfig, existingColumns);
    }

    // -----------------------------------------------------------------------
    // Column metadata retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieves column metadata from {@code system.columns} for the given
     * database and table.
     *
     * @param conn     the database connection.
     * @param database the ClickHouse database name.
     * @param table    the table name.
     * @return a map of column name → {@link ColumnInfo} preserving insertion
     *         order.
     * @throws Exception if a SQL error occurs.
     */
    private Map<String, ColumnInfo> getExistingColumns(
            Connection conn, String database, String table
    ) throws Exception {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        String sql = String.format(
                "SELECT name, type, default_kind, default_expression "
                        + "FROM system.columns "
                        + "WHERE database = '%s' AND table = '%s'",
                database, table);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ColumnInfo info = new ColumnInfo();
                info.name = rs.getString("name");
                info.type = rs.getString("type");
                info.defaultKind = rs.getString("default_kind");
                info.defaultExpression = rs.getString("default_expression");
                columns.put(info.name, info);
            }
        }
        return columns;
    }

    // -----------------------------------------------------------------------
    // Direct override checking
    // -----------------------------------------------------------------------

    /**
     * Checks every direct override for the given schema/table against the
     * existing column types. Throws on the first mismatch found.
     *
     * @throws ColumnTypeOverrideMismatchException if a mismatch is detected.
     */
    private void checkDirectOverrides(
            String database, String tableName, String schemaName,
            ColumnTypeOverrideConfig overrideConfig,
            Map<String, ColumnInfo> existingColumns
    ) {
        List<DirectOverrideEntry> directOverrides =
                overrideConfig.getDirectOverrides(schemaName, tableName);

        for (DirectOverrideEntry doe : directOverrides) {
            String colName = doe.getColumn();
            ColumnInfo colInfo = existingColumns.get(colName);

            // If the column doesn't exist yet in the table, skip — it will
            // be created with the correct type when the schema evolves.
            if (colInfo == null) {
                continue;
            }

            // Skip ALIAS/MATERIALIZED columns — they are handled separately
            if ("ALIAS".equals(colInfo.defaultKind)
                    || "MATERIALIZED".equals(colInfo.defaultKind)) {
                continue;
            }

            String configuredType = doe.getTargetType();
            String existingBaseType = stripNullable(colInfo.type);
            String configuredBaseType = stripNullable(configuredType);

            if (!existingBaseType.equalsIgnoreCase(configuredBaseType)) {
                throw new ColumnTypeOverrideMismatchException(
                        buildMismatchMessage(database, tableName, colName,
                                configuredType, colInfo.type));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Alias override reconciliation
    // -----------------------------------------------------------------------

    /**
     * Reconciles alias overrides by adding missing alias columns or
     * modifying existing ones whose type or expression has drifted from
     * the config.
     */
    private void reconcileAliasOverrides(
            Connection conn, String database, String tableName,
            String schemaName, ColumnTypeOverrideConfig overrideConfig,
            Map<String, ColumnInfo> existingColumns
    ) throws Exception {
        List<AliasOverrideEntry> aliasOverrides =
                overrideConfig.getAliasOverrides(schemaName, tableName);

        for (AliasOverrideEntry ao : aliasOverrides) {
            String aliasColName = ao.getAliasColumnName();
            ColumnInfo existing = existingColumns.get(aliasColName);

            if (existing == null) {
                // ALIAS column doesn't exist — ADD it
                String alterSql = String.format(
                        "ALTER TABLE `%s`.`%s` ADD COLUMN `%s` %s ALIAS %s",
                        database, tableName, aliasColName,
                        ao.getAliasType(), ao.getExpression());
                log.info("Adding missing ALIAS column: {}", alterSql);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(alterSql);
                }
            } else if ("ALIAS".equals(existing.defaultKind)) {
                // ALIAS column exists — check if type or expression differs
                String existingBaseType = stripNullable(existing.type);
                String configBaseType = stripNullable(ao.getAliasType());
                boolean typeDiffers =
                        !existingBaseType.equalsIgnoreCase(configBaseType);
                boolean exprDiffers = existing.defaultExpression != null
                        && !existing.defaultExpression.trim()
                                .equals(ao.getExpression().trim());

                if (typeDiffers || exprDiffers) {
                    String alterSql = String.format(
                            "ALTER TABLE `%s`.`%s` MODIFY COLUMN `%s` %s ALIAS %s",
                            database, tableName, aliasColName,
                            ao.getAliasType(), ao.getExpression());
                    log.info("Updating ALIAS column (type/expr changed): {}",
                            alterSql);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(alterSql);
                    }
                } else {
                    log.debug("ALIAS column {} already matches config, "
                            + "no action needed", aliasColName);
                }
            }
            // If the column exists but is NOT an ALIAS column, we do not
            // touch it — the operator must resolve manually.
        }
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    /**
     * Strips the {@code Nullable()} wrapper from a ClickHouse type string
     * so that base types can be compared regardless of nullability.
     *
     * @param type the type string, e.g. {@code "Nullable(String)"}.
     * @return the inner type if wrapped, otherwise the original string.
     */
    private String stripNullable(String type) {
        if (type != null
                && type.startsWith("Nullable(")
                && type.endsWith(")")) {
            return type.substring("Nullable(".length(), type.length() - 1);
        }
        return type;
    }

    /**
     * Builds a detailed, human-readable error message for a direct override
     * mismatch, including concrete fix instructions.
     */
    private String buildMismatchMessage(
            String database, String tableName, String colName,
            String configuredType, String actualType
    ) {
        return String.format(
                "%n%nERROR: Column type override mismatch detected for "
                        + "table '%s.%s'.%n%n"
                        + "Column '%s':%n"
                        + "  - Configured override type: %s%n"
                        + "  - Actual ClickHouse type:   %s%n%n"
                        + "The table already exists with a different column "
                        + "type than your override config specifies.%n"
                        + "To fix this, you have the following options:%n%n"
                        + "  1. DROP and recreate the table:%n"
                        + "     DROP TABLE `%s`.`%s`;%n"
                        + "     Then re-run the connector to create it with "
                        + "the correct type.%n%n"
                        + "  2. ALTER the column type manually (if safe):%n"
                        + "     ALTER TABLE `%s`.`%s` MODIFY COLUMN `%s` "
                        + "Nullable(%s);%n"
                        + "     WARNING: This may fail if existing data "
                        + "cannot be converted.%n%n"
                        + "  3. Update your override config to match the "
                        + "existing table:%n"
                        + "     Change column_type_override.direct.*.%s.%s=%s"
                        + "%n%n"
                        + "  4. Remove the direct override to use the "
                        + "default type mapping.%n",
                database, tableName,
                colName, configuredType, actualType,
                database, tableName,
                database, tableName, colName, configuredType,
                tableName, colName,
                stripNullable(actualType));
    }

    // -----------------------------------------------------------------------
    // Inner class
    // -----------------------------------------------------------------------

    /**
     * Holds metadata for a single column as retrieved from
     * {@code system.columns}.
     */
    static class ColumnInfo {
        String name;
        String type;
        String defaultKind;
        String defaultExpression;
    }
}
