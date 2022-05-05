package com.altinity.clickhouse.sink.connector.db;

public class DataTypeRange
{
    // Date
    public static final String CLICKHOUSE_MIN_SUPPORTED_DATE = "1970-01-01";
    public static final String CLICKHOUSE_MAX_SUPPORTED_DATE = "2149-06-06";


    // DateTime
    public static final String CLICKHOUSE_MIN_SUPPORTED_DATETIME = "1970-01-01T00:00:00";
    public static final String CLICKHOUSE_MAX_SUPPORTED_DATETIME = "2106-02-07T06:28:15";

    // DateTime
    public static final String CLICKHOUSE_MIN_SUPPORTED_DATETIME64 = "1925-01-01T00:00:00";
    public static final String CLICKHOUSE_MAX_SUPPORTED_DATETIME64 = "2283-11-11T23:59:59";
}
