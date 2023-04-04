package com.altinity.clickhouse.debezium.embedded.ddl.parser;

public class Constants {

    public static final String ALTER_TABLE = "ALTER TABLE %s";
    public static final String CREATE_TABLE = "CREATE TABLE %s";
    public static final String NULLABLE = "Nullable";


    public static final String ADD_COLUMN = "ADD COLUMN %s %s";
    public static final String ADD_COLUMN_NULLABLE = "ADD COLUMN %s Nullable(%s)";

    public static final String MODIFY_COLUMN = "MODIFY COLUMN %s %s";
    public static final String MODIFY_COLUMN_NULLABLE = "MODIFY COLUMN %s Nullable(%s)";

    public static final String RENAME_COLUMN = "RENAME COLUMN";

    public static final String RENAME_COLUMN_NULLABLE = "RENAME COLUMN %s Nullable(%s)";


    public static final String ADD_INDEX = "ADD INDEX %s(%s) TYPE minmax GRANULARITY 1";

    public static final String BEFORE = "BEFORE";
    public static final String AFTER = "AFTER";
    public static final String FIRST = "FIRST";

    public static final String NOT_NULL = "notnull";
    public static final String NULL = "NULL";

    public static final String RENAME_TABLE = "RENAME TABLE %s to %s";
    public static final String TRUNCATE_TABLE = "TRUNCATE TABLE %s";
    public static final String DROP_TABLE = "DROP TABLE";


    public static final String CREATE_DATABASE = "CREATE DATABASE %s";

    public static final String DROP_COLUMN = "DROP COLUMN %s";

}
