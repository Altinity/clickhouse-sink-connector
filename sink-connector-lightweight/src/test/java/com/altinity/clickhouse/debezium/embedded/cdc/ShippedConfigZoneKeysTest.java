package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every connector configuration the repository ships, or loads in a test, names both mandatory zone keys, so
 * none is refused at start (spec 07.03 sections 3.1.2 and 3.1.5).
 *
 * <p>The lightweight YAML files go through the real {@link ConnectionTimeZonePreflight}, the check the engine runs
 * first. The Kafka Connect samples (shell, Python, YAML and Helm) are not loadable here, so each sink connector
 * block is checked as text: after the connector class, and before the next connector class, both keys appear.
 * A config that lacks a key is found here in seconds, not by a Docker integration test hours later.</p>
 */
public class ShippedConfigZoneKeysTest {

    private static final String SOURCE = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;
    private static final String CLICKHOUSE = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final Path MODULE = REPO_ROOT.resolve("sink-connector-lightweight");

    private static final Pattern CONNECTOR_CLASS =
            Pattern.compile("(?:connector\\.)?class[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9_.]+Connector)\\b");
    private static final String SINK_CLASS = "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector";

    private static final List<String> KAFKA_SAMPLE_DIRS = List.of(
            "sink-connector/deploy",
            "sink-connector/tests/integration",
            "sink-connector-lightweight/tests/integration");
    private static final List<String> SAMPLE_EXTENSIONS = List.of(".sh", ".py", ".yaml", ".yml", ".json");

    /** The lightweight YAML configurations: every shipped docker and k8s sample, and the two the integration tests load. */
    private static List<Path> lightweightYaml() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> docker = Files.list(MODULE.resolve("docker"))) {
            files.addAll(docker.filter(p -> p.getFileName().toString().matches("config.*\\.yml")).sorted()
                    .collect(Collectors.toList()));
        }
        files.add(MODULE.resolve("k8s/config.yml"));
        files.add(MODULE.resolve("src/test/resources/config.yml"));
        files.add(MODULE.resolve("src/test/resources/config_postgres.yml"));
        return files;
    }

    @Test
    @DisplayName("every shipped and test-loaded lightweight YAML configuration passes the zone preflight")
    void lightweightYamlConfigurationsPassThePreflight() throws Exception {
        List<Path> files = lightweightYaml();
        assertTrue(files.size() >= 10, "the configurations found: " + files);
        List<String> refused = new ArrayList<>();
        for (Path file : files) {
            assertTrue(Files.isRegularFile(file), "a configuration this test names is missing: " + file);
            Properties props = new ConfigLoader().loadFromFile(file.toString());
            try {
                ConnectionTimeZonePreflight.check(props);
            } catch (IllegalStateException e) {
                refused.add(MODULE.relativize(file) + ": " + e.getMessage());
            }
        }
        assertEquals(List.of(), refused, "configurations the engine would refuse to start with");
    }

    @Test
    @DisplayName("the Postgres test configuration states both zones, so the Postgres integration tests start")
    void postgresTestConfigurationStatesBothZones() throws Exception {
        Properties props = new ConfigLoader().loadFromFile(
                MODULE.resolve("src/test/resources/config_postgres.yml").toString());
        assertEquals("UTC", props.getProperty(SOURCE));
        assertEquals("UTC", props.getProperty(CLICKHOUSE));
    }

    /** The sink connector blocks of one file: the text from each sink connector class to the next connector class. */
    static List<String> sinkBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = CONNECTOR_CLASS.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            classes.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            if (classes.get(i).equals(SINK_CLASS)) {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
                blocks.add(text.substring(spans.get(i)[0], end));
            }
        }
        return blocks;
    }

    @Test
    @DisplayName("every Kafka Connect sink connector sample names both zone keys")
    void kafkaConnectSamplesNameBothZoneKeys() throws IOException {
        int blocks = 0;
        List<String> missing = new ArrayList<>();
        for (String dir : KAFKA_SAMPLE_DIRS) {
            Path root = REPO_ROOT.resolve(dir);
            assertTrue(Files.isDirectory(root), "a directory this test scans is missing: " + root);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> SAMPLE_EXTENSIONS.stream().anyMatch(p.getFileName().toString()::endsWith))
                        .sorted().collect(Collectors.toList());
            }
            for (Path file : files) {
                for (String block : sinkBlocks(Files.readString(file))) {
                    blocks++;
                    for (String key : List.of(SOURCE, CLICKHOUSE)) {
                        if (!block.contains(key)) {
                            missing.add(REPO_ROOT.relativize(file) + " lacks " + key);
                        }
                    }
                }
            }
        }
        // the sink connector blocks the repository ships today; a floor so the scan can never pass by finding nothing
        assertTrue(blocks >= 15, "sink connector blocks found: " + blocks);
        assertEquals(List.of(), missing, "sink connector samples a Kafka Connect worker would refuse");
    }

    @Test
    @DisplayName("a block is cut at the next connector class, so a source connector's keys never count for the sink")
    void aBlockEndsAtTheNextConnectorClass() {
        String text = "{\"connector.class\": \"" + SINK_CLASS + "\", \"a\": 1}\n"
                + "{\"connector.class\": \"io.debezium.connector.mysql.MySqlConnector\", \"" + SOURCE + "\": \"UTC\", \""
                + CLICKHOUSE + "\": \"UTC\"}";
        List<String> blocks = sinkBlocks(text);
        assertEquals(1, blocks.size());
        assertTrue(!blocks.get(0).contains(SOURCE) && !blocks.get(0).contains(CLICKHOUSE), blocks.get(0));
    }

    @Test
    @DisplayName("a YAML custom-resource class line starts a sink block as well")
    void aCustomResourceClassLineStartsABlock() {
        String text = "spec:\n  class: " + SINK_CLASS + "\n  config:\n    " + SOURCE + ": \"UTC\"\n";
        assertEquals(1, sinkBlocks(text).size());
        assertTrue(sinkBlocks(text).get(0).contains(SOURCE));
    }
}
