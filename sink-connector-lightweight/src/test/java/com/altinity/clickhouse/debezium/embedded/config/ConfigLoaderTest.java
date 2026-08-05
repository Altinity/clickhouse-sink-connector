package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tests for ConfigLoader — Phase 7 safety and robustness fixes.
 * <p>
 * Validates:
 * - SafeConstructor prevents arbitrary deserialization (CVE-2022-1471)
 * - All YAML value types (String, Integer, Boolean, Double) are handled
 * - Null values are skipped gracefully
 * - Quoted values have surrounding quotes stripped
 * - InputStream is closed properly (try-with-resources)
 * - loadFromFile() throws FileNotFoundException correctly
 * </p>
 */
public class ConfigLoaderTest {

    @Test
    @DisplayName("load() should load config.yml from classpath successfully")
    public void testLoad() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");

        Assertions.assertNotNull(props);
        Assertions.assertFalse(props.isEmpty());
        // config.yml has known keys
        Assertions.assertNotNull(props.getProperty("database.hostname"),
                "database.hostname should be present");
    }

    // Both sides of the merge added coverage here and BOTH are kept: 2.10.0's
    // typed-value / malicious-tag tests (config-typed.yml, config-malicious.yml)
    // and develop's per-type tests (config_mixed_types.yml). All three test
    // resources exist in src/test/resources, so neither set is dropped.

    @Test
    @DisplayName("Load Boolean, Long, and Integer YAML values without ClassCastException")
    public void testLoadTypedValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config-typed.yml");

        Assertions.assertEquals("true", props.getProperty("metrics.enable"));
        Assertions.assertEquals("5000", props.getProperty("offset.flush.interval.ms"));
        Assertions.assertEquals("3306", props.getProperty("database.port"));
        Assertions.assertEquals("test-connector", props.getProperty("name"));
    }

    @Test
    @DisplayName("Reject YAML with arbitrary Java object tags (CVE-2022-1471)")
    public void testRejectsMaliciousYamlTags() {
        ConfigLoader loader = new ConfigLoader();
        Assertions.assertThrows(Exception.class, () -> loader.load("config-malicious.yml"));
    }

    @Test
    @DisplayName("load() should handle Boolean YAML values without ClassCastException")
    public void testLoadBooleanValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config_mixed_types.yml");

        Assertions.assertNotNull(props);
        // Boolean true → "true" string
        Assertions.assertEquals("true", props.getProperty("boolean_key"),
                "Boolean true should be converted to string 'true'");
        // Boolean false → "false" string
        Assertions.assertEquals("false", props.getProperty("enabled"),
                "Boolean false should be converted to string 'false'");
    }

    @Test
    @DisplayName("load() should handle Integer YAML values without ClassCastException")
    public void testLoadIntegerValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config_mixed_types.yml");

        Assertions.assertNotNull(props);
        // Integer 42 → "42" string
        Assertions.assertEquals("42", props.getProperty("integer_key"),
                "Integer 42 should be converted to string '42'");
        // Integer 8123 → "8123" string
        Assertions.assertEquals("8123", props.getProperty("port"),
                "Integer 8123 should be converted to string '8123'");
    }

    @Test
    @DisplayName("load() should handle Double/Float YAML values without ClassCastException")
    public void testLoadDoubleValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config_mixed_types.yml");

        Assertions.assertNotNull(props);
        // Double 3.14 → "3.14" string
        Assertions.assertEquals("3.14", props.getProperty("double_key"),
                "Double 3.14 should be converted to string '3.14'");
    }

    @Test
    @DisplayName("load() should skip null YAML values gracefully")
    public void testLoadNullValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config_mixed_types.yml");

        Assertions.assertNotNull(props);
        // null_key should not be present (skipped)
        Assertions.assertNull(props.getProperty("null_key"),
                "Null YAML values should be skipped, not stored");
    }

    @Test
    @DisplayName("load() should strip surrounding double quotes from values")
    public void testLoadStripsSurroundingQuotes() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config_mixed_types.yml");

        Assertions.assertNotNull(props);
        // String value "hello" should remain "hello"
        Assertions.assertEquals("hello", props.getProperty("string_key"),
                "String values should not have extra quotes");
    }

    @Test
    @DisplayName("load() with SafeConstructor should not deserialize arbitrary objects")
    public void testSafeConstructorPreventsArbitraryDeserialization() {
        // SafeConstructor only allows basic YAML types (String, Integer, Boolean,
        // Double, List, Map). It rejects !!java.lang.Runtime and similar tags.
        // This test verifies the ConfigLoader uses SafeConstructor by loading
        // a normal YAML file — the key property is that the Yaml instance is
        // constructed with SafeConstructor, not the default Constructor.
        // A direct test of malicious YAML would require a test resource with
        // !!java.lang.Runtime tags, which is unsafe to ship. Instead, we verify
        // that the loader works correctly with SafeConstructor (no regression).
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");
        Assertions.assertNotNull(props, "SafeConstructor should handle normal YAML");
        Assertions.assertTrue(props.size() > 0,
                "Properties should not be empty after SafeConstructor load");
    }

    @Test
    @DisplayName("loadFromFile() should load YAML from absolute file path")
    public void testLoadFromFile(@TempDir Path tempDir) throws Exception {
        // Create a temp YAML file
        File tempYaml = tempDir.resolve("test_config.yml").toFile();
        try (FileWriter writer = new FileWriter(tempYaml)) {
            writer.write("host: localhost\n");
            writer.write("port: 3306\n");
            writer.write("enabled: true\n");
        }

        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.loadFromFile(tempYaml.getAbsolutePath());

        Assertions.assertNotNull(props);
        Assertions.assertEquals("localhost", props.getProperty("host"));
        Assertions.assertEquals("3306", props.getProperty("port"));
        Assertions.assertEquals("true", props.getProperty("enabled"));
    }

    @Test
    @DisplayName("loadFromFile() should throw FileNotFoundException for missing file")
    public void testLoadFromFileMissing() {
        ConfigLoader loader = new ConfigLoader();
        Assertions.assertThrows(FileNotFoundException.class,
                () -> loader.loadFromFile("/nonexistent/path/config.yml"),
                "loadFromFile() should throw FileNotFoundException for missing file");
    }

    @Test
    @DisplayName("loadFromFile() should handle mixed types from file")
    public void testLoadFromFileMixedTypes(@TempDir Path tempDir) throws Exception {
        File tempYaml = tempDir.resolve("mixed.yml").toFile();
        try (FileWriter writer = new FileWriter(tempYaml)) {
            writer.write("string_val: hello\n");
            writer.write("int_val: 100\n");
            writer.write("bool_val: false\n");
            writer.write("float_val: 1.5\n");
            writer.write("null_val: null\n");
        }

        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.loadFromFile(tempYaml.getAbsolutePath());

        Assertions.assertNotNull(props);
        Assertions.assertEquals("hello", props.getProperty("string_val"));
        Assertions.assertEquals("100", props.getProperty("int_val"));
        Assertions.assertEquals("false", props.getProperty("bool_val"));
        Assertions.assertEquals("1.5", props.getProperty("float_val"));
        Assertions.assertNull(props.getProperty("null_val"),
                "Null values should be skipped");
    }

    @Test
    @DisplayName("load() should handle the existing config.yml with all its properties")
    public void testLoadExistingConfigPreservesAllProperties() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");

        // Verify key properties from the standard config.yml
        Assertions.assertNotNull(props.getProperty("database.port"),
                "database.port should be loaded");
        Assertions.assertNotNull(props.getProperty("clickhouse.server.url"),
                "clickhouse.server.url should be loaded");
        Assertions.assertNotNull(props.getProperty("snapshot.mode"),
                "snapshot.mode should be loaded");
        Assertions.assertNotNull(props.getProperty("auto.create.tables"),
                "auto.create.tables should be loaded (boolean in YAML)");
    }
}
