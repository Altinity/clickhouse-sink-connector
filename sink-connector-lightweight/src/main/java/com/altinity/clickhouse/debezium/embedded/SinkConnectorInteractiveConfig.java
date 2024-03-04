package com.altinity.clickhouse.debezium.embedded;

import lombok.Getter;
import lombok.Setter;

public class SinkConnectorInteractiveConfig {
    // Store all the configuration from user
    @Getter
    @Setter
    private String databaseType;

    @Getter
    @Setter
    private String databaseHost;

    @Getter
    @Setter
    private String databasePort;

    @Getter
    @Setter
    private String databaseName;

    @Getter
    @Setter
    private String databaseUser;

    @Getter
    @Setter
    private String databasePassword;

    @Getter
    @Setter
    private String tables;

    @Getter
    @Setter
    private String initialSync;

    @Getter
    @Setter
    private String clickHouseHost;

    @Getter
    @Setter
    private String clickHousePort;

    @Getter
    @Setter
    private String clickHouseUser;


    @Getter
    @Setter
    private String clickHousePassword;

    @Getter
    @Setter
    private String clickHouseDatabase;

}
