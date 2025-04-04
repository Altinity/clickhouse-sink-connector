package com.altinity.clickhouse.debezium.embedded.ddl.parser;

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
}
