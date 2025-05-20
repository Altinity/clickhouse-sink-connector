package com.altinity.clickhouse.sink.connector.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;

/**
 * The {@code ReplicationHistoryConfig} class is responsible for loading the
 * "replication.history.enable" value from a YAML configuration file and returning it as a boolean.
 * <p>
 * The YAML file should reside in the <code>src/main/resources</code> directory
 * (e.g., application.yml or a custom file like replication.yml).
 * </p>
 */
public class ReplicationHistoryConfig {

    // Name of the YAML file on the classpath
    private static final String YAML_FILE = "config_schema_override.yml"; // or "replication.yml"

    /**
     * Loads the "replication.history.enable" flag from the YAML file.
     * <p>
     * Steps:
     * <ol>
     *   <li>Create a SnakeYAML {@code Yaml} instance.</li>
     *   <li>Load the YAML file from the classpath.</li>
     *   <li>Parse it into a {@code Map<String, Object>}.</li>
     *   <li>Navigate through the nested keys: "replication" → "history" → "enable".</li>
     *   <li>Cast the result to {@code Boolean} (or parse from String if necessary).</li>
     * </ol>
     *
     * @return the boolean value of {@code replication.history.enable}.
     * @throws RuntimeException if the file is missing or the structure is invalid.
     */
    public static boolean loadReplicationHistoryEnable() {
        Yaml yaml = new Yaml();
        InputStream in = ReplicationHistoryConfig.class.getClassLoader()
                .getResourceAsStream(YAML_FILE);
        if (in == null) {
            throw new RuntimeException("Cannot find " + YAML_FILE + " in classpath");
        }

        // Parse the top-level YAML content into a Map
        @SuppressWarnings("unchecked")
        Map<String, Object> root = yaml.load(in);
        if (root == null) {
            throw new RuntimeException(YAML_FILE + " is empty or malformed");
        }

        // Retrieve the 'replication' section
        Object repObj = root.get("replication");
        if (!(repObj instanceof Map)) {
            throw new RuntimeException("'replication' section is missing or not a map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> replicationMap = (Map<String, Object>) repObj;

        // Retrieve the 'history' subsection
        Object histObj = replicationMap.get("history");
        if (!(histObj instanceof Map)) {
            throw new RuntimeException("'replication.history' section is missing or not a map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> historyMap = (Map<String, Object>) histObj;

        // Retrieve the 'enable' flag
        Object enableObj = historyMap.get("enable");
        if (enableObj == null) {
            throw new RuntimeException("'replication.history.enable' is not defined");
        }
        // Return boolean if it's already a Boolean
        if (enableObj instanceof Boolean) {
            return (Boolean) enableObj;
        }
        // Support string values "true"/"false"
        if (enableObj instanceof String) {
            return Boolean.parseBoolean((String) enableObj);
        }

        throw new RuntimeException(
                "'replication.history.enable' has an unsupported type: " + enableObj.getClass().getName()
        );
    }

    public static void main(String[] args) {
        // Test reading the flag
        boolean enabled = loadReplicationHistoryEnable();
        System.out.println("replication.history.enable = " + enabled);
    }
}
