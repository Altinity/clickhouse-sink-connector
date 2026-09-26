package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The existing ClickHouse schema of the table an {@code ALTER TABLE} targets,
 * as the DDL translator needs it (Spec 06.03 §3.4).
 *
 * <p>Two questions are asked, each with <b>clean</b> identifiers -- backticks
 * stripped, no database prefix on the table, and the connector's destination
 * database name:</p>
 * <ul>
 *   <li>which columns exist and whether each is {@code Nullable} -- drives the
 *       nullability fallback (Spec 06.05 §3.2) and the case-insensitive
 *       resolution of MySQL column names against ClickHouse's case-sensitive
 *       ones (Spec 06.03 §3.3);</li>
 *   <li>which columns form the sorting key and what ClickHouse type each has --
 *       drives the sorting-key MODIFY/CHANGE/RENAME policy (Spec 06.05 §3.4),
 *       because ClickHouse rejects every type change of a key column with
 *       {@code Code: 524}.</li>
 * </ul>
 *
 * <p>The production implementation reads {@code system.columns} through
 * {@code DBMetadata} on the writer's connection. Unit tests inject a fixed
 * answer (see {@link #fromColumns}) so the rules are testable without a
 * server. An empty map means "unknown": the translator then behaves as it did
 * before this lookup existed -- columns default to {@code Nullable} and no
 * clause is treated as touching a key column.</p>
 */
public interface TargetSchemaLookup {

    /**
     * @param database destination database (clean).
     * @param table    table name (clean).
     * @return column name -> {@code true} when the ClickHouse column is
     *         {@code Nullable(...)}; empty when the table or the schema is
     *         unknown.
     */
    Map<String, Boolean> columnNullability(String database, String table);

    /**
     * @param database destination database (clean).
     * @param table    table name (clean).
     * @return sorting-key column name -> its ClickHouse type as rendered by
     *         {@code system.columns}, in key order; empty when the table has
     *         {@code ORDER BY tuple()} or the schema is unknown.
     */
    Map<String, String> sortingKeyTypes(String database, String table);

    /** Answers "unknown" to both questions. */
    TargetSchemaLookup UNKNOWN = new TargetSchemaLookup() {
        @Override
        public Map<String, Boolean> columnNullability(String database, String table) {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, String> sortingKeyTypes(String database, String table) {
            return Collections.emptyMap();
        }
    };

    /**
     * A fixed lookup for one table, built from its column types and the names
     * of its sorting-key columns.
     *
     * @param columnTypes       column name -> ClickHouse type (e.g.
     *                          {@code Nullable(Int32)}), in position order.
     * @param sortingKeyColumns the sorting-key column names, in key order.
     * @return a lookup that answers from these two facts for any table asked.
     */
    static TargetSchemaLookup fromColumns(Map<String, String> columnTypes,
                                          Collection<String> sortingKeyColumns) {
        final Map<String, Boolean> nullability = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : columnTypes.entrySet()) {
            nullability.put(e.getKey(), e.getValue() != null && e.getValue().startsWith("Nullable("));
        }
        final Map<String, String> keyTypes = new LinkedHashMap<>();
        for (String key : sortingKeyColumns) {
            keyTypes.put(key, columnTypes.get(key));
        }
        return new TargetSchemaLookup() {
            @Override
            public Map<String, Boolean> columnNullability(String database, String table) {
                return nullability;
            }

            @Override
            public Map<String, String> sortingKeyTypes(String database, String table) {
                return keyTypes;
            }
        };
    }
}
