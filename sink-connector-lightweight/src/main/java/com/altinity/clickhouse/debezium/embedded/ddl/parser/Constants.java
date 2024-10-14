package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Constants {

    public static final String ALIAS = "MATERIALIZED";
    public static final String PARTITION_BY = " PARTITION BY ";
    public static final String ORDER_BY = " ORDER BY ";
    public static final String ORDER_BY_TUPLE = " ORDER BY tuple()";

    public static final String LIKE = "LIKE";

    public static final String AS = "AS";
    public static final String ALTER_TABLE = "ALTER TABLE %s";

    public static final String ALTER_RENAME_TABLE = "RENAME TABLE %s TO %s";
    public static final String CREATE_TABLE = "CREATE TABLE";
    public static final String NULLABLE = "Nullable";

    public static final String NOT_NULLABLE = "NOT NULL";

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

    public static final String IF_EXISTS = "if exists ";
    public static final String IF_NOT_EXISTS = "if not exists ";
    public static final String RENAME_TABLE = "RENAME TABLE";
    public static final String TRUNCATE_TABLE = "TRUNCATE TABLE %s";
    public static final String DROP_TABLE = "DROP TABLE";


    public static final String CREATE_DATABASE = "CREATE DATABASE IF NOT EXISTS %s";

    public static final String DROP_COLUMN = "DROP COLUMN %s";

    public static final String DROP_CONSTRAINT = "DROP CONSTRAINT %s";
    public static final String NEW_REPLACING_MERGE_TREE_VERSION = "23.2";


    // There are certain Data types where Nullable is not supported.
    // For example, Point, Geometry, Enum, Array, Map, Decimal, UUID, DateTime64, Date, Time, DateTime, Nullable(DateTime), Nullable(Date), Nullable(Time), Nullable(DateTime64), Nullable(UUID)
    // Create a set of these data types.
    public static final Set<String> NULLABLE_NOT_SUPPORTED_DATA_TYPES = new HashSet<>(Arrays.asList("point"));


}
