package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;

import java.util.HashMap;
import java.util.Map;

/**
 * The SinkConnectorLightWeightConfig class stores configuration variables
 * specific to the lightweight version of the Sink Connector.
 * <p>
 * This class contains various configuration variables that control the behavior
 * of the Sink Connector, such as whether to enable or disable DDL statements,
 * control retry behavior, and configure ports and regular expressions for filtering.
 * </p>
 */
public class SinkConnectorLightWeightConfig {

    /**
     * By default, DDL is enabled. This flag can be used to disable DDL execution.
     * <p>
     * If set to true, DDL will not be executed.
     * </p>
     */
    public static final String DISABLE_DDL = "disable.ddl";

    /**
     * Flag to disable DROP and TRUNCATE DDL statements.
     * <p>
     * If set to true, the Sink Connector will ignore DROP and TRUNCATE DDL
     * statements.
     * </p>
     */
    /*
     * Deliberately the enum's value rather than a second literal: the setting
     * is now honoured on the CDC data path too (issue #1287), and two copies
     * of the string could drift apart into a setting that works on one path
     * and silently does nothing on the other.
     */
    public static final String DISABLE_DROP_TRUNCATE =
            ClickHouseSinkConnectorConfigVariables.DISABLE_DROP_TRUNCATE.toString();

    /**
     * Enable execution of snapshot DDL statements.
     * <p>
     * If set to true, DDL statements received during snapshot will be executed.
     * </p>
     */
    public static final String ENABLE_SNAPSHOT_DDL = "enable.snapshot.ddl";

    /**
     * The port for the Sink Connector's Client CLI.
     * <p>
     * Specifies the port number for the client to connect to the Sink Connector.
     * </p>
     */
    public static final String CLI_PORT = "cli.port";

    /**
     * Flag to enable DDL retry after failure.
     * <p>
     * If set to true, the Sink Connector will retry executing DDL statements
     * if they fail during execution.
     * </p>
     */
    public static final String DDL_RETRY = "ddl.retry";

    /**
     * Regex pattern to ignore DDL statements.
     * <p>
     * If set, the Sink Connector will ignore DDL statements that match the
     * specified regex.
     * </p>
     */
    public static final String IGNORE_DDL_REGEX = "ignore.ddl.regex";

    /**
     * Flag to enable REST API authentication.
     * <p>
     * If set to true, REST API endpoints require HTTP Basic Auth credentials.
     * Defaults to false (authentication disabled).
     * </p>
     */
    public static final String REST_API_AUTH_ENABLED = "rest.api.auth.enabled";

    /**
     * Username for REST API Basic Auth.
     */
    public static final String REST_API_AUTH_USERNAME = "rest.api.auth.username";

    /**
     * Password for REST API Basic Auth.
     */
    public static final String REST_API_AUTH_PASSWORD = "rest.api.auth.password";

    /**
     * A map that holds all configuration variables and their descriptions.
     * <p>
     * This map is used to provide detailed information about each configuration
     * variable. The key is the configuration variable name, and the value is its
     * description.
     * </p>
     */
    private static final Map<String, String> configVariables = new HashMap<>();

    // Static block to initialize the configuration variables map with their descriptions
    static {
        configVariables.put(DISABLE_DDL,
                "This configuration will disable execution of DDL that are read from binlog (Applies only to MySQL).");
        configVariables.put(DISABLE_DROP_TRUNCATE,
                "Disable drop and truncate DDL Statements (Only applies to MySQL).");
        configVariables.put(ENABLE_SNAPSHOT_DDL,
                "Enable snapshot DDL (Enables execution of DDL received during snapshot) (Applies only to MySQL).");
        configVariables.put(CLI_PORT, "Sink connector Client CLI port.");
        configVariables.put(DDL_RETRY,
                "If set to true, the sink connector will retry executing DDL after a failure.");
        configVariables.put(IGNORE_DDL_REGEX,
                "If set, the sink connector will ignore DDL statements matching the regex.");
        configVariables.put(REST_API_AUTH_ENABLED,
                "If set to true, REST API endpoints require HTTP Basic Auth. Defaults to false.");
        configVariables.put(REST_API_AUTH_USERNAME,
                "Username for REST API Basic Auth (required when rest.api.auth.enabled=true).");
        configVariables.put(REST_API_AUTH_PASSWORD,
                "Password for REST API Basic Auth (required when rest.api.auth.enabled=true).");
    }

    /**
     * Returns the map of configuration variables with their descriptions.
     *
     * @return a map of configuration variables and their descriptions.
     */
    public static Map<String, String> getConfigVariables() {
        return configVariables;
    }
}
