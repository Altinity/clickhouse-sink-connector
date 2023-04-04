package com.altinity.clickhouse.debezium.embedded.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The type PropertiesHelper is a class that represents the value stored
 * in the properties file named config.properties.
 */
public class PropertiesHelper {
    /**
     * Gets a Properties object that contains the keys and values defined
     * in the file src/main/resources/config.properties
     *
     * @return a {@link java.util.Properties} object
     * @throws Exception Thrown if the file config.properties is not available
     *                   in the directory src/main/resources
     */
    public static Properties getProperties(String fileName) throws Exception {

        Properties props = null;
        //try to load the file config.properties
        try (InputStream input = PropertiesHelper.class.getClassLoader().getResourceAsStream(fileName)) {

            props = new Properties();

            if (input == null) {
                throw new Exception("Sorry, unable to find config.properties");
            }

            //load a properties file from class path, inside static method
            props.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return props;
    }

    public static HashMap<String, String> toMap(Properties prop) {
        return prop.entrySet().stream().collect(
                Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue()),
                        (prev, next) -> next, HashMap::new
                ));
    }

}