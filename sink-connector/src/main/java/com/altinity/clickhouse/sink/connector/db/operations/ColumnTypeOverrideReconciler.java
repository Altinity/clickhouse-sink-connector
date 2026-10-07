package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig.AliasOverrideEntry;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig.DirectOverrideEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 *       the alias column or the alias definition has drifted. When the DDL
 *       is rejected, {@code system.columns} cannot be read, or the alias
 *       column's name is already taken by a column that is not {@code ALIAS}
 *       kind, reconciliation halts the connector exactly as a direct
 *       override mismatch does (spec 08.05 section 3.3.2): no row is
 *       written while ClickHouse does not match the declared override.</li>
 *   <li><b>Direct overrides</b> cause the connector to halt with a detailed
 *       error message when the configured type does not match the actual
 *       column type in ClickHouse, guiding the operator through the
 *       available fix options.</li>
 * </ul>
 *
 * <p>Both kinds are scoped to columns that match the source: an override
 * entry is skipped (no DDL, no halt) when the column it is defined on is not
 * part of this table. A ClickHouse-only column a user added by hand --
 * including one that happens to carry the name a generated ALIAS column
 * would have -- is never inspected or altered.
 *
 * <p>This class is designed to be called <em>after</em> the table has been
 * confirmed to exist (either pre-existing or just created) and
 * <em>before</em> any data insertion begins.
 */
public class ColumnTypeOverrideReconciler {

    private static final Logger log = LogManager.getLogger(ColumnTypeOverrideReconciler.class);

    /** Reads a table's columns; database and table are bound parameters 1 and 2. */
    static final String EXISTING_COLUMNS_QUERY =
            "SELECT name, type, default_kind, default_expression "
                    + "FROM system.columns WHERE database = ? AND table = ?";

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
     *         not match the existing column type, if an ALIAS override
     *         cannot be added or modified, if an existing column occupies a
     *         configured ALIAS column's name without being ALIAS kind, or if
     *         {@code system.columns} cannot be read while overrides are
     *         configured.
     */
    public void reconcile(
            Connection conn,
            String database,
            String tableName,
            String schemaName,
            ColumnTypeOverrideConfig overrideConfig
    ) {
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
     * @throws ColumnTypeOverrideMismatchException if {@code system.columns}
     *         cannot be read. Overrides are already known to be configured at
     *         this point (the caller checks {@code hasOverrides()} first), so
     *         a failure here would otherwise silently skip both the direct
     *         and the ALIAS checks below it -- the same halt direct and ALIAS
     *         mismatches use, rather than a checked exception a caller could
     *         log and continue past.
     */
    private Map<String, ColumnInfo> getExistingColumns(
            Connection conn, String database, String table
    ) {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        // The names are bound, never interpolated (spec 08.05 section 3.3.1): they
        // are replicated identifiers, and a quote in one would make an
        // interpolated literal malformed or change the predicate -- the same
        // rule DBMetadata#getColumnDefaultExpression follows for this table.
        try (PreparedStatement ps = conn.prepareStatement(EXISTING_COLUMNS_QUERY)) {
            ps.setString(1, database);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo info = new ColumnInfo();
                    info.name = rs.getString("name");
                    info.type = rs.getString("type");
                    info.defaultKind = rs.getString("default_kind");
                    info.defaultExpression = rs.getString("default_expression");
                    columns.put(info.name, info);
                }
            }
        } catch (Exception e) {
            throw new ColumnTypeOverrideMismatchException(String.format(
                    "%n%nERROR: Could not read column metadata for table "
                            + "'%s.%s' while reconciling column_type_override.*.%n"
                            + "ClickHouse reported: %s%n%n"
                            + "No row will be written to this table until "
                            + "system.columns can be read again (check "
                            + "connectivity and that the table/database "
                            + "still exist); the connector retries "
                            + "automatically once the cause is fixed.%n",
                    database, table, e.getMessage()), e);
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
     *
     * <p>Scoped to columns that match the source (manager ruling on
     * parity): an entry is skipped entirely, with no DDL attempted and no
     * exception thrown, when the column the ALIAS is derived from
     * ({@link AliasOverrideEntry#getColumn()}) is not a column of this
     * table. That covers a wildcard override that simply does not apply to
     * this table, and guarantees a ClickHouse-only column a user added by
     * hand -- including one that happens to carry the name a generated
     * ALIAS column would have -- is never inspected or altered.
     *
     * @throws ColumnTypeOverrideMismatchException if the {@code ADD}/
     *         {@code MODIFY COLUMN ... ALIAS} DDL is rejected, or if the
     *         alias column's name is already taken by a column that is not
     *         {@code ALIAS} kind.
     */
    private void reconcileAliasOverrides(
            Connection conn, String database, String tableName,
            String schemaName, ColumnTypeOverrideConfig overrideConfig,
            Map<String, ColumnInfo> existingColumns
    ) {
        List<AliasOverrideEntry> aliasOverrides =
                overrideConfig.getAliasOverrides(schemaName, tableName);

        for (AliasOverrideEntry ao : aliasOverrides) {
            if (!existingColumns.containsKey(ao.getColumn())) {
                // The source column this alias is derived from is not a
                // column of this table -- the override does not apply here.
                // Nothing the connector created is at stake, so this is not
                // touched and does not halt.
                log.debug("Skipping ALIAS override for {}.{}: source column "
                                + "'{}' is not a column of this table",
                        database, tableName, ao.getColumn());
                continue;
            }

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
                } catch (Exception e) {
                    throw new ColumnTypeOverrideMismatchException(
                            buildAliasDdlFailureMessage(database, tableName,
                                    ao.getColumn(), "add", alterSql, e), e);
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
                    } catch (Exception e) {
                        throw new ColumnTypeOverrideMismatchException(
                                buildAliasDdlFailureMessage(database,
                                        tableName, ao.getColumn(), "modify",
                                        alterSql, e), e);
                    }
                } else {
                    log.debug("ALIAS column {} already matches config, "
                            + "no action needed", aliasColName);
                }
            } else {
                // The source column exists on this table (checked above),
                // so this override is in scope, but the name its ALIAS
                // column must have is already taken by a column that is not
                // ALIAS kind. ClickHouse cannot have two columns of the same
                // name, so the override can never be satisfied without an
                // operator resolving the collision -- the same halt a
                // direct-override mismatch uses, not a silent skip.
                throw new ColumnTypeOverrideMismatchException(
                        buildAliasNameCollisionMessage(database, tableName,
                                aliasColName, ao.getColumn(), existing));
            }
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

    /**
     * Builds a detailed error message for a rejected {@code ADD}/
     * {@code MODIFY COLUMN ... ALIAS} DDL statement.
     */
    private String buildAliasDdlFailureMessage(
            String database, String tableName, String sourceColumn,
            String action, String alterSql, Exception cause
    ) {
        return String.format(
                "%n%nERROR: Could not %s the ALIAS override column for "
                        + "table '%s.%s'.%n%n"
                        + "Source column '%s' is configured with "
                        + "column_type_override.alias.*, which requires:%n"
                        + "  %s%n%n"
                        + "ClickHouse rejected it: %s%n%n"
                        + "No row will be written to this table until the "
                        + "ALIAS column can be %sed. Once the underlying "
                        + "cause is fixed (grant ALTER TABLE, correct the "
                        + "expression or type, free up the column name), "
                        + "the connector retries automatically.%n",
                action, database, tableName, sourceColumn, alterSql,
                cause.getMessage(), action);
    }

    /**
     * Builds a detailed error message for a configured ALIAS override whose
     * target column name is already occupied by a non-ALIAS column.
     */
    private String buildAliasNameCollisionMessage(
            String database, String tableName, String aliasColName,
            String sourceColumn, ColumnInfo existing
    ) {
        String existingKind = (existing.defaultKind == null
                || existing.defaultKind.isEmpty())
                ? "an ordinary column" : existing.defaultKind + " column";
        return String.format(
                "%n%nERROR: Column type override mismatch detected for "
                        + "table '%s.%s'.%n%n"
                        + "Source column '%s' is configured with "
                        + "column_type_override.alias.*, which requires an "
                        + "ALIAS column named '%s'. That name is already "
                        + "taken by %s (type %s) in ClickHouse.%n%n"
                        + "To fix this, you have the following options:%n%n"
                        + "  1. Rename or drop the existing column:%n"
                        // DESTRUCTIVE: the "DROP COLUMN" text below is advisory
                        // only -- a suggested fix command printed for a human
                        // operator to copy and run by hand after judging it
                        // safe for their table. The connector never builds or
                        // executes this (or any) DROP statement itself.
                        + "     ALTER TABLE `%s`.`%s` DROP COLUMN `%s`;%n"
                        + "     Then re-run the connector to add the ALIAS "
                        + "column.%n%n"
                        + "  2. Remove the alias override to leave the "
                        + "existing column alone.%n",
                database, tableName, sourceColumn, aliasColName, existingKind,
                existing.type, database, tableName, aliasColName);
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
