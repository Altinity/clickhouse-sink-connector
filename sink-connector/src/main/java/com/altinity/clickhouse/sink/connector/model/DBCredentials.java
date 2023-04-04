package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;

public class DBCredentials {

    @Getter
    @Setter
    private String hostName;

    @Getter
    @Setter
    private String database;

    @Getter
    @Setter
    private Integer port;

    @Getter
    @Setter
    private String userName;

    @Getter
    @Setter
    private String password;

}
