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
     * Template for adding a column, e.g.
     * "ADD COLUMN IF NOT EXISTS %s %s".
     *
     * <p>The existence guard is what makes DDL replay survivable. Debezium
     * flushes offsets periodically, so a restart re-delivers every DDL event
     * committed since the last flush -- including ones already applied to
     * ClickHouse. Without the guard the replayed statement fails, and because
     * DDL is retried indefinitely that failure STALLS THE ENTIRE REPLICATION
     * STREAM, not merely the offending table. Measured on 24.8.14: a replayed
     * unguarded ADD COLUMN returns
     * {@code Code: 15 DUPLICATE_COLUMN "column with this name already exists"}.</p>
     */
    public static final String ADD_COLUMN = "ADD COLUMN IF NOT EXISTS %s %s";

    /**
     * Template for adding a nullable column, e.g.
     * "ADD COLUMN IF NOT EXISTS %s Nullable(%s)".
     *
     * <p>Guarded for the same reason as {@link #ADD_COLUMN}.</p>
     */
    public static final String ADD_COLUMN_NULLABLE =
            "ADD COLUMN IF NOT EXISTS %s Nullable(%s)";

    /**
     * Template for modifying a column, e.g. "MODIFY COLUMN %s %s".
     *
     * <p>Deliberately unguarded: MODIFY COLUMN is already idempotent, since
     * re-applying the same type is a no-op. Verified on 24.8.14 -- a replayed
     * MODIFY COLUMN succeeds. Adding IF EXISTS here would additionally SWALLOW
     * a modification of a column that genuinely does not exist, turning a real
     * schema divergence into silence.</p>
     */
    public static final String MODIFY_COLUMN = "MODIFY COLUMN %s %s";

    /**
     * Template for modifying a column to be nullable, e.g.
     * "MODIFY COLUMN %s Nullable(%s)".
     *
     * <p>Unguarded for the same reason as {@link #MODIFY_COLUMN}.</p>
     */
    public static final String MODIFY_COLUMN_NULLABLE =
            "MODIFY COLUMN %s Nullable(%s)";

    /**
     * The RENAME COLUMN clause in an ALTER TABLE statement.
     *
     * <p>Guarded, because a rename is NOT self-idempotent: once applied, the
     * old name is gone, so replaying it fails with
     * {@code Code: 10 NOT_FOUND_COLUMN_IN_BLOCK "Cannot find column `x` to
     * rename"} (measured on 24.8.14) and stalls the stream. With IF EXISTS the
     * replay is a no-op, because the column already carries the new name.</p>
     */
    public static final String RENAME_COLUMN = "RENAME COLUMN IF EXISTS";

    /**
     * Template for renaming a nullable column, e.g.
     * "RENAME COLUMN IF EXISTS %s Nullable(%s)".
     *
     * <p>Guarded for the same reason as {@link #RENAME_COLUMN}.</p>
     */
    public static final String RENAME_COLUMN_NULLABLE =
            "RENAME COLUMN IF EXISTS %s Nullable(%s)";

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
     * Template for dropping a column, e.g. "DROP COLUMN IF EXISTS %s".
     *
     * <p>Guarded like the sibling DROP templates above. A replayed unguarded
     * drop fails with
     * {@code Code: 10 NOT_FOUND_COLUMN_IN_BLOCK "Cannot find column `x` to
     * drop"} -- reproduced on 24.8.14 -- and since DDL is retried
     * indefinitely, that stalls the entire replication stream. Observed end to
     * end: a connector restarted after a DROP COLUMN retried the replayed
     * statement 20 times and stopped applying any further row events, so the
     * ClickHouse copy silently fell behind its source.</p>
     *
     * <p>Dropping a column that is already gone is precisely the desired
     * outcome, so the guard costs nothing: the intended end state is reached
     * either way.</p>
     */
    // DESTRUCTIVE: a template string, not an executed statement. It renders
    // the column drop that the SOURCE database already performed and that
    // Debezium is replicating; the connector never originates a drop. Blast
    // radius is the single named column of the single mirrored table, and
    // IF EXISTS strictly narrows it further by making a repeat a no-op.
    public static final String DROP_COLUMN = "DROP COLUMN IF EXISTS %s";

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
}
