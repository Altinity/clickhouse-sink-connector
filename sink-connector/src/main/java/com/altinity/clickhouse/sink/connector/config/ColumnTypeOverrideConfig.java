package com.altinity.clickhouse.sink.connector.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses and provides access to column type override configuration for both
 * <b>direct</b> overrides (replacing the default ClickHouse column type) and
 * <b>alias</b> overrides (adding a companion ALIAS column with a user-defined
 * expression).
 *
 * <h3>Configuration format (Java flat properties)</h3>
 * <pre>
 * # Direct overrides  (4-part key — preferred)
 * column_type_override.direct.<database>.<schema>.<table>.<column>=<CHType>
 * column_type_override.direct.mydb.public.events.created_at=DateTime64(3)
 *
 * # Direct overrides  (3-part key — backward compatible, database defaults to *)
 * column_type_override.direct.<schema>.<table>.<column>=<CHType>
 * column_type_override.direct.public.events.created_at=DateTime64(3)
 * column_type_override.direct.*.*.status_code=String
 *
 * # Alias overrides  (4-part key — preferred)
 * column_type_override.alias.<database>.<schema>.<table>.<column>=<CHType>|<expression>
 * column_type_override.alias.app.public.events.created_at=DateTime64(3)|parseDateTime64BestEffort(created_at)
 *
 * # Alias overrides  (3-part key — backward compatible)
 * column_type_override.alias.<schema>.<table>.<column>=<CHType>|<expression>
 * column_type_override.alias.public.events.created_at=DateTime64(3)|parseDateTime64BestEffort(created_at)
 * </pre>
 *
 * <h3>Matching precedence for direct overrides</h3>
 * <ol>
 *   <li>Exact match: {@code database.schema.table.column}</li>
 *   <li>Wildcard database: {@code *.schema.table.column}</li>
 *   <li>Wildcard database+table: {@code *.schema.*.column}</li>
 *   <li>Wildcard database+schema+table: {@code *.*.*.column}</li>
 * </ol>
 */
public class ColumnTypeOverrideConfig {

    private static final Logger log = LogManager.getLogger(ColumnTypeOverrideConfig.class);

    /** Prefix for direct override properties. */
    public static final String DIRECT_PREFIX = "column_type_override.direct.";

    /** Prefix for alias override properties. */
    public static final String ALIAS_PREFIX = "column_type_override.alias.";

    /**
     * Direct overrides keyed by {@code "database.schema.table.column"} (all lower-cased).
     * Value is the target ClickHouse type string.
     */
    private final Map<String, String> directOverrides;

    /**
     * Alias overrides keyed by {@code "database.schema.table.column"} (all lower-cased).
     * Value is the {@link AliasOverrideEntry}.
     */
    private final Map<String, AliasOverrideEntry> aliasOverrides;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Constructs a new config with the given pre-parsed maps.
     *
     * @param directOverrides direct override map.
     * @param aliasOverrides  alias override map.
     */
    private ColumnTypeOverrideConfig(Map<String, String> directOverrides,
                                     Map<String, AliasOverrideEntry> aliasOverrides) {
        this.directOverrides = Collections.unmodifiableMap(directOverrides);
        this.aliasOverrides = Collections.unmodifiableMap(aliasOverrides);
    }

    /**
     * Returns an empty config instance (no overrides configured).
     *
     * @return an empty {@link ColumnTypeOverrideConfig}.
     */
    public static ColumnTypeOverrideConfig empty() {
        return new ColumnTypeOverrideConfig(
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Parses column type override properties from a flat properties map
     * (typically obtained via {@code config.originalsStrings()}).
     *
     * @param props the full connector configuration properties.
     * @return a parsed {@link ColumnTypeOverrideConfig}.
     */
    public static ColumnTypeOverrideConfig fromProperties(Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return empty();
        }

        Map<String, String> direct = new HashMap<>();
        Map<String, AliasOverrideEntry> alias = new HashMap<>();

        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key.startsWith(DIRECT_PREFIX)) {
                parseDirectEntry(key, value, direct);
            } else if (key.startsWith(ALIAS_PREFIX)) {
                parseAliasEntry(key, value, alias);
            }
        }

        if (!direct.isEmpty() || !alias.isEmpty()) {
            log.info("ColumnTypeOverrideConfig loaded: {} direct override(s), {} alias override(s)",
                    direct.size(), alias.size());
        }

        return new ColumnTypeOverrideConfig(direct, alias);
    }

    // -----------------------------------------------------------------------
    // Direct override lookup
    // -----------------------------------------------------------------------

    /**
     * Looks up a direct type override for the given database, schema, table,
     * and column.
     *
     * <p>Matching precedence:
     * <ol>
     *   <li>Exact: {@code database.schema.table.column}</li>
     *   <li>Wildcard database: {@code *.schema.table.column}</li>
     *   <li>Wildcard database+table: {@code *.schema.*.column}</li>
     *   <li>Wildcard database+schema+table: {@code *.*.*.column}</li>
     * </ol>
     *
     * <p>Schema matching also supports CH database suffix matching: when the
     * caller passes a CH database name like {@code prefix__public}, the suffix
     * {@code public} is extracted and compared against config entry schemas.
     *
     * @param database the source database name (e.g. {@code "app"}).
     * @param schema   the source schema name (e.g. {@code "public"}).
     * @param table    the source table name.
     * @param column   the column name.
     * @return the overridden ClickHouse type, or empty if no override matches.
     */
    public Optional<String> getDirectOverride(String database, String schema,
                                              String table, String column) {
        if (directOverrides.isEmpty()) {
            return Optional.empty();
        }

        String dbNorm = normalize(database);
        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);
        String columnNorm = normalize(column);

        // 1. Exact match: database.schema.table.column
        String exactKey = dbNorm + "." + schemaNorm + "." + tableNorm + "." + columnNorm;
        if (directOverrides.containsKey(exactKey)) {
            return Optional.of(directOverrides.get(exactKey));
        }

        // 2. Wildcard database: *.schema.table.column
        String dbWildKey = "*." + schemaNorm + "." + tableNorm + "." + columnNorm;
        if (directOverrides.containsKey(dbWildKey)) {
            return Optional.of(directOverrides.get(dbWildKey));
        }

        // 3. CH database suffix match for schema: extract PG schema from
        //    "prefix__schema" and try both exact and wildcard-db keys.
        int dblUnderscore = schemaNorm.lastIndexOf("__");
        if (dblUnderscore >= 0 && dblUnderscore < schemaNorm.length() - 2) {
            String schemaSuffix = schemaNorm.substring(dblUnderscore + 2);
            String suffixExactKey = dbNorm + "." + schemaSuffix + "." + tableNorm + "." + columnNorm;
            if (directOverrides.containsKey(suffixExactKey)) {
                return Optional.of(directOverrides.get(suffixExactKey));
            }
            String suffixWildDbKey = "*." + schemaSuffix + "." + tableNorm + "." + columnNorm;
            if (directOverrides.containsKey(suffixWildDbKey)) {
                return Optional.of(directOverrides.get(suffixWildDbKey));
            }
            // Also try wildcard table with the extracted suffix
            String suffixWildcardTableKey = "*." + schemaSuffix + ".*." + columnNorm;
            if (directOverrides.containsKey(suffixWildcardTableKey)) {
                return Optional.of(directOverrides.get(suffixWildcardTableKey));
            }
        }

        // 4. Wildcard database+table: *.schema.*.column
        String wildcardTableKey = "*." + schemaNorm + ".*." + columnNorm;
        if (directOverrides.containsKey(wildcardTableKey)) {
            return Optional.of(directOverrides.get(wildcardTableKey));
        }

        // 5. Wildcard database+schema+table: *.*.*.column
        String wildcardAllKey = "*.*.*." + columnNorm;
        if (directOverrides.containsKey(wildcardAllKey)) {
            return Optional.of(directOverrides.get(wildcardAllKey));
        }

        return Optional.empty();
    }

    /**
     * Backward-compatible 3-arg overload — delegates to
     * {@link #getDirectOverride(String, String, String, String)} with
     * wildcard database.
     *
     * @param schema the source schema name (e.g. {@code "public"}).
     * @param table  the source table name.
     * @param column the column name.
     * @return the overridden ClickHouse type, or empty if no override matches.
     */
    public Optional<String> getDirectOverride(String schema, String table, String column) {
        return getDirectOverride("*", schema, table, column);
    }

    /**
     * Returns all direct overrides matching the given database, schema and
     * table.
     *
     * <p>This method iterates all stored direct override keys and returns
     * entries whose database, schema and table components match the given
     * values (including wildcard matches and CH database suffix matching).
     *
     * @param database the source database name (e.g. {@code "app"}).
     * @param schema   the source schema name (e.g. {@code "public"}).
     * @param table    the source table name.
     * @return a list of matching {@link DirectOverrideEntry} instances
     *         (never null).
     */
    public List<DirectOverrideEntry> getDirectOverrides(String database, String schema,
                                                        String table) {
        if (directOverrides.isEmpty()) {
            return Collections.emptyList();
        }

        String dbNorm = normalize(database);
        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);

        List<DirectOverrideEntry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : directOverrides.entrySet()) {
            String key = entry.getKey();
            // key format: database.schema.table.column
            String[] parts = key.split("\\.", 4);
            if (parts.length != 4) continue;

            String entryDb = parts[0];
            String entrySchema = parts[1];
            String entryTable = parts[2];
            String entryColumn = parts[3];

            boolean dbMatch = databaseMatches(entryDb, dbNorm);
            boolean schemaMatch = schemaMatches(entrySchema, schemaNorm);
            boolean tableMatch = "*".equals(entryTable)
                    || entryTable.equals(tableNorm);

            if (dbMatch && schemaMatch && tableMatch) {
                result.add(new DirectOverrideEntry(
                        entryColumn, entry.getValue()));
            }
        }

        return result;
    }

    /**
     * Backward-compatible 2-arg overload — delegates to
     * {@link #getDirectOverrides(String, String, String)} with wildcard
     * database.
     *
     * @param schema the source schema name (e.g. {@code "public"}).
     * @param table  the source table name.
     * @return a list of matching {@link DirectOverrideEntry} instances.
     */
    public List<DirectOverrideEntry> getDirectOverrides(String schema, String table) {
        return getDirectOverrides("*", schema, table);
    }

    // -----------------------------------------------------------------------
    // Alias override lookup
    // -----------------------------------------------------------------------

    /**
     * Returns all alias overrides matching the given database, schema and
     * table.
     *
     * <p>Matching includes exact entries, wildcard entries, and CH database
     * suffix matching for the schema component.
     *
     * @param database the source database name (e.g. {@code "app"}).
     * @param schema   the source schema name.
     * @param table    the source table name.
     * @return a list of matching {@link AliasOverrideEntry} instances (never null).
     */
    public List<AliasOverrideEntry> getAliasOverrides(String database, String schema,
                                                      String table) {
        if (aliasOverrides.isEmpty()) {
            return Collections.emptyList();
        }

        String dbNorm = normalize(database);
        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);

        List<AliasOverrideEntry> result = new ArrayList<>();
        for (Map.Entry<String, AliasOverrideEntry> entry : aliasOverrides.entrySet()) {
            AliasOverrideEntry aliasEntry = entry.getValue();

            String entryDb = aliasEntry.getDatabase();
            String entrySchema = aliasEntry.getSchema();
            String entryTable = aliasEntry.getTable();

            boolean dbMatch = databaseMatches(entryDb, dbNorm);
            boolean schemaMatch = schemaMatches(entrySchema, schemaNorm);
            boolean tableMatch = "*".equals(entryTable) || entryTable.equals(tableNorm);

            if (dbMatch && schemaMatch && tableMatch) {
                result.add(aliasEntry);
            }
        }

        return result;
    }

    /**
     * Backward-compatible 2-arg overload — delegates to
     * {@link #getAliasOverrides(String, String, String)} with wildcard
     * database.
     *
     * @param schema the source schema name.
     * @param table  the source table name.
     * @return a list of matching {@link AliasOverrideEntry} instances.
     */
    public List<AliasOverrideEntry> getAliasOverrides(String schema, String table) {
        return getAliasOverrides("*", schema, table);
    }

    /**
     * Returns {@code true} if this config has any overrides configured.
     *
     * @return {@code true} when at least one override is defined.
     */
    public boolean hasOverrides() {
        return !directOverrides.isEmpty() || !aliasOverrides.isEmpty();
    }

    /**
     * Returns all unique table names that have alias overrides matching the
     * given database name.  Used by startup reconciliation to discover which
     * tables need alias columns applied.
     *
     * <p>The returned table names are the raw (normalized) table names from
     * the config entries, suitable for use with
     * {@link #getAliasOverrides(String, String, String)}.
     *
     * @param database the source database name (e.g. {@code "app"}).
     * @param schema   the schema name (may be a CH database name like
     *                 {@code prefix__schema}).
     * @return a set of table names with matching alias overrides (never null).
     */
    public Set<String> getAllAliasOverrideTables(String database, String schema) {
        if (aliasOverrides.isEmpty()) {
            return Collections.emptySet();
        }

        String dbNorm = normalize(database);
        String schemaNorm = normalize(schema);
        Set<String> tables = new HashSet<>();

        for (AliasOverrideEntry entry : aliasOverrides.values()) {
            boolean dbMatch = databaseMatches(entry.getDatabase(), dbNorm);
            boolean schemaMatch = schemaMatches(entry.getSchema(), schemaNorm);
            if (dbMatch && schemaMatch) {
                tables.add(entry.getTable());
            }
        }
        return tables;
    }

    /**
     * Backward-compatible 1-arg overload — delegates to
     * {@link #getAllAliasOverrideTables(String, String)} with wildcard
     * database.
     *
     * @param schema the schema name (may be a CH database name).
     * @return a set of table names with matching alias overrides.
     */
    public Set<String> getAllAliasOverrideTables(String schema) {
        return getAllAliasOverrideTables("*", schema);
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    /**
     * Parses a direct override property entry.
     *
     * <p>Key format (4-part):
     * {@code column_type_override.direct.<database>.<schema>.<table>.<column>}
     * <br>Key format (3-part, backward compat):
     * {@code column_type_override.direct.<schema>.<table>.<column>}
     *
     * @param key    the full property key.
     * @param value  the ClickHouse type string.
     * @param target the map to populate.
     */
    private static void parseDirectEntry(String key, String value,
                                         Map<String, String> target) {
        String suffix = key.substring(DIRECT_PREFIX.length());
        String[] parts = splitQualifiedKey(suffix);
        if (parts == null) {
            log.warn("Ignoring malformed direct override key: {}", key);
            return;
        }
        String normalizedKey = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        target.put(normalizedKey, value.trim());
    }

    /**
     * Parses an alias override property entry.
     *
     * <p>Key format (4-part):
     * {@code column_type_override.alias.<database>.<schema>.<table>.<column>}
     * <br>Key format (3-part, backward compat):
     * {@code column_type_override.alias.<schema>.<table>.<column>}
     * <br>Value format: {@code <CHType>|<expression>}
     *
     * @param key    the full property key.
     * @param value  the alias type and expression separated by {@code |}.
     * @param target the map to populate.
     */
    private static void parseAliasEntry(String key, String value,
                                        Map<String, AliasOverrideEntry> target) {
        String suffix = key.substring(ALIAS_PREFIX.length());
        String[] parts = splitQualifiedKey(suffix);
        if (parts == null) {
            log.warn("Ignoring malformed alias override key: {}", key);
            return;
        }

        int pipeIdx = value.indexOf('|');
        if (pipeIdx < 0) {
            log.warn("Ignoring alias override with missing '|' separator: {}={}", key, value);
            return;
        }

        String aliasType = value.substring(0, pipeIdx).trim();
        String expression = value.substring(pipeIdx + 1).trim();

        if (aliasType.isEmpty() || expression.isEmpty()) {
            log.warn("Ignoring alias override with empty type or expression: {}={}", key, value);
            return;
        }

        String database = parts[0];
        String schema = parts[1];
        String table = parts[2];
        String column = parts[3];
        String normalizedKey = database + "." + schema + "." + table + "." + column;

        AliasOverrideEntry entry = new AliasOverrideEntry(
                database, schema, table, column, aliasType, expression);
        target.put(normalizedKey, entry);
    }

    /**
     * Splits a qualified key suffix into exactly four normalised parts
     * {@code [database, schema, table, column]}.
     *
     * <p>Supports two formats:
     * <ul>
     *   <li><b>4-part</b>: {@code database.schema.table.column}</li>
     *   <li><b>3-part</b> (backward compat): {@code schema.table.column}
     *       — database defaults to {@code *}</li>
     * </ul>
     *
     * <p>Handles the special case of wildcard {@code *}.
     *
     * @param suffix the key suffix after the prefix has been stripped.
     * @return a 4-element array {@code [database, schema, table, column]},
     *         or {@code null} if the format is invalid.
     */
    static String[] splitQualifiedKey(String suffix) {
        if (suffix == null || suffix.isEmpty()) return null;

        String[] rawParts = suffix.split("\\.");
        String[] parts;

        if (rawParts.length == 4) {
            // 4-part: database.schema.table.column
            parts = rawParts;
        } else if (rawParts.length == 3) {
            // 3-part (backward compat): schema.table.column → database = *
            parts = new String[] { "*", rawParts[0], rawParts[1], rawParts[2] };
        } else {
            return null;
        }

        // Normalize each part: lowercase unless it's a wildcard
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (!"*".equals(parts[i])) {
                parts[i] = parts[i].toLowerCase();
            }
        }

        // Column name (parts[3]) must not be empty
        if (parts[3].isEmpty()) return null;

        return parts;
    }

    /**
     * Normalizes a name for lookup: trims, lowercases, and handles null.
     *
     * @param name the name to normalize.
     * @return the normalized name.
     */
    private static String normalize(String name) {
        if (name == null || name.isEmpty()) return "*";
        return name.trim().toLowerCase();
    }

    /**
     * Checks whether a database name from the config entry matches the given
     * lookup database name.
     *
     * <p>Matching rules (after normalization):
     * <ol>
     *   <li>Wildcard {@code *} matches everything.</li>
     *   <li>Exact match (e.g. {@code app} == {@code app}).</li>
     * </ol>
     *
     * @param entryDb  the normalized database from the config entry.
     * @param lookupDb the normalized database passed by the caller.
     * @return {@code true} if the entry database matches the lookup database.
     */
    private static boolean databaseMatches(String entryDb, String lookupDb) {
        if ("*".equals(entryDb)) return true;
        if ("*".equals(lookupDb)) return true;
        return entryDb.equals(lookupDb);
    }

    /**
     * Checks whether a schema name from the config entry matches the given
     * lookup schema name.
     *
     * <p>Matching rules (after normalization):
     * <ol>
     *   <li>Wildcard {@code *} matches everything.</li>
     *   <li>Exact match (e.g. {@code public} == {@code public}).</li>
     *   <li>CH database suffix match: when the connector uses
     *       {@code clickhouse.database.schema.suffix=true}, the CH database
     *       name follows the pattern {@code <prefix>__<pgSchema>}.  If the
     *       lookup schema contains {@code __}, the PG schema suffix is
     *       extracted and compared to the entry schema.  For example,
     *       lookup schema {@code litellm_prod_app__public} will match config
     *       entry schema {@code public}.</li>
     * </ol>
     *
     * @param entrySchema  the normalized schema from the config entry.
     * @param lookupSchema the normalized schema passed by the caller (may be
     *                     a CH database name).
     * @return {@code true} if the entry schema matches the lookup schema.
     */
    private static boolean schemaMatches(String entrySchema, String lookupSchema) {
        if ("*".equals(entrySchema)) return true;
        if (entrySchema.equals(lookupSchema)) return true;

        // CH database suffix match: extract PG schema from "prefix__schema"
        int dblUnderscore = lookupSchema.lastIndexOf("__");
        if (dblUnderscore >= 0 && dblUnderscore < lookupSchema.length() - 2) {
            String suffix = lookupSchema.substring(dblUnderscore + 2);
            if (entrySchema.equals(suffix)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Type name normalization
    // -----------------------------------------------------------------------

    /**
     * Normalizes a ClickHouse type name for use in ALIAS column name
     * generation. Lowercases, replaces all non-alphanumeric characters with
     * underscores, collapses consecutive underscores, and ensures a trailing
     * underscore.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code DateTime64(3)} → {@code datetime64_3_}</li>
     *   <li>{@code Float64} → {@code float64_}</li>
     *   <li>{@code Decimal(10, 2)} → {@code decimal_10_2_}</li>
     * </ul>
     *
     * @param chType the ClickHouse type string.
     * @return the normalized type name suitable for embedding in a column name.
     */
    public static String normalizeTypeName(String chType) {
        if (chType == null || chType.isEmpty()) return "_";
        String result = chType.toLowerCase().replaceAll("[^a-z0-9]", "_");
        // Collapse consecutive underscores
        result = result.replaceAll("_+", "_");
        // Ensure trailing underscore
        if (!result.endsWith("_")) {
            result = result + "_";
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Inner class: AliasOverrideEntry
    // -----------------------------------------------------------------------

    /**
     * Represents a single alias column override, consisting of the source
     * column name, the alias column ClickHouse type, and the expression used
     * to compute the alias value.
     */
    public static class AliasOverrideEntry {

        private final String database;
        private final String schema;
        private final String table;
        private final String column;
        private final String aliasType;
        private final String expression;

        /**
         * Constructs a new alias override entry.
         *
         * @param database   the source database name (normalized).
         * @param schema     the source schema name (normalized).
         * @param table      the source table name (normalized).
         * @param column     the source column name.
         * @param aliasType  the ClickHouse type for the ALIAS column.
         * @param expression the ClickHouse expression for the ALIAS column.
         */
        public AliasOverrideEntry(String database, String schema, String table,
                                  String column, String aliasType, String expression) {
            this.database = database;
            this.schema = schema;
            this.table = table;
            this.column = column;
            this.aliasType = aliasType;
            this.expression = expression;
        }

        /**
         * Backward-compatible constructor without database (defaults to {@code *}).
         *
         * @param schema     the source schema name (normalized).
         * @param table      the source table name (normalized).
         * @param column     the source column name.
         * @param aliasType  the ClickHouse type for the ALIAS column.
         * @param expression the ClickHouse expression for the ALIAS column.
         */
        public AliasOverrideEntry(String schema, String table, String column,
                                  String aliasType, String expression) {
            this("*", schema, table, column, aliasType, expression);
        }

        /** @return the source database name. */
        public String getDatabase() { return database; }

        /** @return the source schema name. */
        public String getSchema() { return schema; }

        /** @return the source table name. */
        public String getTable() { return table; }

        /** @return the source column name. */
        public String getColumn() { return column; }

        /** @return the ClickHouse type for the ALIAS column. */
        public String getAliasType() { return aliasType; }

        /** @return the ClickHouse expression for the ALIAS column. */
        public String getExpression() { return expression; }

        /**
         * Returns the generated ALIAS column name following the convention:
         * {@code {column}_{normalizedType}}.
         *
         * <p>Example: for column {@code created_at} with alias type
         * {@code DateTime64(3)}, returns {@code created_at_datetime64_3_}.
         *
         * @return the ALIAS column name.
         */
        public String getAliasColumnName() {
            return column + "_" + normalizeTypeName(aliasType);
        }

        @Override
        public String toString() {
            return "AliasOverrideEntry{"
                    + "database='" + database + '\''
                    + ", schema='" + schema + '\''
                    + ", table='" + table + '\''
                    + ", column='" + column + '\''
                    + ", aliasType='" + aliasType + '\''
                    + ", expression='" + expression + '\''
                    + ", aliasColumnName='" + getAliasColumnName() + '\''
                    + '}';
        }
    }

    // -----------------------------------------------------------------------
    // Inner class: DirectOverrideEntry
    // -----------------------------------------------------------------------

    /**
     * Represents a single direct column type override, consisting of the
     * column name and the target ClickHouse type.
     */
    public static class DirectOverrideEntry {

        private final String column;
        private final String targetType;

        /**
         * Constructs a new direct override entry.
         *
         * @param column     the column name.
         * @param targetType the target ClickHouse type.
         */
        public DirectOverrideEntry(String column, String targetType) {
            this.column = column;
            this.targetType = targetType;
        }

        /** @return the column name. */
        public String getColumn() { return column; }

        /** @return the target ClickHouse type. */
        public String getTargetType() { return targetType; }

        @Override
        public String toString() {
            return "DirectOverrideEntry{"
                    + "column='" + column + '\''
                    + ", targetType='" + targetType + '\''
                    + '}';
        }
    }
}
