package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 09.05: the static facts that are written next to each offset. Pure functions, no database.
 */
public class ConnectorStaticInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ConnectorStaticInfo.Inputs inputs(String dockerTag, String invocationId, String jarPath) {
        return new ConnectorStaticInfo.Inputs(dockerTag, invocationId, jarPath, "sink-prod",
                "UTC", "America/Chicago", false, "21.0.4", "Amazon.com Inc.", "Linux", "amd64",
                "UTC", "3.1.3.Final", 4242L, "host-a", 1792900000123L);
    }

    @Test
    @DisplayName("the Docker image tag wins over a version in the jar path")
    public void dockerTagWins() {
        ConnectorStaticInfo.Version v = ConnectorStaticInfo.resolveVersion("2.11.1-lt",
                "/opt/software/clickhouse-sink-connector-2.8.0/bin/clickhouse-sink-connector.jar");
        assertEquals("2.11.1-lt", v.value());
        assertEquals("env:DOCKER_TAG", v.source());
    }

    @Test
    @DisplayName("the version is read from the module directory of the jar, nearest directory first")
    public void versionFromJarPath() {
        ConnectorStaticInfo.Version v = ConnectorStaticInfo.resolveVersion(null,
                "/opt/software/clickhouse-sink-connector-2.11.0/bin/clickhouse-sink-connector.jar");
        assertEquals("2.11.0", v.value());
        assertEquals("jar_path", v.source());

        ConnectorStaticInfo.Version suffixed = ConnectorStaticInfo.resolveVersion("  ",
                "/opt/clickhouse-sink-connector-2.11.1-patch1/bin/app.jar");
        assertEquals("2.11.1-patch1", suffixed.value());

        ConnectorStaticInfo.Version nearest = ConnectorStaticInfo.resolveVersion(null,
                "/opt/9.9.9/clickhouse-sink-connector-2.10.1/bin/app.jar");
        assertEquals("2.10.1", nearest.value(), "the directory nearest the jar is searched first");
    }

    @Test
    @DisplayName("no version token, a token farther than three directories, a longer token and no path are unknown")
    public void unknownWhenNothingNamesTheVersion() {
        for (String path : new String[]{
                "/app.jar",
                "/home/u/csc/sink-connector-lightweight/target/clickhouse-debezium-embedded-0.0.9.jar",
                "/opt/2.11.0/a/b/c/app.jar",
                "/opt/clickhouse-sink-connector-12.11.0x/bin/app.jar",
                "",
                null}) {
            ConnectorStaticInfo.Version v = ConnectorStaticInfo.resolveVersion(null, path);
            assertEquals(ConnectorStaticInfo.VERSION_UNKNOWN, v.value(), String.valueOf(path));
            assertEquals("none", v.source(), String.valueOf(path));
        }
    }

    @Test
    @DisplayName("the collected fields are exactly the whitelist, in a stable order")
    public void collectedFieldsAreTheWhitelist() {
        Map<String, Object> fields = ConnectorStaticInfo.collect(inputs(null, "inv-1",
                "/opt/software/clickhouse-sink-connector-2.11.0/bin/clickhouse-sink-connector.jar"));
        Set<String> expected = new LinkedHashSet<>(java.util.List.of("schema", "connector_version",
                "version_source", "connector_name", "jar_path", "deployment", "java_version", "java_vendor",
                "os_name", "os_arch", "jvm_time_zone", "source_time_zone", "clickhouse_time_zone",
                "jdbc_driver", "debezium_version", "host", "pid", "started_utc", "started_epoch_s"));
        assertEquals(expected, fields.keySet(), "a new field must be reviewed here: no credential, no URL");
        assertEquals(java.util.List.copyOf(expected), java.util.List.copyOf(fields.keySet()));
        assertEquals("systemd", fields.get("deployment"));
        assertEquals("v2", fields.get("jdbc_driver"));
    }

    @Test
    @DisplayName("the deployment is docker with an image tag, systemd with an invocation id, process otherwise")
    public void deploymentKinds() {
        assertEquals("docker", ConnectorStaticInfo.collect(inputs("tag", "inv", null)).get("deployment"));
        assertEquals("systemd", ConnectorStaticInfo.collect(inputs(null, "inv", null)).get("deployment"));
        assertEquals("process", ConnectorStaticInfo.collect(inputs(null, null, null)).get("deployment"));
    }

    @Test
    @DisplayName("a configuration full of secrets cannot reach the JSON: only the whitelist is read")
    public void secretsNeverReachTheJson() {
        Properties props = new Properties();
        props.setProperty("name", "engine");
        props.setProperty("clickhouse.server.password", "SECRET-ONE");
        props.setProperty("database.password", "SECRET-TWO");
        props.setProperty("offset.storage.jdbc.password", "SECRET-THREE");
        props.setProperty("offset.storage.jdbc.url", "jdbc:clickhouse://host:8123/db?password=SECRET-FOUR");
        props.setProperty("database.user", "SECRET-FIVE");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(
                ZoneFixtures.withZones(new HashMap<>()));
        String json = ConnectorStaticInfo.toJson(ConnectorStaticInfo.collect(
                ConnectorStaticInfo.Inputs.live(props, config)));
        assertFalse(json.contains("SECRET"), json);
        assertTrue(json.contains("\"connector_name\":\"engine\""), json);
        assertTrue(json.contains("\"source_time_zone\":\"UTC\""), json);
        assertTrue(json.contains("\"clickhouse_time_zone\":\"UTC\""), json);
    }

    @Test
    @DisplayName("the live inputs read this process without failing")
    public void liveInputsReadTheProcess() throws Exception {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(
                ZoneFixtures.withZones(new HashMap<>()));
        Map<String, Object> fields = ConnectorStaticInfo.collect(
                ConnectorStaticInfo.Inputs.live(new Properties(), config));
        JsonNode node = MAPPER.readTree(ConnectorStaticInfo.toJson(fields));
        assertEquals(ConnectorStaticInfo.SCHEMA_VERSION, node.get("schema").asInt());
        assertNotNull(node.get("java_version"));
        assertTrue(node.get("pid").asLong() > 0);
        assertFalse(node.path("debezium_version").asText("").isBlank(), node.toString());
        assertTrue(node.get("started_epoch_s").asLong() > 0, node.toString());
    }

    @Test
    @DisplayName("every time is written as an ISO instant with Z and as epoch seconds")
    public void timesAreExplicit() throws Exception {
        JsonNode node = MAPPER.readTree(ConnectorStaticInfo.toJson(
                ConnectorStaticInfo.collect(inputs(null, null, null))));
        assertEquals("2026-10-25T03:46:40.123Z", node.get("started_utc").asText());
        assertEquals(1792900000L, node.get("started_epoch_s").asLong());
    }

    @Test
    @DisplayName("an unknown start time, pid and host are left out, not written as zero")
    public void unknownFieldsAreLeftOut() {
        ConnectorStaticInfo.Inputs in = new ConnectorStaticInfo.Inputs(null, null, null, null, null, null, true,
                null, null, null, null, null, null, -1L, null, -1L);
        Map<String, Object> fields = ConnectorStaticInfo.collect(in);
        assertEquals(java.util.List.of("schema", "connector_version", "version_source", "deployment",
                "jdbc_driver"), java.util.List.copyOf(fields.keySet()));
        assertEquals("v1", fields.get("jdbc_driver"));
    }

    @Test
    @DisplayName("the JSON never holds an apostrophe or a question mark, and reads back to the same text")
    public void jsonHoldsNoStatementCharacters() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", 1);
        fields.put("connector_name", "it's a?b %s 100% \\ back \"quote\" café 😀");
        String json = ConnectorStaticInfo.toJson(fields);
        assertFalse(json.contains("'"), json);
        assertFalse(json.contains("?"), json);
        assertTrue(json.chars().allMatch(c -> c < 128), "plain ASCII: " + json);
        assertEquals("it's a?b %s 100% \\ back \"quote\" café 😀",
                MAPPER.readTree(json).get("connector_name").asText());
    }

    @Test
    @DisplayName("a long value is cut and the document never exceeds its bound")
    public void documentIsBounded() throws Exception {
        String longText = "x".repeat(10_000);
        ConnectorStaticInfo.Inputs in = new ConnectorStaticInfo.Inputs(longText, longText, longText, longText,
                longText, longText, false, longText, longText, longText, longText, longText, longText, 1L,
                longText, 1L);
        Map<String, Object> fields = ConnectorStaticInfo.collect(in);
        for (Object value : fields.values()) {
            if (value instanceof String s) {
                assertTrue(s.length() <= ConnectorStaticInfo.MAX_VALUE_CHARS, "value length " + s.length());
            }
        }
        String json = ConnectorStaticInfo.toJson(fields);
        assertTrue(json.length() <= ConnectorStaticInfo.MAX_JSON_CHARS, "json length " + json.length());
        JsonNode node = MAPPER.readTree(json);
        assertEquals(java.util.List.of("schema", "connector_version", "version_source"),
                java.util.List.copyOf(fieldNames(node)),
                "past the bound only the identity fields are kept");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("a cut never splits a surrogate pair")
    public void cutKeepsSurrogatePairsWhole() {
        String text = "a".repeat(ConnectorStaticInfo.MAX_VALUE_CHARS - 1) + "😀";
        String cut = ConnectorStaticInfo.bound(text);
        assertTrue(cut.length() <= ConnectorStaticInfo.MAX_VALUE_CHARS);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "no lone high surrogate");
        assertEquals(ConnectorStaticInfo.MAX_VALUE_CHARS - 1, cut.length());

        String fits = "a".repeat(ConnectorStaticInfo.MAX_VALUE_CHARS - 2) + "😀";
        assertEquals(ConnectorStaticInfo.MAX_VALUE_CHARS, fits.length());
        assertEquals(fits, ConnectorStaticInfo.bound(fits), "a pair that fits is kept whole");
    }

    @Test
    @DisplayName("the Docker tag is a field of its own, and a blank tag is left out")
    public void dockerTagFieldIsOptional() {
        assertEquals("2.11.1-lt", ConnectorStaticInfo.collect(inputs("2.11.1-lt", null, null)).get("docker_tag"));
        assertFalse(ConnectorStaticInfo.collect(inputs("  ", null, null)).containsKey("docker_tag"));
    }
}
