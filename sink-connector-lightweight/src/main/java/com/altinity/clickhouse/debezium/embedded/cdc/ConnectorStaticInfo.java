package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The static facts about a running connector that {@link OffsetExtraInfo} writes next to each offset
 * (spec 09.05).
 *
 * <p>Static means fixed for the life of the process: the version, where the jar came from, the Java and OS
 * it runs on, the two explicit time zones, and when the process started. Nothing here changes while the
 * connector runs, and nothing here is read from the source or the target.</p>
 *
 * <p><b>Whitelist, never a dump.</b> {@link #collect(Inputs)} copies a fixed list of fields. It never walks
 * the configuration, so a password, user name or URL cannot reach the JSON. Every time is written twice, as
 * an ISO instant with {@code Z} and as epoch seconds, so no reader has to guess a zone.</p>
 *
 * <p><b>Bounded.</b> A value longer than {@link #MAX_VALUE_CHARS} is cut. A document longer than
 * {@link #MAX_JSON_CHARS} is replaced by the identity fields alone, so the text is always short enough to sit
 * in an INSERT statement.</p>
 */
final class ConnectorStaticInfo {

    /** Version of this JSON layout; bump it when a field changes meaning. */
    static final int SCHEMA_VERSION = 1;

    /** The longest text a single value may carry. */
    static final int MAX_VALUE_CHARS = 256;

    /** The longest document {@link #toJson} returns. */
    static final int MAX_JSON_CHARS = 2048;

    /** Where the version came from when nothing named it. */
    static final String VERSION_UNKNOWN = "unknown";

    /** The directories nearest the jar that are searched for a version token. */
    private static final int JAR_PATH_LEVELS = 3;

    /**
     * A release number such as {@code 2.11.1}, with an optional suffix such as {@code -patch1}, bounded by
     * characters that cannot belong to another token. The fleet puts it in the module directory name
     * ({@code clickhouse-sink-connector-2.11.1}).
     */
    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "(?<![0-9A-Za-z.])(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z][0-9A-Za-z._]*)?)(?![0-9A-Za-z.])");

    /**
     * The JSON writer. Non-ASCII text is escaped so the document is plain ASCII, and {@link #write} escapes
     * the two characters that matter inside a JDBC statement.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);

    private ConnectorStaticInfo() {
    }

    /**
     * A resolved version and how it was found.
     *
     * @param value  the version text
     * @param source {@code env:DOCKER_TAG}, {@code jar_path} or {@code none}
     */
    record Version(String value, String source) {
    }

    /**
     * Everything {@link #collect(Inputs)} reads, so a test can supply it without touching the process.
     *
     * @param dockerTag        the {@code DOCKER_TAG} environment value, or null
     * @param invocationId     the systemd {@code INVOCATION_ID} environment value, or null
     * @param jarPath          the path of the running jar, or null when the code does not run from a jar
     * @param connectorName    the {@code name} property, or null
     * @param sourceZone       {@code database.connectionTimeZone}, or null
     * @param clickHouseZone   {@code clickhouse.datetime.timezone}, or null
     * @param jdbcV1           true when the legacy V1 ClickHouse JDBC driver is configured
     * @param javaVersion      {@code java.version}, or null
     * @param javaVendor       {@code java.vendor}, or null
     * @param osName           {@code os.name}, or null
     * @param osArch           {@code os.arch}, or null
     * @param jvmZone          the JVM default zone id, or null
     * @param debeziumVersion  the Debezium library version, or null
     * @param pid              the process id, or a negative number when unknown
     * @param host             the host name, or null
     * @param startedEpochMs   the process start time in epoch milliseconds, or a negative number when unknown
     */
    record Inputs(String dockerTag, String invocationId, String jarPath, String connectorName,
                  String sourceZone, String clickHouseZone, boolean jdbcV1, String javaVersion,
                  String javaVendor, String osName, String osArch, String jvmZone, String debeziumVersion,
                  long pid, String host, long startedEpochMs) {

        /**
         * Reads the live process. Every read is guarded: a field that cannot be read is left out, never an
         * error.
         *
         * @param props  the connector properties
         * @param config the connector configuration
         * @return the inputs of this process
         */
        static Inputs live(Properties props, ClickHouseSinkConnectorConfig config) {
            Supplier<String> sourceZone = () -> config.getString(
                    ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
            Supplier<String> chZone = () -> config.getString(
                    ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString());
            Supplier<Boolean> v1 = () -> config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString());
            return new Inputs(
                    System.getenv("DOCKER_TAG"),
                    System.getenv("INVOCATION_ID"),
                    jarPathOf(ConnectorStaticInfo.class),
                    props == null ? null : props.getProperty("name"),
                    guarded(sourceZone),
                    guarded(chZone),
                    Boolean.TRUE.equals(guarded(v1)),
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    guarded(() -> ZoneId.systemDefault().getId()),
                    guarded(io.debezium.Module::version),
                    guarded(() -> ProcessHandle.current().pid(), -1L),
                    hostName(),
                    guarded(() -> java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime(), -1L));
        }
    }

    private static <T> T guarded(Supplier<T> read) {
        return guarded(read, null);
    }

    private static <T> T guarded(Supplier<T> read, T fallback) {
        try {
            T value = read.get();
            return value == null ? fallback : value;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }

    /** The kernel host name, read from the file the kernel exposes; no name service is asked. */
    private static String hostName() {
        try {
            return Files.readString(Path.of("/proc/sys/kernel/hostname")).trim();
        } catch (Exception e) {
            return System.getenv("HOSTNAME");
        }
    }

    /**
     * The path of the jar (or class directory) that holds {@code anchor}.
     *
     * @param anchor a class of this connector
     * @return the absolute path, or null when it cannot be determined
     */
    static String jarPathOf(Class<?> anchor) {
        try {
            return Paths.get(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves the connector version. The order is fixed: the Docker image tag, then a release number in
     * one of the three directories nearest the jar, then unknown. Nothing in the jar carries the release
     * version (the Maven version is a placeholder), so the deployment is the only witness.
     *
     * @param dockerTag the {@code DOCKER_TAG} environment value, or null
     * @param jarPath   the path of the running jar, or null
     * @return the version and its source; never null
     */
    static Version resolveVersion(String dockerTag, String jarPath) {
        if (dockerTag != null && !dockerTag.isBlank()) {
            return new Version(dockerTag.trim(), "env:DOCKER_TAG");
        }
        if (jarPath != null && !jarPath.isBlank()) {
            Path dir = Paths.get(jarPath).getParent();
            for (int level = 0; dir != null && level < JAR_PATH_LEVELS; level++, dir = dir.getParent()) {
                Path name = dir.getFileName();
                if (name == null) {
                    break;
                }
                Matcher token = VERSION_TOKEN.matcher(name.toString());
                if (token.find()) {
                    return new Version(token.group(1), "jar_path");
                }
            }
        }
        return new Version(VERSION_UNKNOWN, "none");
    }

    /**
     * Builds the field map from a fixed whitelist. A null or blank input is left out.
     *
     * @param in the inputs
     * @return the fields in a stable order
     */
    static Map<String, Object> collect(Inputs in) {
        Version version = resolveVersion(in.dockerTag(), in.jarPath());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", SCHEMA_VERSION);
        fields.put("connector_version", bound(version.value()));
        fields.put("version_source", version.source());
        put(fields, "connector_name", in.connectorName());
        put(fields, "jar_path", in.jarPath());
        put(fields, "docker_tag", in.dockerTag());
        fields.put("deployment", deployment(in));
        put(fields, "java_version", in.javaVersion());
        put(fields, "java_vendor", in.javaVendor());
        put(fields, "os_name", in.osName());
        put(fields, "os_arch", in.osArch());
        put(fields, "jvm_time_zone", in.jvmZone());
        put(fields, "source_time_zone", in.sourceZone());
        put(fields, "clickhouse_time_zone", in.clickHouseZone());
        fields.put("jdbc_driver", in.jdbcV1() ? "v1" : "v2");
        put(fields, "debezium_version", in.debeziumVersion());
        put(fields, "host", in.host());
        if (in.pid() >= 0) {
            fields.put("pid", in.pid());
        }
        if (in.startedEpochMs() >= 0) {
            fields.put("started_utc", Instant.ofEpochMilli(in.startedEpochMs()).toString());
            fields.put("started_epoch_s", in.startedEpochMs() / 1000L);
        }
        return fields;
    }

    private static String deployment(Inputs in) {
        if (in.dockerTag() != null && !in.dockerTag().isBlank()) {
            return "docker";
        }
        if (in.invocationId() != null && !in.invocationId().isBlank()) {
            return "systemd";
        }
        return "process";
    }

    private static void put(Map<String, Object> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, bound(value.trim()));
        }
    }

    /**
     * Cuts a text to {@link #MAX_VALUE_CHARS} characters without splitting a surrogate pair.
     *
     * @param text the text
     * @return the text, or its first characters
     */
    static String bound(String text) {
        if (text.length() <= MAX_VALUE_CHARS) {
            return text;
        }
        int end = MAX_VALUE_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Writes the fields as one JSON object no longer than {@link #MAX_JSON_CHARS}. Past that limit only
     * {@code schema}, {@code connector_version} and {@code version_source} are written.
     *
     * @param fields the field map from {@link #collect}
     * @return the JSON text; plain ASCII, with no {@code '} and no {@code ?} character
     */
    static String toJson(Map<String, Object> fields) {
        String json = write(fields);
        if (json.length() <= MAX_JSON_CHARS) {
            return json;
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        for (String key : new String[]{"schema", "connector_version", "version_source"}) {
            if (fields.containsKey(key)) {
                identity.put(key, fields.get(key));
            }
        }
        return write(identity);
    }

    /**
     * Serialises the map. The apostrophe and the question mark are written as JSON escapes: both are legal
     * only inside a string, where {@code '} and {@code ?} mean the same character, and neither can
     * then be taken for a statement quote or a bind placeholder by a JDBC driver.
     */
    private static String write(Map<String, Object> fields) {
        try {
            return JSON.writeValueAsString(fields).replace("'", "\\u0027").replace("?", "\\u003f");
        } catch (JsonProcessingException e) {
            // Values are strings, numbers and booleans; a failure is a programming error, never data. The
            // caller of OffsetExtraInfo treats any exception as "not available" and starts normally.
            throw new IllegalStateException("could not write the connector static info", e);
        }
    }
}
