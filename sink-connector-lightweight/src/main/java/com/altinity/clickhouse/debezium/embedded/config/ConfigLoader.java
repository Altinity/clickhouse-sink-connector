package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * A utility class responsible for loading configuration properties from
 * YAML files. It provides two methods: one that loads a resource file from
 * the classpath and another that loads from a specified file path.
 */
public class ConfigLoader {

    /**
     * Creates a Yaml instance with SafeConstructor to prevent
     * arbitrary object deserialization (CVE-2022-1471).
     *
     * @return A safely-configured Yaml instance.
     */
    private Yaml createSafeYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /**
     * Loads properties from a YAML file located on the classpath.
     *
     * @param resourceFileName The name of the resource file on the classpath.
     * @return A {@link Properties} object containing the configuration
     *         key-value pairs.
     */
    public Properties load(String resourceFileName) {
        // Use try-with-resources to ensure the InputStream is closed. 2.10.0's
        // variant declared the stream in the resource list but then parsed
        // INSIDE the resource specification, which does not compile.
        try (InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream(resourceFileName)) {

            Map<String, Object> yamlFile = createSafeYaml().load(fis);

            return convertYamlMapToProperties(yamlFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource: " + resourceFileName, e);
        }
    }

    /**
     * Loads properties from a YAML file specified by its file system path.
     *
     * @param fileName The full path of the YAML file.
     * @return A {@link Properties} object containing the configuration
     *         key-value pairs.
     * @throws FileNotFoundException If the specified file does not exist.
     */
    public Properties loadFromFile(String fileName)
            throws FileNotFoundException {
        // Use try-with-resources to ensure InputStream is closed
        try (InputStream fis = new FileInputStream(fileName)) {

            Map<String, Object> yamlFile = createSafeYaml().load(fis);

            return convertYamlMapToProperties(yamlFile);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file: " + fileName, e);
        }
    }

    /**
     * Converts a YAML map to a Properties object, handling all value types
     * safely (not just String and Integer).
     *
     * @param yamlFile The map parsed from the YAML file. May be {@code null}
     *                 when the document is empty.
     * @return A {@link Properties} object with string representations of all values.
     */
    private Properties convertYamlMapToProperties(Map<String, Object> yamlFile) {
        final Properties props = new Properties();

        // An empty YAML document parses to null. This guard is carried over
        // from the 2.10.0 side of the merge; without it an empty config file
        // throws a NullPointerException instead of yielding empty Properties.
        if (yamlFile == null) {
            return props;
        }

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            // Use toString() instead of casting to (String) to handle
            // all YAML value types: Integer, Long, Boolean, Double, etc.
            String stringValue = value.toString();
            // Strip surrounding quotes if present (legacy behavior)
            stringValue = stringValue.replace("\"", "");
            props.setProperty(entry.getKey(), stringValue);
        }
        return props;
    }
}
