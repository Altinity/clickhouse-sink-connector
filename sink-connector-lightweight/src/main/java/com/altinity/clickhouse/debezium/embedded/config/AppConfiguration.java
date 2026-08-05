package com.altinity.clickhouse.debezium.embedded.config;

import lombok.Getter;
import lombok.Setter;

/**
 * AppConfiguration holds the configuration values for both the source
 * database and the ClickHouse database connections. This includes host
 * addresses, ports, credentials, database names, and tables to be monitored.
 */
public class AppConfiguration {

    /**
     * The host or IP address of the source database server.
     */
    @Getter
    private final String sourceHost;

    /**
     * The port number of the source database server.
     */
    @Getter
    private final String sourcePort;

    /**
     * The username used to connect to the source database.
     */
    @Getter
    private final String sourceUserName;

    /**
     * The password used to connect to the source database.
     */
    @Getter
    private final String sourcePassword;

    /**
     * The name of the source database.
     */
    @Getter
    private final String sourceDatabase;

    /**
     * A comma-separated list of source tables to monitor or process.
     */
    @Getter
    private final String sourceTables;

    /**
     * The host or IP address of the ClickHouse server.
     */
    @Getter
    private final String clickHouseHost;

    /**
     * The port number of the ClickHouse server.
     */
    @Getter
    private final String clickHousePort;

    /**
     * The password used to connect to the ClickHouse server.
     */
    @Getter
    private final String clickHousePassword;

    /**
     * The name of the ClickHouse database.
     */
    @Getter
    private final String clickHouseDatabase;

    /**
     * The username used to connect to the ClickHouse server.
     */
    @Getter
    private final String clickHouseUserName;

    /**
     * Constructs an AppConfiguration instance with the specified parameters.
     *
     * @param sourceHost         the host or IP address of the source database
     *                           server
     * @param sourcePort         the port number of the source database server
     * @param sourceUserName     the username to connect to the source database
     * @param sourcePassword     the password to connect to the source database
     * @param sourceDatabase     the name of the source database
     * @param sourceTables       a comma-separated list of source tables to
     *                           monitor or process
     * @param clickHouseHost     the host or IP address of ClickHouse server
     * @param clickHousePort     the port number of the ClickHouse server
     * @param clickHousePassword the password to connect to ClickHouse server
     * @param clickHouseDatabase the name of the ClickHouse database
     * @param clickHouseUserName the username to connect to ClickHouse server
     */
    public AppConfiguration(String sourceHost, String sourcePort,
                            String sourceUserName, String sourcePassword,
                            String sourceDatabase, String sourceTables,
                            String clickHouseHost, String clickHousePort,
                            String clickHousePassword,
                            String clickHouseDatabase,
                            String clickHouseUserName) {
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.sourceUserName = sourceUserName;
        this.sourcePassword = sourcePassword;
        this.sourceDatabase = sourceDatabase;
        this.sourceTables = sourceTables;
        this.clickHouseHost = clickHouseHost;
        this.clickHousePort = clickHousePort;
        this.clickHousePassword = clickHousePassword;
        this.clickHouseDatabase = clickHouseDatabase;
        this.clickHouseUserName = clickHouseUserName;
    }
}
