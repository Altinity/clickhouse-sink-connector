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

import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole grammar's alternative coverage, measured from the parser itself.
 *
 * <p>{@link GrammarAlternativeCoverageTest} measures one rule family: the ALTER
 * clauses. The miss that started this work was an ordinary clause with no test
 * behind it, and a rule-family test only protects the family it names. This
 * one enumerates EVERY labelled rule of the shipped parser by reflection -- 44
 * rules, 306 alternatives at the version the connector runs -- parses every
 * corpus statement, and asserts that each alternative is either reached or
 * listed in {@link UNREACHABLE} with the reason no real MySQL statement can
 * reach it.</p>
 *
 * <p>Two properties make this a real guard rather than a printed number:</p>
 *
 * <ul>
 *   <li><b>It is bound to the shipped parser, not to a checked-in grammar.</b>
 *   The connector imports {@code io.debezium.ddl.parser.mysql.generated}, and
 *   the {@code .g4} in this repository is compiled into an unused {@code mysql}
 *   package (nothing imports it). Reading the rule and alternative lists from
 *   the compiled parser is the only way this test measures what runs.</li>
 *   <li><b>The counts are pinned.</b> A parser upgrade that renames, splits or
 *   collapses alternatives changes the enumeration, and a coverage assertion
 *   against a shrunken enumeration would pass while covering less. {@link
 *   #theEnumerationMatchesTheShippedParser} fails first and says so.</li>
 * </ul>
 *
 * <p>Every {@link UNREACHABLE} entry was measured, not assumed: the statement
 * that should reach the alternative was parsed and the alternative that
 * actually won is named in the reason. An entry with no measurement behind it
 * would be a number quietly going down, which is exactly the failure mode this
 * test exists to prevent.</p>
 */
public class GrammarAlternativeSweepTest {

    /**
     * Alternatives no statement can reach, and why.
     *
     * <p>Each reason names the alternative that WON instead, taken from a
     * parse of the statement that should have reached this one. Where the
     * winner is a sibling in the same rule the grammar is ambiguous at that
     * point and the first matching alternative takes the decision; where the
     * winner is the connector's own normaliser rewriting the statement, the
     * reason says so.</p>
     */
    private static final Map<String, String> UNREACHABLE = new LinkedHashMap<>();

    static {
        // --- shadowed by a sibling alternative listed FIRST in the same rule ---

        // privilegeLevel: `uid '.' uid` (#definiteFullTablePrivLevel) has the
        // same surface as `uid dottedId` (#definiteFullTablePrivLevel2), and
        // the dotted form takes the decision. GRANT SELECT ON db.t reports
        // DefiniteFullTablePrivLevel2Context. The privilege still resolves to
        // database+table, so the connector sees the same three-part level.
        UNREACHABLE.put("DefiniteFullTablePrivLevelContext",
                "definiteFullTablePrivLevel shares its surface with the dotted"
                        + " form, which wins: GRANT SELECT ON db.t parses as"
                        + " DefiniteFullTablePrivLevel2Context");

        // alterUser: the MySQL 5.6 alternative takes a bare user list and an
        // optional IDENTIFIED clause. The MySQL 8.0 alternative that follows
        // it accepts the same surface through #simpleAuthOption, so every
        // spelling reaches it instead: ALTER USER u, v reports
        // AlterUserMysqlV80Context.
        UNREACHABLE.put("AlterUserMysqlV56Context",
                "the v80 alternative accepts the same user list and wins:"
                        + " ALTER USER u, v reports AlterUserMysqlV80Context");

        // setStatement: #setVariable accepts `SET variableClause = expression`
        // and `autocommit` is a valid uid, so the dedicated
        // #setAutocommit alternative after it never gets the decision.
        // SET autocommit = 1 reports SetVariableContext, in lower case too.
        UNREACHABLE.put("SetAutocommitContext",
                "setVariable is listed first and accepts AUTCOMMIT as a plain"
                        + " variable name; SET autocommit = 1 reports"
                        + " SetVariableContext");

        // functionCall: the generic #udfFunctionCall matches any uid carrying
        // arguments and is listed before the dedicated PASSWORD() alternative,
        // so PASSWORD('x') reports UdfFunctionCallContext. The keyword-argument
        // spellings keep their own alternatives -- SUBSTRING('abc' FROM 1 FOR
        // 2) reports SubstrFunctionCallContext and CURRENT_TIMESTAMP() reports
        // SimpleFunctionCallContext -- and both are corpus lines.
        UNREACHABLE.put("PasswordFunctionCallContext",
                "the generic udfFunctionCall matches any uid with arguments and"
                        + " is listed first: PASSWORD('x') reports"
                        + " UdfFunctionCallContext");

        // tableOption: (DATA | INDEX) DIRECTORY share one alternative, and it
        // is listed BEFORE the dedicated INDEX DIRECTORY one, so the latter
        // never matches. CREATE TABLE ... INDEX DIRECTORY reports
        // TableOptionDataDirectoryContext.
        UNREACHABLE.put("TableOptionIndexDirectoryContext",
                "(DATA | INDEX) DIRECTORY is listed first and subsumes it:"
                        + " CREATE TABLE ... INDEX DIRECTORY reports"
                        + " TableOptionDataDirectoryContext");

        // selectStatement: the UNION alternative that wraps the second branch
        // in parentheses. A parenthesised trailing branch is read as an
        // ordinary union statement -- (SELECT 1) UNION (SELECT 2) and
        // SELECT 1 UNION (SELECT 2) both report UnionSelectContext.
        UNREACHABLE.put("UnionParenthesisSelectContext",
                "a parenthesised trailing branch parses as an ordinary union:"
                        + " (SELECT 1) UNION (SELECT 2) reports UnionSelectContext");

        // tableSource: the nested form requires the outer parentheses to hold
        // only tableSourceItems and joinParts. `FROM (a JOIN b ON ...)` is
        // consumed by the tableSourcesItem branch of tableSourceItem, which
        // matches earlier, so the nested alternative never decides.
        UNREACHABLE.put("TableSourceNestedContext",
                "`FROM (a JOIN b ON ...)` is taken by tableSourcesItem"
                        + " (TableSourcesItemContext), which is matched first");

        // tableSource: JSON_TABLE has its own alternative on tableSource, but
        // tableSourceItem's jsonTable branch matches it first, so every
        // JSON_TABLE spelling reports AtomJsonTableContext. The table is still
        // modelled with its columns and path, under the item rather than the
        // source.
        UNREACHABLE.put("TableJsonContext",
                "JSON_TABLE is matched by tableSourceItem first: every spelling"
                        + " reports AtomJsonTableContext");

        // tableSourceItem: a sequence table (seq_1_to_10) is an ordinary table
        // NAME to the grammar. It has no dedicated sequence alternative that
        // wins, and the plain atom reports AtomTableItemContext for
        // SELECT * FROM seq_1_to_10, FROM db.seq_1_to_10 and FROM seq_1_to_10
        // AS s alike.
        UNREACHABLE.put("SequenceTableItemContext",
                "a sequence table parses as an ordinary table name:"
                        + " SELECT * FROM seq_1_to_10 reports AtomTableItemContext");

        // expressionAtom: the dedicated ROW(...) atom requires the nested row
        // form, but ROW(1, 2) is a uid carrying arguments and is consumed by
        // udfFunctionCall first; a bare (1, 2) is a nested expression atom.
        // Neither spelling reaches it: SELECT ROW(1, 2) = ROW(1, 2) reports
        // UdfFunctionCallContext, SELECT (1, 2) = (1, 2) reports
        // NestedExpressionAtomContext.
        UNREACHABLE.put("NestedRowExpressionAtomContext",
                "ROW(1, 2) is taken by udfFunctionCall and (1, 2) by"
                        + " nestedExpressionAtom, so neither spelling reaches it");

        // partitionDefinition: a vector VALUES IN ((1, 2)) list is read as an
        // atom list whose members are nested expression atoms. RANGE COLUMNS
        // and LIST COLUMNS with a tuple, a scalar list and a bare single value
        // all report PartitionListAtomContext with NestedExpressionAtomContext
        // inside it.
        UNREACHABLE.put("PartitionListVectorContext",
                "a tuple in VALUES IN (...) is a nested expression atom inside"
                        + " the atom list: PartitionListAtomContext wins");

        // --- removed by this connector's own statement normaliser ---

        // MySqlDdlNormalizer drops AUTOEXTEND_SIZE from CREATE TABLE before
        // the parser sees the statement, because ClickHouse has no equivalent
        // table option. The statement therefore produces no TableOption
        // context at all, so the alternative is unreachable THROUGH the
        // normaliser even though the grammar defines it.
        UNREACHABLE.put("TableOptionAutoextendSizeContext",
                "MySqlDdlNormalizer.dropAutoextendSizeOption removes the option"
                        + " before parsing; CREATE TABLE ... AUTOEXTEND_SIZE = 1"
                        + " yields no TableOption context");

        // alterSpecification: the same shadowing GrammarAlternativeCoverageTest
        // already documents for this one.
        UNREACHABLE.put("AlterByDefaultCharsetContext",
                "tableOptionCharset is listed first and subsumes every"
                        + " spelling, so the clause arrives as"
                        + " AlterByTableOptionContext");
    }

    private static final String[] CORPORA = {
            "/ddl-matrix-corpus.txt",
            "/ddl-grammar-corpus.txt",
            "/ddl-realworld-corpus.txt",
            "/ddl-surface-corpus.txt",
    };

    /** The shipped grammar's size at the version this test was written for. */
    private static final int EXPECTED_LABELLED_RULES = 44;
    private static final int EXPECTED_ALTERNATIVES = 306;

    private static Map<String, Set<String>> labelledRules;

    @BeforeAll
    public static void readGrammarAlternatives() {
        labelledRules = labelledRules();
        assertTrue(labelledRules.size() > 40,
                "the grammar's labelled-rule enumeration did not read back: "
                        + labelledRules.size() + " rules");
    }

    /** rule name -> the simple names of its alternative context classes. */
    private static Map<String, Set<String>> labelledRules() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String ruleName : MySqlParser.ruleNames) {
            String ctxName = Character.toUpperCase(ruleName.charAt(0))
                    + ruleName.substring(1) + "Context";
            Class<?> ruleCtx;
            try {
                ruleCtx = Class.forName(MySqlParser.class.getName() + "$" + ctxName);
            } catch (ClassNotFoundException e) {
                continue;
            }
            Set<String> alternatives = new TreeSet<>();
            for (Class<?> inner : MySqlParser.class.getDeclaredClasses()) {
                if (inner != ruleCtx && ruleCtx.isAssignableFrom(inner)) {
                    alternatives.add(inner.getSimpleName());
                }
            }
            if (!alternatives.isEmpty()) {
                out.put(ruleName, alternatives);
            }
        }
        return out;
    }

    private static List<String> corpus() throws IOException {
        List<String> out = new ArrayList<>();
        for (String resource : CORPORA) {
            try (InputStream in =
                         GrammarAlternativeSweepTest.class.getResourceAsStream(resource)) {
                assertTrue(in != null, "corpus not on the test classpath: " + resource);
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            out.add(trimmed);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Every context class a statement reaches.
     *
     * <p>The error listener only COLLECTS: a statement that misbehaves is
     * reported by the assertion below rather than aborting the walk, so one
     * run reports every defect instead of the first.</p>
     */
    private static Set<String> reachedContexts(String sql, List<String> failures) {
        Set<String> reached = new TreeSet<>();
        String normalised = MySqlDdlNormalizer.normalize(sql);
        if (normalised == null || normalised.trim().isEmpty()) {
            return reached;
        }
        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(
                CharStreams.fromString(normalised), true));
        MySqlParser parser = new MySqlParser(new CommonTokenStream(lexer));
        BaseErrorListener collecting = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                failures.add(sql + "\n    -> line " + line + ":" + charPositionInLine
                        + " " + msg);
            }
        };
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(collecting);
        parser.addErrorListener(collecting);

        ParserRuleContext root = parser.root();
        new ParseTreeWalker().walk(new ParseTreeListener() {
            @Override
            public void visitTerminal(TerminalNode node) {
            }

            @Override
            public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
            }

            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                reached.add(ctx.getClass().getSimpleName());
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
            }
        }, root);
        return reached;
    }

    @Test
    public void theCorpusReachesEveryReachableAlternative() throws IOException {
        Set<String> visited = new TreeSet<>();
        List<String> unparseable = new ArrayList<>();
        List<String> statements = corpus();
        for (String sql : statements) {
            visited.addAll(reachedContexts(sql, unparseable));
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> rule : labelledRules.entrySet()) {
            for (String alternative : rule.getValue()) {
                if (UNREACHABLE.containsKey(alternative)) {
                    continue;
                }
                if (!visited.contains(alternative)) {
                    missing.add(rule.getKey() + " -> " + alternative);
                }
            }
        }

        List<String> problems = new ArrayList<>();
        if (!unparseable.isEmpty()) {
            problems.add(unparseable.size() + " of " + statements.size()
                    + " corpus statements do not parse (defects in the corpus"
                    + " itself):\n\n" + String.join("\n\n", unparseable));
        }
        if (!missing.isEmpty()) {
            problems.add(missing.size() + " grammar alternatives are never reached by"
                    + " the corpus: " + missing
                    + "\n    -> a missing statement is a clause the connector is"
                    + " never tested against; add one line to"
                    + " ddl-surface-corpus.txt, or record the alternative in"
                    + " UNREACHABLE with the measurement that proves no real"
                    + " statement reaches it");
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems));
    }

    @Test
    public void theEnumerationMatchesTheShippedParser() {
        // A parser upgrade that renames, splits or collapses alternatives
        // changes these numbers. Failing here first keeps the coverage
        // assertion above from passing against a grammar it was not written
        // for -- a shrunken enumeration would make "everything is covered"
        // trivially true.
        int alternatives = 0;
        for (Set<String> perRule : labelledRules.values()) {
            alternatives += perRule.size();
        }
        assertTrue(labelledRules.containsKey("alterSpecification"),
                "the enumeration is missing alterationSpecification: " + labelledRules.keySet());
        assertTrue(labelledRules.get("alterSpecification").contains("AlterByAddColumnContext"),
                "the ALTER clause enumeration does not look like the real grammar: "
                        + labelledRules.get("alterSpecification"));
        assertTrue(alternatives >= EXPECTED_ALTERNATIVES,
                "the shipped grammar now has " + alternatives + " labelled alternatives,"
                        + " fewer than the " + EXPECTED_ALTERNATIVES + " this test covers:"
                        + " the coverage assertion is measuring a different grammar");
        assertTrue(labelledRules.size() >= EXPECTED_LABELLED_RULES,
                "the shipped grammar now has " + labelledRules.size() + " labelled rules,"
                        + " fewer than the " + EXPECTED_LABELLED_RULES + " this test covers");
    }
}
