package com.altinity.clickhouse.debezium.embedded;

import java.util.Scanner;

public class InteractiveMode {
    // Prompt to the user to enter if its MySQL or PostgreSQL
    public static String getDatabaseType() {
        System.out.println("Enter the database type (mysql/postgres): ");
        return new Scanner(System.in).nextLine();
    }
    // Prompt to the user to enter the MySQL database host
    public static String getDatabaseHost() {
        System.out.println("Enter the database host: ");
        return new Scanner(System.in).nextLine();
    }
    // Prompt to the user to enter the MySQL database port
    public static String getDatabasePort() {
        System.out.println("Enter the database port: ");
        return new Scanner(System.in).nextLine();
    }

    // Prompt to the user to enter the MySQL database name
    public static String getDatabaseName() {
        System.out.println("Enter the database name: ");
        return new Scanner(System.in).nextLine();
    }
    // Prompt to the user to enter the MySQL database user
    public static String getDatabaseUser() {
        System.out.println("Enter the database user: ");
        return new Scanner(System.in).nextLine();
    }
    // Prompt to the user to enter the MySQL database password
    public static String getDatabasePassword() {
        System.out.println("Enter the database password: ");
        return new Scanner(System.in).nextLine();
    }
    // Prompt the user to enter the tables to be replicated
    public static String getTables() {
        System.out.println("Enter the tables to be replicated: ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to check if replication should transfer all existing data
    // or only the changes from the time of the replication start
    public static String getInitialSync() {
        System.out.println("Do you want to replicate all existing data? (yes/no): ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to enter the ClickHouse host
    public static String getClickHouseHost() {
        System.out.println("Enter the ClickHouse host: ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to enter the ClickHouse port
    public static String getClickHousePort() {
        System.out.println("Enter the ClickHouse port: ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to enter the ClickHouse user
    public static String getClickHouseUser() {
        System.out.println("Enter the ClickHouse user: ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to enter the ClickHouse password
    public static String getClickHousePassword() {
        System.out.println("Enter the ClickHouse password: ");
        return new Scanner(System.in).nextLine();
    }

    //Prompt the user to enter the ClickHouse database
    public static String getClickHouseDatabase() {
        System.out.println("Enter the ClickHouse database: ");
        return new Scanner(System.in).nextLine();
    }

    // Call all the scanner and populate the class
    public static void populate() {

        // Create the new Config class.
        // Change Config to SinkConnectorInteractiveConfig
        SinkConnectorInteractiveConfig Config = new SinkConnectorInteractiveConfig();

        Config.setDatabaseType(getDatabaseType());
        Config.setDatabaseHost(getDatabaseHost());
        Config.setDatabasePort(getDatabasePort());
        Config.setDatabaseName(getDatabaseName());
        Config.setDatabaseUser(getDatabaseUser());
        Config.setDatabasePassword(getDatabasePassword());
        Config.setTables(getTables());
        Config.setInitialSync(getInitialSync());
        Config.setClickHouseHost(getClickHouseHost());
        Config.setClickHousePort(getClickHousePort());
        Config.setClickHouseUser(getClickHouseUser());
        Config.setClickHousePassword(getClickHousePassword());
        Config.setClickHouseDatabase(getClickHouseDatabase());
    }


}
