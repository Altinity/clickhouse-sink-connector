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
 * Exhaustive matrix over the three grammar rules that decide which table SHAPE
 * the connector accepts: {@code tableOption}, {@code createTable} and
 * {@code createDefinition}.
 *
 * <p>These rules are as deterministic and unambiguous as {@code dataType} and
 * {@code columnConstraint} (covered by {@code DataTypeAndColumnAttributeMatrixTest}),
 * so the set of shapes MySQL accepts is enumerable from the grammar rather than
 * guessable from the statements anyone happened to try. This test enumerates it
 * by reflection over the Debezium parser the connector actually SHIPS and
 * asserts:</p>
 *
 * <ol>
 *   <li><b>Every alternative has a matrix row.</b> A Debezium bump that adds a
 *       {@code tableOption}, a {@code createTable} form or a
 *       {@code createDefinition} form fails here until a row covers it.</li>
 *   <li><b>Every row enters its declared alternative.</b> A statement that
 *       parses through a different alternative silently widens the apparent
 *       coverage.</li>
 *   <li><b>No row throws.</b> {@code ErrorListenerImpl.syntaxError} throws
 *       {@code RuntimeException("Error parsing DDL")}, and that exception
 *       halts the replication stream. A table option MySQL accepts must not be
 *       the reason the connector stops.</li>
 * </ol>
 *
 * <p><b>Why "emits nothing" is the correct expectation for a table option.</b>
 * {@code MySqlDDLParserListenerImpl.isNoOpSpecification} returns {@code true}
 * for {@code AlterByTableOptionContext}, alongside indexes, keys, foreign keys,
 * DEFAULT changes, CHARSET/COLLATE, ALGORITHM/LOCK hints and every partition
 * operation: the connector DROPS every table option deliberately. ClickHouse
 * has no counterpart for ENGINE, ROW_FORMAT, AUTO_INCREMENT, MAX_ROWS,
 * TABLESPACE and the rest, and the ClickHouse table is built from the source
 * COLUMN definitions with a ReplacingMergeTree engine of the connector's
 * choosing. Emitting the option would produce DDL ClickHouse rejects, which
 * would be retried and would wedge the binlog position for every later event.
 * The requirement is therefore not that the option is translated, but that the
 * statement parses, is skipped, and does not throw.</p>
 */
public class TableOptionAndCreateFormMatrixTest {

    private static MySQLDDLParserService service;

    /** A {@code CREATE TABLE} carrying exactly one option under test. */
    private static final String HEAD = "CREATE TABLE t (id INT) ";

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
     * names would be wrong -- {@code ConvertedDataTypeContext} ends in
     * {@code DataTypeContext} by coincidence and is not an alternative of
     * {@code dataType}.</p>
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

    /** {@code TableOptionAutoIncrementContext} becomes {@code tableOptionAutoIncrement}. */
    private static String labelOf(Class<?> contextClass) {
        String simple = contextClass.getSimpleName();
        String label = simple.substring(0, simple.length() - "Context".length());
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    /** The alternatives of the named rule that walking {@code sql} actually enters. */
    private static Set<String> alternativesEntered(String sql, Class<?> rule) {
        Set<String> entered = new TreeSet<>();
        MySqlParser.RootContext root = new MySqlParser(
                new org.antlr.v4.runtime.CommonTokenStream(
                        new io.debezium.ddl.parser.mysql.generated.MySqlLexer(
                                new io.debezium.antlr.CaseChangingCharStream(
                                        org.antlr.v4.runtime.CharStreams.fromString(sql), true))))
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
     * One matrix row: the grammar rule and alternative it covers, one statement
     * for it, and what must come out.
     *
     * <p>The rule is carried explicitly because two rules are covered here and
     * their alternative names must never be compared as one set: a
     * {@code createDefinition} alternative and a {@code createTable}
     * alternative that shared a name would make a missing row look covered.</p>
     */
    private static final class Row {
        final Class<?> rule;
        final String alternative;
        final String sql;
        /** {@code true} when the row must produce a ClickHouse CREATE TABLE. */
        final boolean emitsTable;

        private Row(Class<?> rule, String alternative, String sql, boolean emitsTable) {
            this.rule = rule;
            this.alternative = alternative;
            this.sql = sql;
            this.emitsTable = emitsTable;
        }

        @Override
        public String toString() {
            return rule.getSimpleName().replace("Context", "") + "." + alternative + " / " + sql;
        }
    }

    /**
     * One row per {@code tableOption} alternative the shipped parser labels.
     *
     * <p>The list is the thirty-five alternatives of
     * {@code debezium-ddl-parser-3.1.3.Final}, established by reflection, not
     * by reading {@code src/main/antlr4/mysql/MySqlParser.g4} -- that checked-in
     * copy is stale and is not what runs. The divergence is real:
     * {@code tableOptionSecondaryEngine} is present in the shipped parser and
     * absent from the copy, and {@code tableOptionEncrypted} exists only in
     * {@code MariaDBParser}, never in the MySQL one.</p>
     */
    private static List<Row> tableOptionRows() {
        List<Row> rows = new ArrayList<>();
        // A table option is dropped: the table is still created, but the option
        // itself contributes nothing to the emitted statement.
        add(rows, "tableOptionEngine", HEAD + "ENGINE = InnoDB");
        add(rows, "tableOptionEngineAttribute", HEAD + "ENGINE_ATTRIBUTE = '{\"x\":1}'");
        add(rows, "tableOptionAutoextendSize", HEAD + "AUTOEXTEND_SIZE = 1048576");
        add(rows, "tableOptionAutoIncrement", HEAD + "AUTO_INCREMENT = 100");
        add(rows, "tableOptionAverage", HEAD + "AVG_ROW_LENGTH = 100");
        add(rows, "tableOptionCharset", HEAD + "DEFAULT CHARACTER SET = utf8mb4");
        add(rows, "tableOptionChecksum", HEAD + "CHECKSUM = 1");
        add(rows, "tableOptionCollate", HEAD + "COLLATE = utf8mb4_general_ci");
        add(rows, "tableOptionComment", HEAD + "COMMENT = 'c'");
        add(rows, "tableOptionCompression", HEAD + "COMPRESSION = 'ZLIB'");
        add(rows, "tableOptionConnection", HEAD + "CONNECTION = 'a/b'");
        add(rows, "tableOptionDataDirectory", HEAD + "DATA DIRECTORY = '/var/lib/mysql-files/d'");
        add(rows, "tableOptionDelay", HEAD + "DELAY_KEY_WRITE = 1");
        add(rows, "tableOptionEncryption", HEAD + "ENCRYPTION = 'Y'");
        add(rows, "tableOptionEncryptionKeyId", HEAD + "ENCRYPTION_KEY_ID = 3");
        // INDEX DIRECTORY has no row: the grammar declares an alternative for
        // it, but an earlier alternative matches the same keywords first, so
        // the label is unreachable. The statement is still covered -- by the
        // tableOptionDataDirectory row above, which is where it lands. See
        // UNREACHABLE_TABLE_OPTIONS.
        add(rows, "tableOptionInsertMethod", HEAD + "INSERT_METHOD = FIRST");
        add(rows, "tableOptionKeyBlockSize", HEAD + "KEY_BLOCK_SIZE = 8");
        add(rows, "tableOptionMaxRows", HEAD + "MAX_ROWS = 1000");
        add(rows, "tableOptionMinRows", HEAD + "MIN_ROWS = 10");
        add(rows, "tableOptionPackKeys", HEAD + "PACK_KEYS = DEFAULT");
        add(rows, "tableOptionPageCompressed", HEAD + "PAGE_COMPRESSED = 1");
        add(rows, "tableOptionPageCompressionLevel", HEAD + "PAGE_COMPRESSION_LEVEL = 6");
        add(rows, "tableOptionPassword", HEAD + "PASSWORD = 'p'");
        add(rows, "tableOptionRowFormat", HEAD + "ROW_FORMAT = DYNAMIC");
        // START TRANSACTION has no row either: MySQL does not accept it as a
        // CREATE TABLE option, so no binlog can carry it. See
        // UNREACHABLE_TABLE_OPTIONS.
        add(rows, "tableOptionSecondaryEngineAttribute", HEAD + "SECONDARY_ENGINE_ATTRIBUTE = '{}'");
        add(rows, "tableOptionSecondaryEngine", HEAD + "SECONDARY_ENGINE = 'rapid'");
        add(rows, "tableOptionRecalculation", HEAD + "STATS_AUTO_RECALC = DEFAULT");
        add(rows, "tableOptionPersistent", HEAD + "STATS_PERSISTENT = 0");
        add(rows, "tableOptionSamplePage", HEAD + "STATS_SAMPLE_PAGES = 100");
        add(rows, "tableOptionTablespace", HEAD + "TABLESPACE ts1");
        add(rows, "tableOptionTableType", HEAD + "TABLE_TYPE = MYSQL");
        add(rows, "tableOptionTransactional", HEAD + "TRANSACTIONAL = 1");
        add(rows, "tableOptionUnion", HEAD + "UNION = (t1)");
        return rows;
    }

    /**
     * Table options the grammar declares but no statement can enter.
     *
     * <p>Both are unreachable for a reason that is checkable, not a matter of
     * taste, so they are named here rather than quietly dropped from the
     * matrix:</p>
     *
     * <ul>
     *   <li>{@code tableOptionIndexDirectory} — {@code INDEX DIRECTORY '/x'} is
     *       also matched by the earlier {@code (DATA | INDEX) DIRECTORY}
     *       alternative, which wins because ANTLR resolves to the first
     *       matching alternative. The statement is covered, by the
     *       {@code tableOptionDataDirectory} row.</li>
     *   <li>{@code tableOptionStartTransaction} — {@code START TRANSACTION} is
     *       a statement, not a CREATE TABLE option; MySQL rejects it there, so
     *       no binlog event can carry it.</li>
     * </ul>
     *
     * <p>{@link #unreachableTableOptionsAreStillUnreachable()} fails if either
     * becomes reachable, so a grammar upgrade that fixes the shadowing cannot
     * leave the gap unaddressed.</p>
     */
    private static final Set<String> UNREACHABLE_TABLE_OPTIONS =
            new TreeSet<>(List.of("tableOptionIndexDirectory", "tableOptionStartTransaction"));

    /** Every row added here is a {@code tableOption} alternative. */
    private static void add(List<Row> rows, String alternative, String sql) {
        rows.add(new Row(MySqlParser.TableOptionContext.class, alternative, sql, true));
    }

    /** One row per {@code createTable} alternative and per {@code createDefinition} alternative. */
    private static List<Row> createFormRows() {
        List<Row> rows = new ArrayList<>();

        // createTable: the form decides whether a table is emitted at all.
        // `LIKE` copies a source table and is translated to a ClickHouse
        // CREATE ... AS, so it emits a table. The query form (CTAS) has no
        // translator handler and emits NOTHING -- deliberately: MySQL logs a
        // CTAS as the CREATE plus the row events the SELECT inserted, and
        // DbWriter.initializeTableEngine auto-creates the ClickHouse table from
        // the first record's schema. Emitting a ClickHouse CREATE from the
        // SELECT would run a MySQL-shaped query against ClickHouse.
        Class<?> createTable = MySqlParser.CreateTableContext.class;
        rows.add(new Row(createTable, "copyCreateTable", "CREATE TABLE t2 LIKE t1", true));
        rows.add(new Row(createTable, "copyCreateTable", "CREATE TABLE t2 (LIKE t1)", true));
        rows.add(new Row(createTable, "queryCreateTable", "CREATE TABLE t2 AS SELECT 1 AS id", false));
        rows.add(new Row(createTable, "columnCreateTable", "CREATE TABLE t2 (id INT, PRIMARY KEY (id))", true));

        // createDefinition: three alternatives. Columns are translated; the
        // constraint and index forms are in the skip class (isNoOpSpecification
        // covers indexes, keys and foreign keys), so a table declaring one still
        // creates but the clause contributes nothing.
        Class<?> createDefinition = MySqlParser.CreateDefinitionContext.class;
        rows.add(new Row(createDefinition, "columnDeclaration",
                "CREATE TABLE t2 (id INT, v VARCHAR(10))", true));
        rows.add(new Row(createDefinition, "constraintDeclaration",
                "CREATE TABLE t2 (id INT, PRIMARY KEY (id))", true));
        rows.add(new Row(createDefinition, "indexDeclaration",
                "CREATE TABLE t2 (id INT, KEY k (id))", true));
        return rows;
    }

    // ------------------------------------------------------------------
    // Assertions.
    // ------------------------------------------------------------------

    @Test
    public void everyTableOptionAlternativeHasAMatrixRow() {
        Set<String> shipped = labelledAlternatives(MySqlParser.TableOptionContext.class);
        // Guard first: an empty reflection result would otherwise satisfy the
        // assertion below against an empty matrix, and this test would report
        // green while covering nothing.
        assertFalse(shipped.isEmpty(),
                "reflection found no tableOption alternatives -- the assertion below would pass vacuously");

        Set<String> expected = new TreeSet<>(shipped);
        expected.removeAll(UNREACHABLE_TABLE_OPTIONS);

        Set<String> covered = alternativesIn(tableOptionRows(), MySqlParser.TableOptionContext.class);
        assertFalse(covered.isEmpty(), "the tableOption matrix is empty");
        assertEquals(expected, covered,
                "the shipped parser's tableOption alternatives and the matrix disagree");
    }

    /**
     * The documented-unreachable options really are unreachable.
     *
     * <p>Proven by parsing, not by restating the list: each candidate is run
     * through the shipped parser and its label must not be entered. Without
     * this, a grammar upgrade that made one reachable would leave it untested
     * while the completeness assertion stayed green, because that assertion
     * subtracts this set.</p>
     */
    @Test
    public void unreachableTableOptionsAreStillUnreachable() {
        List<String> reachable = new ArrayList<>();
        for (String option : UNREACHABLE_TABLE_OPTIONS) {
            String sql = switch (option) {
                case "tableOptionIndexDirectory" -> HEAD + "INDEX DIRECTORY = '/var/lib/mysql-files/i'";
                case "tableOptionStartTransaction" -> HEAD + "START TRANSACTION";
                default -> throw new IllegalStateException("no probe statement for " + option);
            };
            if (alternativesEntered(sql, MySqlParser.TableOptionContext.class).contains(option)) {
                reachable.add(option + " / " + sql);
            }
        }
        assertTrue(reachable.isEmpty(),
                "options listed as unreachable are in fact reachable and need matrix rows:\n  "
                        + String.join("\n  ", reachable));
    }

    @Test
    public void everyCreateFormAlternativeHasAMatrixRow() {
        Class<?>[] rules = {MySqlParser.CreateTableContext.class, MySqlParser.CreateDefinitionContext.class};
        for (Class<?> rule : rules) {
            Set<String> shipped = labelledAlternatives(rule);
            assertFalse(shipped.isEmpty(), "reflection found no alternatives for "
                    + rule.getSimpleName() + " -- the assertion below would pass vacuously");
            Set<String> covered = alternativesIn(createFormRows(), rule);
            assertFalse(covered.isEmpty(), "the matrix has no rows for " + rule.getSimpleName());
            assertEquals(shipped, covered,
                    "the shipped parser's " + rule.getSimpleName() + " alternatives and the matrix disagree");
        }
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

    @Test
    public void eachRowEntersItsDeclaredAlternative() {
        List<String> wrong = new ArrayList<>();
        // Each row is judged against ITS OWN rule: a createDefinition row is
        // never entered through CreateTableContext, so checking every row
        // against one rule would fail rows that are in fact correct.
        for (Row row : allRows()) {
            if (!alternativesEntered(row.sql, row.rule).contains(row.alternative)) {
                wrong.add(row.toString());
            }
        }
        assertTrue(wrong.isEmpty(), "rows that did not enter their declared alternative:\n  "
                + String.join("\n  ", wrong));
    }

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

    @Test
    public void aTableOptionIsDroppedButTheTableIsStillCreated() {
        for (Row row : tableOptionRows()) {
            String emitted = translate(row.sql);
            assertTrue(emitted.toUpperCase().startsWith("CREATE TABLE"),
                    row + " -> a single table option must not suppress the CREATE, got: " + emitted);
            // The table is created WITHOUT the option: ClickHouse has no
            // equivalent for any tableOption alternative, so the connector
            // builds the table from the column definitions and its own engine
            // choice. The column must survive.
            assertTrue(emitted.contains("`id`") || emitted.contains("id "),
                    row + " -> the column was lost: " + emitted);
        }
    }

    @Test
    public void aQueryCreateTableEmitsNothing() {
        // The one createTable form with no translator handler. It must emit
        // NOTHING rather than a broken statement: the row events that follow
        // the CTAS in the binlog auto-create the table from their schema.
        for (Row row : createFormRows()) {
            if (!"queryCreateTable".equals(row.alternative)) {
                continue;
            }
            assertEquals("", translate(row.sql),
                    "the query form has no handler and must emit nothing, but produced: "
                            + translate(row.sql));
        }
    }

    @Test
    public void everyCreateFormThatHasAHandlerEmitsATable() {
        for (Row row : createFormRows()) {
            if (!row.emitsTable) {
                continue;
            }
            String emitted = translate(row.sql);
            assertTrue(emitted.toUpperCase().startsWith("CREATE TABLE"),
                    row + " -> expected a ClickHouse CREATE, got: " + emitted);
            assertFalse(emitted.contains("InnoDB"),
                    row + " -> a MySQL engine leaked into the ClickHouse DDL: " + emitted);
        }
    }

    private static List<Row> allRows() {
        List<Row> rows = new ArrayList<>(tableOptionRows());
        rows.addAll(createFormRows());
        return rows;
    }

    // ------------------------------------------------------------------
    // The same alternatives in ALTER TABLE position.
    // ------------------------------------------------------------------

    /**
     * Every {@code tableOption} alternative also arrives as an {@code ALTER
     * TABLE} specification, and that path is the one that kills replication.
     *
     * <p>The rows above prove the CREATE form. They say nothing about
     * {@code ALTER TABLE t <option>}: a different grammar rule
     * ({@code alterSpecification}), a different listener callback
     * ({@code AlterByTableOptionContext}), and a failure mode the CREATE side
     * does not have. {@code AUTOEXTEND_SIZE} reached production exactly here --
     * it parsed in CREATE and threw in ALTER -- and the throw escapes
     * {@code ErrorListenerImpl} to the CDC caller, which stops the stream for
     * every table on the host, not only the altered one.</p>
     *
     * <p>So the ALTER form gets its own statement per option and its own
     * assertion: it must not throw, and it must emit nothing. A table option has
     * no ClickHouse equivalent, so an empty emission is the correct result --
     * what must not happen is a throw.</p>
     */
    @Test
    public void everyTableOptionAlsoParsesInAlterTablePosition() {
        List<String> failures = new ArrayList<>();
        int probed = 0;
        for (Row row : tableOptionRows()) {
            String alter = row.sql.startsWith(HEAD)
                    ? "ALTER TABLE t " + row.sql.substring(HEAD.length())
                    : null;
            if (alter == null) {
                continue;
            }
            probed++;
            try {
                String emitted = translate(alter);
                if (!emitted.trim().isEmpty()) {
                    failures.add("ALTER " + row.alternative + " [" + alter
                            + "] emitted a statement for a table option: [" + emitted + "]");
                }
            } catch (RuntimeException e) {
                failures.add("ALTER " + row.alternative + " [" + alter + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        // A zero here would mean the rewrite above matched nothing and the
        // assertion below passed over an empty sweep.
        assertTrue(probed >= 30,
                "expected the ALTER sweep to probe every table option row, probed only " + probed);
        assertTrue(failures.isEmpty(),
                "an ALTER TABLE table option must parse and emit nothing:\n  "
                        + String.join("\n  ", failures));
    }
}
