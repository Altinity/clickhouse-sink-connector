package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;

/**
 * A class that holds the database credentials required for connecting
 * to a ClickHouse database. This class encapsulates the necessary
 * connection details such as hostname, database name, port, username,
 * and password.
 */
public class DBCredentials {

    /**
     * The hostname of the ClickHouse server.
     */
    @Getter
    @Setter
    private String hostName;

    /**
     * The name of the database to connect to on the ClickHouse server.
     */
    @Getter
    @Setter
    private String database;

    /**
     * The port number used to connect to the ClickHouse server.
     */
    @Getter
    @Setter
    private Integer port;

    /**
     * The username used for authentication when connecting to the ClickHouse server.
     */
    @Getter
    @Setter
    private String userName;

    /**
     * The password used for authentication when connecting to the ClickHouse server.
     */
    @Getter
    @Setter
    private String password;
}
