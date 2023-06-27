package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    public Properties load(String fileName) {
        InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream(fileName);
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