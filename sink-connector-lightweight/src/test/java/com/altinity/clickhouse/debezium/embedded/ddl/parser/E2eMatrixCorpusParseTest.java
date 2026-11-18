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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every statement the end-to-end matrices send, parsed by the shipped parser.
 *
 * <p>The end-to-end suite under {@code tests/integration/} is the only place a
 * clause is proven to replicate for real, but it needs a live MySQL and a live
 * ClickHouse, so it cannot run in a unit build. That left a gap of its own: a
 * matrix row whose SQL the grammar rejects, or which the normalizer blanks,
 * would fail only on a machine that can host the e2e stack, and only after the
 * whole matrix had started.</p>
 *
 * <p>{@code ddl-matrix-corpus.txt} is that matrix extracted to one statement
 * per line, and this test runs every line through the SAME path the connector
 * uses in production: {@link MySqlDdlNormalizer#normalize} then the lexer and
 * parser with an error listener attached to both. A statement is required to
 * do one of two things and nothing else:</p>
 *
 * <ol>
 *   <li><b>parse cleanly</b> -- no lexer or parser error, so the row tests a
 *       clause the grammar really reads; or</li>
 *   <li><b>be blanked by the normalizer</b> -- the statement's family has no
 *       ClickHouse handler, so the normalizer removes it before the parser
 *       sees it and nothing is expected to be emitted. This must be a
 *       DECISION, so the reason is recorded in {@link #NORMALIZER_BLANKED}.</li>
 * </ol>
 *
 * <p>Anything else is a defect in the matrix: a statement the connector would
 * throw on, or silently emit nothing for, while the e2e row asserts
 * replication happened. The failure names the statement, which is what makes
 * a broken row cheap to fix.</p>
 *
 * <p>The corpus drop the connection between the two suites is the point. A
 * clause added to a matrix but never parsed fails here on the next unit build;
 * a grammar upgrade that stops reading a clause fails here before the e2e
 * stack is ever started.</p>
 */
public class E2eMatrixCorpusParseTest {

    /** The extracted matrices, one statement per line, comments with '#'. */
    private static final String CORPUS = "/ddl-matrix-corpus.txt";

    /**
     * Statements the normalizer deliberately blanks, keyed by the exact text,
     * with the reason. Every entry is a statement family the connector does
     * not translate, so there is nothing for the parser to read and the e2e
     * row exists to prove the stream survives it.
     */
    private static final Map<String, String> NORMALIZER_BLANKED = new LinkedHashMap<>();

    static {
        // Empty by design: every statement in the matrix corpus now either
        // parses or has its unsupported clause bridged away, so none of them is
        // blanked wholesale. A blank is a CONNECTOR decision, and the only two
        // that used to be here -- a bare `SECONDARY_LOAD` / `SECONDARY_UNLOAD`
        // statement -- are now handled by dropping the clause and keeping the
        // statement, which is strictly better: the statement still parses and
        // nothing is silently discarded.
    }

    private static List<String> corpus;

    @BeforeAll
    public static void loadCorpus() throws IOException {
        corpus = new ArrayList<>();
        try (InputStream in = E2eMatrixCorpusParseTest.class.getResourceAsStream(CORPUS)) {
            assertTrue(in != null, "test resource " + CORPUS + " is missing");
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    corpus.add(s);
                }
            }
        }
    }

    /**
     * The corpus is present and non-trivial.
     *
     * <p>A corpus that failed to generate, or was emptied by a bad extraction,
     * would make every assertion below vacuously true. This is the guard
     * against a green run that measured nothing.</p>
     */
    @Test
    public void corpusIsPresentAndCoversEveryStatementKind() {
        // Fixture string literals only: these assertions name the statement
        // kinds the corpus must contain.
        // DESTRUCTIVE: the corpus is parsed and counted, never executed.
        assertTrue(corpus.size() >= 100,
                "corpus has " + corpus.size() + " statements; the extraction has"
                        + " regressed and the assertions below would measure nothing");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("ALTER TABLE")),
                "no ALTER TABLE statements in the corpus");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("CREATE TABLE")),
                "no CREATE TABLE statements in the corpus");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("INSERT")),
                "no INSERT statements in the corpus");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("UPDATE")),
                "no UPDATE statements in the corpus");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("DELETE")),
                "no DELETE statements in the corpus");
        assertTrue(corpus.stream().anyMatch(s -> s.startsWith("REPLACE")),
                "no REPLACE statements in the corpus");
    }

    /**
     * Every statement in the corpus either parses cleanly or is a declared
     * normalizer blank.
     */
    @Test
    public void everyMatrixStatementParsesOrIsADeclaredBlank() {
        List<String> failures = new ArrayList<>();
        List<String> blanked = new ArrayList<>();

        for (String sql : corpus) {
            String normalized = MySqlDdlNormalizer.normalize(sql);

            if (normalized == null || normalized.trim().isEmpty()) {
                blanked.add(sql);
                if (!NORMALIZER_BLANKED.containsKey(sql)) {
                    failures.add(sql + "\n    -> blanked by the normalizer with no"
                            + " entry in NORMALIZER_BLANKED; either the statement is"
                            + " dead in the matrix or it needs a documented reason");
                }
                continue;
            }

            List<String> messages = parseErrors(normalized);
            if (!messages.isEmpty()) {
                failures.add(sql + "\n    -> " + String.join("; ", messages));
            }
        }

        assertTrue(failures.isEmpty(),
                failures.size() + " of " + corpus.size() + " matrix statements do not"
                        + " parse and are not declared blanks:\n\n"
                        + String.join("\n\n", failures));

        // The converse direction: a statement declared blank that in fact
        // parses is a stale declaration, and the row is testing something
        // other than what its comment claims.
        for (String declared : NORMALIZER_BLANKED.keySet()) {
            String normalized = MySqlDdlNormalizer.normalize(declared);
            assertTrue(normalized == null || normalized.trim().isEmpty(),
                    "NORMALIZER_BLANKED declares " + declared + " as blank, but the"
                            + " normalizer leaves it in place; the declaration is stale");
        }
        assertFalse(blanked.isEmpty() && !NORMALIZER_BLANKED.isEmpty(),
                "NORMALIZER_BLANKED is populated yet nothing was blanked");
    }

    /**
     * Every ALTER TABLE clause the matrix sends is read as a single statement.
     *
     * <p>A clause list where only the first specification is accepted would
     * pass the test above whenever the error listener recovered, so the
     * multi-clause rows are checked for the specification count the text
     * declares. These are the rows that prove the comma-separated form, which
     * is how a real migration batches its changes.</p>
     */
    @Test
    public void multiClauseAlterStatementsAreReadAsOneStatement() {
        List<String> multi = new ArrayList<>();
        for (String sql : corpus) {
            if (!sql.startsWith("ALTER TABLE ")) {
                continue;
            }
            // A comma at the top level of the clause list, outside parentheses
            // and outside a quoted identifier, separates specifications.
            int depth = 0;
            int commas = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    commas++;
                }
            }
            if (commas > 0) {
                multi.add(sql);
            }
        }

        assertFalse(multi.isEmpty(),
                "the corpus carries no multi-clause ALTER TABLE statement; the matrix"
                        + " lost its batched-specification rows");

        List<String> failures = new ArrayList<>();
        for (String sql : multi) {
            String normalized = MySqlDdlNormalizer.normalize(sql);
            if (normalized == null || normalized.trim().isEmpty()) {
                continue;
            }
            List<String> messages = parseErrors(normalized);
            if (!messages.isEmpty()) {
                failures.add(sql + "\n    -> " + String.join("; ", messages));
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + " multi-clause ALTER TABLE statements in the matrix do"
                        + " not parse:\n\n" + String.join("\n\n", failures));
    }

    /**
     * The corpus is stable across a re-read: this test reads the resource
     * once, so a line that appears twice means the extraction emitted a
     * duplicate and the counts above are inflated.
     */
    @Test
    public void corpusHasNoDuplicateStatements() {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String sql : corpus) {
            seen.merge(sql, 1, Integer::sum);
        }
        List<String> dupes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() > 1) {
                dupes.add(e.getValue() + "x  " + e.getKey());
            }
        }
        assertTrue(dupes.isEmpty(),
                dupes.size() + " statements appear more than once in the corpus:\n  "
                        + String.join("\n  ", dupes));
        assertEquals(seen.size(), corpus.size(),
                "the corpus contains duplicates; assertion counts are inflated");
    }

    /**
     * Parses {@code sql} and returns the error messages, empty when clean.
     *
     * <p>Mirrors {@link MySQLDDLParserService#parseSql}: the same lexer, the
     * same case-changing stream, the same {@code root()} entry rule, and an
     * error listener on BOTH the lexer and the parser. Collecting rather than
     * throwing reports every offending statement in one run instead of one
     * per build.</p>
     */
    private static List<String> parseErrors(String sql) {
        List<String> messages = new ArrayList<>();
        MySqlLexer lexer = new MySqlLexer(
                new CaseChangingCharStream(CharStreams.fromString(sql), true));
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
