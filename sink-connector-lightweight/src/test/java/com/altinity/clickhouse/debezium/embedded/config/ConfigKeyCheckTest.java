package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.debezium.embedded.converters.Latin1TextConverter;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import io.debezium.config.Field;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spec 10.07: by default unknown, renamed and ignored keys only warn. Under config.keys.strict=true an unknown or a
 * renamed key refuses the start, a legacy key set with its replacement and a dead shipped key never do, and an
 * invalid strict value refuses in every mode.
 */
public class ConfigKeyCheckTest {

    /** A String constant declaration; group 1 is the name, group 2 the initializer (may span lines). */
    private static final Pattern CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*([^;]+);");

    /**
     * A Properties read (System.getProperty excluded). Group 1 is the first argument; one level of nested
     * parentheses is allowed, so {@code X.toString()} stays whole.
     */
    private static final Pattern READ = Pattern.compile(
            "(?<!System\\.)getProperty\\(\\s*((?:[^,()]|\\([^()]*\\))+)");

    /** A local String declaration; group 1 is the variable, group 2 the initializer. */
    private static final Pattern LOCAL = Pattern.compile("String\\s+(\\w+)\\s*=\\s*([^;]+);");

    private static final Pattern LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** A possibly qualified reference; group 1 is the qualifier chain (may be empty), group 2 the name. */
    private static final Pattern REFERENCE = Pattern.compile("((?:[A-Za-z_]\\w*\\s*\\.\\s*)*)([A-Za-z_]\\w*)");

    /** A plus sign outside a string literal. */
    private static final Pattern PLUS = Pattern.compile("\\+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    private static final int MAX_PASSES = 6;

    /**
     * Reads whose key comes from a method parameter or an array element, which the scanner cannot follow. Each entry
     * is keyed by {@code <file simple name>#<variable>} and lists the keys that the call sites pass in. The test
     * {@code reviewedDynamicReadsAreStillPresent} fails if the read has moved or a listed key is unknown.
     */
    static final Map<String, List<String>> DYNAMIC_READS = Map.of(
            "DebeziumChangeEventCapture#propKey",
            List.of("offset.storage.jdbc.url", "schema.history.internal.jdbc.url"),
            "DebeziumOffsetStorage#key",
            List.of("offset.storage.jdbc.table.ddl", "offset.storage.jdbc.offset.table.ddl"));

    /**
     * Classes whose constants the scanner resolves by reflection, keyed by simple name: the Debezium configuration
     * classes and the sink-connector constant holders that the module reads (the reads are on the classpath, so
     * the values come from the code, not from a copy).
     */
    private static final Map<String, Class<?>> CODE_CLASSES = codeClasses();

    private static Map<String, Class<?>> codeClasses() {
        List<String> names = new ArrayList<>();
        names.add("io.debezium.relational.RelationalDatabaseConnectorConfig");
        names.add("io.debezium.config.CommonConnectorConfig");
        names.add("com.altinity.clickhouse.sink.connector.common.TimeZoneResolver");
        for (ConfigKeyCheck.FieldSource source : ConfigKeyCheck.FIELD_SOURCES) {
            names.add(source.className());
            if (source.prefixClass() != null) {
                names.add(source.prefixClass());
            }
        }
        Map<String, Class<?>> out = new LinkedHashMap<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name);
                out.putIfAbsent(type.getSimpleName(), type);
            } catch (ClassNotFoundException | LinkageError absent) {
                // not on the test classpath; references to it stay unresolved
            }
        }
        return out;
    }

    /** The keys read by the module, with the file where each was first read, and the reads the scanner cannot resolve. */
    record Scan(Map<String, String> keyToFile, List<String> unresolved) {
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static List<ConfigKeyCheck.Finding> findings(Properties p) {
        return ConfigKeyCheck.findings(p.stringPropertyNames(), false);
    }

    private static List<ConfigKeyCheck.Finding> findingsStrict(Properties p) {
        return ConfigKeyCheck.findings(p.stringPropertyNames(), true);
    }

    /** The properties built from the pairs, with config.keys.strict set to true. */
    private static Properties strictProps(String... kv) {
        Properties p = props(kv);
        p.setProperty(ConfigKeyCheck.STRICT_KEY, "true");
        return p;
    }

    private static ConfigKeyCheck.Finding only(Properties p) {
        List<ConfigKeyCheck.Finding> found = findings(p);
        Assertions.assertEquals(1, found.size(), "findings: " + found);
        return found.get(0);
    }

    private static List<Path> javaSources(String root) throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get(root))) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /**
     * Maps each String constant name to its values across the sources. A constant may be a literal, a concatenation,
     * a reference to another constant, a Debezium {@code Field.name()} or a sink enum key; the declarations are
     * resolved to a fixed point, so a chain of constants resolves whatever its order.
     */
    static Map<String, Set<String>> constants(List<String> sources) {
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (String source : sources) {
            Matcher m = CONSTANT.matcher(source);
            while (m.find()) {
                declared.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(m.group(2));
            }
        }
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (Map.Entry<String, List<String>> entry : declared.entrySet()) {
                for (String rhs : entry.getValue()) {
                    Set<String> resolved = resolveExpression(rhs, values);
                    if (resolved != null) {
                        changed |= values.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).addAll(resolved);
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return values;
    }

    /**
     * Resolves a concatenation of atoms; null when any atom is not resolvable. The values of each atom are combined,
     * so a multi-valued constant yields every combination.
     */
    static Set<String> resolveExpression(String expr, Map<String, Set<String>> constants) {
        Set<String> acc = new TreeSet<>(Set.of(""));
        for (String atom : PLUS.split(expr)) {
            Set<String> values = resolveAtom(atom, constants);
            if (values == null || values.isEmpty()) {
                return null;
            }
            Set<String> next = new TreeSet<>();
            for (String prefix : acc) {
                for (String value : values) {
                    next.add(prefix + value);
                }
            }
            acc = next;
        }
        return acc;
    }

    /**
     * Resolves one atom: a string literal, {@code Enum.KEY.toString()}, {@code Debezium.FIELD.name()},
     * {@code Debezium.STRING}, or a module constant by its name. Returns null when the atom is none of these.
     */
    static Set<String> resolveAtom(String atom, Map<String, Set<String>> constants) {
        String e = atom.trim();
        Matcher literal = LITERAL.matcher(e);
        if (literal.matches()) {
            return Set.of(literal.group(1));
        }
        boolean nameCall = false;
        if (e.endsWith(".toString()")) {
            e = e.substring(0, e.length() - ".toString()".length()).trim();
        } else if (e.endsWith(".name()")) {
            nameCall = true;
            e = e.substring(0, e.length() - ".name()".length()).trim();
        }
        Matcher ref = REFERENCE.matcher(e);
        if (!ref.matches()) {
            return null;
        }
        String qualifier = null;
        String chain = ref.group(1).replaceAll("\\s", "");
        if (!chain.isEmpty()) {
            String[] segments = chain.split("\\.");
            qualifier = segments[segments.length - 1];
        }
        String name = ref.group(2);
        if ("ClickHouseSinkConnectorConfigVariables".equals(qualifier)) {
            try {
                return Set.of(ClickHouseSinkConnectorConfigVariables.valueOf(name).toString());
            } catch (IllegalArgumentException notAKey) {
                return null;
            }
        }
        Class<?> owner = qualifier == null ? null : CODE_CLASSES.get(qualifier);
        if (owner != null) {
            try {
                Object member = owner.getField(name).get(null);
                if (member instanceof Field field) {
                    return Set.of(field.name());
                }
                if (member instanceof String text && !nameCall) {
                    return Set.of(text);
                }
            } catch (ReflectiveOperationException notPublic) {
                return null;
            }
            return null;
        }
        return nameCall ? null : constants.get(name);
    }

    /** Scans one source text for property reads, resolving constants through {@code constants}. */
    static Scan scan(Map<String, Set<String>> constants, String file, String source, Map<String, String> into,
                     List<String> unresolved) {
        Matcher m = READ.matcher(source);
        while (m.find()) {
            String arg = m.group(1).trim();
            Set<String> values = resolveExpression(arg, constants);
            if (values == null && arg.matches("[a-z]\\w*")) {
                values = resolveVariable(source, file, arg, constants);
            }
            if (values == null || values.isEmpty()) {
                unresolved.add(file + ": getProperty(" + arg + ")");
                continue;
            }
            for (String value : values) {
                into.putIfAbsent(value, file);
            }
        }
        return new Scan(into, unresolved);
    }

    /**
     * Resolves a variable read: from its String declarations in the same file when there are any, else from the
     * reviewed dynamic reads. Returns null when neither applies.
     */
    static Set<String> resolveVariable(String source, String file, String name, Map<String, Set<String>> constants) {
        Set<String> values = new TreeSet<>();
        Matcher local = LOCAL.matcher(source);
        while (local.find()) {
            if (local.group(1).equals(name)) {
                Set<String> resolved = resolveExpression(local.group(2), constants);
                if (resolved == null) {
                    return null;
                }
                values.addAll(resolved);
            }
        }
        if (!values.isEmpty()) {
            return values;
        }
        List<String> reviewed = DYNAMIC_READS.get(simpleName(file) + "#" + name);
        return reviewed == null ? null : new TreeSet<>(reviewed);
    }

    private static String simpleName(String file) {
        String base = Path.of(file).getFileName().toString();
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    /** Scans every Java source under the module's main sources. */
    static Scan scanModule() throws Exception {
        List<Path> files = javaSources("src/main/java");
        List<String> texts = new ArrayList<>();
        for (Path p : files) {
            texts.add(Files.readString(p));
        }
        Map<String, Set<String>> constants = constants(texts);
        Map<String, String> keys = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            scan(constants, files.get(i).toString(), texts.get(i), keys, unresolved);
        }
        return new Scan(keys, unresolved);
    }

    @Test
    @DisplayName("an unknown key warns by default and refuses only under strict, naming the nearest known key")
    public void unknownKeyWarnsByDefault() {
        Properties p = props("offset.table.name", "x");
        Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(p));
        ConfigKeyCheck.Finding f = only(p);
        Assertions.assertEquals(ConfigKeyCheck.Kind.UNKNOWN, f.kind());
        Assertions.assertFalse(f.fatal());
        Properties strict = strictProps("offset.table.name", "x");
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> ConfigKeyCheck.check(strict));
        Assertions.assertTrue(e.getMessage().contains("key=offset.table.name"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("nearest=offset.storage.jdbc.table.name"), e.getMessage());
    }

    @Test
    @DisplayName("a misspelt key is UNKNOWN with the nearest known key as the detail; fatal only under strict")
    public void misspelledKeyIsUnknownWithSuggestion() {
        ConfigKeyCheck.Finding f = only(props("database.hostnmae", "h"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.UNKNOWN, f.kind());
        Assertions.assertFalse(f.fatal());
        Assertions.assertEquals("database.hostname", f.detail());
        List<ConfigKeyCheck.Finding> strict = findingsStrict(props("database.hostnmae", "h"));
        Assertions.assertEquals(1, strict.size(), strict.toString());
        Assertions.assertTrue(strict.get(0).fatal());
    }

    @Test
    @DisplayName("config.keys.strict is a known key, so setting it is not reported as UNKNOWN")
    public void strictKeyIsKnown() {
        Assertions.assertTrue(ConfigKeyCheck.isKnown(ConfigKeyCheck.STRICT_KEY, ConfigKeyCheck.knownKeys()));
        Assertions.assertEquals(List.of(), findings(props(ConfigKeyCheck.STRICT_KEY, "true")));
        Assertions.assertEquals(List.of(), findings(props(ConfigKeyCheck.STRICT_KEY, "false")));
    }

    @Test
    @DisplayName("config.keys.strict is false when absent, and true or false in any case after trimming")
    public void strictParsesTrueAndFalse() {
        Assertions.assertFalse(ConfigKeyCheck.strictOf(props()));
        Assertions.assertTrue(ConfigKeyCheck.strictOf(props(ConfigKeyCheck.STRICT_KEY, "true")));
        Assertions.assertTrue(ConfigKeyCheck.strictOf(props(ConfigKeyCheck.STRICT_KEY, " TRUE ")));
        Assertions.assertFalse(ConfigKeyCheck.strictOf(props(ConfigKeyCheck.STRICT_KEY, "FALSE")));
        Assertions.assertFalse(ConfigKeyCheck.strictOf(props(ConfigKeyCheck.STRICT_KEY, "False")));
    }

    @Test
    @DisplayName("an invalid config.keys.strict value refuses the start in the default mode too")
    public void strictInvalidValueRefuses() {
        for (String bad : List.of("yes", "1", "")) {
            Properties p = props(ConfigKeyCheck.STRICT_KEY, bad);
            IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> ConfigKeyCheck.check(p), bad);
            Assertions.assertTrue(e.getMessage().contains(ConfigKeyCheck.STRICT_KEY), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
        }
    }

    @Test
    @DisplayName("dead shipped keys are accepted with an IGNORED finding, not refused, in either mode")
    public void deadShippedKeysAreIgnoredNotRefused() {
        for (String key : List.of("converter.schemas.enable", "schemas.enable",
                "schema.history.internal.table.name", "schema.history.internal.table.ddl", "jar_version")) {
            ConfigKeyCheck.Finding f = only(props(key, "x"));
            Assertions.assertEquals(ConfigKeyCheck.Kind.IGNORED, f.kind(), key);
            Assertions.assertFalse(f.fatal(), key);
            Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(props(key, "x")), key);
            List<ConfigKeyCheck.Finding> strict = findingsStrict(props(key, "x"));
            Assertions.assertEquals(1, strict.size(), key);
            Assertions.assertFalse(strict.get(0).fatal(), key);
            Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(strictProps(key, "x")), key);
        }
    }

    @Test
    @DisplayName("an ignored key is never suggested for an unknown key")
    public void ignoredKeysAreNotSuggested() {
        ConfigKeyCheck.Finding f = only(props("schema.history.internal.table.nme", "x"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.UNKNOWN, f.kind());
        Assertions.assertNotEquals("schema.history.internal.table.name", f.detail());
        Assertions.assertEquals("schema.history.internal.jdbc.table.name", f.detail());
    }

    @Test
    @DisplayName("JDBC offset store keys are known under the offset.storage. prefix, including the aliases")
    public void prefixedJdbcOffsetKeysAreKnown() {
        Set<String> known = ConfigKeyCheck.knownKeys();
        for (String key : List.of("offset.storage.jdbc.table.name", "offset.storage.jdbc.table.ddl",
                "offset.storage.jdbc.connection.url", "offset.storage.jdbc.url", "offset.storage.jdbc.user",
                "offset.storage.jdbc.offset.table.name")) {
            Assertions.assertTrue(ConfigKeyCheck.isKnown(key, known), key);
        }
        Assertions.assertFalse(ConfigKeyCheck.isKnown("offset.table.name", known));
    }

    @Test
    @DisplayName("JDBC schema history keys are known under the schema.history.internal. prefix, including the aliases")
    public void prefixedJdbcSchemaHistoryKeysAreKnown() {
        Set<String> known = ConfigKeyCheck.knownKeys();
        for (String key : List.of("schema.history.internal.jdbc.table.name",
                "schema.history.internal.jdbc.connection.url", "schema.history.internal.jdbc.url",
                "schema.history.internal.jdbc.schema.history.table.name")) {
            Assertions.assertTrue(ConfigKeyCheck.isKnown(key, known), key);
        }
    }

    @Test
    @DisplayName("a misspelt JDBC offset key is UNKNOWN and suggests the shipped key")
    public void misspeltJdbcOffsetKeyIsUnknown() {
        ConfigKeyCheck.Finding f = only(props("offset.storage.jdbc.tabel.name", "x"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.UNKNOWN, f.kind());
        Assertions.assertEquals("offset.storage.jdbc.table.name", f.detail());
    }

    @Test
    @DisplayName("column type override keys under the direct and alias prefixes are known; typos are still UNKNOWN")
    public void columnTypeOverrideAliasAndDirectKeysAreKnown() {
        Set<String> known = ConfigKeyCheck.knownKeys();
        for (String key : List.of("column_type_override.alias.tbl.col", "column_type_override.direct.tbl.col")) {
            Assertions.assertTrue(ConfigKeyCheck.isKnown(key, known), key);
            Assertions.assertEquals(List.of(), findings(props(key, "x")), key);
        }
        for (String typo : List.of("column_type_overide.direct.tbl.col", "column_type_override.aliass.tbl.col")) {
            Assertions.assertEquals(ConfigKeyCheck.Kind.UNKNOWN, only(props(typo, "x")).kind(), typo);
        }
    }

    @Test
    @DisplayName("a renamed key warns by default and refuses under strict; with its replacement set it is ignored")
    public void renamedKeyWarnsByDefaultRefusesUnderStrict() {
        ConfigKeyCheck.Finding warned = only(props("database.whitelist", "app"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED, warned.kind());
        Assertions.assertFalse(warned.fatal());
        List<ConfigKeyCheck.Finding> strictFound = findingsStrict(props("database.whitelist", "app"));
        Assertions.assertEquals(1, strictFound.size(), strictFound.toString());
        Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED, strictFound.get(0).kind());
        Assertions.assertTrue(strictFound.get(0).fatal());
        ConfigKeyCheck.Finding ignored = only(props("database.whitelist", "app", "database.include.list", "app"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED_IGNORED, ignored.kind());
        Assertions.assertFalse(ignored.fatal());
    }

    @Test
    @DisplayName("under strict an unknown key and a renamed key both refuse; a legacy key with its replacement does not")
    public void strictRefusesEveryUnknownKeyNotOnlyRenamed() {
        List<ConfigKeyCheck.Finding> found = findingsStrict(props("offset.table.name", "x", "database.whitelist", "app"));
        Assertions.assertEquals(2, found.size(), found.toString());
        for (ConfigKeyCheck.Finding f : found) {
            Assertions.assertTrue(f.fatal(), f.key());
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> ConfigKeyCheck.check(
                strictProps("offset.table.name", "x", "database.whitelist", "app")));
        Properties pair = strictProps("database.whitelist", "app", "database.include.list", "app");
        List<ConfigKeyCheck.Finding> legacyPair = findingsStrict(pair);
        Assertions.assertEquals(1, legacyPair.size(), legacyPair.toString());
        Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED_IGNORED, legacyPair.get(0).kind());
        Assertions.assertFalse(legacyPair.get(0).fatal());
        Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(pair));
    }

    @Test
    @DisplayName("database.history.* keys are renamed to schema.history.internal.*; they are fatal only under strict")
    public void historyKeysAreRenamed() {
        ConfigKeyCheck.Finding f = only(props("database.history.kafka.topic", "t"));
        Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED, f.kind());
        Assertions.assertFalse(f.fatal());
        Assertions.assertEquals("schema.history.internal.kafka.topic", f.detail());
        Assertions.assertTrue(findingsStrict(props("database.history.kafka.topic", "t")).get(0).fatal());
    }

    @Test
    @DisplayName("the rename targets are Debezium field names or sink keys, and the legacy names are not honoured")
    public void renameTargetsAreDebeziumFieldsOrSinkKeys() {
        Set<String> debeziumNames = ConfigKeyCheck.debeziumFieldNames();
        Set<String> aliases = ConfigKeyCheck.debeziumDeprecatedAliases();
        for (String legacy : List.of("database.whitelist", "database.blacklist", "table.whitelist",
                "table.blacklist", "column.blacklist", "clickhouse.server.pass", "database.server.name")) {
            ConfigKeyCheck.Finding f = only(props(legacy, "x"));
            Assertions.assertEquals(ConfigKeyCheck.Kind.RENAMED, f.kind(), legacy);
            Assertions.assertFalse(f.fatal(), legacy);
            Assertions.assertTrue(ConfigKeyCheck.describe(f).contains("Debezium 3.1.3 ignores this key"),
                    legacy + " -> " + ConfigKeyCheck.describe(f));
            Assertions.assertFalse(debeziumNames.contains(legacy), legacy + " is a Debezium field name");
            Assertions.assertFalse(aliases.contains(legacy), legacy + " is a Debezium deprecated alias");
            Assertions.assertTrue(debeziumNames.contains(f.detail()) || ConfigKeyCheck.knownKeys().contains(f.detail()),
                    legacy + " -> " + f.detail() + " is not a known key");
        }
    }

    @Test
    @DisplayName("every rename note starts with the Debezium 3.1.3 evidence")
    public void renamedNoteSaysDebeziumIgnores() {
        Assertions.assertFalse(ConfigKeyCheck.RENAMES.isEmpty());
        for (Map.Entry<String, ConfigKeyCheck.Rename> rename : ConfigKeyCheck.RENAMES.entrySet()) {
            Assertions.assertTrue(rename.getValue().evidence().startsWith("Debezium 3.1.3 ignores this key"),
                    rename.getKey());
        }
    }

    @Test
    @DisplayName("no renamed key is a Debezium field or a known key, so a rename cannot hide a valid key")
    public void renamedKeysAreNotDebeziumFieldNames() {
        Set<String> known = ConfigKeyCheck.knownKeys();
        for (String legacy : ConfigKeyCheck.RENAMES.keySet()) {
            Assertions.assertFalse(known.contains(legacy), legacy + " is a known key");
        }
    }

    @Test
    @DisplayName("the Debezium field sets are loaded from the classes, not hand-typed")
    public void debeziumFieldSetsAreLoaded() {
        Set<String> names = ConfigKeyCheck.debeziumFieldNames();
        for (String key : List.of("database.hostname", "topic.prefix", "snapshot.mode",
                "offset.storage.jdbc.table.name", "schema.history.internal.jdbc.table.name")) {
            Assertions.assertTrue(names.contains(key), key);
        }
    }

    @Test
    @DisplayName("every Debezium field source loads and contributes fields; a silent skip fails here")
    public void everyFieldSourceLoadsWithFields() throws Exception {
        for (ConfigKeyCheck.FieldSource source : ConfigKeyCheck.FIELD_SOURCES) {
            List<Field> fields = ConfigKeyCheck.fieldsOf(Class.forName(source.className()));
            Assertions.assertFalse(fields.isEmpty(), source.className());
        }
    }

    @Test
    @DisplayName("the JDBC store prefixes are read from Debezium's constants")
    public void jdbcStorePrefixesComeFromDebezium() throws Exception {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (ConfigKeyCheck.FieldSource source : ConfigKeyCheck.FIELD_SOURCES) {
            prefixes.put(source.className(), ConfigKeyCheck.prefixOf(source));
        }
        Assertions.assertEquals("offset.storage.",
                prefixes.get("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig"));
        Assertions.assertEquals("schema.history.internal.",
                prefixes.get("io.debezium.storage.jdbc.history.JdbcSchemaHistoryConfig"));
        Assertions.assertEquals("", prefixes.get("io.debezium.connector.mysql.MySqlConnectorConfig"));
    }

    @Test
    @DisplayName("driver keys come from the driver enums; a misspelt or inert database key is refused under strict")
    public void driverKeysAreDerivedFromTheDriverEnums() {
        Set<String> drivers = ConfigKeyCheck.driverKeys();
        Set<String> known = ConfigKeyCheck.knownKeys();
        // Connector/J PropertyKey: a key name, and the camel-case alias serverTimezone of connectionTimeZone.
        Assertions.assertTrue(drivers.contains("database.allowPublicKeyRetrieval"), drivers.toString());
        Assertions.assertTrue(drivers.contains("database.serverTimezone"), drivers.toString());
        // pgjdbc PGProperty name.
        Assertions.assertTrue(drivers.contains("database.sslmode"), drivers.toString());
        Assertions.assertTrue(ConfigKeyCheck.isKnown("database.serverTimezone", known));
        Assertions.assertTrue(ConfigKeyCheck.isKnown("driver.max_packet_size", known));
        Assertions.assertFalse(ConfigKeyCheck.isKnown("database.allowPublicKeyRetreival", known));
        // Not Debezium fields and not driver properties: inert in the shipped samples, so they must be refused.
        Assertions.assertFalse(ConfigKeyCheck.isKnown("database.connection.reconnect.backoff.ms", known));
        Assertions.assertFalse(ConfigKeyCheck.isKnown("database.keep.alive.interval.ms", known));
    }

    @Test
    @DisplayName("every property key the module reads is known, and every read resolves")
    public void everyKeyReadByTheModuleIsKnown() throws Exception {
        Scan scan = scanModule();
        Assertions.assertTrue(scan.unresolved().isEmpty(), "reads the scanner cannot resolve: " + scan.unresolved());
        Assertions.assertTrue(scan.keyToFile().size() >= 10, "too few reads found: " + scan.keyToFile().size());
        Set<String> known = ConfigKeyCheck.knownKeys();
        List<String> unregistered = new ArrayList<>();
        for (Map.Entry<String, String> read : scan.keyToFile().entrySet()) {
            boolean ignored = ConfigKeyCheck.IGNORED_KEYS.containsKey(read.getKey());
            if (!ConfigKeyCheck.isKnown(read.getKey(), known) || ignored) {
                unregistered.add(read.getKey() + " (" + read.getValue() + ")");
            }
        }
        Assertions.assertEquals(List.of(), unregistered, "read but not registered");
    }

    @Test
    @DisplayName("each reviewed dynamic read is still in its file, and every key it lists is known")
    public void reviewedDynamicReadsAreStillPresent() throws Exception {
        Set<String> known = ConfigKeyCheck.knownKeys();
        for (Map.Entry<String, List<String>> entry : DYNAMIC_READS.entrySet()) {
            String[] parts = entry.getKey().split("#");
            List<Path> files = javaSources("src/main/java").stream()
                    .filter(p -> p.getFileName().toString().equals(parts[0] + ".java"))
                    .toList();
            Assertions.assertEquals(1, files.size(), entry.getKey());
            Assertions.assertTrue(Files.readString(files.get(0)).contains("getProperty(" + parts[1] + ")"),
                    entry.getKey() + " is no longer read in its file");
            for (String key : entry.getValue()) {
                Assertions.assertTrue(ConfigKeyCheck.isKnown(key, known), entry.getKey() + " lists " + key);
            }
        }
    }

    @Test
    @DisplayName("the source scan fails on a synthetic read of an unregistered key")
    public void scannerFailsOnUnregisteredRead() {
        String source = "class X { void f(Properties props) { props.getProperty(\"new.unregistered.key\"); } }";
        Scan scan = scan(Map.of(), "synthetic", source, new LinkedHashMap<>(), new ArrayList<>());
        Assertions.assertEquals(Set.of("new.unregistered.key"), scan.keyToFile().keySet());
        Set<String> unregistered = new TreeSet<>(scan.keyToFile().keySet());
        unregistered.removeAll(ConfigKeyCheck.knownKeys());
        Assertions.assertEquals(Set.of("new.unregistered.key"), unregistered);
    }

    @Test
    @DisplayName("the source scan resolves a qualified constant and reports an unresolvable argument")
    public void scannerResolvesConstantsAndReportsDynamicReads() {
        String holder = "class H { static final String OTHER_KEY = \"other.key\"; }";
        String reader = "class R { void f(Properties p, String k) { p.getProperty(H.OTHER_KEY, \"x\"); p.getProperty(k); } }";
        Map<String, Set<String>> constants = constants(List.of(holder, reader));
        Scan scan = scan(constants, "synthetic", reader, new LinkedHashMap<>(), new ArrayList<>());
        Assertions.assertEquals(Set.of("other.key"), scan.keyToFile().keySet());
        Assertions.assertEquals(1, scan.unresolved().size(), scan.unresolved().toString());
    }

    @Test
    @DisplayName("the shipped defaults and the test configuration warn by default and never refuse")
    public void shippedDefaultsWarnButNeverRefuseByDefault() throws Exception {
        // The shipped file is read by its module path. The test classpath holds a fixture with the same name whose
        // unescaped multi-line DDL is deliberate (OffsetTableDdlSortKeyTest matches it as raw text), so it is not linted.
        Properties shipped = ConfigKeyCheck.load("src/main/resources/config.properties");
        Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(shipped), "shipped defaults");
        Properties test = ConfigKeyCheck.load("src/test/resources/config.yml");
        Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(test), "test configuration keys");
    }

    @Test
    @DisplayName("every key the latin1text registration sets is known, and a misspelt type key is not")
    public void latin1TextRegistrationKeysAreKnown() {
        Set<String> known = ConfigKeyCheck.knownKeys();
        Properties registered = new Properties();
        Latin1TextConverter.ensureRegistered(registered);
        Assertions.assertTrue(registered.stringPropertyNames().contains("latin1text.type"), registered.toString());
        for (String key : registered.stringPropertyNames()) {
            Assertions.assertTrue(ConfigKeyCheck.isKnown(key, known), key + " is set by the registration");
        }
        Assertions.assertFalse(ConfigKeyCheck.isKnown("latin1text.typ", known));
        Assertions.assertFalse(ConfigKeyCheck.isKnown("latin1text.types", known));
        Properties operator = new Properties();
        operator.setProperty("latin1text.type", "operator.Own");
        Assertions.assertDoesNotThrow(() -> ConfigKeyCheck.check(operator), "an operator-set converter type key");
    }

    @Test
    @DisplayName("the IGNORED reason names the key to use instead")
    public void ignoredReasonNamesTheReplacement() {
        ConfigKeyCheck.Finding f = only(props("schema.history.internal.table.ddl", "x"));
        Assertions.assertTrue(f.detail().contains("schema.history.internal.jdbc.table.ddl"), f.detail());
    }
}
