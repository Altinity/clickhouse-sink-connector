package com.altinity.clickhouse.sink.connector.db.operations;

public class ClickHouseDbConstants {

    public static final String ALTER_TABLE = "ALTER TABLE";
    public static final String ALTER_TABLE_ADD_COLUMN = "add column";
    public static final String ALTER_TABLE_DELETE_COLUMN = "delete column";


    public static final String VERSION_COLUMN = "_version";
    public static final String VERSION_COLUMN_DATA_TYPE = "UInt64";
    public static final String SIGN_COLUMN = "_sign";
    public static final String SIGN_COLUMN_DATA_TYPE = "Int8";

    public static final String CREATE_TABLE = "CREATE TABLE";

    public static final String NULL = "NULL";
    public static final String NOT_NULL = "NOT NULL";
}
