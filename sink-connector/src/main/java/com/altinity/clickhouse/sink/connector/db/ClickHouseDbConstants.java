package com.altinity.clickhouse.sink.connector.db;

/**
 * ClickHouseDbConstants holds a set of string constants
 * and SQL snippets used for creating and altering
 * tables in ClickHouse.
 *
 * <p>The constants include ALTER statements, table creation
 * statements, and column naming/typing definitions.
 */
public class ClickHouseDbConstants {

    /**
     * The ALTER TABLE keyword.
     */
    public static final String ALTER_TABLE = "ALTER TABLE";

    /**
     * Fragment used to add a column in an ALTER TABLE
     * statement.
     */
    public static final String ALTER_TABLE_ADD_COLUMN = "add column";

    /**
     * Fragment used to delete a column in an ALTER TABLE
     * statement.
     */
    public static final String ALTER_TABLE_DELETE_COLUMN = "delete column";

    /**
     * The version column name used in ReplacingMergeTree
     * tables.
     */
    public static final String VERSION_COLUMN = "_version";

    /**
     * The data type of the version column, typically a
     * 64-bit unsigned integer.
     */
    public static final String VERSION_COLUMN_DATA_TYPE = "UInt64";

    /**
     * The name of the column indicating whether a record
     * has been deleted.
     */
    public static final String IS_DELETED_COLUMN = "is_deleted";

    /**
     * The data type of the is_deleted column, typically
     * an 8-bit unsigned integer.
     */
    public static final String IS_DELETED_COLUMN_DATA_TYPE = "UInt8";

    /**
     * The sign column name used in certain table engines,
     * such as CollapsingMergeTree.
     */
    public static final String SIGN_COLUMN = "_sign";

    /**
     * The data type of the sign column, typically an
     * 8-bit signed integer.
     */
    public static final String SIGN_COLUMN_DATA_TYPE = "Int8";

    /**
     * The CREATE TABLE statement keyword.
     */
    public static final String CREATE_TABLE = "CREATE TABLE";

    /**
     * Represents a nullability specification of NULL.
     */
    public static final String NULL = "NULL";

    /**
     * Represents a nullability specification of NOT NULL.
     */
    public static final String NOT_NULL = "NOT NULL";

    /**
     * The PRIMARY KEY fragment in a CREATE TABLE or
     * ALTER TABLE statement.
     */
    public static final String PRIMARY_KEY = "PRIMARY KEY";

    /**
     * The ORDER BY fragment in a CREATE TABLE or
     * ALTER TABLE statement.
     */
    public static final String ORDER_BY = "ORDER BY";

    /**
     * A default ORDER BY tuple clause for tables not
     * needing a primary key or index.
     */
    public static final String ORDER_BY_TUPLE = "ORDER BY tuple()";

    /**
     * A SQL statement used to create the topic_offset_metadata
     * table for managing Kafka offsets.
     */
    public static final String OFFSET_TABLE_CREATE_SQL =
            "CREATE TABLE topic_offset_metadata(`_topic` String, "
                    + "`_partition` UInt64,`_offset` SimpleAggregateFunction(max, "
                    + "UInt64))ENGINE = AggregatingMergeTree ORDER BY "
                    + "(_topic, _partition)";

    /**
     * A SQL query to check if a database exists in ClickHouse,
     * by querying system.databases.
     */
    public static final String CHECK_DB_EXISTS_SQL =
            "SELECT name from system.databases where name='%s'";
}

