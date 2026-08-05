package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This class holds commonly used constants for DDL (Data Definition Language)
 * operations in ClickHouse, such as commands for creating, altering, or
 * dropping tables, columns, and databases.
 */
public class Constants {

    /**
     * Alias keyword for materialized columns.
     */
    public static final String ALIAS = "MATERIALIZED";

    /**
     * PARTITION BY clause for ClickHouse DDL statements.
     */
    public static final String PARTITION_BY = " PARTITION BY ";

    /**
     * SETTINGS clause for ClickHouse DDL statements.
     */
    public static final String SETTINGS = " SETTINGS ";

    /**
     * ORDER BY clause for ClickHouse DDL statements.
     */
    public static final String ORDER_BY = " ORDER BY ";

    /**
     * ORDER BY clause used with empty tuple.
     */
    public static final String ORDER_BY_TUPLE = " ORDER BY tuple()";

    /**
     * LIKE keyword used in certain DDL contexts.
     */
    public static final String LIKE = "LIKE";

    /**
     * AS keyword in SQL statements.
     */
    public static final String AS = "AS";

    /**
     * Template for ALTER TABLE statements, e.g. "ALTER TABLE %s".
     */
    public static final String ALTER_TABLE = "ALTER TABLE %s";

    /**
     * Template for renaming tables, e.g. "RENAME TABLE %s TO %s".
     */
    public static final String ALTER_RENAME_TABLE = "RENAME TABLE %s TO %s";

    /**
     * CREATE TABLE command.
     */
    public static final String CREATE_TABLE = "CREATE TABLE";

    /**
     * A keyword used in ClickHouse to indicate a nullable column type.
     */
    public static final String NULLABLE = "Nullable";

    /**
     * Standard NOT NULL constraint in SQL.
     */
    public static final String NOT_NULLABLE = "NOT NULL";

    /**
     * Template for adding a column, e.g. "ADD COLUMN %s %s".
     */
    public static final String ADD_COLUMN = "ADD COLUMN %s %s";

    /**
     * Template for adding a nullable column, e.g.
     * "ADD COLUMN %s Nullable(%s)".
     */
    public static final String ADD_COLUMN_NULLABLE =
            "ADD COLUMN %s Nullable(%s)";

    /**
     * Template for modifying a column, e.g. "MODIFY COLUMN %s %s".
     */
    public static final String MODIFY_COLUMN = "MODIFY COLUMN %s %s";

    /**
     * Template for modifying a column to be nullable, e.g.
     * "MODIFY COLUMN %s Nullable(%s)".
     */
    public static final String MODIFY_COLUMN_NULLABLE =
            "MODIFY COLUMN %s Nullable(%s)";

    /**
     * The RENAME COLUMN clause in an ALTER TABLE statement.
     */
    public static final String RENAME_COLUMN = "RENAME COLUMN";

    /**
     * Template for renaming a nullable column, e.g.
     * "RENAME COLUMN %s Nullable(%s)".
     */
    public static final String RENAME_COLUMN_NULLABLE =
            "RENAME COLUMN %s Nullable(%s)";

    /**
     * Template for adding an index, e.g.
     * "ADD INDEX %s(%s) TYPE minmax GRANULARITY 1".
     */
    public static final String ADD_INDEX =
            "ADD INDEX %s(%s) TYPE minmax GRANULARITY 1";

    /**
     * BEFORE keyword often used in ALTER TABLE statements.
     */
    public static final String BEFORE = "BEFORE";

    /**
     * AFTER keyword often used in ALTER TABLE statements.
     */
    public static final String AFTER = "AFTER";

    /**
     * FIRST keyword often used in ALTER TABLE statements.
     */
    public static final String FIRST = "FIRST";

    /**
     * notnull descriptor, used in some DDL contexts to indicate
     * non-null columns.
     */
    public static final String NOT_NULL = "notnull";

    /**
     * NULL descriptor, used in some DDL contexts to indicate
     * nullable columns.
     */
    public static final String NULL = "NULL";

    /**
     * if exists clause for conditional DDL statements.
     */
    public static final String IF_EXISTS = "if exists ";

    /**
     * if not exists clause for conditional DDL statements.
     */
    public static final String IF_NOT_EXISTS = "if not exists ";

    /**
     * RENAME TABLE command in SQL.
     */
    public static final String RENAME_TABLE = "RENAME TABLE";

    /**
     * Template for truncating a table, e.g. "TRUNCATE TABLE %s".
     */
    public static final String TRUNCATE_TABLE = "TRUNCATE TABLE %s";

    /**
     * DROP TABLE command in SQL.
     */
    public static final String DROP_TABLE = "DROP TABLE";

    /**
     * Template for creating a database if it does not exist, e.g.
     * "CREATE DATABASE IF NOT EXISTS %s".
     */
    public static final String CREATE_DATABASE =
            "CREATE DATABASE IF NOT EXISTS %s";

    /**
     * Template for dropping a database if it exists, e.g.
     * "DROP DATABASE IF EXISTS %s".
     */
    public static final String DROP_DATABASE = "DROP DATABASE IF EXISTS %s";

    /**
     * Template for dropping a column, e.g. "DROP COLUMN %s".
     */
    public static final String DROP_COLUMN = "DROP COLUMN %s";

    /**
     * Template for dropping a constraint, e.g. "DROP CONSTRAINT %s".
     */
    public static final String DROP_CONSTRAINT = "DROP CONSTRAINT IF EXISTS %s";

    /**
     * Version number associated with ReplacingMergeTree improvements
     * introduced in ClickHouse 23.2.
     */
    public static final String NEW_REPLACING_MERGE_TREE_VERSION = "23.2";

    /**
     * A set of data types that do not support being marked as Nullable
     * in ClickHouse. This is used as a workaround during DDL processing.
     */
    public static final Set<String> NULLABLE_NOT_SUPPORTED_DATA_TYPES =
            new HashSet<>(Arrays.asList("point", "polygon"));

    /**
     * Backtick-escapes a SQL identifier (table name, column name, database name)
     * to safely handle reserved words and special characters.
     * Strips any existing backticks first to avoid double-escaping.
     *
     * @param identifier The identifier to escape.
     * @return The backtick-escaped identifier, or null if input is null.
     */
    public static String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String stripped = identifier.replace("`", "");
        return "`" + stripped + "`";
    }
}
