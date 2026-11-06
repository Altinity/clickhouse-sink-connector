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

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grammar-derived coverage of the DML surface.
 *
 * <p>{@link StatementSurfaceCoverageTest} accounts for every {@code
 * ddlStatement} alternative. This test does the same for {@code
 * dmlStatement}, and for the same reason: the parser accepts the whole MySQL
 * language, so a DML statement that reaches {@code parseSql} is parsed and
 * then dropped without a word if the listener has no handler for it.</p>
 *
 * <p>Under row-based binlog format -- the only format this connector is
 * deployed against -- row changes arrive as row events and the DML text never
 * reaches the parser. The text still reaches it from two other places: a
 * source that runs STATEMENT or MIXED format, and any statement Debezium
 * decides to forward as SQL. In both cases the requirement is the same and it
 * is narrow: parse cleanly and emit nothing. {@code
 * ErrorListenerImpl.syntaxError} throws {@code RuntimeException("Error parsing
 * DDL")}, so an unparseable-but-harmless DML statement would halt replication
 * of every table on the host. Emitting nothing is correct because ClickHouse
 * rows come from the row events, not from re-executing the SQL; if that ever
 * changes, this test is where the decision has to be made explicit.</p>
 */
public class DmlStatementSurfaceCoverageTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /** The production parse path, returning whatever ClickHouse DDL it emits. */
    private static String translate(String sql) {
        service.setDdlEventTimestampMs(1700000000123L);
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /**
     * One representative statement per shipped {@code dmlStatement}
     * alternative, keyed by the accessor name on {@code DmlStatementContext}.
     *
     * <p>Representative, not exhaustive: the alternative is the unit of
     * coverage. What each alternative accepts internally is the grammar's
     * business, and the parser enforces it.</p>
     */
    private static Map<String, String> probes() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("callStatement", "CALL p()");
        // DESTRUCTIVE: fixture text only. The statement is handed to the parser
        // and classified; nothing is executed, so no row is deleted.
        probes.put("deleteStatement", "DELETE FROM t WHERE id = 1");
        probes.put("doStatement", "DO 1");
        probes.put("handlerStatement", "HANDLER t OPEN");
        probes.put("insertStatement", "INSERT INTO t VALUES (1)");
        probes.put("loadDataStatement", "LOAD DATA INFILE 'f.csv' INTO TABLE t");
        probes.put("loadXmlStatement", "LOAD XML INFILE 'f.xml' INTO TABLE t");
        probes.put("replaceStatement", "REPLACE INTO t VALUES (1)");
        probes.put("selectStatement", "SELECT 1");
        probes.put("tableStatement", "TABLE t");
        probes.put("updateStatement", "UPDATE t SET a = 1");
        // The ROW(...) spelling is the same grammar alternative with the keyword
        // MySqlDdlNormalizer drops, so it is covered by rowConstructorSpellingParses()
        // rather than here: this map is keyed one-to-one on the shipped
        // alternatives, and a second key would break that correspondence.
        probes.put("valuesStatement", "VALUES (1), (2)");
        probes.put("withStatement", "WITH c AS (SELECT 1) SELECT * FROM c");
        return probes;
    }

    /**
     * The DML alternatives of the shipped parser.
     *
     * <p>{@code dmlStatement} is an unlabelled rule, so its alternatives are
     * named sub-rules rather than {@code Context} subclasses: the accessors on
     * the context are the alternative list.</p>
     */
    private static Set<String> shippedDmlAlternatives() {
        Set<String> names = new TreeSet<>();
        for (Method method : MySqlParser.DmlStatementContext.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && !method.getName().startsWith("get")
                    && method.getReturnType().getSimpleName().endsWith("Context")) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * Every DML alternative the parser ships is probed, and no probe names an
     * alternative the parser does not have.
     *
     * <p>A parser upgrade that adds a DML form fails here until someone writes
     * its probe -- which is the moment to decide whether the new form parses in
     * this parser at all.</p>
     */
    @Test
    public void everyDmlAlternativeIsProbed() {
        Set<String> shipped = shippedDmlAlternatives();
        assertFalse(shipped.isEmpty(), "reflection found no dmlStatement alternatives");

        Set<String> probed = new TreeSet<>(probes().keySet());

        Set<String> unprobed = new TreeSet<>(shipped);
        unprobed.removeAll(probed);

        Set<String> stale = new TreeSet<>(probed);
        stale.removeAll(shipped);

        assertTrue(unprobed.isEmpty(), "DML alternatives with no probe:\n  "
                + String.join("\n  ", unprobed));
        assertTrue(stale.isEmpty(), "probes for alternatives the shipped grammar no longer defines:\n  "
                + String.join("\n  ", stale));
        assertEquals(shipped, probed, "the DML surface and the probe set disagree");
    }

    /**
     * The {@code VALUES ROW(...)} spelling MySQL 8.0.19 introduced parses too.
     *
     * <p>MySQL 8 accepts both row-constructor spellings -- {@code VALUES (1)}
     * and {@code VALUES ROW(1)}. The shipped parser's {@code
     * ValuesStatementContext} exposes {@code VALUES}, {@code LR_BRACKET},
     * {@code expressionsWithDefaults}, {@code COMMA}, {@code RR_BRACKET} and
     * nothing else, so the {@code ROW} keyword has nowhere to go and the raw
     * grammar fails at {@code no viable alternative at input 'VALUES ROW'}.
     * {@code MySqlDdlNormalizer} drops the keyword, which leaves exactly the
     * parenthesised list the grammar reads.</p>
     *
     * <p>Both the standalone {@code VALUES} statement and the {@code INSERT ...
     * VALUES ROW(...)} form are covered: the grammar has an alternative for the
     * standalone form too, so it needs the same rewrite.</p>
     */
    @Test
    public void rowConstructorSpellingParses() {
        String[] statements = {
                "VALUES ROW(1)",
                "VALUES ROW(1), ROW(2)",
                "INSERT INTO t VALUES ROW(1, 2), ROW(3, 4)",
                "INSERT INTO t (a, b) VALUES ROW(1, 2)",
        };
        for (String sql : statements) {
            assertTrue(translate(sql).trim().isEmpty(),
                    "a VALUES statement must parse and emit nothing: [" + sql + "]");
        }

        assertEquals("VALUES (1)", MySqlDdlNormalizer.normalize("VALUES ROW(1)"));
        // A quoted `row` and a literal carrying the word are not constructors.
        assertEquals("INSERT INTO t (`row`, b) VALUES (1, 2)",
                MySqlDdlNormalizer.normalize("INSERT INTO t (`row`, b) VALUES (1, 2)"));
        assertEquals("INSERT INTO t (a, b) VALUES (1, 'ROW(9)')",
                MySqlDdlNormalizer.normalize("INSERT INTO t (a, b) VALUES (1, 'ROW(9)')"));
    }

    /**
     * The shapes the end-to-end DML matrix adds beyond the one-per-alternative
     * probes: the modifiers and sub-forms each alternative accepts internally.
     *
     * <p>These exist because a per-alternative probe proves only that the
     * alternative parses at all. The grammar has real optionality inside an
     * alternative -- {@code INSERT ... AS new}, {@code PARTITION (...)} on
     * {@code REPLACE}, {@code AS? uid} on {@code DELETE}, a nested join as a
     * {@code tableSource} -- and an unparseable-but-harmless statement halts
     * replication of every table on the host, so each spelling in
     * {@code dml_ddl_matrix.py} is pinned here too. The end-to-end matrix
     * proves the rows arrive; this proves the text never throws.</p>
     */
    private static Map<String, String> extendedShapes() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("insertAliasOnDuplicate", "INSERT INTO t (id, c) VALUES (1, 2) AS new "
                + "ON DUPLICATE KEY UPDATE c = new.c");
        shapes.put("insertAliasOnDuplicateColumnAliases",
                "INSERT INTO t (id, c) VALUES (1, 2) AS new(m, n) "
                        + "ON DUPLICATE KEY UPDATE c = m + n");
        shapes.put("insertValuesDefault", "INSERT INTO t (id, c, d) VALUES (1, 10, DEFAULT)");
        shapes.put("insertSetMultiple", "INSERT INTO t SET id = 1, c = 10, s = 'multi'");
        shapes.put("replacePartition", "REPLACE INTO t PARTITION (p1) (id, c) VALUES (2000, 1)");
        shapes.put("replaceSelect", "REPLACE INTO t (id, c) SELECT id, c FROM src");
        // DESTRUCTIVE: fixture text only. The aliased DELETE spelling is parsed
        // and classified; nothing is executed, so no row is deleted.
        shapes.put("deleteAliased", "DELETE FROM t AS d WHERE d.id = 1");
        shapes.put("updateNestedJoin",
                "UPDATE (t INNER JOIN u ON t.id = u.id) SET t.c = 55");
        shapes.put("updateOrderByLimit",
                "UPDATE t SET c = 99, s = 'tail' WHERE c > 0 ORDER BY id DESC LIMIT 1");
        // DESTRUCTIVE: fixture text only. DELETE with ORDER BY is parsed and
        // classified; nothing is executed, so no row is deleted.
        shapes.put("deleteOrderBy", "DELETE FROM t WHERE id > 0 ORDER BY id");
        shapes.put("updateDefaultMultiple", "UPDATE t SET c = DEFAULT, d = DEFAULT WHERE id = 1");
        shapes.put("insertMultiRowWithDefault",
                "INSERT INTO t (id, c, d) VALUES (1, 10, DEFAULT), (2, 20, 99), (3, 30, DEFAULT)");
        // MySQL 8.0.19 puts the row alias after the whole SET assignment list.
        // The shipped grammar has `AS? uid` only where a VALUES list has just
        // ended, so the raw statement fails at 'AS'; the normalizer drops the
        // clause. Both spellings are pinned because they reach the parser from
        // different producers (an ORM writing SET form vs a hand-written script).
        shapes.put("insertSetAlias",
                "INSERT INTO t SET id = 1, c = 10 AS new ON DUPLICATE KEY UPDATE c = new.c");
        shapes.put("insertSetAliasColumnAliases",
                "INSERT INTO t SET id = 1, c = 10 AS new(m, n) "
                        + "ON DUPLICATE KEY UPDATE c = m + n");
        shapes.put("insertSetAliasBackticked",
                "INSERT INTO t SET id = 1, c = 10 AS `new`(m) ON DUPLICATE KEY UPDATE c = m");
        // The row-constructor spelling, mixed with the plain one in one list.
        shapes.put("insertRowConstructor",
                "INSERT INTO t VALUES ROW(1, 2), ROW(3, 4)");
        shapes.put("insertRowConstructorMixed",
                "INSERT INTO t (id, c) VALUES ROW(1, 2), (3, 4)");
        return shapes;
    }

    /**
     * Every optional clause the shipped insert/replace/update/delete contexts
     * expose, one statement each.
     *
     * <p>Derived by sweeping the accessors javap reports on those contexts, not
     * by picking statements: the grammar decides what MySQL accepts, so the
     * accessors decide what has to be covered. A clause with no statement here
     * is a spelling that could reach the parser untested -- and an unparseable
     * spelling kills replication of every table on the host.</p>
     *
     * <p>The sweep that produced this list runs 79 valid MySQL 8 statements
     * through the shipped parser with the connector's own lexer setup, at the
     * production entry point ({@code root()}, which requires the whole
     * statement to parse). Three of them did not parse and are bridged in
     * {@code MySqlDdlNormalizer}: the row-alias column list, the row alias on
     * an {@code INSERT ... SET} statement, and the {@code VALUES ROW(...)}
     * constructor. All three are pinned below.</p>
     */
    private static Map<String, String> clauseSweepShapes() {
        Map<String, String> shapes = new LinkedHashMap<>();

        shapes.put("insert.lowPriority", "INSERT LOW_PRIORITY INTO t VALUES (1)");
        shapes.put("insert.delayed", "INSERT DELAYED INTO t VALUES (1)");
        shapes.put("insert.highPriority", "INSERT HIGH_PRIORITY INTO t VALUES (1)");
        shapes.put("insert.ignore", "INSERT IGNORE INTO t VALUES (1)");
        shapes.put("insert.partition", "INSERT INTO t PARTITION (p0) VALUES (1)");
        shapes.put("insert.partitionColumns", "INSERT INTO t PARTITION (p0) (a) VALUES (1)");
        shapes.put("insert.select", "INSERT INTO t SELECT * FROM s");
        shapes.put("insert.odkuValuesFn", "INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE a = VALUES(a)");
        shapes.put("insert.multiRowAlias", "INSERT INTO t VALUES (1), (2) AS new ON DUPLICATE KEY UPDATE a = new.a");
        // Added by the second clause sweep. Each one is an expression or
        // value-source shape the grammar accepts and that no earlier row
        // spelled, so a bridging change that widened the normalizer's
        // patterns over the whole statement could have broken it unseen.
        shapes.put("insert.unionSelect",
                "INSERT INTO t (a) SELECT a FROM s1 UNION SELECT a FROM s2");
        shapes.put("insert.unionAllSelect",
                "INSERT INTO t (a) SELECT a FROM s1 UNION ALL SELECT a FROM s2");
        shapes.put("insert.setDefault", "INSERT INTO t SET a = DEFAULT");
        shapes.put("insert.odkuDefault", "INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE a = DEFAULT");
        shapes.put("insert.ignoreSet", "INSERT IGNORE INTO t SET a = 1");
        shapes.put("insert.emptyColumnList", "INSERT INTO t () VALUES ()");
        shapes.put("insert.emptyTuple", "INSERT INTO t VALUES ()");
        shapes.put("insert.bitLiteral", "INSERT INTO t (a) VALUES (b'1010')");
        shapes.put("insert.hexLiteral", "INSERT INTO t (a) VALUES (x'1f')");
        shapes.put("insert.castJson", "INSERT INTO t (a) VALUES (CAST('{}' AS JSON))");
        shapes.put("insert.interval", "INSERT INTO t (a) VALUES (INTERVAL 1 DAY)");
        shapes.put("insert.collate", "INSERT INTO t (a) VALUES ('x' COLLATE utf8mb4_bin)");
        shapes.put("insert.memberOf", "INSERT INTO t (a) VALUES (1 MEMBER OF (b))");
        shapes.put("insert.varAssign", "INSERT INTO t (a) VALUES (@x := 1)");

        shapes.put("replace.lowPriority", "REPLACE LOW_PRIORITY INTO t VALUES (1)");
        shapes.put("replace.partitionColumns", "REPLACE INTO t PARTITION (p0) (a) VALUES (1)");
        shapes.put("replace.set", "REPLACE INTO t SET a = 1");
        shapes.put("replace.delayed", "REPLACE DELAYED INTO t VALUES (1)");
        shapes.put("replace.setMultiple", "REPLACE INTO t SET a = 1, b = 2");
        shapes.put("replace.partitionSet", "REPLACE INTO t PARTITION (p0) SET a = 1");

        shapes.put("update.lowPriority", "UPDATE LOW_PRIORITY t SET a = 1");
        shapes.put("update.ignore", "UPDATE IGNORE t SET a = 1");
        shapes.put("update.limit", "UPDATE t SET a = 1 LIMIT 5");
        shapes.put("update.alias", "UPDATE t AS x SET x.a = 1");
        shapes.put("update.aliasBare", "UPDATE t x SET x.a = 1");
        shapes.put("update.join", "UPDATE t INNER JOIN u ON t.id = u.id SET t.a = u.b");
        shapes.put("update.multiTable", "UPDATE t, u SET t.a = u.b WHERE t.id = u.id");
        shapes.put("update.leftJoin", "UPDATE t LEFT JOIN u ON t.id = u.id SET t.a = u.b");
        shapes.put("update.subqueryAssign", "UPDATE t SET a = (SELECT max(b) FROM u)");
        shapes.put("update.straightJoin", "UPDATE t STRAIGHT_JOIN u ON t.id = u.id SET t.a = 1");
        shapes.put("update.joinMultiAssign",
                "UPDATE t INNER JOIN u ON t.id = u.id SET t.a = 1, t.b = 2");
        shapes.put("update.setDefaultMulti", "UPDATE t SET a = DEFAULT, b = DEFAULT");
        shapes.put("update.limitNoOrder", "UPDATE t SET a = 1 LIMIT 5");
        shapes.put("update.nestedJoinAlias",
                "UPDATE (t INNER JOIN u ON t.id = u.id) AS j SET j.a = 1");
        shapes.put("update.simpleCase",
                "UPDATE t SET a = CASE WHEN b > 0 THEN 1 ELSE 0 END");

        shapes.put("delete.lowPriority", "DELETE LOW_PRIORITY FROM t");
        shapes.put("delete.quick", "DELETE QUICK FROM t");
        shapes.put("delete.ignore", "DELETE IGNORE FROM t");
        // DESTRUCTIVE: fixture text only. Each DELETE spelling below is parsed
        // and classified; nothing is executed, so no row is deleted.
        shapes.put("delete.partition", "DELETE FROM t PARTITION (p0)");
        shapes.put("delete.partitionList", "DELETE FROM t PARTITION (p0, p1)");
        shapes.put("delete.limit", "DELETE FROM t LIMIT 10");
        shapes.put("delete.orderByLimit", "DELETE FROM t ORDER BY id LIMIT 10");
        shapes.put("delete.aliasBare", "DELETE FROM t d WHERE d.id = 1");
        shapes.put("delete.multiFrom", "DELETE t1, t2 FROM t1 INNER JOIN t2 ON t1.id = t2.id");
        // DESTRUCTIVE: fixture text only. The multi-table DELETE is parsed and
        // classified; nothing is executed, so no row is deleted.
        shapes.put("delete.multiUsing", "DELETE FROM t1, t2 USING t1 INNER JOIN t2 ON t1.id = t2.id");
        shapes.put("delete.star", "DELETE t1.*, t2.* FROM t1, t2 WHERE t1.id = t2.id");
        shapes.put("delete.starOne", "DELETE t1.* FROM t1 WHERE t1.id = 1");
        shapes.put("delete.quickLimit", "DELETE QUICK FROM t LIMIT 5");

        shapes.put("handler.read", "HANDLER t READ FIRST");
        shapes.put("handler.close", "HANDLER t CLOSE");

        // Producer-specific shapes. The rows above are grammar shapes; these are
        // what a driver, an ORM or a bulk loader actually puts on the wire, and
        // they combine clauses the grammar-shaped rows keep apart. A statement
        // MySQL accepted is a statement the binlog can carry to the connector,
        // so each of these must survive the same way.
        shapes.put("insert.jdbcBatchPlaceholders",
                "INSERT INTO t (a, b) VALUES (?, ?), (?, ?), (?, ?)");
        shapes.put("insert.namedColumnsBackticked",
                "INSERT INTO `db`.`t` (`a`, `b`) VALUES (1, 2)");
        shapes.put("insert.onDuplicateMixedAssignments",
                "INSERT INTO t (a, b) VALUES (1, 2) ON DUPLICATE KEY UPDATE "
                        + "b = VALUES(b), a = a + 1");
        shapes.put("insert.largeMultiRow",
                "INSERT INTO t (a) VALUES (1), (2), (3), (4), (5), (6), (7), (8)");
        shapes.put("insert.selectNested",
                "INSERT INTO t (a) SELECT x.a FROM (SELECT a FROM s) x");
        shapes.put("insert.selectWithCte",
                "INSERT INTO t (a) WITH c AS (SELECT 1 AS a) SELECT a FROM c");
        shapes.put("insert.setExpression",
                "INSERT INTO t SET a = 1 + 1, b = CONCAT('x', 'y')");
        shapes.put("insert.jsonDefaultAlias",
                "INSERT INTO t (a, b) VALUES (1, DEFAULT) AS new "
                        + "ON DUPLICATE KEY UPDATE b = new.b");
        shapes.put("insert.bulkLoaderAllDefaults",
                "INSERT INTO t VALUES (DEFAULT, DEFAULT, DEFAULT)");
        shapes.put("insert.unhex",
                "INSERT INTO t (a) VALUES (UNHEX('1f'))");
        shapes.put("insert.negativeAndFloat",
                "INSERT INTO t (a, b) VALUES (-1, 1.5e10), (0, -0.0)");
        shapes.put("insert.stringEscapes",
                "INSERT INTO t (a) VALUES ('it''s'), ('a\\\\b'), ('tab\\there')");
        shapes.put("insert.nullExplicit",
                "INSERT INTO t (a, b) VALUES (NULL, NULL)");

        shapes.put("update.batchStyle", "UPDATE t SET a = 1, b = 2, c = 3 WHERE id IN (1, 2, 3)");
        shapes.put("update.whereStatic", "UPDATE t SET a = 1 WHERE 1 = 1");
        shapes.put("update.optimisticLock", "UPDATE t SET a = 1, version = version + 1 WHERE id = 1");
        shapes.put("update.leftJoinThree",
                "UPDATE t LEFT JOIN u ON t.id = u.id LEFT JOIN w ON u.id = w.id "
                        + "SET t.a = w.b");
        // DESTRUCTIVE: fixture text only. The cascading DELETE spelling is parsed
        // and classified; nothing is executed, so no row is deleted.
        shapes.put("delete.inList", "DELETE FROM t WHERE id IN (1, 2, 3)");
        shapes.put("delete.range", "DELETE FROM t WHERE created < '2020-01-01'");
        shapes.put("delete.andOr", "DELETE FROM t WHERE a = 1 AND b = 2 OR c = 3");

        shapes.put("replace.selectColumns", "REPLACE INTO t (a, b) SELECT a, b FROM s");
        shapes.put("replace.ignore", "REPLACE LOW_PRIORITY INTO t (a) VALUES (1)");

        shapes.put("call.withArgs", "CALL p(1, 'x', NULL)");
        shapes.put("do.multiple", "DO 1, 2, SLEEP(0)");
        shapes.put("handler.readNext", "HANDLER t READ NEXT");
        shapes.put("handler.readIndex", "HANDLER t READ `idx` FIRST");
        return shapes;
    }

    /**
     * No MySQL 8 clause the shipped grammar exposes may throw.
     *
     * <p>This is the regression net for the class of defect that started this
     * work: a spelling that is valid MySQL, reaches the parser, and dies at
     * {@code sqlStatements} -- which stops replication for every table on the
     * host, not only the table named in the statement.</p>
     */
    @Test
    public void everyInsertReplaceUpdateAndDeleteClauseParses() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> shape : clauseSweepShapes().entrySet()) {
            String sql = shape.getValue();
            try {
                service.setDdlEventTimestampMs(1700000000123L);
                StringBuffer output = new StringBuffer();
                service.parseSql(sql, "t", output);
                if (!output.toString().trim().isEmpty()) {
                    failures.add(shape.getKey() + " [" + sql + "] -> emitted [" + output + "]");
                }
            } catch (RuntimeException e) {
                failures.add(shape.getKey() + " [" + sql + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "every MySQL 8 DML clause must parse silently:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * Every extended shape parses without throwing and emits nothing.
     *
     * <p>Emitting nothing is the correct outcome for DML on every path: the
     * connector's rows come from binlog row events, so there is no ClickHouse
     * SQL to produce. If that ever changes, this test is where it shows up.</p>
     */
    @Test
    public void extendedDmlShapesParseAndEmitNothing() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> shape : extendedShapes().entrySet()) {
            String sql = shape.getValue();
            try {
                service.setDdlEventTimestampMs(1700000000123L);
                StringBuffer output = new StringBuffer();
                service.parseSql(sql, "t", output);
                if (!output.toString().trim().isEmpty()) {
                    failures.add(shape.getKey() + " [" + sql + "] -> emitted [" + output + "]");
                }
            } catch (RuntimeException e) {
                failures.add(shape.getKey() + " [" + sql + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "extended DML shapes must parse silently:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * Each DML statement parses without throwing and emits nothing.
     *
     * <p>Both halves matter. A throw stops the replication stream, and an
     * emission would mean the connector is translating SQL text into
     * ClickHouse SQL on a path nobody designed for it.</p>
     */
    @Test
    public void everyDmlStatementParsesAndEmitsNothing() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> probe : probes().entrySet()) {
            String sql = probe.getValue();
            try {
                service.setDdlEventTimestampMs(1700000000123L);
                StringBuffer output = new StringBuffer();
                service.parseSql(sql, "t", output);
                if (!output.toString().trim().isEmpty()) {
                    failures.add(probe.getKey() + " [" + sql + "] -> emitted [" + output + "]");
                }
            } catch (RuntimeException e) {
                failures.add(probe.getKey() + " [" + sql + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "DML statements must parse silently:\n  "
                + String.join("\n  ", failures));
    }
}
