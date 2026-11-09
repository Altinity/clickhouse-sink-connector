/*
 * Copyright (c) 2023 Altinity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ClickHouse {@code PARTITION BY} clause the connector emits for a MySQL
 * partitioned table.
 *
 * <p>Every shape asserted here was executed against ClickHouse 24.8.8.17
 * before it was asserted. The failure mode being defended against is not a
 * cosmetic difference: ClickHouse rejects the CREATE TABLE, the connector
 * retries the same binlog position for as long as it lives, and every later
 * event for that table fails with it.</p>
 *
 * <p>Three defects the grammar matrix surfaced, each reproduced first and then
 * fixed:</p>
 *
 * <ol>
 *   <li><b>A bare comma list.</b> {@code PARTITION BY KEY(a,b)} was emitted as
 *       {@code PARTITION BY a,b}. ClickHouse has no such form -- the partition
 *       key is one expression, and a multi-column key is a tuple -- and
 *       rejects it with {@code Code: 62} at the comma. It is now
 *       {@code PARTITION BY (a,b)}, which was verified accepted.</li>
 *   <li><b>A nullable partition column without {@code allow_nullable_key}.</b>
 *       The setting was emitted only when the SORTING key spanned a nullable
 *       column. A source {@code PRIMARY KEY} makes the sorting key NOT NULL, so
 *       a table whose partition column is its only nullable column was emitted
 *       without the setting and failed with {@code Code: 44}. Verified:
 *       {@code CREATE TABLE s5 (id Int32 NOT NULL, d Nullable(Date)) ENGINE =
 *       ReplacingMergeTree ORDER BY id PARTITION BY (d)} fails, the same
 *       statement with {@code SETTINGS allow_nullable_key = 1} succeeds.</li>
 *   <li><b>{@code PARTITION BY HASH}. </b> It produced no partition clause at
 *       all, silently dropping the source's partitioning. ClickHouse has no
 *       {@code hash()} function ({@code Code: 46}) but accepts
 *       {@code cityHash64()}, which is now emitted.</li>
 * </ol>
 */
public class PartitionKeyEmissionTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString().trim();
    }

    /** The emitted partition clause, or "" when the statement has none. */
    private static String partitionClause(String emitted) {
        int from = emitted.indexOf("PARTITION BY");
        if (from < 0) {
            return "";
        }
        int to = emitted.indexOf("ORDER BY", from);
        return (to < 0 ? emitted.substring(from) : emitted.substring(from, to)).trim();
    }

    // ------------------------------------------------------------------
    // The comma list is a tuple.
    // ------------------------------------------------------------------

    /**
     * {@code PARTITION BY KEY(a,b)} emits a TUPLE, never a bare comma list.
     * The spacing inside the tuple is ANTLR's {@code getText()}, which drops
     * whitespace between tokens -- ClickHouse accepts either.
     */
    @Test
    public void multiColumnKeyIsEmittedAsATuple() {
        assertEquals("PARTITION BY (a,b)",
                partitionClause(translate("CREATE TABLE t (a INT, b INT) PARTITION BY KEY(a, b)")));
    }

    /** {@code RANGE COLUMNS(a,b)} already collects its own parentheses. */
    @Test
    public void rangeColumnsKeyIsEmittedAsATuple() {
        assertEquals("PARTITION BY (a,b)",
                partitionClause(translate("CREATE TABLE t (a INT, b INT) PARTITION BY RANGE COLUMNS(a, b) "
                        + "(PARTITION p0 VALUES LESS THAN (10, 20))")));
    }

    /** A single column is a valid one-expression key and is not wrapped. */
    @Test
    public void singleColumnKeyIsNotWrapped() {
        assertEquals("PARTITION BY a",
                partitionClause(translate("CREATE TABLE t (a INT) PARTITION BY KEY(a)")));
    }

    /** A function key is one expression already and is not wrapped. */
    @Test
    public void functionKeyIsNotWrapped() {
        assertEquals("PARTITION BY toYear(order_date)",
                partitionClause(translate("CREATE TABLE t (order_date DATE) "
                        + "PARTITION BY RANGE(YEAR(order_date)) "
                        + "(PARTITION p0 VALUES LESS THAN (2000))")));
    }

    /**
     * Exactly one space separates the keywords from the key. The clause was
     * emitted with two, because the constant carries its own trailing space
     * and the caller appended another.
     */
    @Test
    public void clauseHasNoDoubleSpace() {
        String emitted = translate("CREATE TABLE t (a INT) PARTITION BY KEY(a)");
        assertFalse(emitted.contains("PARTITION BY  "), "double space in: " + emitted);
    }

    // ------------------------------------------------------------------
    // hashCode / LIST COLUMNS are translated, not dropped.
    // ------------------------------------------------------------------

    /**
     * {@code PARTITION BY HASH(id)} is translated. It used to emit NO clause,
     * so the source's partitioning was silently discarded.
     */
    @Test
    public void hashKeyIsTranslatedToCityHash64() {
        assertEquals("PARTITION BY cityHash64(id)",
                partitionClause(translate("CREATE TABLE t (id INT) PARTITION BY HASH(id) PARTITIONS 4")));
    }

    /** {@code PARTITION BY LIST COLUMNS(a,b)} is a tuple, the same as KEY. */
    @Test
    public void listColumnsKeyIsEmittedAsATuple() {
        assertEquals("PARTITION BY (a,b)",
                partitionClause(translate("CREATE TABLE t (a INT, b INT) PARTITION BY LIST COLUMNS(a, b) "
                        + "(PARTITION p0 VALUES IN ((1, 2)))")));
    }

    // ------------------------------------------------------------------
    // allow_nullable_key follows the partition column too.
    // ------------------------------------------------------------------

    /**
     * A nullable partition column with a NOT NULL sorting key still needs the
     * setting: ClickHouse rejects the statement with Code: 44 otherwise. This
     * is the case a source PRIMARY KEY produces, and it was the one the old
     * sorting-key-only check missed.
     */
    @Test
    public void nullablePartitionColumnGetsAllowNullableKey() {
        String emitted = translate("CREATE TABLE t (id INT PRIMARY KEY, d DATE) "
                + "PARTITION BY RANGE COLUMNS(d) (PARTITION p0 VALUES LESS THAN ('2020-01-01'))");
        assertEquals("PARTITION BY (d)", partitionClause(emitted));
        assertTrue(emitted.contains("allow_nullable_key"),
                "a nullable partition column needs the setting, got: " + emitted);
    }

    /** A NOT NULL partition column does not get the setting -- it is not needed. */
    @Test
    public void notNullPartitionColumnGetsNoSetting() {
        String emitted = translate("CREATE TABLE t (id INT NOT NULL PRIMARY KEY, d DATE NOT NULL) "
                + "PARTITION BY RANGE COLUMNS(d) (PARTITION p0 VALUES LESS THAN ('2020-01-01'))");
        assertFalse(emitted.contains("allow_nullable_key"),
                "no nullable key column, so the setting must be absent: " + emitted);
    }

    /**
     * The setting is not added a second time when the operator already set it
     * through a schema override -- that would emit
     * {@code SETTINGS allow_nullable_key=0,allow_nullable_key=1}, and the
     * operator's own value must win.
     *
     * <p>An override that names one specific table and is built through the
     * real {@code SchemaOverrideConfig}, so the property lookup is the shipped
     * one rather than a stub.</p>
     */
    @Test
    public void operatorSuppliedSettingIsNotDuplicated() {
        java.util.Map<String, String> props = new java.util.HashMap<>();
        props.put("databases.db.tables.t.settings", "allow_nullable_key=0");
        MySQLDDLParserService overridden =
                new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props)), "db");

        StringBuffer output = new StringBuffer();
        overridden.parseSql("CREATE TABLE t (id INT PRIMARY KEY, d DATE) "
                + "PARTITION BY RANGE COLUMNS(d) (PARTITION p0 VALUES LESS THAN ('2020-01-01'))", "t", output);
        String emitted = output.toString().trim();

        assertEquals(1, countOccurrences(emitted.toLowerCase(), "allow_nullable_key"),
                "the operator's setting must appear exactly once: " + emitted);
        assertTrue(emitted.contains("allow_nullable_key=0"),
                "the operator's own value must be preserved: " + emitted);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // The pure helpers.
    // ------------------------------------------------------------------

    /** {@code parenIfCommaList} wraps only a bare comma list. */
    @Test
    public void parenIfCommaListWrapsOnlyBareLists() {
        assertEquals("(a,b)", MySqlDDLParserListenerImpl.parenIfCommaList("a,b"));
        assertEquals("(a, b)", MySqlDDLParserListenerImpl.parenIfCommaList(" a, b "));
        assertEquals("(a,b)", MySqlDDLParserListenerImpl.parenIfCommaList("(a,b)"));
        assertEquals("a", MySqlDDLParserListenerImpl.parenIfCommaList("a"));
        assertEquals("toYear(d)", MySqlDDLParserListenerImpl.parenIfCommaList("toYear(d)"));
        assertEquals("cityHash64(a,b)",
                MySqlDDLParserListenerImpl.parenIfCommaList("cityHash64(a,b)"),
                "a function whose arguments are comma-separated must not be wrapped into a tuple");
        assertEquals(null, MySqlDDLParserListenerImpl.parenIfCommaList(null));
    }

    /** {@code namesNullableColumn} looks at identifiers, not at function names. */
    @Test
    public void namesNullableColumnFindsOnlyRealColumns() {
        List<String> columns = List.of("id", "d", "`Mixed`");
        Set<String> notNull = Set.of("id");

        assertTrue(MySqlDDLParserListenerImpl.namesNullableColumn("d", columns, notNull));
        assertTrue(MySqlDDLParserListenerImpl.namesNullableColumn("(d)", columns, notNull));
        assertTrue(MySqlDDLParserListenerImpl.namesNullableColumn("toYear(d)", columns, notNull));
        assertTrue(MySqlDDLParserListenerImpl.namesNullableColumn("(a, d)", columns, notNull));
        assertTrue(MySqlDDLParserListenerImpl.namesNullableColumn("`Mixed`", columns, notNull));

        assertFalse(MySqlDDLParserListenerImpl.namesNullableColumn("id", columns, notNull));
        assertFalse(MySqlDDLParserListenerImpl.namesNullableColumn("(id, d2)", columns, notNull),
                "d2 is not a column of this table");
        assertFalse(MySqlDDLParserListenerImpl.namesNullableColumn("toYear(order_date)", columns, notNull),
                "a function name and an unknown column are both skipped");
        assertFalse(MySqlDDLParserListenerImpl.namesNullableColumn(null, columns, notNull));
        assertFalse(MySqlDDLParserListenerImpl.namesNullableColumn("", columns, notNull));
    }
}
