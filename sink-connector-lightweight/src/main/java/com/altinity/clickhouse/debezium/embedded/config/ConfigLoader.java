package com.altinity.clickhouse.debezium.embedded.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    private static final Logger log = LogManager.getLogger(ConfigLoader.class);

    public Properties load(String resourceFileName) {
        InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream(resourceFileName);

        Map<String, Object> yamlFile = new Yaml().load(fis);


        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            if(entry.getValue() instanceof Integer) {
                props.setProperty(entry.getKey(), Integer.toString((Integer) entry.getValue()));
            } else {
                Object entryValue = entry.getValue();
                // Check if value is an instance of String.
                if (entryValue instanceof String) {
                    entryValue = (String) entryValue;
                }
                else {
                    // Additional
                    log.info("entryValue is not a String");
                    if (entryValue instanceof LinkedHashMap) {
                        // iterate through the map and add the properties to the props.
                        for (Map.Entry<String, Object> mapEntry : ((LinkedHashMap<String, Object>) entryValue).entrySet()) {
                            // prfix the key with the entry key.
                            String key = entry.getKey() + "." + mapEntry.getKey();
                            props.setProperty(key, mapEntry.getValue().toString());
                        }
                    }

                }
                if (entryValue instanceof String) {
                    props.setProperty(entry.getKey(), ((String) entryValue).replace("\"", ""));
                }
            }
        }

        return props;
    }
    public Properties loadFromFile(String fileName) throws FileNotFoundException {
        InputStream fis  = new FileInputStream(fileName);
        Map<String, Object> yamlFile = new Yaml().load(fis);


        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            if(entry.getValue() instanceof Integer) {
                props.setProperty(entry.getKey(), Integer.toString((Integer) entry.getValue()));
            } else {
                String value = (String) entry.getValue();
                props.setProperty(entry.getKey(), value.replace("\"", ""));
            }
        }

        return props;
    }
}