package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.FileInputStream;
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

    private static Yaml createSafeYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static Properties toProperties(Map<String, Object> yamlFile) {
        final Properties props = new Properties();
        if (yamlFile == null) {
            return props;
        }
        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            Object value = entry.getValue();
            if (value != null) {
                String strValue = String.valueOf(value);
                if (value instanceof String) {
                    strValue = strValue.replace("\"", "");
                }
                props.setProperty(entry.getKey(), strValue);
            }
        }
        return props;
    }

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

        Map<String, Object> yamlFile = createSafeYaml().load(fis);
        return toProperties(yamlFile);
    }

    /**
     * Loads properties from a YAML file specified by its file system path.
     *
     * @param fileName The full path of the YAML file.
     * @return A {@link Properties} object containing the configuration
     *         key-value pairs.
     * @throws IOException If the specified file cannot be read.
     */
    public Properties loadFromFile(String fileName)
            throws IOException {

        try (InputStream fis = new FileInputStream(fileName)) {
            Map<String, Object> yamlFile = createSafeYaml().load(fis);
            return toProperties(yamlFile);
        }
    }
}
