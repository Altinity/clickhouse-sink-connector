package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;

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
     * The CREATE TABLE statement keyword.
     */
    public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS";

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
     * The version column name used in ReplacingMergeTree
     * tables.
     */
    public static final String VERSION_COLUMN = "_version";

    /**
     * The data type of the version column, typically a
     * 64-bit unsigned integer.
     */
    public static final String VERSION_COLUMN_DATA_TYPE = "UInt64";


    public static final String DELETED_TIME_COLUMN_TO_DATE = "toDate(`%s`)";
    /**
     * The name of the column indicating the deleted time.
     */
    public static final String DELETED_TIME_COLUMN = "_valid_to";

    /**
     * _valid_from column to the table
     */
    public static final String DELETED_FROM_TIME_COLUMN = "_valid_from";
    /**
     * The data type of the deleted time column, typically a date time.
     */
    public static final String DELETED_TIME_COLUMN_DATA_TYPE = "DateTime DEFAULT '" + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MAX_TTL) + "'";
    
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

    //--- New CDC binlog columns -------------------------------------------

    /**
     * The column name for the source database.
     */
    public static final String DATABASE_COLUMN = "database";

    /**
     * The data type of the database column.
     */
    public static final String DATABASE_COLUMN_DATA_TYPE = "String";

    /**
     * The column name for the source table.
     */
    public static final String TABLE_COLUMN = "table";

    /**
     * CDC payload for before
     */
    public static final String BEFORE_COLUMN = "before";

    /**
     * CDC payload for after
     *
     */
    public static final String AFTER_COLUMN = "after";

    /**
     * The data type of the table column.
     */
    public static final String TABLE_COLUMN_DATA_TYPE = "String";

    /**
     * The column name for the raw event payload.
     */
    public static final String RAW_COLUMN = "_raw";

    /**
     * The data type of the raw column.
     */
    public static final String RAW_COLUMN_DATA_TYPE = "String";

    /**
     * The column name for the event timestamp.
     */
    public static final String TIME_COLUMN = "_time";

    /**
     * The data type of the time column.
     */
    public static final String TIME_COLUMN_DATA_TYPE = "UInt64";

    /**
     * The column name for the operation type.
     */
    public static final String OPERATION_COLUMN = "_operation";

    /**
     * The data type of the operation column.
     */
    public static final String OPERATION_COLUMN_DATA_TYPE = "LowCardinality(String)";

    /**
     * The column name for the host where the event originated.
     */
    public static final String HOST_COLUMN = "host";

    /**
     * The data type of the host column.
     */
    public static final String HOST_COLUMN_DATA_TYPE = "String";

    /**
     * The column name for the binlog file name.
     */
    public static final String LOGFILE_COLUMN = "logfile";

    /**
     * The data type of the logfile column.
     */
    public static final String LOGFILE_COLUMN_DATA_TYPE = "String";

    /**
     * The column name for the position within the binlog file.
     */
    public static final String POSITION_COLUMN = "position";

    /**
     * The data type of the position column.
     */
    public static final String POSITION_COLUMN_DATA_TYPE = "UInt64";

    /**
     * The column name for the primary host in a master-slave setup.
     */
    public static final String PRIMARY_HOST_COLUMN = "primary_host";

    /**
     * The data type of the primary_host column.
     */
    public static final String PRIMARY_HOST_COLUMN_DATA_TYPE = "String";

    /**
     * Default table name for the Kafka offset metadata table.
     */
    public static final String DEFAULT_OFFSET_TABLE_NAME = "topic_offset_metadata";

    /**
     * Returns a SQL statement to create the offset metadata table with
     * the specified table name.
     *
     * @param tableName the name of the offset table to create
     * @return the CREATE TABLE SQL statement
     */
    public static String getOffsetTableCreateSql(String tableName) {
        return "CREATE TABLE " + tableName + "(`_topic` String, "
                + "`_partition` UInt64,`_offset` SimpleAggregateFunction(max, "
                + "UInt64))ENGINE = AggregatingMergeTree ORDER BY "
                + "(_topic, _partition)";
    }

    /**
     * Returns a SQL statement to create the default offset metadata table.
     * @deprecated Use {@link #getOffsetTableCreateSql(String)} with a configured table name.
     */
    @Deprecated
    public static final String OFFSET_TABLE_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS topic_offset_metadata(`_topic` String, "
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
