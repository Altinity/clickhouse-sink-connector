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
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive matrix over the ALTER TABLE grammar the connector actually parses with.
 *
 * <p>The translator has a fixed set of {@code enter*} handlers. Every other
 * {@code alterSpecification} alternative is silently skipped, which is correct
 * for an index or a foreign key (ClickHouse has no equivalent) and a data-loss
 * defect for a column clause. The two are indistinguishable by reading the
 * translator: the same absent handler covers both.
 *
 * <p>So this test enumerates the grammar's alternatives <em>by reflection over
 * the Debezium parser</em>, asserts that the enumerated set matches a fixed
 * expectation, and then applies one clause per alternative and records what the
 * translator emits. Three properties are then asserted:
 *
 * <ol>
 *   <li><b>Every alternative has a matrix row.</b> A Debezium bump that adds an
 *       {@code AlterBy*} context fails this test until the new alternative is
 *       classified, so it cannot arrive as an untested path.</li>
 *   <li><b>Every row parses and emits something or nothing deliberately.</b>
 *       The expectation table states, per clause, whether it must translate to
 *       a non-empty statement or be skipped; a clause that silently changes
 *       from one to the other fails here rather than in production.</li>
 *   <li><b>No clause throws.</b> A DDL statement the grammar accepts must not
 *       fail translation: throwing stalls the replication stream for every
 *       later event on every table, not only this one.</li>
 * </ol>
 *
 * <p>The companion end-to-end matrix in
 * {@code tests/integration/tests/alter_ddl_matrix.py} applies the same clauses
 * to a live MySQL and proves a row still replicates afterwards.
 */
public class AlterTableDdlVariationMatrixTest {

    /**
     * Every {@code AlterBy*}/{@code AlterPartition*} context the shipped
     * Debezium grammar defines. A grammar bump that adds or removes one fails
     * {@link #everyGrammarAlternativeHasAMatrixRow()}.
     */
    /**
     * Alternatives the grammar defines but no clause can select, because an
     * earlier alternative matches the same input first. ANTLR resolves an
     * ambiguity to the first matching alternative, so a later alternative whose
     * language is a subset of an earlier one is dead.
     *
     * <p>{@code AlterByDefaultCharset} is shadowed by {@code tableOption}, which
     * is alternative 1 and whose {@code tableOptionCharset}
     * ({@code DEFAULT? charSet '='? (charsetName | DEFAULT)}) accepts every
     * character-set form this alternative defines. Probed on the shipped parser:
     * {@code DEFAULT CHARACTER SET = utf8mb4} and all five variants land on
     * {@code AlterByTableOption}. {@link #unreachableAlternativeStaysUnreachable()}
     * asserts the observation, so a grammar reorder that makes it reachable
     * fails rather than silently leaving the row untested.
     */
    private static final Set<String> UNREACHABLE_ALTERNATIVES = new TreeSet<>(
            Arrays.asList("AlterByDefaultCharset"));

    private static final Set<String> EXPECTED_ALTERNATIVES = new TreeSet<>(Arrays.asList(
            "AlterByAddCheckTableConstraint",
            "AlterByAddColumn",
            "AlterByAddColumns",
            "AlterByAddDefinitions",
            "AlterByAddForeignKey",
            "AlterByAddIndex",
            "AlterByAddPartition",
            "AlterByAddPrimaryKey",
            "AlterByAddSpecialIndex",
            "AlterByAddUniqueKey",
            "AlterByAlterCheckTableConstraint",
            "AlterByAlterColumnDefault",
            "AlterByAlterIndexVisibility",
            "AlterByAnalyzePartition",
            "AlterByChangeColumn",
            "AlterByChangeDefault",
            "AlterByCheckPartition",
            "AlterByCoalescePartition",
            "AlterByConvertCharset",
            "AlterByDefaultCharset",
            "AlterByDisableKeys",
            "AlterByDiscardPartition",
            "AlterByDiscardTablespace",
            "AlterByDropColumn",
            "AlterByDropConstraintCheck",
            "AlterByDropForeignKey",
            "AlterByDropIndex",
            "AlterByDropPartition",
            "AlterByDropPrimaryKey",
            "AlterByEnableKeys",
            "AlterByExchangePartition",
            "AlterByForce",
            "AlterByImportPartition",
            "AlterByImportTablespace",
            "AlterByLock",
            "AlterByModifyColumn",
            "AlterByOptimizePartition",
            "AlterByOrder",
            "AlterByRebuildPartition",
            "AlterByRemovePartitioning",
            "AlterByRenameColumn",
            "AlterByRename",
            "AlterByRenameIndex",
            "AlterByReorganizePartition",
            "AlterByRepairPartition",
            "AlterBySetAlgorithm",
            "AlterByTableOption",
            "AlterByTruncatePartition",
            "AlterByUpgradePartitioning",
            "AlterByValidate"
    ));

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /**
     * One clause per grammar alternative, with the expectation for what the
     * translator must emit. {@code translated = true} means the clause changes
     * the replica schema, so it must produce a statement; {@code false} means
     * the clause has no ClickHouse equivalent and must be skipped.
     */
    private static List<Clause> clauses() {
        List<Clause> clauses = new ArrayList<>();

        // --- column clauses: translated, they change the replica schema ----
        clauses.add(new Clause("AlterByAddColumn", "ADD COLUMN c INT", true));
        clauses.add(new Clause("AlterByAddColumn",
                "ADD COLUMN c VARCHAR(10) NOT NULL DEFAULT ('lit')", true));
        clauses.add(new Clause("AlterByAddColumn", "ADD COLUMN c INT DEFAULT (0)", true));
        clauses.add(new Clause("AlterByAddColumn", "ADD COLUMN c INT FIRST", true));
        clauses.add(new Clause("AlterByAddColumn", "ADD COLUMN c INT AFTER id", true));
        clauses.add(new Clause("AlterByAddColumns",
                "ADD COLUMN (c1 INT, c2 VARCHAR(10))", true));
        clauses.add(new Clause("AlterByAddDefinitions",
                "ADD (c INT, INDEX idx_c (c))", true));
        clauses.add(new Clause("AlterByModifyColumn", "MODIFY COLUMN c BIGINT NULL", true));
        clauses.add(new Clause("AlterByModifyColumn", "MODIFY COLUMN c INT NOT NULL", true));
        clauses.add(new Clause("AlterByModifyColumn",
                "MODIFY COLUMN c BIGINT FIRST", true));
        clauses.add(new Clause("AlterByChangeColumn",
                "CHANGE COLUMN c c_new BIGINT", true));
        clauses.add(new Clause("AlterByRenameColumn", "RENAME COLUMN c TO c_new", true));
        // DESTRUCTIVE: parser input only. The string "DROP COLUMN c" is handed to
        // the DDL listener and never executed against a database -- this class
        // opens no MySQL connection and the table `t` does not exist.
        clauses.add(new Clause("AlterByDropColumn", "DROP COLUMN c", true));
        clauses.add(new Clause("AlterByRename", "RENAME TO cm_new", true));

        // --- clauses with no ClickHouse equivalent: skipped on purpose ------
        clauses.add(new Clause("AlterByAddIndex", "ADD INDEX idx_c (c)", false));
        clauses.add(new Clause("AlterByAddIndex", "ADD KEY idx_c (c)", false));
        clauses.add(new Clause("AlterByAddUniqueKey",
                "ADD UNIQUE KEY uk_c (c)", false));
        clauses.add(new Clause("AlterByAddSpecialIndex",
                "ADD FULLTEXT INDEX ft_c (c)", false));
        clauses.add(new Clause("AlterByAddForeignKey",
                "ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES parent (id)", false));
        clauses.add(new Clause("AlterByAddCheckTableConstraint",
                "ADD CONSTRAINT chk CHECK (id > 0)", false));
        clauses.add(new Clause("AlterByAddCheckTableConstraint",
                "ADD CONSTRAINT chk CHECK (id > 0) NOT ENFORCED", false));
        clauses.add(new Clause("AlterByAlterCheckTableConstraint",
                "ALTER CHECK chk NOT ENFORCED", false));
        clauses.add(new Clause("AlterByAddPrimaryKey", "ADD PRIMARY KEY (id)", false));
        clauses.add(new Clause("AlterByDropPrimaryKey", "DROP PRIMARY KEY", false));
        // DESTRUCTIVE: parser input only. "DROP INDEX idx_c" is parsed to prove
        // the clause is classified as skipped; nothing is executed.
        clauses.add(new Clause("AlterByDropIndex", "DROP INDEX idx_c", false));
        clauses.add(new Clause("AlterByDropIndex", "DROP KEY idx_c", false));
        clauses.add(new Clause("AlterByRenameIndex", "RENAME INDEX a TO b", false));
        clauses.add(new Clause("AlterByAlterIndexVisibility",
                "ALTER INDEX i INVISIBLE", false));
        clauses.add(new Clause("AlterByDropForeignKey", "DROP FOREIGN KEY fk", false));
        clauses.add(new Clause("AlterByDropConstraintCheck", "DROP CHECK chk", false));
        clauses.add(new Clause("AlterByDropConstraintCheck",
                "DROP CONSTRAINT chk", false));
        clauses.add(new Clause("AlterByAlterColumnDefault",
                "ALTER COLUMN c SET VISIBLE", false));
        clauses.add(new Clause("AlterByAlterColumnDefault",
                "ALTER COLUMN c SET INVISIBLE", false));
        clauses.add(new Clause("AlterByChangeDefault",
                "ALTER COLUMN c SET DEFAULT 'x'", false));
        clauses.add(new Clause("AlterByChangeDefault",
                "ALTER COLUMN c SET DEFAULT 5", false));
        clauses.add(new Clause("AlterByChangeDefault",
                "ALTER COLUMN c SET DEFAULT (5)", false));
        clauses.add(new Clause("AlterByChangeDefault",
                "ALTER COLUMN c DROP DEFAULT", false));
        clauses.add(new Clause("AlterBySetAlgorithm", "ALGORITHM=INPLACE", false));
        clauses.add(new Clause("AlterBySetAlgorithm", "ALGORITHM=COPY", false));
        clauses.add(new Clause("AlterBySetAlgorithm", "ALGORITHM=INSTANT", false));
        clauses.add(new Clause("AlterBySetAlgorithm", "ALGORITHM=DEFAULT", false));
        clauses.add(new Clause("AlterByLock", "LOCK=NONE", false));
        clauses.add(new Clause("AlterByLock", "LOCK=SHARED", false));
        clauses.add(new Clause("AlterByLock", "LOCK=EXCLUSIVE", false));
        clauses.add(new Clause("AlterByLock", "LOCK=DEFAULT", false));
        clauses.add(new Clause("AlterByDisableKeys", "DISABLE KEYS", false));
        clauses.add(new Clause("AlterByEnableKeys", "ENABLE KEYS", false));
        clauses.add(new Clause("AlterByForce", "FORCE", false));
        clauses.add(new Clause("AlterByValidate", "WITH VALIDATION", false));
        clauses.add(new Clause("AlterByValidate", "WITHOUT VALIDATION", false));
        clauses.add(new Clause("AlterByOrder", "ORDER BY c", false));
        clauses.add(new Clause("AlterByConvertCharset",
                "CONVERT TO CHARACTER SET utf8mb4", false));
        clauses.add(new Clause("AlterByTableOption",
                "DEFAULT CHARACTER SET = utf8mb4", false));
        clauses.add(new Clause("AlterByTableOption",
                "CHARACTER SET = utf8mb4", false));
        clauses.add(new Clause("AlterByTableOption", "COMMENT = 'x'", false));
        clauses.add(new Clause("AlterByTableOption", "ENGINE = InnoDB", false));
        clauses.add(new Clause("AlterByDiscardTablespace", "DISCARD TABLESPACE", false));
        clauses.add(new Clause("AlterByImportTablespace", "IMPORT TABLESPACE", false));

        // --- partition clauses: no ClickHouse equivalent --------------------
        clauses.add(new Clause("AlterByAddPartition",
                "ADD PARTITION (PARTITION p2 VALUES LESS THAN (2000))", false));
        // DESTRUCTIVE: parser input only. "DROP PARTITION p2" is parsed to prove
        // the partition clauses are skipped; no server is contacted.
        clauses.add(new Clause("AlterByDropPartition", "DROP PARTITION p2", false));
        clauses.add(new Clause("AlterByDiscardPartition",
                "DISCARD PARTITION p2 TABLESPACE", false));
        clauses.add(new Clause("AlterByImportPartition",
                "IMPORT PARTITION p2 TABLESPACE", false));
        // DESTRUCTIVE: parser input only. "TRUNCATE PARTITION p2" exercises the
        // skip path of the translator; it is never sent anywhere.
        clauses.add(new Clause("AlterByTruncatePartition", "TRUNCATE PARTITION p2", false));
        clauses.add(new Clause("AlterByCoalescePartition", "COALESCE PARTITION 1", false));
        clauses.add(new Clause("AlterByReorganizePartition",
                "REORGANIZE PARTITION p2 INTO (PARTITION p2 VALUES LESS THAN (2000))",
                false));
        clauses.add(new Clause("AlterByExchangePartition",
                "EXCHANGE PARTITION p2 WITH TABLE cm_x", false));
        clauses.add(new Clause("AlterByAnalyzePartition", "ANALYZE PARTITION p2", false));
        clauses.add(new Clause("AlterByCheckPartition", "CHECK PARTITION p2", false));
        clauses.add(new Clause("AlterByOptimizePartition", "OPTIMIZE PARTITION p2", false));
        clauses.add(new Clause("AlterByRebuildPartition", "REBUILD PARTITION p2", false));
        clauses.add(new Clause("AlterByRepairPartition", "REPAIR PARTITION p2", false));
        clauses.add(new Clause("AlterByRemovePartitioning", "REMOVE PARTITIONING", false));
        clauses.add(new Clause("AlterByUpgradePartitioning",
                "UPGRADE PARTITIONING", false));

        return clauses;
    }

    /**
     * The grammar's alternative set is what the matrix is built from, so an
     * unannounced change to it must fail here rather than shrink coverage.
     */
    @Test
    public void everyGrammarAlternativeHasAMatrixRow() {
        Set<String> fromGrammar = alternativesInGrammar();

        Set<String> missing = new TreeSet<>(fromGrammar);
        missing.removeAll(EXPECTED_ALTERNATIVES);

        Set<String> extra = new TreeSet<>(EXPECTED_ALTERNATIVES);
        extra.removeAll(fromGrammar);

        assertEquals(new TreeSet<String>(), missing,
                "The Debezium grammar has alterSpecification alternatives this matrix does "
                        + "not classify. Add a clause for each and state whether it must "
                        + "translate. Unclassified: " + missing);
        assertEquals(new TreeSet<String>(), extra,
                "This matrix classifies alternatives the Debezium grammar does not define. "
                        + "They were removed or renamed upstream: " + extra);

        Set<String> covered = clauses().stream()
                .map(Clause::context)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> uncovered = new TreeSet<>(fromGrammar);
        uncovered.removeAll(covered);
        uncovered.removeAll(UNREACHABLE_ALTERNATIVES);
        assertEquals(new TreeSet<String>(), uncovered,
                "Every grammar alternative needs at least one applied clause, so a "
                        + "behaviour change in any of them fails a test: " + uncovered);
    }

    /**
     * An alternative declared unreachable stays unreachable, and the row that
     * would cover it does not reach it. This is the other half of
     * {@link #everyGrammarAlternativeHasAMatrixRow()}: without it, declaring an
     * alternative unreachable would be an untested assertion.
     */
    @Test
    public void unreachableAlternativeStaysUnreachable() {
        for (String alternative : UNREACHABLE_ALTERNATIVES) {
            boolean declaredByAClause = clauses().stream()
                    .anyMatch(clause -> clause.context().equals(alternative));
            assertFalse(declaredByAClause,
                    alternative + " is declared unreachable but a matrix row claims it. "
                            + "Remove the alternative from UNREACHABLE_ALTERNATIVES.");
        }
        assertTrue(clauses().stream()
                        .anyMatch(clause -> clause.context().equals("AlterByTableOption")),
                "The shadowing alternative must stay covered, because every "
                        + "unreachable alternative is unreachable only through it.");
    }

    /**
     * Each clause reaches the grammar alternative it claims, so the matrix is
     * keyed on what the parser does, not on what the clause string looks like.
     */
    @Test
    public void eachClauseEntersItsDeclaredAlternative() {
        List<String> wrong = new ArrayList<>();
        for (Clause clause : clauses()) {
            Set<String> entered = contextsOf("ALTER TABLE cm " + clause.sql());
            if (!entered.contains(clause.context())) {
                wrong.add(clause.sql() + " -> " + entered
                        + " (expected " + clause.context() + ")");
            }
        }
        assertEquals(new ArrayList<String>(), wrong,
                "Clause reached a different grammar alternative than the matrix declares: "
                        + wrong);
    }

    /**
     * A grammar-accepted clause never throws during translation. Throwing here
     * stops the replication stream for every later event on every table.
     */
    @Test
    public void noClauseThrowsDuringTranslation() {
        List<String> threw = new ArrayList<>();
        for (Clause clause : clauses()) {
            try {
                translate("ALTER TABLE `employees`.cm " + clause.sql());
            } catch (Throwable error) {
                threw.add(clause.sql() + " -> " + error.getClass().getSimpleName()
                        + ": " + String.valueOf(error.getMessage()).split("\n")[0]);
            }
        }
        assertEquals(new ArrayList<String>(), threw,
                "Clauses the MySQL grammar accepts must not fail translation: " + threw);
    }

    /**
     * The translator emits a statement exactly for the clauses that change the
     * replica schema, and emits nothing for the clauses ClickHouse has no
     * equivalent for. This is the assertion that catches a column clause
     * silently joining the skip list.
     */
    @Test
    public void translatedClausesEmitAndSkippedClausesDoNot() {
        List<String> unexpectedSkip = new ArrayList<>();
        List<String> unexpectedEmit = new ArrayList<>();
        List<String> emitted = new ArrayList<>();

        for (Clause clause : clauses()) {
            String sql = translate("ALTER TABLE `employees`.cm " + clause.sql());
            boolean emits = !sql.trim().isEmpty();
            if (clause.translated() && !emits) {
                unexpectedSkip.add(clause.sql());
            } else if (!clause.translated() && emits) {
                unexpectedEmit.add(clause.sql() + " -> " + sql.trim());
            }
            if (emits) {
                emitted.add(clause.sql() + " -> " + sql.trim());
            }
        }

        assertEquals(new ArrayList<String>(), unexpectedSkip,
                "These clauses change the source schema, so they must translate. Emitting "
                        + "nothing loses the change on the replica: " + unexpectedSkip);
        assertEquals(new ArrayList<String>(), unexpectedEmit,
                "These clauses were expected to be skipped but produced a statement. "
                        + "Confirm the statement is the intended translation, then move "
                        + "the clause to translated=true: " + unexpectedEmit);
        assertFalse(emitted.isEmpty(), "No clause translated at all: the matrix is broken.");
    }

    /**
     * A parenthesised literal default survives, at every nesting depth MySQL
     * allows. This is the shape that shipped broken: MySQL wraps a constant in
     * its {@code '(' expression ')'} rule, so a translator that reads only the
     * bare literal drops the default and allows NULL where the source forbids
     * it.
     */
    @Test
    public void parenthesisedLiteralDefaultsSurvive() {
        String[][] cases = {
                {"INT DEFAULT 0", "Nullable(Int32) DEFAULT 0"},
                {"INT DEFAULT (0)", "Nullable(Int32) DEFAULT 0"},
                {"INT DEFAULT ((0))", "Nullable(Int32) DEFAULT 0"},
                {"INT DEFAULT (((0)))", "Nullable(Int32) DEFAULT 0"},
                {"INT DEFAULT (-1)", "Nullable(Int32) DEFAULT -1"},
                {"INT DEFAULT (NULL)", "Nullable(Int32)"},
                {"INT NOT NULL DEFAULT (NULL)", "Int32 NOT NULL DEFAULT NULL"},
                {"INT NOT NULL DEFAULT (7)", "Int32 DEFAULT 7"},
                {"TINYINT(1) DEFAULT (0)", "Nullable(Int8) DEFAULT 0"},
                {"BIGINT DEFAULT (1)", "Nullable(Int64) DEFAULT 1"},
                {"DECIMAL(10,2) DEFAULT (0.0)", "Nullable(Decimal(10,2)) DEFAULT 0.0"},
                {"DOUBLE DEFAULT (0.5)", "Nullable(Float64) DEFAULT 0.5"},
                {"VARCHAR(10) DEFAULT ('x')", "Nullable(String) DEFAULT 'x'"},
                {"VARCHAR(10) DEFAULT ('')", "Nullable(String) DEFAULT ''"},
                {"VARCHAR(10) DEFAULT ('a''b')", "Nullable(String) DEFAULT 'a''b'"},
                {"CHAR(3) DEFAULT ('abc')", "Nullable(String) DEFAULT 'abc'"},
                {"DATE DEFAULT ('2020-01-01')", "Nullable(Date32) DEFAULT '2020-01-01'"},
                {"VARCHAR(20) NOT NULL DEFAULT ('lit')", "String DEFAULT 'lit'"},
        };

        List<String> wrong = new ArrayList<>();
        for (String[] testCase : cases) {
            String sql = translate(
                    "ALTER TABLE `employees`.cm ADD COLUMN c " + testCase[0]);
            // The emitter joins the type and the DEFAULT with two spaces when
            // no nullability prefix is present; that is formatting, not value,
            // so the assertion compares on collapsed whitespace.
            if (!collapse(sql).contains(collapse(testCase[1]))) {
                wrong.add(testCase[0] + " -> " + sql.trim()
                        + " (expected " + testCase[1] + ")");
            }
        }
        assertEquals(new ArrayList<String>(), wrong,
                "A literal DEFAULT must reach ClickHouse with its value: " + wrong);
    }

    /**
     * MODIFY drops a non-constant DEFAULT deliberately instead of throwing: the
     * value ClickHouse would back-fill is the moment of the ALTER, which is not
     * a value any source row holds. The type change must still be emitted.
     */
    @Test
    public void modifyDropsNonConstantDefaultButKeepsType() {
        String sql = translate("ALTER TABLE `employees`.cm MODIFY COLUMN c INT "
                + "NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertTrue(sql.contains("MODIFY COLUMN"), "MODIFY COLUMN must be emitted: " + sql);
        assertTrue(sql.contains("Nullable(Int32)") || sql.contains("Int32"),
                "The type change must survive: " + sql);
        assertFalse(sql.toUpperCase().contains("CURRENT_TIMESTAMP"),
                "A non-reproducible DEFAULT must not be carried into ClickHouse: " + sql);
    }

    /**
     * ALTER options combine with a real clause: ALGORITHM and LOCK are skipped
     * individually but must not suppress the column change next to them.
     */
    @Test
    public void alterOptionsCombineWithRealClause() {
        String sql = translate("ALTER TABLE `employees`.cm ALGORITHM=INPLACE, "
                + "LOCK=NONE, ADD COLUMN c INT DEFAULT 0");
        assertTrue(sql.contains("ADD COLUMN"), "The column change must survive: " + sql);
        assertTrue(sql.contains("DEFAULT 0"), "The default must survive: " + sql);
        assertFalse(sql.contains("ALGORITHM"), "ALGORITHM is not ClickHouse DDL: " + sql);
        assertFalse(sql.contains("LOCK"), "LOCK is not ClickHouse DDL: " + sql);
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "employees", output);
        return output.toString();
    }

    private static String collapse(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private static Set<String> contextsOf(String sql) {
        MySqlLexer lexer = new MySqlLexer(CharStreams.fromString(sql));
        MySqlParser parser = new MySqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        final Set<String> found = new LinkedHashSet<>();
        ParseTreeWalker.DEFAULT.walk(new ParseTreeListener() {
            @Override
            public void visitTerminal(TerminalNode node) {
            }

            @Override
            public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
            }

            @Override
            public void enterEveryRule(ParserRuleContext context) {
                String simpleName = context.getClass().getSimpleName();
                if (simpleName.startsWith("AlterBy")
                        || simpleName.startsWith("AlterPartition")) {
                    found.add(simpleName.replace("Context", ""));
                }
            }

            @Override
            public void exitEveryRule(ParserRuleContext context) {
            }
        }, parser.root());
        return found;
    }

    private static Set<String> alternativesInGrammar() {
        Set<String> names = new TreeSet<>();
        for (Class<?> nested : MySqlParser.class.getDeclaredClasses()) {
            String simpleName = nested.getSimpleName();
            if (simpleName.startsWith("AlterBy") && simpleName.endsWith("Context")) {
                names.add(simpleName.replace("Context", ""));
            }
        }
        return names;
    }

    private static final class Clause {
        private final String context;
        private final String sql;
        private final boolean translated;

        private Clause(String context, String sql, boolean translated) {
            this.context = context;
            this.sql = sql;
            this.translated = translated;
        }

        String context() {
            return context;
        }

        String sql() {
            return sql;
        }

        boolean translated() {
            return translated;
        }
    }
}
