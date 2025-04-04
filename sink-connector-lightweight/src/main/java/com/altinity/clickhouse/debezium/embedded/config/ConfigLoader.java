package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
     * Loads properties from a YAML file located on the classpath.
     *
     * @param resourceFileName The name of the resource file on the classpath.
     * @return A {@link Properties} object containing the configuration
     *         key-value pairs.
     */
    public Properties load(String resourceFileName) {
        InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream(resourceFileName);

        Map<String, Object> yamlFile = new Yaml().load(fis);

        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            if (entry.getValue() instanceof Integer) {
                props.setProperty(
                        entry.getKey(),
                        Integer.toString((Integer) entry.getValue())
                );
            } else {
                String value = (String) entry.getValue();
                props.setProperty(
                        entry.getKey(),
                        value.replace("\"", "")
                );
            }
        }
        return props;
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

        InputStream fis = new FileInputStream(fileName);
        Map<String, Object> yamlFile = new Yaml().load(fis);

        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            if (entry.getValue() instanceof Integer) {
                props.setProperty(
                        entry.getKey(),
                        Integer.toString((Integer) entry.getValue())
                );
            } else {
                String value = (String) entry.getValue();
                props.setProperty(
                        entry.getKey(),
                        value.replace("\"", "")
                );
            }
        }
        return props;
    }
}
