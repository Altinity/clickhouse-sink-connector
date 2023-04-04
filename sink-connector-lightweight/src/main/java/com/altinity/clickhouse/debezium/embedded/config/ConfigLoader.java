package com.altinity.clickhouse.debezium.embedded.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    public Properties load() {
        InputStream fis = this.getClass()
                .getClassLoader()
                .getResourceAsStream("config.yaml");
        Map<String, Object> yamlFile = new Yaml().load(fis);


        final Properties props = new Properties();

        for (Map.Entry<String, Object> entry : yamlFile.entrySet()) {
            props.setProperty(entry.getKey(), (String) entry.getValue());
        }

        return props;
    }

}