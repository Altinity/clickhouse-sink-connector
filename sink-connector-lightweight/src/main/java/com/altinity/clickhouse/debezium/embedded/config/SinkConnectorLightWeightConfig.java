package com.altinity.clickhouse.debezium.embedded.config;

/**
 * This class is used to store all the configuration variables
 * specific to the Sink Connector lightweight version
 */
public class SinkConnectorLightWeightConfig {

    // By default DDL is true, this flag is used to disable ddl.
    public static final String DISABLE_DDL = "disable.ddl";


    // Enable execution of snapshot ddl.
    public static final String ENABLE_SNAPSHOT_DDL = "enable.snapshot.ddl";
}
