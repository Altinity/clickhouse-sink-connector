package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.debezium.embedded.cdc.BinlogConnectionGuard;
import com.altinity.clickhouse.debezium.embedded.converters.Latin1TextConverter;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import io.debezium.config.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the keys of a user configuration file before the connector starts (spec 10.07).
 *
 * <ul>
 *   <li>By default the check never refuses the start. An unknown, renamed or ignored key is logged at WARN, and the
 *       log names the nearest known key or the key to use instead.</li>
 *   <li>With {@code config.keys.strict=true} an unknown key and a renamed key refuse the start. A legacy key that is
 *       set together with its replacement, and a dead shipped key, never refuse.</li>
 *   <li>An invalid {@code config.keys.strict} value refuses the start in every mode.</li>
 * </ul>
 */
public final class ConfigKeyCheck {

    private static final Logger LOG = LogManager.getLogger(ConfigKeyCheck.class);

    /** The user-file key that turns strict mode on: an unknown or renamed key then refuses the start. */
    static final String STRICT_KEY = "config.keys.strict";

    private static final String HISTORY_LEGACY_PREFIX = "database.history.";
    private static final String HISTORY_PREFIX = "schema.history.internal.";

    /**
     * Prefixes whose suffix is free-form: a key with one of these prefixes and a non-empty suffix is known. The driver
     * properties that Debezium forwards by name ({@code driver.max_packet_size}) use the driver's own vocabulary. The
     * column type overrides name a schema, table and column after the prefix, and ColumnTypeOverrideConfig reads the
     * prefixes from the same enum constants. Every other known key is listed by name, so a misspelt key under a
     * Debezium or JDBC-store prefix is still reported.
     */
    private static final List<String> PASS_THROUGH_PREFIXES = List.of(
            "driver.",
            ClickHouseSinkConnectorConfigVariables.COLUMN_TYPE_OVERRIDE_DIRECT_PREFIX.toString(),
            ClickHouseSinkConnectorConfigVariables.COLUMN_TYPE_OVERRIDE_ALIAS_PREFIX.toString());

    /**
     * A class whose {@code Field} constants are configuration keys. {@code prefixClass} and {@code prefixField} name
     * the static String that the connector prepends to each field name; both are null when the names are full keys.
     *
     * @param className   the class that declares the fields
     * @param prefixClass the class that declares the prefix constant, or null
     * @param prefixField the prefix constant's name, or null
     */
    public record FieldSource(String className, String prefixClass, String prefixField) {

        static FieldSource full(String className) {
            return new FieldSource(className, null, null);
        }
    }

    /** Debezium field sources. The JDBC stores are prefixed: their fields are read under the store's prefix. */
    static final List<FieldSource> FIELD_SOURCES = List.of(
            FieldSource.full("io.debezium.connector.mysql.MySqlConnectorConfig"),
            FieldSource.full("io.debezium.connector.postgresql.PostgresConnectorConfig"),
            FieldSource.full("io.debezium.connector.mongodb.MongoDbConnectorConfig"),
            FieldSource.full("io.debezium.embedded.EmbeddedEngineConfig"),
            FieldSource.full("io.debezium.relational.history.SchemaHistory"),
            new FieldSource("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig",
                    "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig", "OFFSET_STORAGE_PREFIX"),
            new FieldSource("io.debezium.storage.jdbc.history.JdbcSchemaHistoryConfig",
                    "io.debezium.relational.history.SchemaHistory", "CONFIGURATION_FIELD_PREFIX_STRING"));

    /**
     * A driver's property enum: the class, and the no-argument methods that return a property's name and its
     * camel-case alias (null when the driver has no alias method).
     *
     * @param enumClass  the enum class that lists the driver's properties
     * @param nameMethod the method that returns the property name
     * @param aliasMethod the method that returns the alias, or null
     */
    private record DriverNames(String enumClass, String nameMethod, String aliasMethod) {
    }

    /**
     * The driver property enums that the relational connectors can reach (MySQL Connector/J and pgjdbc).
     */
    private static final List<DriverNames> DRIVER_NAME_SOURCES = List.of(
            new DriverNames("com.mysql.cj.conf.PropertyKey", "getKeyName", "getCcAlias"),
            new DriverNames("org.postgresql.PGProperty", "getName", null));

    /**
     * Keys that the lightweight module reads through a property lookup, either as a literal or through a constant.
     * The set is hand-maintained only for keys that no code object declares. {@code ConfigKeyCheckTest} scans the
     * module sources, resolves the constants and fails when a read key is known to none of the sets, so the list
     * cannot drift silently.
     */
    static final Set<String> APP_READ_KEYS = Set.of(
            "name", "clickhouse.server.url", "clickhouse.server.port", "clickhouse.server.user",
            "clickhouse.server.password", "connector.class", "database.hostname", "database.port",
            "database.user", "database.password", "database.dbname", "database.ssl.mode",
            "errors.max.retries", "column.propagate.source.type",
            // KeylessTablePreflight.SKIP_PROPERTY and BinlogRowImagePreflight.SKIP_PROPERTY
            "keyless.table.check.skip", "binlog.row.image.check.skip",
            // OffsetExtraInfo.ENABLE_PROPERTY
            "offset.extra.info.enable",
            // read by strictOf
            STRICT_KEY);

    /**
     * Keys that the shipped defaults or deployment templates set but that the connector does not read. Accepted at start with a
     * WARN; the value names the key to use instead. They are not suggestions for an unknown key.
     */
    static final Map<String, String> IGNORED_KEYS = Map.of(
            "converter.schemas.enable",
            "not read; Debezium reads the converter option under key.converter. or value.converter. "
                    + "(for example value.converter.schemas.enable)",
            "schemas.enable", "not read by the connector or by Debezium 3.1.3",
            "schema.history.internal.table.name",
            "not read; the schema history table key is schema.history.internal.jdbc.schema.history.table.name "
                    + "(deprecated alias schema.history.internal.jdbc.table.name)",
            "schema.history.internal.table.ddl",
            "not read; the schema history DDL key is schema.history.internal.jdbc.schema.history.table.ddl "
                    + "(deprecated alias schema.history.internal.jdbc.table.ddl)",
            "jar_version", "version marker in deployed configurations; not read by the connector");

    /**
     * A legacy key and what replaces it. The evidence says what Debezium 3.1.3 does with the legacy key; it is
     * shown in the RENAMED finding.
     */
    public record Rename(String replacement, String evidence) {
    }

    static final Map<String, Rename> RENAMES;

    static {
        Map<String, Rename> m = new LinkedHashMap<>();
        m.put("database.whitelist", new Rename("database.include.list",
                "Debezium 3.1.3 ignores this key; it has no database.whitelist"));
        m.put("database.blacklist", new Rename("database.exclude.list",
                "Debezium 3.1.3 ignores this key; it has no database.blacklist"));
        m.put("table.whitelist", new Rename("table.include.list",
                "Debezium 3.1.3 ignores this key; it has no table.whitelist"));
        m.put("table.blacklist", new Rename("table.exclude.list",
                "Debezium 3.1.3 ignores this key; it has no table.blacklist"));
        m.put("column.blacklist", new Rename("column.exclude.list",
                "Debezium 3.1.3 ignores this key; it has no column.blacklist"));
        m.put("clickhouse.server.pass", new Rename("clickhouse.server.password",
                "Debezium 3.1.3 ignores this key; the connector reads only the password key"));
        m.put("database.server.name", new Rename("topic.prefix",
                "Debezium 3.1.3 ignores this key; topic.prefix is the effective name. "
                        + "Shipped templates still set it (spec 10.07 section 6.3)"));
        RENAMES = Collections.unmodifiableMap(m);
    }

    /** The kind of a finding. */
    public enum Kind { UNKNOWN, RENAMED, RENAMED_IGNORED, IGNORED }

    /**
     * One finding about a user key.
     *
     * @param kind   the kind of finding
     * @param key    the user key
     * @param detail the nearest known key or none (UNKNOWN), the reason (IGNORED), or the replacement (RENAMED kinds)
     * @param fatal  true when the start is refused
     */
    public record Finding(Kind kind, String key, String detail, boolean fatal) {
    }

    private ConfigKeyCheck() {
    }

    /**
     * Checks a set of user keys without side effects. Only an unknown or a renamed key can be fatal, and only under
     * strict mode; a legacy key set together with its replacement and a dead shipped key are never fatal.
     *
     * @param userKeys the keys set by the user file
     * @param strict   true under {@code config.keys.strict=true}
     * @return the findings, sorted by key
     */
    public static List<Finding> findings(Set<String> userKeys, boolean strict) {
        Set<String> known = knownKeys();
        List<Finding> findings = new ArrayList<>();
        for (String key : new TreeSet<>(userKeys)) {
            Rename rename = renameFor(key);
            if (rename != null) {
                if (userKeys.contains(rename.replacement())) {
                    findings.add(new Finding(Kind.RENAMED_IGNORED, key, rename.replacement(), false));
                } else {
                    findings.add(new Finding(Kind.RENAMED, key, rename.replacement(), strict));
                }
            } else if (IGNORED_KEYS.containsKey(key)) {
                findings.add(new Finding(Kind.IGNORED, key, IGNORED_KEYS.get(key), false));
            } else if (!isKnown(key, known)) {
                findings.add(new Finding(Kind.UNKNOWN, key, suggest(key, known), strict));
            }
        }
        return findings;
    }

    /**
     * Checks the user file keys, logs each finding and a summary line, and refuses the start when a finding is fatal.
     * The strict flag is read from the file; an invalid value refuses in every mode.
     *
     * @param fileProps the properties parsed from the user file only
     * @throws IllegalArgumentException when the strict value is invalid, or a finding is fatal under strict
     */
    public static void check(Properties fileProps) {
        boolean strict = strictOf(fileProps);
        Set<String> keys = fileProps.stringPropertyNames();
        List<Finding> findings = findings(keys, strict);
        List<String> refused = new ArrayList<>();
        for (Finding f : findings) {
            LOG.warn("CONFIG-KEY-CHECK " + describe(f) + actionSuffix(f));
            if (f.fatal()) {
                refused.add(describe(f));
            }
        }
        String summary = "CONFIG-KEY-CHECK summary keys=" + keys.size()
                + " unknown=" + count(findings, Kind.UNKNOWN)
                + " renamed=" + count(findings, Kind.RENAMED)
                + " renamedIgnored=" + count(findings, Kind.RENAMED_IGNORED)
                + " ignored=" + count(findings, Kind.IGNORED)
                + " strict=" + strict + " refused=" + !refused.isEmpty();
        if (findings.isEmpty()) {
            LOG.info(summary);
        } else {
            LOG.warn(summary);
        }
        if (!refused.isEmpty()) {
            throw new IllegalArgumentException(STRICT_KEY + "=true refuses the start: " + String.join("; ", refused)
                    + ". Remove or rename these keys, or set " + STRICT_KEY + "=false to start with a warning.");
        }
    }

    /**
     * Reads {@code config.keys.strict} from the user file. An absent key is false. The values true and false are
     * accepted in any case after trimming; any other value is invalid.
     *
     * @param fileProps the properties parsed from the user file only
     * @return true when strict mode is on
     * @throws IllegalArgumentException when the value is neither true nor false
     */
    static boolean strictOf(Properties fileProps) {
        String raw = fileProps.getProperty(STRICT_KEY);
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(STRICT_KEY + " has the invalid value '" + raw + "'; use true or false");
    }

    private static long count(List<Finding> findings, Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).count();
    }

    /**
     * Lints configuration files from the command line: YAML, or a {@code .properties} file when the path ends in
     * that suffix. Exit 0 when no finding is fatal, 1 when one is, 2 on a usage or read error, or an invalid
     * {@code config.keys.strict} value.
     *
     * @param args the configuration file paths
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: ConfigKeyCheck <config file> [...]");
            System.exit(2);
        }
        boolean anyFatal = false;
        for (String path : args) {
            Properties props;
            try {
                props = load(path);
            } catch (Exception e) {
                System.out.println("CONFIG-KEY-CHECK file=" + path + " error=" + e);
                System.exit(2);
                return;
            }
            boolean strict;
            try {
                strict = strictOf(props);
            } catch (IllegalArgumentException e) {
                System.err.println("CONFIG-KEY-CHECK file=" + path + " error=" + e.getMessage());
                System.exit(2);
                return;
            }
            Set<String> keys = props.stringPropertyNames();
            List<Finding> findings = findings(keys, strict);
            System.out.println("CONFIG-KEY-CHECK file=" + path + " keys=" + keys.size()
                    + " findings=" + findings.size());
            for (Finding f : findings) {
                System.out.println("CONFIG-KEY-CHECK file=" + path + " " + describe(f) + actionSuffix(f));
                anyFatal |= f.fatal();
            }
        }
        System.exit(anyFatal ? 1 : 0);
    }

    static Properties load(String path) throws Exception {
        if (path.endsWith(".properties")) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                p.load(in);
            }
            return p;
        }
        return new ConfigLoader().loadFromFile(path);
    }

    static Rename renameFor(String key) {
        Rename exact = RENAMES.get(key);
        if (exact != null) {
            return exact;
        }
        if (key.startsWith(HISTORY_LEGACY_PREFIX)) {
            return new Rename(HISTORY_PREFIX + key.substring(HISTORY_LEGACY_PREFIX.length()),
                    "Debezium 3.x renamed database.history.* to schema.history.internal.*");
        }
        return null;
    }

    static boolean isKnown(String key, Set<String> known) {
        if (known.contains(key)) {
            return true;
        }
        for (String prefix : PASS_THROUGH_PREFIXES) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The known-key set, derived from the code objects on the classpath: the sink configuration definition,
     * the sink configuration enum, the lightweight configuration variables, the Debezium field sets (names and
     * deprecated aliases, with the JDBC-store prefixes applied), the driver keys and the application-read keys.
     *
     * @return the known keys
     */
    static Set<String> knownKeys() {
        Set<String> known = new LinkedHashSet<>(ClickHouseSinkConnectorConfig.definedKeys());
        for (ClickHouseSinkConnectorConfigVariables v : ClickHouseSinkConnectorConfigVariables.values()) {
            known.add(v.toString());
        }
        known.addAll(SinkConnectorLightWeightConfig.getConfigVariables().keySet());
        known.addAll(debeziumFieldNames());
        known.addAll(debeziumDeprecatedAliases());
        known.addAll(driverKeys());
        known.addAll(APP_READ_KEYS);
        // Read by the module through a property lookup and declared in no ConfigDef (spec 01.09 section 3.2).
        known.add(BinlogConnectionGuard.PROPERTY);
        // The converter type key that Latin1TextConverter.ensureRegistered sets and Debezium reads.
        known.add(Latin1TextConverter.NAME + ".type");
        return known;
    }

    /**
     * The field names that Debezium reads, with each source's prefix applied.
     *
     * @return the field names; a source that is absent is logged and contributes nothing
     */
    static Set<String> debeziumFieldNames() {
        return debeziumKeys(false);
    }

    /**
     * The deprecated aliases that Debezium still honours, with each source's prefix applied.
     *
     * @return the alias keys; a source that is absent is logged and contributes nothing
     */
    static Set<String> debeziumDeprecatedAliases() {
        return debeziumKeys(true);
    }

    /**
     * The driver property names that reach the driver under the {@code database.} prefix. Debezium 3.1.3 builds the
     * JDBC configuration of the relational connectors from {@code database.*} with the prefix stripped
     * ({@code RelationalDatabaseConnectorConfig}), so an unknown {@code database.*} key is passed to the driver and
     * ignored there. Each name is therefore read from the driver's own enum and listed with the prefix (Connector/J
     * {@code PropertyKey} key name and alias, pgjdbc {@code PGProperty} name). A misspelt key is not in the set and is
     * refused. A driver that is not on the classpath contributes nothing and is logged.
     *
     * @return the prefixed driver property names
     */
    static Set<String> driverKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (DriverNames source : DRIVER_NAME_SOURCES) {
            try {
                Class<?> type = Class.forName(source.enumClass());
                for (Object constant : type.getEnumConstants()) {
                    addDriverName(keys, invokeName(type, constant, source.nameMethod()));
                    if (source.aliasMethod() != null) {
                        addDriverName(keys, invokeName(type, constant, source.aliasMethod()));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("Driver property enum {} not available: {}", source.enumClass(), e.toString());
            }
        }
        return keys;
    }

    private static void addDriverName(Set<String> keys, String name) {
        if (name != null && !name.isEmpty()) {
            keys.add("database." + name);
        }
    }

    private static String invokeName(Class<?> type, Object constant, String method) throws ReflectiveOperationException {
        Object value = type.getMethod(method).invoke(constant);
        return value == null ? null : value.toString();
    }

    private static Set<String> debeziumKeys(boolean aliases) {
        Set<String> keys = new LinkedHashSet<>();
        for (FieldSource source : FIELD_SOURCES) {
            try {
                String prefix = prefixOf(source);
                for (Field field : fieldsOf(Class.forName(source.className()))) {
                    if (aliases) {
                        for (String alias : field.deprecatedAliases()) {
                            keys.add(prefix + alias);
                        }
                    } else {
                        keys.add(prefix + field.name());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("Debezium config source {} not available: {}", source.className(), e.toString());
            }
        }
        return keys;
    }

    static String prefixOf(FieldSource source) throws ReflectiveOperationException {
        if (source.prefixClass() == null) {
            return "";
        }
        return (String) Class.forName(source.prefixClass()).getField(source.prefixField()).get(null);
    }

    /**
     * The Field constants of a configuration class: the ALL_FIELDS list when declared, plus every public static
     * Field constant. The two are merged by name, so a field declared only as a constant is still known.
     */
    static List<Field> fieldsOf(Class<?> type) throws ReflectiveOperationException {
        Map<String, Field> byName = new LinkedHashMap<>();
        try {
            java.lang.reflect.Field all = type.getField("ALL_FIELDS");
            if (Modifier.isStatic(all.getModifiers()) && all.get(null) instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    byName.putIfAbsent(((Field) o).name(), (Field) o);
                }
            }
        } catch (NoSuchFieldException e) {
            // no ALL_FIELDS list; the public constants below are the field set
        }
        for (java.lang.reflect.Field f : type.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Field.class.isAssignableFrom(f.getType())) {
                Field field = (Field) f.get(null);
                byName.putIfAbsent(field.name(), field);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * The nearest known key. A known key that contains the segments of {@code key} in order wins, with the fewest
     * extra segments; otherwise the closest known key by edit distance, when it is close enough. Suggestions come
     * only from the known set, so a shipped dead key is never suggested.
     *
     * @param key   the unknown key
     * @param known the known keys
     * @return the nearest known key, or {@code none}
     */
    static String suggest(String key, Set<String> known) {
        String[] wanted = key.split("\\.");
        String bestContaining = null;
        int bestExtra = Integer.MAX_VALUE;
        int bestContainingDistance = Integer.MAX_VALUE;
        String bestClose = null;
        int bestCloseDistance = Integer.MAX_VALUE;
        for (String candidate : new TreeSet<>(known)) {
            int distance = editDistance(key, candidate);
            if (distance < bestCloseDistance) {
                bestClose = candidate;
                bestCloseDistance = distance;
            }
            String[] have = candidate.split("\\.");
            if (isSubsequence(wanted, have)) {
                int extra = have.length - wanted.length;
                if (extra < bestExtra || (extra == bestExtra && distance < bestContainingDistance)) {
                    bestContaining = candidate;
                    bestExtra = extra;
                    bestContainingDistance = distance;
                }
            }
        }
        if (bestContaining != null) {
            return bestContaining;
        }
        return bestCloseDistance <= Math.max(2, key.length() / 4) ? bestClose : "none";
    }

    static boolean isSubsequence(String[] wanted, String[] candidate) {
        int next = 0;
        for (String segment : candidate) {
            if (next < wanted.length && segment.equals(wanted[next])) {
                next++;
            }
        }
        return next == wanted.length;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** The action suffix of a finding's log line: refused when the finding is fatal, otherwise ignored. */
    static String actionSuffix(Finding f) {
        return f.fatal() ? " action=refused" : " action=ignored";
    }

    /** The body of a finding's line, without the prefix or the action suffix. */
    static String describe(Finding f) {
        switch (f.kind()) {
            case UNKNOWN:
                return "kind=UNKNOWN key=" + f.key() + " nearest=" + f.detail();
            case IGNORED:
                return "kind=IGNORED key=" + f.key() + " reason=" + f.detail();
            case RENAMED_IGNORED:
                return "kind=RENAMED_IGNORED key=" + f.key() + " nearest=" + f.detail()
                        + " note=the legacy value is ignored because the replacement is set";
            case RENAMED:
            default:
                return "kind=RENAMED key=" + f.key() + " nearest=" + f.detail()
                        + " note=" + renameFor(f.key()).evidence();
        }
    }
}
