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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every statement generated from the GRAMMAR parses, or is a declared blank.
 *
 * <p>{@link E2eMatrixCorpusParseTest} proves the statements the end-to-end
 * matrices happen to send. That only covers clauses somebody wrote into a
 * matrix row. {@code ddl-grammar-corpus.txt} is generated from the parser's own
 * alternative lists -- one statement per table option, per column constraint,
 * per ALTER clause and per partition clause -- so a clause a matrix never
 * mentions still has a statement behind it.</p>
 *
 * <p>This matters because of what an unparseable statement does in production:
 * {@code ErrorListenerImpl} logs and throws, the exception escapes to the CDC
 * caller, and replication stops for EVERY table on the host -- not just the one
 * the DDL touched. A clause the connector cannot translate is survivable (the
 * normalizer bridges or drops it); a clause the GRAMMAR cannot read is fatal.
 * So the requirement here is deliberately weaker than "replicates correctly"
 * and strictly wider than "the matrices test it": every generated statement
 * must PARSE.</p>
 *
 * <p>Statements that legitimately cannot parse are listed in {@link BLANKED}
 * with the reason. The converse is asserted too: a declared blank that in fact
 * parses is a stale declaration and is reported, so the list cannot quietly
 * grow to hide failures.</p>
 */
public class GrammarCorpusParseTest {

    private static final String GRAMMAR_CORPUS = "/ddl-grammar-corpus.txt";

    /**
     * The other half: what real producers put on the wire.
     *
     * <p>{@code ddl-grammar-corpus.txt} holds one statement per grammar
     * alternative, which keeps the clauses apart. Real DDL combines them --
     * a mysqldump {@code CREATE TABLE} carries six table options and two keys
     * in one statement, gh-ost appends {@code ALGORITHM=INPLACE, LOCK=NONE} to
     * whatever the migration asked for. Coverage of each clause in isolation
     * does not imply coverage of the combinations, and the combinations are
     * what actually runs.</p>
     */
    private static final String REALWORLD_CORPUS = "/ddl-realworld-corpus.txt";

    /**
     * Statements that do not parse, with the measured reason and where the
     * clause is covered instead.
     *
     * <p>Empty by design, and that is a measured result rather than an
     * assumption: every one of the generated statements was run against the
     * runtime parser (3.1.3.Final) and parsed. Any future entry here is a
     * reviewed decision about a real grammar limit, not a place to park a
     * failure.</p>
     */
    private static final Map<String, String> BLANKED = new LinkedHashMap<>();

    /**
     * Real-world statements that do not parse, with the measured reason.
     *
     * <p>Kept separate from {@link #BLANKED} so a failure says which corpus it
     * came from, and empty for the same reason: every statement in
     * {@code ddl-realworld-corpus.txt} was run against the runtime parser
     * (3.1.3.Final) and parsed. A future entry here is a reviewed decision
     * about a real producer shape, never a place to park a failure.</p>
     */
    private static final Map<String, String> REALWORLD_BLANKED = new LinkedHashMap<>();

    private static List<String> corpus;
    private static List<String> realWorld;

    @BeforeAll
    public static void loadCorpora() throws IOException {
        corpus = readCorpus(GRAMMAR_CORPUS);
        realWorld = readCorpus(REALWORLD_CORPUS);
    }

    /** Reads one statement-per-line resource, ignoring blanks and {@code #} comments. */
    private static List<String> readCorpus(String resource) throws IOException {
        List<String> statements = new ArrayList<>();
        try (InputStream in = GrammarCorpusParseTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "test resource " + resource + " is missing");
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    statements.add(s);
                }
            }
        }
        return statements;
    }

    /** A corpus that failed to generate would make every assertion vacuous. */
    @Test
    public void corpusIsPresentAndCoversEveryClauseFamily() {
        assertTrue(corpus.size() >= 100,
                "grammar corpus has " + corpus.size() + " statements; the generator"
                        + " has regressed and the assertions below would measure nothing");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("CREATE TABLE")),
                "no CREATE TABLE statements: the table-option and column-constraint"
                        + " families are uncovered");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("ALTER TABLE")),
                "no ALTER TABLE statements: the ALTER and partition clause families"
                        + " are uncovered");
    }

    /** Every generated statement parses, or is a declared blank with a reason. */
    @Test
    public void everyGrammarStatementParsesOrIsADeclaredBlank() {
        assertCorpusParses(corpus, BLANKED, "grammar");
    }

    /**
     * Every real-world statement parses, or is a declared blank with a reason.
     *
     * <p>This is the regression net for the class of defect this work started
     * from: a spelling real tools emit, valid MySQL, that reaches the parser
     * and dies there. It covers the combinations the per-alternative corpus
     * keeps apart.</p>
     */
    @Test
    public void everyRealWorldStatementParsesOrIsADeclaredBlank() {
        assertCorpusParses(realWorld, REALWORLD_BLANKED, "real-world");
    }

    /**
     * Runs one corpus through the production parse path and reports every
     * statement that neither parses nor carries a declared reason.
     *
     * <p>The converse is asserted too: a declared blank that in fact parses is
     * a stale declaration, so the list cannot quietly grow to hide failures.</p>
     */
    private static void assertCorpusParses(List<String> statements,
                                           Map<String, String> blanked,
                                           String label) {
        List<String> failures = new ArrayList<>();
        for (String sql : statements) {
            if (blanked.containsKey(sql)) {
                continue;
            }
            List<String> messages = parseErrors(sql);
            if (!messages.isEmpty()) {
                failures.add(sql + "\n    -> " + String.join("; ", messages)
                        + "\n    -> this statement stops replication for the WHOLE host"
                        + " when it arrives on a replicated table: bridge it in the"
                        + " normalizer, or record it as a " + label + " blank with"
                        + " the reason");
            }
        }

        assertTrue(failures.isEmpty(),
                failures.size() + " of " + statements.size() + " " + label
                        + " statements do not parse:\n\n" + String.join("\n\n", failures));

        List<String> stale = new ArrayList<>();
        for (String declared : blanked.keySet()) {
            if (parseErrors(declared).isEmpty()) {
                stale.add(declared);
            }
        }
        assertTrue(stale.isEmpty(),
                stale.size() + " " + label + " statement(s) are declared unparseable"
                        + " but parse cleanly; the declaration is stale: " + stale);
    }

    /**
     * The real-world corpus stays a real corpus.
     *
     * <p>It is a fixture, so it can rot: a truncated file or one emptied of
     * its producer families would leave the assertions above green and
     * measuring nothing. The families are asserted by prefix, not by count, so
     * the file can grow without touching this test.</p>
     */
    @Test
    public void realWorldCorpusCoversEveryProducerFamily() {
        assertTrue(realWorld.size() >= 80,
                "real-world corpus has " + realWorld.size() + " statements; the file"
                        + " has regressed and its assertions measure nothing");
        // DESTRUCTIVE: fixture prefix names only; the loop asserts which
        // producer families the corpus carries and executes no statement.
        for (String prefix : new String[] {"ALTER TABLE", "CREATE TABLE",
                "CREATE INDEX", "CREATE UNIQUE INDEX", "DROP INDEX", "RENAME TABLE"}) {
            assertTrue(realWorld.stream().anyMatch(s -> s.startsWith(prefix)),
                    "no \"" + prefix + "\" statement: that producer family is uncovered");
        }
        assertTrue(realWorld.stream().anyMatch(s -> s.contains("ALGORITHM=")),
                "no gh-ost / pt-osc statement: the online-DDL family is uncovered");
        assertTrue(realWorld.stream().anyMatch(s -> s.contains("PARTITION BY")),
                "no PARTITION BY statement: the partitioning family is uncovered");
    }

    /**
     * The generated statements reach every ALTER clause the grammar defines,
     * except the ones proven unreachable.
     *
     * <p>This is the assertion that makes the corpus a COVERAGE instrument
     * rather than a smoke test: it fails when a new clause appears in the
     * grammar and nobody wrote a statement for it. The set is computed from the
     * parser by reflection, so a parser upgrade is detected rather than
     * silently ignored.</p>
     */
    @Test
    public void theGeneratedStatementsReachEveryAlterClause() {
        List<String> alternatives = new ArrayList<>();
        for (Class<?> inner : MySqlParser.class.getDeclaredClasses()) {
            if (MySqlParser.AlterSpecificationContext.class.isAssignableFrom(inner)
                    && inner != MySqlParser.AlterSpecificationContext.class) {
                alternatives.add(inner.getSimpleName());
            }
        }
        assertTrue(alternatives.size() > 20,
                "the grammar's ALTER clause enumeration did not read back: "
                        + alternatives.size() + " alternatives");

        List<String> missing = new ArrayList<>();
        List<String> reached = reachedAlternatives();
        for (String alternative : alternatives) {
            if (alternative.equals("AlterByDefaultCharsetContext")) {
                continue; // proven unreachable, see GrammarAlternativeCoverageTest
            }
            if (!reached.contains(alternative)) {
                missing.add(alternative);
            }
        }
        assertTrue(missing.isEmpty(),
                missing.size() + " ALTER clauses have no generated statement: " + missing);
        assertFalse(reached.isEmpty(), "no ALTER clauses were reached at all");
    }

    private static List<String> reachedAlternatives() {
        List<String> reached = new ArrayList<>();
        for (String sql : corpus) {
            String normalised = MySqlDdlNormalizer.normalize(sql);
            if (normalised == null || normalised.trim().isEmpty()) {
                continue;
            }
            MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(
                    CharStreams.fromString(normalised), true));
            MySqlParser parser = new MySqlParser(new CommonTokenStream(lexer));
            collect(parser.root(), reached);
        }
        return reached;
    }

    private static void collect(ParserRuleContext ctx, List<String> reached) {
        if (ctx instanceof MySqlParser.AlterSpecificationContext) {
            String name = ctx.getClass().getSimpleName();
            if (!reached.contains(name)) {
                reached.add(name);
            }
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext) {
                collect((ParserRuleContext) ctx.getChild(i), reached);
            }
        }
    }

    /**
     * Parses {@code sql} the way production does -- lexer and parser with an
     * error listener on both -- and returns the messages, empty when clean.
     */
    private static List<String> parseErrors(String sql) {
        List<String> messages = new ArrayList<>();
        String normalised = MySqlDdlNormalizer.normalize(sql);
        if (normalised == null || normalised.trim().isEmpty()) {
            return messages;
        }
        MySqlLexer lexer = new MySqlLexer(
                new CaseChangingCharStream(CharStreams.fromString(normalised), true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);

        BaseErrorListener collecting = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                messages.add("line " + line + ":" + charPositionInLine + " " + msg);
            }
        };
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(collecting);
        parser.addErrorListener(collecting);

        try {
            ParserRuleContext root = parser.root();
            if (root == null) {
                messages.add("parse produced no root");
            }
        } catch (RuntimeException e) {
            messages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return messages;
    }
}
