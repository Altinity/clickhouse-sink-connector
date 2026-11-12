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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage measured against the GRAMMAR, not against a hand-written list.
 *
 * <p>The corpus test proves every extracted statement parses. That answers
 * "does this SQL work", never "which of MySQL's ALTER clauses is never sent at
 * all". A clause nobody writes into a matrix row is invisible to it: the
 * grammar accepts it, the connector may or may not handle it, and no failure
 * ever appears. That is exactly the shape of the miss that started this work --
 * an ordinary clause with no test behind it.</p>
 *
 * <p>{@code MySqlParser.AlterSpecificationContext} is the grammar's own
 * enumeration of the ALTER clauses MySQL allows, so it is the authoritative
 * list of what must be covered. This test reads those alternative class names
 * out of the compiled parser by reflection (no second copy that can drift),
 * parses the corpus, records which alternatives the corpus actually reaches,
 * and asserts coverage of the ones the connector must translate.</p>
 *
 * <p>{@link #NOT_REQUIRED} is the deliberate exclusion list: alternatives this
 * connector cannot and should not translate. Each entry carries its reason, so
 * adding one is a reviewed decision rather than a number quietly going down.</p>
 */
public class GrammarAlternativeCoverageTest {

    /** The clause list the connector must have a statement for, by alternative. */
    private static final Map<String, String> NOT_REQUIRED = new LinkedHashMap<>();

    static {
        // Measured, not assumed: `AlterByDefaultCharset` cannot be reached.
        // `tableOptionCharset` is `DEFAULT? charSet '='? (charsetName|DEFAULT)`
        // and the `alterByTableOption` alternative is listed FIRST in the rule,
        // so `DEFAULT CHARACTER SET [=] utf8mb4` matches it and the
        // `alterByDefaultCharset` alternative never gets a chance. Verified by
        // parsing every spelling against the runtime parser (3.1.3.Final):
        // all of them report AlterByTableOptionContext. The clause is still
        // covered -- the statement parses and the connector sees a table
        // option -- it just arrives under a different alternative.
        NOT_REQUIRED.put("AlterByDefaultCharsetContext",
                "unreachable in the shipped grammar: tableOptionCharset is "
                        + "listed first and subsumes every spelling, so the "
                        + "clause arrives as AlterByTableOptionContext");
    }

    private static final String CORPUS = "/ddl-matrix-corpus.txt";

    /**
     * Statements generated from the grammar's own alternative lists, one per
     * alternative, for the clauses the end-to-end matrices do not happen to
     * send. Both files are read: the matrix corpus proves the real rows parse,
     * this one proves every clause the grammar defines is survivable.
     */
    private static final String GRAMMAR_CORPUS = "/ddl-grammar-corpus.txt";

    private static List<String> alterAlternatives;

    @BeforeAll
    public static void readGrammarAlternatives() throws Exception {
        alterAlternatives = new ArrayList<>();
        // The generated parser names one inner class per alternative; the base
        // class's subclass set IS the grammar's clause enumeration.
        for (Class<?> inner : MySqlParser.class.getDeclaredClasses()) {
            if (MySqlParser.AlterSpecificationContext.class.isAssignableFrom(inner)
                    && inner != MySqlParser.AlterSpecificationContext.class) {
                alterAlternatives.add(inner.getSimpleName());
            }
        }
        assertTrue(alterAlternatives.size() > 20,
                "the grammar's ALTER clause enumeration did not read back: "
                        + alterAlternatives.size() + " alternatives");
    }

    private static List<String> corpus() throws IOException {
        List<String> out = new ArrayList<>(readCorpus(CORPUS));
        out.addAll(readCorpus(GRAMMAR_CORPUS));
        return out;
    }

    private static List<String> readCorpus(String resource) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = GrammarAlternativeCoverageTest.class.getResourceAsStream(resource)) {
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
        return out;
    }

    /**
     * The ALTER alternatives a statement reaches, or an empty set if normalised
     * away.
     *
     * <p>A statement that does not parse is added to {@code failures} rather
     * than thrown, so one run reports EVERY broken statement instead of the
     * first -- the difference between fixing the corpus in one pass and
     * discovering it one build at a time.</p>
     */
    private static Set<String> reachedAlternatives(String sql, List<String> failures) {
        String normalised = MySqlDdlNormalizer.normalize(sql);
        if (normalised == null || normalised.trim().isEmpty()) {
            return new TreeSet<>();
        }
        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(
                CharStreams.fromString(normalised), true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
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
        Set<String> reached = new TreeSet<>();
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(new ParseTreeListener() {
            @Override
            public void visitTerminal(TerminalNode node) {
            }

            @Override
            public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
            }

            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                if (ctx instanceof MySqlParser.AlterSpecificationContext) {
                    reached.add(ctx.getClass().getSimpleName());
                }
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
            }
        }, root);
        return reached;
    }

    @Test
    public void theCorpusReachesEveryRequiredAlterClause() throws IOException {
        Set<String> reached = new TreeSet<>();
        List<String> unparseable = new ArrayList<>();
        List<String> statements = corpus();
        for (String sql : statements) {
            reached.addAll(reachedAlternatives(sql, unparseable));
        }

        List<String> missing = new ArrayList<>();
        for (String alternative : alterAlternatives) {
            if (NOT_REQUIRED.containsKey(alternative)) {
                continue;
            }
            if (!reached.contains(alternative)) {
                missing.add(alternative);
            }
        }

        // Both defects in ONE report. A generated statement the grammar rejects
        // contributes no alternative, so it shows up as a coverage hole too --
        // and a hole whose cause is a typo is a very different fix from a hole
        // whose cause is a missing matrix row. Reporting them together means
        // one build cycle per round of fixes instead of one per statement.
        List<String> problems = new ArrayList<>();
        if (!unparseable.isEmpty()) {
            problems.add(unparseable.size() + " of " + statements.size()
                    + " corpus statements do not parse (defects in the corpus"
                    + " itself):\n\n" + String.join("\n\n", unparseable));
        }
        if (!missing.isEmpty()) {
            problems.add(missing.size() + " of " + alterAlternatives.size()
                    + " ALTER clauses the grammar defines are never reached by the"
                    + " corpus: " + missing
                    + "\n    -> add a statement for each, or record it in"
                    + " NOT_REQUIRED with the reason it can never appear in a"
                    + " replicated schema");
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems));
    }

    @Test
    public void theGrammarEnumerationMatchesTheGeneratedParser() {
        // Guards the reflection above against a parser upgrade renaming or
        // collapsing the alternatives: a silent drop to a handful would make the
        // coverage assertion pass while covering almost nothing. These are the
        // alternatives whose spelling is certain, so a parser that no longer
        // contains them is not the parser this test was written against.
        for (String known : new String[]{
                "AlterByAddColumnContext",
                "AlterByDropColumnContext",
                "AlterByModifyColumnContext",
                "AlterByChangeColumnContext",
                "AlterByRenameContext"}) {
            assertTrue(alterAlternatives.contains(known),
                    "the enumeration is missing " + known + ", so it is not reading"
                            + " the real grammar: " + alterAlternatives);
        }
        assertEquals(36, alterAlternatives.size(),
                "the grammar's ALTER clause count changed, so the coverage assertion"
                        + " is measuring a different grammar: " + alterAlternatives);
    }
}
