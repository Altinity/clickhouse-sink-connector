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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valid MySQL statements the shipped parser cannot read, and the damage each
 * one does.
 *
 * <p>The coverage tests answer "is every grammar alternative exercised". They
 * cannot answer the opposite question: <em>is there MySQL syntax the parser
 * rejects outright?</em> A statement that never parses never reaches an
 * alternative, so it is invisible to a coverage sweep -- the corpus simply has
 * no line for it, and nothing fails. That is the same blind spot that let an
 * ordinary ALTER clause go untested in the first place.</p>
 *
 * <p>Each row pairs a statement a MySQL server accepts with the parse failure
 * the shipped parser produces today. {@link
 * MySqlDdlNormalizer#handlesStatementKind} decides the severity and is
 * asserted per row: inside the handled set a syntax error throws and stops
 * replication for every table on the host; outside it the failure is caught
 * and costs only that statement.</p>
 *
 * <p><b>When a row here starts parsing, this test fails on purpose.</b> The
 * statement is then readable, so it belongs in {@code ddl-surface-corpus.txt}
 * as a real corpus line (where the sweep covers its alternatives) and the row
 * must be deleted from {@link #KNOWN_GAPS}. A parser upgrade, or a normaliser
 * rewrite that masks a gap, must not quietly widen what the connector can read
 * without a statement proving it.</p>
 *
 * <p><b>The harness is proved, not assumed.</b> A statement the normaliser
 * blanks would "fail to parse" without the grammar ever being consulted, so
 * that case is reported as a harness failure rather than counted as a gap.
 * Every row must be measured against the parser itself.</p>
 */
public class KnownParserGapsTest {

    /**
     * Statement -> what the shipped parser does with it.
     *
     * <p>Every entry is a form a MySQL server accepts (each is cited against
     * the MySQL 8.0 manual where the form was introduced). The reason names
     * the parse failure and whether the connector contains it.</p>
     */
    private static final Map<String, String> KNOWN_GAPS = new LinkedHashMap<>();

    static {
        // MySQL 8.0 EXPLAIN syntax is
        //   {EXPLAIN|DESCRIBE|DESC} [explain_type] {explainable_stmt | FOR CONNECTION connection_id}
        // and connection_id is numeric. describeObjectClause is
        // `FOR CONNECTION uid`, an identifier, so a real connection id cannot
        // parse. EXPLAIN is outside the handled set, so the failure is
        // contained: the statement is logged and dropped, and no other table
        // is affected.
        KNOWN_GAPS.put("EXPLAIN FOR CONNECTION 42",
                "describeObjectClause takes `FOR CONNECTION uid`, so the numeric"
                        + " connection id MySQL documents cannot parse; contained"
                        + " (not a handled kind)");

        // MySQL 8.0.18 introduced EXPLAIN ANALYZE. The grammar has no
        // alternative for the ANALYZE keyword after EXPLAIN. Contained.
        KNOWN_GAPS.put("EXPLAIN ANALYZE SELECT 1",
                "the grammar has no EXPLAIN ANALYZE alternative, although MySQL"
                        + " 8.0.18 added it; contained (not a handled kind)");

        // MySQL 8.0 row-locking modifiers. The repository .g4 defines
        // lockOption = waitNowaitClause | SKIP_ LOCKED, but the shipped
        // parser rejects both -- one of the rules the two grammars disagree on.
        KNOWN_GAPS.put("SELECT * FROM test.t FOR UPDATE SKIP LOCKED",
                "the shipped parser rejects SKIP LOCKED although the repository"
                        + " grammar defines it; contained (not a handled kind)");
        KNOWN_GAPS.put("SELECT * FROM test.t FOR UPDATE NOWAIT",
                "the shipped parser rejects NOWAIT although the repository"
                        + " grammar defines it; contained (not a handled kind)");
    }

    /**
     * Statements that must stay rejected, because they are not MySQL.
     *
     * <p>{@code ADD COLUMN IF NOT EXISTS} is MariaDB syntax: the MySQL 8.0
     * manual's ALTER TABLE grammar is {@code ADD [COLUMN] col_name
     * column_definition [FIRST | AFTER col_name]} with no IF NOT EXISTS, and
     * the repository grammar's own shorthands carry the comment "ifNotExists
     * is MariaDB-specific only" beside each alternative. MySQL never writes
     * it, so the connector being unable to read it is correct, not a gap.</p>
     *
     * <p>It is asserted anyway because it falls inside the handled set, where
     * a parse failure stops replication rather than dropping one statement.
     * The row therefore records the severity, and trips if a normaliser
     * rewrite starts accepting MariaDB syntax on a MySQL source -- a silent
     * widening of what the connector reads. If such a rewrite is ever
     * intended, delete the row in the same reviewed change and add the
     * original spelling to {@code ddl-surface-corpus.txt}.</p>
     */
    private static final String[] MUST_STAY_REJECTED = {
            "ALTER TABLE test.t ADD COLUMN IF NOT EXISTS x INT",
    };

    @Test
    public void everyKnownGapIsStillUnparseable() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> gap : KNOWN_GAPS.entrySet()) {
            String sql = gap.getKey();
            List<String> errors = new ArrayList<>();
            Boolean parsed = parseAfterNormalising(sql, errors);
            if (parsed == null) {
                problems.add("HARNESS FAILURE: the normaliser blanked the statement,"
                        + " so the parser was never asked: " + sql
                        + "\n    -> a blanked statement cannot evidence a grammar gap;"
                        + " fix the harness or remove the row");
                continue;
            }
            if (parsed) {
                problems.add("KNOWN GAP NOW PARSES: " + sql
                        + "\n    -> move this statement into ddl-surface-corpus.txt"
                        + " so the coverage sweep exercises it, and delete it from"
                        + " KNOWN_GAPS. Recorded reason was: " + gap.getValue());
                continue;
            }
            if (errors.isEmpty()) {
                problems.add("KNOWN GAP reported neither a parse nor a syntax error: "
                        + sql + " -- the harness did not measure what it claims");
            }
            if (MySqlDdlNormalizer.handlesStatementKind(sql)) {
                problems.add("KNOWN GAP IS A HANDLED KIND: " + sql
                        + "\n    -> a parse failure here stops replication for the"
                        + " host, not just this statement; recorded reason was: "
                        + gap.getValue());
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems));
    }

    @Test
    public void mariaDbOnlySpellingsStayRejected() {
        List<String> problems = new ArrayList<>();
        for (String sql : MUST_STAY_REJECTED) {
            List<String> errors = new ArrayList<>();
            Boolean parsed = parseAfterNormalising(sql, errors);
            if (parsed == null) {
                problems.add("HARNESS FAILURE: the normaliser blanked the statement,"
                        + " so the parser was never asked: " + sql);
                continue;
            }
            if (parsed) {
                problems.add("a MariaDB-only spelling now parses on a MySQL grammar,"
                        + " which changes what the connector accepts: " + sql
                        + "\n    -> if the widening is intended, delete this row and"
                        + " add the original spelling to ddl-surface-corpus.txt");
                continue;
            }
            if (errors.isEmpty()) {
                problems.add("the statement neither parsed nor reported an error: " + sql);
            }
            if (!MySqlDdlNormalizer.handlesStatementKind(sql)) {
                problems.add("this statement was recorded as the handled-kind case,"
                        + " but handlesStatementKind says it is not handled: " + sql
                        + "\n    -> the severity recorded beside it is wrong");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems));
    }

    /**
     * Parses {@code sql} after the normaliser has had its turn.
     *
     * @return {@code TRUE} when the parser accepted it with no syntax error,
     *         {@code FALSE} when the parser reported one, and {@code null}
     *         when the normaliser blanked the statement so the parser was
     *         never consulted -- a harness failure, not a gap. Returning null
     *         rather than false is what stops a blanked statement from
     *         passing as evidence that the grammar rejects it.
     */
    private static Boolean parseAfterNormalising(String sql, List<String> errors) {
        String normalised = MySqlDdlNormalizer.normalize(sql);
        if (normalised == null || normalised.trim().isEmpty()) {
            return null;
        }
        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(
                CharStreams.fromString(normalised), true));
        MySqlParser parser = new MySqlParser(new CommonTokenStream(lexer));
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        BaseErrorListener collecting = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                errors.add("line " + line + ":" + charPositionInLine + " " + msg);
            }
        };
        lexer.addErrorListener(collecting);
        parser.addErrorListener(collecting);
        ParserRuleContext root = parser.root();
        return errors.isEmpty() && root != null;
    }
}
