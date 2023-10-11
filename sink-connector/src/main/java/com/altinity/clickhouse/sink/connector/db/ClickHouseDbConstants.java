package com.altinity.clickhouse.sink.connector.db;

public class ClickHouseDbConstants {

    public static final String ALTER_TABLE = "ALTER TABLE";
    public static final String ALTER_TABLE_ADD_COLUMN = "add column";
    public static final String ALTER_TABLE_DELETE_COLUMN = "delete column";


    public static final String VERSION_COLUMN = "_version";
    public static final String VERSION_COLUMN_DATA_TYPE = "UInt64";

    public static final String IS_DELETED_COLUMN = "is_deleted";
    public static final String IS_DELETED_COLUMN_DATA_TYPE = "UInt8";

    public static final String SIGN_COLUMN = "_sign";
    public static final String SIGN_COLUMN_DATA_TYPE = "Int8";

    public static final String CREATE_TABLE = "CREATE TABLE";

    public static final String NULL = "NULL";
    public static final String NOT_NULL = "NOT NULL";

    public static final String PRIMARY_KEY = "PRIMARY KEY";

    public static final String ORDER_BY = "ORDER BY";

    public static final String ORDER_BY_TUPLE = "ORDER BY tuple()";

    public static final String OFFSET_TABLE_CREATE_SQL = "CREATE TABLE topic_offset_metadata(`_topic` String, `_partition` UInt64,`_offset` SimpleAggregateFunction(max, UInt64))ENGINE = AggregatingMergeTree ORDER BY (_topic, _partition)";

    public static final String CHECK_DB_EXISTS_SQL = "SELECT name from system.databases where name='%s'";
}
