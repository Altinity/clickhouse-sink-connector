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
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive matrix over the six grammar rules that decide the constraint,
 * index-declaration and partition SHAPE of a {@code CREATE TABLE}: {@code
 * tableConstraint}, {@code indexColumnDefinition}, {@code
 * partitionFunctionDefinition}, {@code subpartitionFunctionDefinition},
 * {@code partitionDefinition} and {@code partitionOption}.
 *
 * <p>These are the remaining clauses of a table definition that a production
 * schema uses constantly and that no other matrix asserts. A table created with
 * {@code PARTITION BY RANGE} or a named {@code CHECK} constraint is ordinary
 * MySQL, not an edge case: every partitioned table starts life as one of these
 * statements, and a parser that refuses one stops the replication stream
 * before the table has any rows to lose.</p>
 *
 * <p>Coverage is enumerated from the shipped parser by reflection, the same way
 * the sibling matrices do it, so a Debezium bump that adds an alternative fails
 * here until a row covers it. For each alternative the matrix asserts three
 * things that a hand-written statement list cannot:</p>
 *
 * <ol>
 *   <li>the alternative has a row (both directions: nothing shipped is missed,
 *       and no row names a label the grammar dropped);</li>
 *   <li>the row's statement really parses through THAT alternative, so a
 *       statement accepted by a different alternative cannot inflate the
 *       apparent coverage;</li>
 *   <li>the statement does not throw, and the table is still created.</li>
 * </ol>
 *
 * <p><b>Why the table must still be created.</b> Constraints, index
 * declarations and partitions have no ClickHouse counterpart in this
 * connector's translation: sorting/primary keys are chosen by the connector,
 * secondary indexes are not modelled, and ClickHouse partitioning is not
 * carried over. The connector therefore DROPS these clauses -- {@code
 * MySqlDDLParserListenerImpl.isNoOpSpecification} is the skip path for exactly
 * this class of clause. Dropping the clause is correct. Dropping the TABLE is
 * not: the table exists in MySQL and the connector must create it in
 * ClickHouse, or every later row event for it lands on a table that was never
 * made.</p>
 */
public class CreateTableConstraintAndPartitionMatrixTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.setDdlEventTimestampMs(1700000000123L);
        service.parseSql(sql, "t", output);
        return output.toString().trim();
    }

    // ------------------------------------------------------------------
    // Reflection over the parser the connector ships.
    // ------------------------------------------------------------------

    /**
     * The labelled alternatives of one grammar rule.
     *
     * <p>Membership is decided by SUPERCLASS, never by class name: a labelled
     * alternative's context extends the rule's own context class, while the
     * rule's base context extends {@code ParserRuleContext}. Comparing simple
     * names would match a coincidence such as {@code
     * ConvertedDataTypeContext} against {@code dataType}.</p>
     */
    private static Set<String> labelledAlternatives(Class<?> rule) {
        Set<String> names = new TreeSet<>();
        for (Class<?> nested : MySqlParser.class.getDeclaredClasses()) {
            if (nested.getSuperclass() != rule) {
                continue;
            }
            names.add(labelOf(nested));
        }
        return names;
    }

    /** {@code PartitionOptionNodeGroupContext} becomes {@code partitionOptionNodeGroup}. */
    private static String labelOf(Class<?> contextClass) {
        String simple = contextClass.getSimpleName();
        String label = simple.substring(0, simple.length() - "Context".length());
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    /**
     * The alternatives of the named rule that walking {@code sql} actually
     * enters.
     *
     * <p>The statement goes through {@link MySqlDdlNormalizer} first, because
     * that is what the connector parses: {@code MySQLDDLParserService} calls
     * {@code normalize} before constructing the lexer. Parsing the raw text here
     * would test a pipeline production does not have, and would report a
     * statement the connector handles as unparseable.</p>
     */
    private static Set<String> alternativesEntered(String sql, Class<?> rule) {
        Set<String> entered = new TreeSet<>();
        String normalized = MySqlDdlNormalizer.normalize(sql);
        MySqlParser.RootContext root = new MySqlParser(
                new org.antlr.v4.runtime.CommonTokenStream(
                        new io.debezium.ddl.parser.mysql.generated.MySqlLexer(
                                new io.debezium.antlr.CaseChangingCharStream(
                                        org.antlr.v4.runtime.CharStreams.fromString(normalized), true))))
                .root();
        org.antlr.v4.runtime.tree.ParseTreeWalker.DEFAULT.walk(
                new org.antlr.v4.runtime.tree.ParseTreeListener() {
                    @Override
                    public void enterEveryRule(org.antlr.v4.runtime.ParserRuleContext ctx) {
                        if (ctx.getClass().getSuperclass() == rule) {
                            entered.add(labelOf(ctx.getClass()));
                        }
                    }

                    @Override
                    public void exitEveryRule(org.antlr.v4.runtime.ParserRuleContext ctx) {
                    }

                    @Override
                    public void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) {
                    }

                    @Override
                    public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
                    }
                }, root);
        return entered;
    }

    // ------------------------------------------------------------------
    // Matrix.
    // ------------------------------------------------------------------

    /**
     * One matrix row: the rule and alternative it covers, one statement for it,
     * and whether that statement must still produce a ClickHouse CREATE TABLE.
     *
     * <p>The rule is carried explicitly because six rules are covered here and
     * their label sets must never be compared as one: two rules that each had
     * an alternative called {@code engineOption} would make a missing row look
     * covered.</p>
     */
    private static final class Row {
        final Class<?> rule;
        final String alternative;
        final String sql;
        final boolean emitsTable;
        /**
         * The source columns the emitted CREATE TABLE must still declare. It
         * defaults to the single key column most rows use, and a row that
         * declares different columns names them, so the "nothing was lost"
         * assertion states what the statement actually asked for instead of
         * assuming every table has an {@code id}.
         */
        final List<String> columns;

        private Row(Class<?> rule, String alternative, String sql, boolean emitsTable,
                    String... columns) {
            this.rule = rule;
            this.alternative = alternative;
            this.sql = sql;
            this.emitsTable = emitsTable;
            this.columns = columns.length == 0 ? List.of("id") : List.of(columns);
        }

        @Override
        public String toString() {
            return rule.getSimpleName().replace("Context", "") + "." + alternative + " / " + sql;
        }
    }

    /** Table body carrying exactly one {@code tableConstraint} alternative. */
    private static List<Row> tableConstraintRows() {
        Class<?> rule = MySqlParser.TableConstraintContext.class;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(rule, "primaryKeyTableConstraint",
                "CREATE TABLE t (id INT, CONSTRAINT pk PRIMARY KEY (id))", true));
        rows.add(new Row(rule, "uniqueKeyTableConstraint",
                "CREATE TABLE t (id INT, UNIQUE KEY uk (id))", true));
        rows.add(new Row(rule, "foreignKeyTableConstraint",
                "CREATE TABLE t (id INT, CONSTRAINT fk FOREIGN KEY (id) REFERENCES r (id))", true));
        rows.add(new Row(rule, "checkTableConstraint",
                "CREATE TABLE t (id INT, CONSTRAINT ck CHECK (id > 0))", true));
        // CLUSTERING KEY parses in the shipped grammar but MySQL 8 never emits
        // it, so no binlog can carry this statement. It is covered because the
        // parser ACCEPTS it: an accepted-but-foreign clause must still not
        // stop the stream.
        rows.add(new Row(rule, "clusteringKeyTableConstraint",
                "CREATE TABLE t (id INT, CLUSTERING KEY ck (id))", true));
        return rows;
    }

    /** Table body carrying exactly one {@code indexColumnDefinition} alternative. */
    private static List<Row> indexDeclarationRows() {
        Class<?> rule = MySqlParser.IndexColumnDefinitionContext.class;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(rule, "simpleIndexDeclaration",
                "CREATE TABLE t (id INT, KEY k (id))", true));
        rows.add(new Row(rule, "specialIndexDeclaration",
                "CREATE TABLE t (v VARCHAR(10), FULLTEXT KEY ft (v))", true, "v"));
        return rows;
    }

    /** One {@code PARTITION BY} clause per {@code partitionFunctionDefinition} alternative. */
    private static List<Row> partitionFunctionRows() {
        Class<?> rule = MySqlParser.PartitionFunctionDefinitionContext.class;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(rule, "partitionFunctionHash",
                "CREATE TABLE t (id INT) PARTITION BY HASH(id) PARTITIONS 4", true));
        rows.add(new Row(rule, "partitionFunctionKey",
                "CREATE TABLE t (id INT) PARTITION BY KEY(id) PARTITIONS 4", true));
        rows.add(new Row(rule, "partitionFunctionRange",
                "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "(PARTITION p0 VALUES LESS THAN (10), "
                        + "PARTITION p1 VALUES LESS THAN MAXVALUE)", true));
        rows.add(new Row(rule, "partitionFunctionList",
                "CREATE TABLE t (id INT) PARTITION BY LIST(id) "
                        + "(PARTITION p0 VALUES IN (1, 2))", true));
        return rows;
    }

    /** One {@code SUBPARTITION BY} clause per alternative; both need a parent range. */
    private static List<Row> subpartitionFunctionRows() {
        Class<?> rule = MySqlParser.SubpartitionFunctionDefinitionContext.class;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(rule, "subPartitionFunctionHash",
                "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "SUBPARTITION BY HASH(id) SUBPARTITIONS 2 "
                        + "(PARTITION p0 VALUES LESS THAN (10))", true));
        rows.add(new Row(rule, "subPartitionFunctionKey",
                "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "SUBPARTITION BY KEY(id) SUBPARTITIONS 2 "
                        + "(PARTITION p0 VALUES LESS THAN (10))", true));
        return rows;
    }

    /** One partition-list entry per {@code partitionDefinition} alternative. */
    private static List<Row> partitionDefinitionRows() {
        Class<?> rule = MySqlParser.PartitionDefinitionContext.class;
        List<Row> rows = new ArrayList<>();
        // Two spellings reach partitionComparison: parenthesised definers and a
        // bare one. Both are the same labelled alternative, so one row suffices
        // for completeness; the bare form is exercised by the MAXVALUE row in
        // partitionFunctionRows.
        rows.add(new Row(rule, "partitionComparison",
                "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "(PARTITION p0 VALUES LESS THAN (10))", true));
        rows.add(new Row(rule, "partitionListAtom",
                "CREATE TABLE t (id INT) PARTITION BY LIST(id) "
                        + "(PARTITION p0 VALUES IN (1, 2))", true));
        rows.add(new Row(rule, "partitionListVector",
                // A vector of plain integers does NOT reach this alternative:
                // ((1, 2), (3, 4)) is absorbed by partitionListAtom plus a
                // nested expression atom. The vector alternative is reached
                // only when an atom is MAXVALUE, which is also the realistic
                // spelling -- a LIST COLUMNS table ends with a catch-all
                // partition. Verified on the shipped parser by reflection.
                "CREATE TABLE t (a INT, b INT) PARTITION BY LIST COLUMNS(a, b) "
                        + "(PARTITION p0 VALUES IN ((MAXVALUE, 5)))", true, "a", "b"));
        rows.add(new Row(rule, "partitionSimple",
                "CREATE TABLE t (id INT) PARTITION BY HASH(id) "
                        + "(PARTITION p0, PARTITION p1)", true));
        return rows;
    }

    /** One {@code partitionOption} per alternative, attached to a real partition. */
    private static List<Row> partitionOptionRows() {
        Class<?> rule = MySqlParser.PartitionOptionContext.class;
        String head = "CREATE TABLE t (id INT) PARTITION BY RANGE(id) (PARTITION p0 VALUES LESS THAN (10) ";
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(rule, "partitionOptionEngine", head + "ENGINE = InnoDB)", true));
        rows.add(new Row(rule, "partitionOptionComment", head + "COMMENT = 'c')", true));
        rows.add(new Row(rule, "partitionOptionDataDirectory",
                head + "DATA DIRECTORY = '/var/lib/mysql-files/d')", true));
        rows.add(new Row(rule, "partitionOptionIndexDirectory",
                head + "INDEX DIRECTORY = '/var/lib/mysql-files/i')", true));
        rows.add(new Row(rule, "partitionOptionMaxRows", head + "MAX_ROWS = 1000)", true));
        rows.add(new Row(rule, "partitionOptionMinRows", head + "MIN_ROWS = 10)", true));
        rows.add(new Row(rule, "partitionOptionTablespace", head + "TABLESPACE ts1)", true));
        // The unquoted numeric value is what MySQL's own documentation shows.
        // The shipped grammar accepts only a uid there, and uid cannot be a
        // bare number -- MySqlDdlNormalizer quotes it before parsing, which is
        // what makes this row parseable (the quoted form is the one the parser
        // sees; the second row proves the quoted spelling directly).
        rows.add(new Row(rule, "partitionOptionNodeGroup", head + "NODEGROUP = 2)", true));
        rows.add(new Row(rule, "partitionOptionNodeGroup", head + "NODEGROUP = `2`)", true));
        return rows;
    }

    private static List<Row> allRows() {
        List<Row> rows = new ArrayList<>();
        rows.addAll(tableConstraintRows());
        rows.addAll(indexDeclarationRows());
        rows.addAll(partitionFunctionRows());
        rows.addAll(subpartitionFunctionRows());
        rows.addAll(partitionDefinitionRows());
        rows.addAll(partitionOptionRows());
        return rows;
    }

    // ------------------------------------------------------------------
    // Assertions.
    // ------------------------------------------------------------------

    /**
     * Every alternative of all six rules has a matrix row, and no row names an
     * alternative the shipped grammar no longer defines.
     */
    @Test
    public void everyAlternativeHasAMatrixRow() {
        Class<?>[] rules = {
                MySqlParser.TableConstraintContext.class,
                MySqlParser.IndexColumnDefinitionContext.class,
                MySqlParser.PartitionFunctionDefinitionContext.class,
                MySqlParser.SubpartitionFunctionDefinitionContext.class,
                MySqlParser.PartitionDefinitionContext.class,
                MySqlParser.PartitionOptionContext.class,
        };
        for (Class<?> rule : rules) {
            Set<String> shipped = labelledAlternatives(rule);
            // Guard first: an empty reflection result would satisfy the
            // equality below against an empty matrix and report green while
            // covering nothing.
            assertFalse(shipped.isEmpty(), "reflection found no alternatives for "
                    + rule.getSimpleName() + " -- the assertion below would pass vacuously");

            Set<String> covered = alternativesIn(allRows(), rule);
            assertFalse(covered.isEmpty(), "the matrix has no rows for " + rule.getSimpleName());

            Set<String> missing = new TreeSet<>(shipped);
            missing.removeAll(covered);
            Set<String> stale = new TreeSet<>(covered);
            stale.removeAll(shipped);

            assertTrue(missing.isEmpty(), rule.getSimpleName()
                    + " alternatives with no matrix row:\n  " + String.join("\n  ", missing));
            assertTrue(stale.isEmpty(), "matrix rows for " + rule.getSimpleName()
                    + " alternatives the shipped grammar does not define:\n  "
                    + String.join("\n  ", stale));
        }
    }

    /** Each row parses through the alternative it claims, not a neighbouring one. */
    @Test
    public void eachRowEntersItsDeclaredAlternative() {
        List<String> wrong = new ArrayList<>();
        for (Row row : allRows()) {
            if (!alternativesEntered(row.sql, row.rule).contains(row.alternative)) {
                wrong.add(row.toString());
            }
        }
        assertTrue(wrong.isEmpty(), "rows that did not enter their declared alternative:\n  "
                + String.join("\n  ", wrong));
    }

    /**
     * No row stops the parser.
     *
     * <p>{@code ErrorListenerImpl.syntaxError} throws {@code
     * RuntimeException("Error parsing DDL")} and the CDC caller wraps that into
     * a terminal {@code DDLReplicationException}. One unparseable constraint on
     * one table would otherwise halt replication for every table on the host.</p>
     */
    @Test
    public void noRowThrowsDuringTranslation() {
        List<String> failures = new ArrayList<>();
        for (Row row : allRows()) {
            try {
                translate(row.sql);
            } catch (RuntimeException e) {
                failures.add(row + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "rows that stopped the parser:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * The clause is dropped; the table is still created with its columns.
     *
     * <p>This is the assertion that separates "we ignore the constraint" from
     * "we lose the table". The first is the intended translation; the second
     * means every subsequent row event for a real MySQL table targets a
     * ClickHouse table that does not exist.</p>
     */
    @Test
    public void theClauseIsDroppedButTheTableIsStillCreated() {
        List<String> failures = new ArrayList<>();
        for (Row row : allRows()) {
            if (!row.emitsTable) {
                continue;
            }
            String emitted = translate(row.sql);
            if (!emitted.toUpperCase().startsWith("CREATE TABLE")) {
                failures.add(row + " -> no ClickHouse CREATE TABLE, got: [" + emitted + "]");
                continue;
            }
            for (String column : row.columns) {
                if (!declaresColumn(emitted, column)) {
                    failures.add(row + " -> the column " + column + " was lost: " + emitted);
                    break;
                }
            }
        }
        assertTrue(failures.isEmpty(), "these statements lost the table, not just the clause:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * True when the emitted CREATE TABLE DECLARES the named column.
     *
     * <p>The check reads the column list itself rather than searching the whole
     * statement: the name also appears in {@code ORDER BY (id)} and
     * {@code PARTITION BY id}, so a plain substring test passes even for a
     * statement whose column declaration was lost. The name is matched against
     * the first token of each top-level entry of the body, which is exactly
     * what "this table has that column" means.</p>
     */
    private static boolean declaresColumn(String emitted, String column) {
        int open = emitted.indexOf('(');
        if (open < 0) {
            return false;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < emitted.length(); i++) {
            char c = emitted.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return false;
        }
        String body = emitted.substring(open + 1, close);
        int entryStart = 0;
        depth = 0;
        for (int i = 0; i <= body.length(); i++) {
            boolean atEnd = i == body.length();
            char c = atEnd ? ',' : body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String entry = body.substring(entryStart, i).trim();
                entryStart = i + 1;
                String name = entry.split("\\s+", 2)[0].replace("`", "").trim();
                if (name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * No MySQL-only partition or constraint syntax leaks into the emitted DDL.
     *
     * <p>The emitted statement is executed against ClickHouse. A surviving
     * {@code FOREIGN KEY}, {@code ENGINE = InnoDB} or MySQL partition
     * DEFINITION ({@code PARTITIONS 4}, {@code SUBPARTITION BY}, {@code VALUES
     * LESS THAN}, {@code VALUES IN}, {@code NODEGROUP}, {@code MAXVALUE},
     * {@code DATA DIRECTORY}) makes ClickHouse reject it, and the rejection is
     * retried against the same binlog position for as long as the connector
     * lives.</p>
     *
     * <p>{@code PARTITION BY} itself is NOT in the list, and must not be: the
     * connector translates the MySQL partition KEY into a ClickHouse partition
     * key on purpose ({@code DDL.md}, "CREATE TABLE ... PARTITION BY KEY(col1)"
     * becomes {@code PARTITION BY col1}), so its presence is the contract, not
     * a leak. What must never survive is the MySQL partition-definition
     * syntax, which carries no ClickHouse meaning.</p>
     */
    @Test
    public void noMySqlOnlySyntaxLeaksIntoTheEmittedDdl() {
        List<String> leaks = new ArrayList<>();
        for (Row row : allRows()) {
            String emitted = translate(row.sql).toUpperCase();
            for (String forbidden : List.of("INNODB", "FOREIGN KEY", "FULLTEXT",
                    "SUBPARTITION", "PARTITIONS ", "VALUES LESS THAN", "VALUES IN",
                    "NODEGROUP", "MAXVALUE", "DATA DIRECTORY", "INDEX DIRECTORY",
                    "TABLESPACE")) {
                if (emitted.contains(forbidden)) {
                    leaks.add(row + " -> emitted [" + emitted + "] carries " + forbidden);
                }
            }
        }
        assertTrue(leaks.isEmpty(), "MySQL-only syntax reached the ClickHouse statement:\n  "
                + String.join("\n  ", leaks));
    }

    /** The alternatives of ONE rule that {@code rows} declare; other rules' rows are ignored. */
    private static Set<String> alternativesIn(List<Row> rows, Class<?> rule) {
        Set<String> names = new TreeSet<>();
        for (Row row : rows) {
            if (row.rule == rule) {
                names.add(row.alternative);
            }
        }
        return names;
    }
}
