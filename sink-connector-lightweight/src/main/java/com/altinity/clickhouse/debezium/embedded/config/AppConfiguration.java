package com.altinity.clickhouse.debezium.embedded.config;

import lombok.Getter;
import lombok.Setter;


public class AppConfiguration {

    @Getter
    @Setter
    private final String sourceHost;

    @Getter
    @Setter
    private final String sourcePort;

    @Getter
    @Setter
    private final String sourceUserName;

    @Getter
    @Setter
    private final String sourcePassword;

    @Getter
    @Setter
    private final String sourceDatabase;

    @Getter
    @Setter
    private final String sourceTables;

    @Getter
    @Setter
    private final String clickHouseHost;

    @Getter
    @Setter
    private final String clickHousePort;

    @Getter
    @Setter
    private final String clickHousePassword;

    @Getter
    @Setter
    private final String clickHouseDatabase;

    @Getter
    @Setter
    private final String clickHouseUserName;


    public AppConfiguration(String sourceHost, String sourcePort, String sourceUserName, String sourcePassword,
                            String sourceDatabase, String sourceTables, String clickHouseHost, String clickHousePort, String clickHousePassword,
                            String clickHouseDatabase, String clickHouseUserName) {
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
