package com.altinity.clickhouse.debezium.embedded.config;

import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to store all the configuration variables
 * specific to the Sink Connector lightweight version
 */
public class SinkConnectorLightWeightConfig {

    // By default DDL is true, this flag is used to disable ddl.
    public static final String DISABLE_DDL = "disable.ddl";

    public static final String DISABLE_DROP_TRUNCATE = "disable.drop.truncate";


    // Enable execution of snapshot ddl.
    public static final String ENABLE_SNAPSHOT_DDL = "enable.snapshot.ddl";

    public static final String CLI_PORT = "cli.port";


    public static final String DDL_RETRY = "ddl.retry";


    // Create a Map of all the configuration variables
    // with the value as a description of the variable
    // Createa a Map of all the configuration variables
    // with the value as a description of the variable
    private static final Map<String, String> configVariables = new HashMap<>();
    static {
        configVariables.put(DISABLE_DDL, "This configuration will disable execution of DDL that are read from binlog(Applies only to MySQL).");
        configVariables.put(DISABLE_DROP_TRUNCATE, "Disable drop truncate DDL Statements(Only applies to MySQL) ");
        configVariables.put(ENABLE_SNAPSHOT_DDL, "Enable snapshot DDL (Enables execution of DDL that are received during snapshot) (Applies only to MySQL)");
        configVariables.put(CLI_PORT, "Sink connector Client CLI port");
        configVariables.put(DDL_RETRY, "If this configuration is set to true, the sink connector will retry executing DDL after a failure");
    }


}
