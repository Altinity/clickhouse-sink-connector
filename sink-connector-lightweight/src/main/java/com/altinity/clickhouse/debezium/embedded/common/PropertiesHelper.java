package com.altinity.clickhouse.debezium.embedded.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The PropertiesHelper class is responsible for loading and handling the
 * properties stored in a file named "config.properties".
 * <p>
 * This class provides utility methods to load the properties file from
 * the classpath and convert the loaded properties into a HashMap for
 * easier handling.
 * </p>
 */
public class PropertiesHelper {

    /**
     * Retrieves a Properties object containing the key-value pairs
     * defined in the specified properties file (e.g., config.properties).
     * <p>
     * The properties file should be located in the "src/main/resources"
     * directory.
     * </p>
     *
     * @param fileName the name of the properties file to load.
     * @return a {@link java.util.Properties} object containing the key-value
     *         pairs from the specified file.
     * @throws Exception thrown if the file is not found or cannot be loaded.
     */
    public static Properties getProperties(String fileName) throws Exception {

        Properties props = null;
        // Try to load the properties file
        try (InputStream input = PropertiesHelper.class.getClassLoader().getResourceAsStream(fileName)) {

            props = new Properties();

            // If the input stream is null, the file was not found
            if (input == null) {
                throw new Exception("Sorry, unable to find " + fileName);
            }

            // Load the properties file from the classpath
            props.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return props;
    }

    /**
     * Converts a Properties object into a HashMap.
     * <p>
     * This method iterates through the properties and converts them to a
     * HashMap where the key-value pairs are stored as Strings.
     * </p>
     *
     * @param prop the Properties object to be converted.
     * @return a HashMap containing the key-value pairs of the properties.
     */
    public static HashMap<String, String> toMap(Properties prop) {
        return prop.entrySet().stream().collect(
                Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue()),
                        (prev, next) -> next, HashMap::new
                ));
    }

}
