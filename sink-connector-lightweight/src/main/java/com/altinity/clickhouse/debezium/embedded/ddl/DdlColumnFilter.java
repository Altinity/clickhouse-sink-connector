package com.altinity.clickhouse.debezium.embedded.ddl;

import io.debezium.relational.ColumnFilterMode;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.Tables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Decides whether a source column is replicated, from the connector's
 * {@code column.include.list} / {@code column.exclude.list}.
 *
 * <p><b>The gap this closes.</b> Debezium applies the column lists to the
 * record schema, so the writer never sends an excluded column's value. The
 * schema-DDL path ({@code enable.snapshot.ddl=true}) translates the raw MySQL
 * {@code CREATE TABLE} text instead, where no filter was applied: every
 * excluded column was still created in ClickHouse, empty, because the two
 * paths disagreed about the same table. The lists exist so a column such as a
 * password can be kept out of the analytics database entirely, so a column
 * that is empty but present defeats the intent and makes the ClickHouse
 * schema depend on which creation path happened to run.</p>
 *
 * <p><b>Semantics are Debezium's, not a reimplementation.</b> Matching is
 * delegated to {@link Tables.ColumnNameFilterFactory}, the same factory the
 * MySQL connector uses to build its own filter, so an entry such as
 * {@code app.users.(id|name)} means exactly the same thing on both paths:
 * comma-separated regular expressions, matched in full and case-insensitively
 * against {@code database.table.column}. The mode is
 * {@link ColumnFilterMode#CATALOG} because MySQL has no schema: the database
 * name is the catalog, and the table and column follow it.</p>
 *
 * <p><b>Property names come from Debezium.</b> The two list keys are read
 * through {@link RelationalDatabaseConnectorConfig}'s field definitions, never
 * restated as literals, so a rename on the Debezium side is picked up by the
 * dependency bump instead of silently turning the filter into a no-op (the
 * same rule {@code DdlCaptureFilter} follows for the table lists).</p>
 *
 * <p><b>Conservative in the direction of KEEPING a column.</b> With no list
 * configured nothing is filtered; an unknown database is never filtered (with
 * a {@code null} catalog the matcher answers "no" for every column, which
 * would drop the whole table); an empty column name is never filtered.</p>
 */
public final class DdlColumnFilter {

    private static final Logger log = LogManager.getLogger(DdlColumnFilter.class);

    /** {@code column.include.list}, as Debezium defines it. */
    public static final String COLUMN_INCLUDE_LIST =
            RelationalDatabaseConnectorConfig.COLUMN_INCLUDE_LIST.name();
    /** {@code column.exclude.list}, as Debezium defines it. */
    public static final String COLUMN_EXCLUDE_LIST =
            RelationalDatabaseConnectorConfig.COLUMN_EXCLUDE_LIST.name();

    /** Keeps every column; the answer when no column list is configured. */
    public static final DdlColumnFilter NONE = new DdlColumnFilter(null, null);

    /** The Debezium matcher, or null when no list is configured. */
    private final Tables.ColumnNameFilter filter;

    /** The list {@link #filter} was built from, for the log lines. */
    private final String listName;

    private DdlColumnFilter(Tables.ColumnNameFilter filter, String listName) {
        this.filter = filter;
        this.listName = listName;
    }

    /**
     * Builds the filter for one connector configuration. The patterns are
     * compiled once here rather than per column: a wide source table asks the
     * same question for every one of its columns.
     *
     * @param properties the connector properties; a {@link java.util.Properties}
     *                   is accepted as it stands.
     * @return the filter, or {@link #NONE} when neither list is set.
     */
    public static DdlColumnFilter from(Map<?, ?> properties) {
        if (properties == null) {
            return NONE;
        }
        String includeCsv = value(properties.get(COLUMN_INCLUDE_LIST));
        if (includeCsv != null && !includeCsv.trim().isEmpty()) {
            // Debezium: an include list, when set, is the whole rule and the
            // exclude list is ignored.
            return new DdlColumnFilter(
                    Tables.ColumnNameFilterFactory.createIncludeListFilter(includeCsv, ColumnFilterMode.CATALOG),
                    COLUMN_INCLUDE_LIST + "=" + includeCsv);
        }
        String excludeCsv = value(properties.get(COLUMN_EXCLUDE_LIST));
        if (excludeCsv != null && !excludeCsv.trim().isEmpty()) {
            return new DdlColumnFilter(
                    Tables.ColumnNameFilterFactory.createExcludeListFilter(excludeCsv, ColumnFilterMode.CATALOG),
                    COLUMN_EXCLUDE_LIST + "=" + excludeCsv);
        }
        return NONE;
    }

    /** @return true when no column list is configured. */
    public boolean isDisabled() {
        return this.filter == null;
    }

    /** {@code Properties} carries Objects; a non-String entry is no value. */
    private static String value(Object configured) {
        return configured instanceof String ? (String) configured : null;
    }

    /**
     * @param database the SOURCE database of the statement; {@code null} or
     *                 empty when unknown.
     * @param table    the SOURCE table; clean, without backticks.
     * @param column   the column; clean, without backticks.
     * @return true when the column is replicated and must appear in the
     *         ClickHouse schema.
     */
    public boolean keeps(String database, String table, String column) {
        if (this.filter == null) {
            return true;
        }
        if (database == null || database.isEmpty() || column == null || column.isEmpty()) {
            // Unknown identifiers cannot be judged; never drop a column by
            // guessing (a null catalog makes the matcher answer "no").
            return true;
        }
        boolean kept = this.filter.matches(database, null, table, column);
        if (!kept) {
            log.info("Not replicating column {}.{}.{}: excluded by {}", database, table, column, this.listName);
        }
        return kept;
    }
}
