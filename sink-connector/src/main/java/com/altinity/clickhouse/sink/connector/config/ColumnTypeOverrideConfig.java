package com.altinity.clickhouse.sink.connector.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses and provides access to column type override configuration for both
 * <b>direct</b> overrides (replacing the default ClickHouse column type) and
 * <b>alias</b> overrides (adding a companion ALIAS column with a user-defined
 * expression).
 *
 * <h3>Configuration format (Java flat properties)</h3>
 * <pre>
 * # Direct overrides
 * column_type_override.direct.<schema>.<table>.<column>=<CHType>
 * column_type_override.direct.public.events.created_at=DateTime64(3)
 * column_type_override.direct.*.*.status_code=String
 *
 * # Alias overrides
 * column_type_override.alias.<schema>.<table>.<column>=<CHType>|<expression>
 * column_type_override.alias.public.events.created_at=DateTime64(3)|parseDateTime64BestEffort(created_at)
 * </pre>
 *
 * <h3>Matching precedence for direct overrides</h3>
 * <ol>
 *   <li>Exact match: {@code schema.table.column}</li>
 *   <li>Wildcard table: {@code schema.*.column}</li>
 *   <li>Wildcard schema+table: {@code *.*.column}</li>
 * </ol>
 */
public class ColumnTypeOverrideConfig {

    private static final Logger log = LogManager.getLogger(ColumnTypeOverrideConfig.class);

    /** Prefix for direct override properties. */
    public static final String DIRECT_PREFIX = "column_type_override.direct.";

    /** Prefix for alias override properties. */
    public static final String ALIAS_PREFIX = "column_type_override.alias.";

    /**
     * Direct overrides keyed by {@code "schema.table.column"} (all lower-cased).
     * Value is the target ClickHouse type string.
     */
    private final Map<String, String> directOverrides;

    /**
     * Alias overrides keyed by {@code "schema.table.column"} (all lower-cased).
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
     * Looks up a direct type override for the given schema, table, and column.
     *
     * <p>Matching precedence:
     * <ol>
     *   <li>Exact: {@code schema.table.column}</li>
     *   <li>Wildcard table: {@code schema.*.column}</li>
     *   <li>Wildcard schema+table: {@code *.*.column}</li>
     * </ol>
     *
     * @param schema the source schema name (e.g. {@code "public"}).
     * @param table  the source table name.
     * @param column the column name.
     * @return the overridden ClickHouse type, or empty if no override matches.
     */
    public Optional<String> getDirectOverride(String schema, String table, String column) {
        if (directOverrides.isEmpty()) {
            return Optional.empty();
        }

        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);
        String columnNorm = normalize(column);

        // 1. Exact match
        String exactKey = schemaNorm + "." + tableNorm + "." + columnNorm;
        if (directOverrides.containsKey(exactKey)) {
            return Optional.of(directOverrides.get(exactKey));
        }

        // 2. Wildcard table: schema.*.column
        String wildcardTableKey = schemaNorm + ".*." + columnNorm;
        if (directOverrides.containsKey(wildcardTableKey)) {
            return Optional.of(directOverrides.get(wildcardTableKey));
        }

        // 3. Wildcard schema+table: *.*.column
        String wildcardAllKey = "*.*." + columnNorm;
        if (directOverrides.containsKey(wildcardAllKey)) {
            return Optional.of(directOverrides.get(wildcardAllKey));
        }

        return Optional.empty();
    }

    /**
     * Returns all direct overrides matching the given schema and table.
     *
     * <p>This method iterates all stored direct override keys and returns
     * entries whose schema and table components match the given values
     * (including wildcard matches).
     *
     * @param schema the source schema name (e.g. {@code "public"}).
     * @param table  the source table name.
     * @return a list of matching {@link DirectOverrideEntry} instances
     *         (never null).
     */
    public List<DirectOverrideEntry> getDirectOverrides(String schema, String table) {
        if (directOverrides.isEmpty()) {
            return Collections.emptyList();
        }

        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);

        List<DirectOverrideEntry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : directOverrides.entrySet()) {
            String key = entry.getKey();
            // key format: schema.table.column
            String[] parts = key.split("\\.", 3);
            if (parts.length != 3) continue;

            String entrySchema = parts[0];
            String entryTable = parts[1];
            String entryColumn = parts[2];

            boolean schemaMatch = "*".equals(entrySchema)
                    || entrySchema.equals(schemaNorm);
            boolean tableMatch = "*".equals(entryTable)
                    || entryTable.equals(tableNorm);

            if (schemaMatch && tableMatch) {
                result.add(new DirectOverrideEntry(
                        entryColumn, entry.getValue()));
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Alias override lookup
    // -----------------------------------------------------------------------

    /**
     * Returns all alias overrides matching the given schema and table.
     *
     * <p>Matching includes exact {@code schema.table} entries and wildcard
     * entries ({@code *.table}, {@code *.*}).
     *
     * @param schema the source schema name.
     * @param table  the source table name.
     * @return a list of matching {@link AliasOverrideEntry} instances (never null).
     */
    public List<AliasOverrideEntry> getAliasOverrides(String schema, String table) {
        if (aliasOverrides.isEmpty()) {
            return Collections.emptyList();
        }

        String schemaNorm = normalize(schema);
        String tableNorm = normalize(table);

        List<AliasOverrideEntry> result = new ArrayList<>();
        for (Map.Entry<String, AliasOverrideEntry> entry : aliasOverrides.entrySet()) {
            String key = entry.getKey();
            AliasOverrideEntry aliasEntry = entry.getValue();

            // key format: schema.table.column
            String entrySchema = aliasEntry.getSchema();
            String entryTable = aliasEntry.getTable();

            boolean schemaMatch = "*".equals(entrySchema) || entrySchema.equals(schemaNorm);
            boolean tableMatch = "*".equals(entryTable) || entryTable.equals(tableNorm);

            if (schemaMatch && tableMatch) {
                result.add(aliasEntry);
            }
        }

        return result;
    }

    /**
     * Returns {@code true} if this config has any overrides configured.
     *
     * @return {@code true} when at least one override is defined.
     */
    public boolean hasOverrides() {
        return !directOverrides.isEmpty() || !aliasOverrides.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    /**
     * Parses a direct override property entry.
     *
     * <p>Key format: {@code column_type_override.direct.<schema>.<table>.<column>}
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
        String normalizedKey = parts[0] + "." + parts[1] + "." + parts[2];
        target.put(normalizedKey, value.trim());
    }

    /**
     * Parses an alias override property entry.
     *
     * <p>Key format: {@code column_type_override.alias.<schema>.<table>.<column>}
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

        String schema = parts[0];
        String table = parts[1];
        String column = parts[2];
        String normalizedKey = schema + "." + table + "." + column;

        AliasOverrideEntry entry = new AliasOverrideEntry(schema, table, column, aliasType, expression);
        target.put(normalizedKey, entry);
    }

    /**
     * Splits a qualified key suffix {@code "schema.table.column"} into exactly
     * three normalised parts. Handles the special case of wildcard {@code *}.
     *
     * @param suffix the key suffix after the prefix has been stripped.
     * @return a 3-element array {@code [schema, table, column]}, or {@code null}
     *         if the format is invalid.
     */
    static String[] splitQualifiedKey(String suffix) {
        if (suffix == null || suffix.isEmpty()) return null;

        String[] parts = suffix.split("\\.", 3);
        if (parts.length != 3) return null;

        // Normalize each part: lowercase unless it's a wildcard
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (!"*".equals(parts[i])) {
                parts[i] = parts[i].toLowerCase();
            }
        }

        // Column name (parts[2]) must not be empty
        if (parts[2].isEmpty()) return null;

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

        private final String schema;
        private final String table;
        private final String column;
        private final String aliasType;
        private final String expression;

        /**
         * Constructs a new alias override entry.
         *
         * @param schema     the source schema name (normalized).
         * @param table      the source table name (normalized).
         * @param column     the source column name.
         * @param aliasType  the ClickHouse type for the ALIAS column.
         * @param expression the ClickHouse expression for the ALIAS column.
         */
        public AliasOverrideEntry(String schema, String table, String column,
                                  String aliasType, String expression) {
            this.schema = schema;
            this.table = table;
            this.column = column;
            this.aliasType = aliasType;
            this.expression = expression;
        }

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
                    + "schema='" + schema + '\''
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
