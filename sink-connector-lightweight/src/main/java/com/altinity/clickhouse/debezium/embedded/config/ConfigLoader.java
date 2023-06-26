package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    public Properties load(String resourceFileName) {
        InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream(resourceFileName);
        Map<String, Object> yamlFile = new Yaml().load(fis);


        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            props.setProperty(entry.getKey(), (String) entry.getValue());
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
                props.setProperty(entry.getKey(), (String) entry.getValue());
            }
        }

        return props;
    }
}