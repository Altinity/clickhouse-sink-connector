package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DDLParserService defines methods for parsing DDL SQL queries.
 * <p>
 * This interface provides methods to parse SQL queries for DDL
 * operations, tailored for a specific table. It also supports
 * determining whether the query is a DROP or TRUNCATE command.
 * </p>
 */
public interface DDLParserService {

    /**
     * Parses the given SQL query for a specific table.
     *
     * @param sql         The SQL query to parse.
     * @param tableName   The target table name.
     * @param parsedQuery A StringBuffer to store the parsed query.
     * @return The parsed SQL query as a String.
     */
    String parseSql(String sql, String tableName, StringBuffer parsedQuery);

    /**
     * Parses the given SQL query for a specific table and
     * determines if the query is a DROP or TRUNCATE command.
     *
     * @param sql              The SQL query to parse.
     * @param tableName        The target table name.
     * @param parsedQuery      A StringBuffer to store the parsed query.
     * @param isDropOrTruncate An AtomicBoolean flag set to true if the
     *                         query is a DROP or TRUNCATE command.
     * @return The parsed SQL query as a String.
     */
    String parseSql(String sql, String tableName, StringBuffer parsedQuery,
                    AtomicBoolean isDropOrTruncate);

    /**
     * Supplies the statement time of the DDL event about to be parsed
     * ({@code source.ts_ms} of the Debezium schema-change record), which the
     * MySQL translator needs to back-fill an {@code ADD COLUMN ... DEFAULT
     * CURRENT_TIMESTAMP} with the instant the source used (Spec 06.04 §3.2.2).
     * Parsers that do not need it ignore it.
     *
     * @param ddlEventTimestampMs epoch milliseconds; {@code 0} when unknown.
     */
    default void setDdlEventTimestampMs(long ddlEventTimestampMs) {
    }

    /**
     * The table rebuild the last parsed statement requires on the replica
     * because it changed the source table's row identity (Spec 06.09 §3.1);
     * executed by {@code PrimaryKeyRebuild} at the DDL barrier once the
     * statement's other clauses have been applied.
     *
     * @return the plan, or {@code null} when the last statement needs none.
     */
    default PrimaryKeyRebuildPlan primaryKeyRebuildPlan() {
        return null;
    }

    /**
     * The SCD2 tables the last parsed statement must bulk-close in history
     * mode ({@code replication.history.enable=true}, Spec 12.03 section 3.4):
     * a source {@code TRUNCATE-TABLE} / {@code DROP-TABLE} is translated to no
     * DDL text and one request per named table, which
     * {@code performDDLOperation} applies with
     * {@code ReplicationHistoryHandler.executeHistoryBulkClose} in place of
     * the statement.
     *
     * @return the requests, or an empty list when the last statement needs none
     *         (every statement in standard mode).
     */
    default List<MySqlDDLParserListenerImpl.HistoryBulkClose> historyBulkCloses() {
        return Collections.emptyList();
    }
}
