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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the two MySQL spellings the bundled Debezium grammar
 * cannot parse, and for the NATIONAL CHAR registration miss.
 *
 * <p>Each case below failed before the fix, and failed in the worst way: the
 * whole statement was rejected, which stalls replication for every later event
 * on every table, not only for the table the DDL touched. The tests go through
 * {@link MySQLDDLParserService}, the class the CDC pipeline calls, so they
 * exercise the shipped grammar rather than this repository's unused copy of it
 * under {@code src/main/antlr4}.</p>
 *
 * <p>The translation itself is asserted, not just the absence of an exception:
 * a bridge that let the statement parse but dropped the generated column would
 * pass a "does not throw" test and still lose the column.</p>
 */
public class MySqlDdlNormalizerTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /**
     * {@code PERSISTENT} means the generated value is stored, so it has to
     * translate exactly like {@code STORED}.
     *
     * <p>Both words are absent from the shipped parser, and the MySQL
     * reference manuals document {@code [VIRTUAL | STORED]} only -- the
     * spelling in practice is MariaDB's. The cost of bridging it is nothing
     * when it never appears, and the cost of not bridging it is a statement
     * that halts replication for every table on the host.</p>
     */
    @Test
    public void persistentGeneratedColumnTranslatesLikeStored() {
        String persistent = translate("CREATE TABLE t (a INT, b INT AS (a + 1) PERSISTENT)");
        String stored = translate("CREATE TABLE t (a INT, b INT AS (a + 1) STORED)");
        assertEquals(stored, persistent);
        assertTrue(persistent.contains("DEFAULT a+1"),
                "the generated column must keep its expression: " + persistent);
    }

    /**
     * Every spelling of the generated column MySQL accepts, with PERSISTENT in
     * each attribute position. Before the fix all five threw.
     */
    @Test
    public void persistentIsAcceptedInEveryAttributePosition() {
        String[] statements = {
                "CREATE TABLE t (a INT, b INT AS (a + 1) PERSISTENT)",
                "CREATE TABLE t (a INT, b INT AS (a + 1) PERSISTENT NOT NULL)",
                "CREATE TABLE t (a INT, b INT GENERATED ALWAYS AS (a+1) PERSISTENT COMMENT 'x')",
                "ALTER TABLE t ADD COLUMN c INT AS (a+1) PERSISTENT",
                "ALTER TABLE t MODIFY COLUMN b INT AS (a+1) PERSISTENT",
        };
        for (String sql : statements) {
            String emitted = translate(sql);
            assertTrue(emitted.contains("DEFAULT a+1") || emitted.contains("DEFAULT a + 1"),
                    "expected a DEFAULT column for [" + sql + "] but got: " + emitted);
        }
    }

    /**
     * The rewrite must not touch an unrelated identifier that merely contains
     * the word, nor a string literal that quotes it.
     */
    @Test
    public void persistentIsOnlyRewrittenAsAKeyword() {
        String tableOption = translate("CREATE TABLE t (a INT) STATS_PERSISTENT=1");
        assertTrue(tableOption.contains("a Nullable(Int32)"),
                "STATS_PERSISTENT is a table option and must not block the CREATE: " + tableOption);

        String normalised = MySqlDdlNormalizer.normalize(
                "CREATE TABLE t (a INT COMMENT 'set PERSISTENT here') STATS_PERSISTENT=1");
        assertTrue(normalised.contains("COMMENT 'set PERSISTENT here'"),
                "text inside a string literal must be left alone: " + normalised);
        assertTrue(normalised.contains("STATS_PERSISTENT"),
                "the table option must be left alone: " + normalised);
    }

    /**
     * The shipped grammar reads {@code SRID <n>} only immediately after the
     * spatial type, so every other position MySQL allows was a hard parse
     * failure.
     */
    @Test
    public void sridIsAcceptedInEveryColumnAttributePosition() {
        String[] statements = {
                "CREATE TABLE t (g GEOMETRY NOT NULL SRID 4326)",
                "CREATE TABLE t (g GEOMETRY NULL SRID 4326)",
                "CREATE TABLE t (g GEOMETRY COMMENT 'x' SRID 4326)",
                "CREATE TABLE t (g GEOMETRY DEFAULT NULL SRID 4326)",
                "CREATE TABLE t (g POINT NOT NULL SRID 4326)",
                "ALTER TABLE t ADD COLUMN g GEOMETRY NOT NULL SRID 4326",
                "ALTER TABLE t MODIFY COLUMN g GEOMETRY NOT NULL SRID 4326",
        };
        for (String sql : statements) {
            String emitted = translate(sql);
            assertTrue(emitted.contains("g "),
                    "expected column g for [" + sql + "] but got: " + emitted);
            assertFalse(emitted.contains("SRID"),
                    "SRID has no ClickHouse equivalent and must not be emitted: " + emitted);
        }
    }

    /**
     * A spatial column and a later non-spatial column in the same statement: the
     * SRID must attach to the spatial column only, and the second column must
     * still translate.
     */
    @Test
    public void sridAttachesToItsOwnColumn() {
        String emitted = translate("CREATE TABLE t (id INT, g GEOMETRY NOT NULL SRID 4326, note INT NOT NULL)");
        assertTrue(emitted.contains("g String NOT NULL"), "spatial column: " + emitted);
        assertTrue(emitted.contains("note Int32 NOT NULL"), "later column: " + emitted);
    }

    /**
     * {@code NATIONAL CHAR} is a grammar alternative
     * ({@code NationalStringDataTypeContext.CHAR()}) with no entry in the type
     * resolver, so it reached {@code Unrecognized dataType} and the statement
     * failed. It is the same type as {@code NATIONAL CHARACTER} and must now
     * translate identically.
     */
    @Test
    public void nationalCharTranslatesLikeNationalCharacter() {
        String charForm = translate("CREATE TABLE t (c NATIONAL CHAR(3))");
        String characterForm = translate("CREATE TABLE t (c NATIONAL CHARACTER(3))");
        assertEquals(characterForm, charForm);
        assertTrue(charForm.contains("c Nullable(String)"),
                "NATIONAL CHAR must map to String: " + charForm);
    }

    /** Every NATIONAL spelling the grammar defines must still work. */
    @Test
    public void everyNationalStringSpellingTranslates() {
        String[] statements = {
                "CREATE TABLE t (c NCHAR)",
                "CREATE TABLE t (c NCHAR(3))",
                "CREATE TABLE t (c NATIONAL CHAR)",
                "CREATE TABLE t (c NATIONAL CHAR(3) BINARY NOT NULL)",
                "CREATE TABLE t (c NATIONAL VARCHAR(5))",
                "CREATE TABLE t (c NATIONAL CHAR VARYING(5))",
                "CREATE TABLE t (c NATIONAL CHARACTER VARYING(5))",
                "CREATE TABLE t (c NVARCHAR(5))",
                "ALTER TABLE t ADD COLUMN d NCHAR(3)",
        };
        for (String sql : statements) {
            String emitted = translate(sql);
            assertFalse(emitted.isEmpty(),
                    "expected an emitted statement for [" + sql + "]");
        }
    }

    /**
     * A statement that needs no rewrite is returned by identity, so the common
     * path costs nothing and cannot be perturbed by the bridge.
     */
    @Test
    public void untouchedStatementsAreReturnedUnchanged() {
        assertSame(null, MySqlDdlNormalizer.normalize(null));
        String plain = "CREATE TABLE t (a INT)";
        assertSame(plain, MySqlDdlNormalizer.normalize(plain));
        String srid = "CREATE TABLE t (g GEOMETRY SRID 4326)";
        assertSame(srid, MySqlDdlNormalizer.normalize(srid));
    }

    /**
     * {@code 0b<binary>} is MySQL 8's unquoted bit-value literal. The shipped
     * lexer has no rule for it -- only {@code '0x' HEX_DIGIT+} and the quoted
     * {@code b'...'} -- so the token split into {@code 0} and an identifier and
     * the whole statement was a syntax error. Verified against MySQL 8.0.41:
     * {@code HEX(0b101)} is {@code '05'}.
     */
    @Test
    public void binaryValueLiteralParsesLikeTheQuotedForm() {
        String unquoted = translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0b101");
        String quoted = translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT b'101'");
        assertEquals(quoted, unquoted,
                "0b101 and b'101' are the same bit value and must translate identically");
        assertTrue(unquoted.contains("DEFAULT '05'"),
                "a bit value on a bytes column is the writer's hex text: " + unquoted);
    }

    /**
     * A bit-value literal on a numeric column is the number the bits denote,
     * for both spellings and inside the parentheses MySQL allows.
     */
    @Test
    public void binaryValueLiteralIsAcceptedInEveryDefaultPosition() {
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT DEFAULT 0b101").contains("DEFAULT 5"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT DEFAULT (0b101)").contains("DEFAULT 5"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT NOT NULL DEFAULT 0b1").contains("DEFAULT 1"));
        assertTrue(translate("ALTER TABLE t MODIFY COLUMN c INT DEFAULT 0b101").contains("DEFAULT 5"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c BIGINT DEFAULT 0b1111111111111111")
                .contains("DEFAULT 65535"));
    }

    /**
     * A single-digit bit value is a literal in MySQL: {@code 0b1} is
     * {@code b'\x01'} and {@code 0b0} is {@code b'\x00'} (verified on 8.0.41).
     * The first cut of this bridge required two or more digits and aborted the
     * statement instead, so the one-digit case is a named regression.
     */
    @Test
    public void singleDigitBinaryValueLiteralIsRewritten() {
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT DEFAULT 0b1").contains("DEFAULT 1"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT DEFAULT 0b0").contains("DEFAULT 0"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0b1")
                        .contains("DEFAULT '01'"),
                "one bit is one byte, 0x01");
    }

    /**
     * Only the lower-case spelling is a bit value: MySQL reads {@code 0B101} as
     * an identifier (verified on 8.0.41 -- it is "Unknown column '0B101'"), and
     * a bare {@code 0b} is that identifier too. Neither may be rewritten.
     */
    @Test
    public void onlyLowerCaseBinaryValueLiteralIsRewritten() {
        assertSame("CREATE TABLE t (a INT) COMMENT '0b101'",
                MySqlDdlNormalizer.normalize("CREATE TABLE t (a INT) COMMENT '0b101'"));

        String identifier = "CREATE TABLE t (0B101 INT)";
        assertSame(identifier, MySqlDdlNormalizer.normalize(identifier));

        String hex = "ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0x0b10";
        assertSame(hex, MySqlDdlNormalizer.normalize(hex),
                "0x0b10 is a hexadecimal literal, not a bit value");
    }

    /**
     * A digit outside {@code [01]} ends the literal: MySQL reads {@code 0b102}
     * as an identifier (verified on 8.0.41 -- "Unknown column '0b102'"), not as
     * {@code 0b10} followed by a {@code 2}. Rewriting only the binary prefix
     * would turn a statement MySQL rejects into one that replicates a different
     * value, so the whole token must be left alone.
     */
    @Test
    public void nonBinaryDigitIsNotAPartialMatch() {
        String notALiteral = "CREATE TABLE t (0b102 INT)";
        assertSame(notALiteral, MySqlDdlNormalizer.normalize(notALiteral));
    }

    /**
     * MySQL's {@code REAL_LITERAL} rule is {@code (DEC_DIGIT+)? '.' DEC_DIGIT+},
     * so {@code .5} is a valid literal, and ClickHouse rejects a number that
     * starts with a dot ({@code Code: 62 ... failed at position 47 ('.')} on
     * 24.8). The leading zero is the whole difference, and both engines read
     * {@code .5} and {@code 0.5} as the same value.
     */
    @Test
    public void realLiteralWithoutAnIntegerPartGetsALeadingZero() {
        String[] forms = {
                "ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5",
                "ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT -.5",
                "ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5e3",
                "ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5E-3",
                "ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT (.5)",
                "ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT .5",
        };
        for (String sql : forms) {
            String emitted = translate(sql);
            assertFalse(emitted.matches("(?s).*DEFAULT -?\\.\\d.*"),
                    "a leading-dot literal must not be emitted verbatim for [" + sql + "]: " + emitted);
            assertTrue(emitted.contains("0."),
                    "expected a leading zero for [" + sql + "] but got: " + emitted);
        }
        // A real literal that already has an integer part is untouched.
        assertTrue(translate("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 0.5").contains("DEFAULT 0.5"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 1.5").contains("DEFAULT 1.5"));
    }

    /**
     * An odd number of hexadecimal digits is left-padded to a whole byte,
     * because that is the byte MySQL reads: {@code HEX(0x1)} is {@code '01'} and
     * {@code HEX(0xabc)} is {@code '0ABC'} (verified on 8.0.41). Without the
     * padding the emitted text described a byte the source never held.
     */
    @Test
    public void oddHexDigitsArePaddedToWholeBytes() {
        assertTrue(translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0x1")
                        .contains("DEFAULT '01'"),
                "0x1 is the single byte 0x01");
        assertTrue(translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0xabc")
                        .contains("DEFAULT '0abc'"),
                "0xabc is the two bytes 0x0ABC");
        assertTrue(translate("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0x4142")
                        .contains("DEFAULT '4142'"),
                "an even digit count is already whole bytes");
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT DEFAULT 0x1")
                        .contains("DEFAULT 1"),
                "on a numeric column the digits are the number, so no padding applies");
    }

    /**
     * An SRID that belongs to no spatial column is left where it is, so the
     * parser reports it rather than the text being silently rearranged into
     * something that parses differently from the source.
     */
    @Test
    public void sridWithoutASpatialColumnIsLeftAlone() {
        String sql = "CREATE TABLE t (a INT) INDEX(a) SRID 4326";
        assertEquals(sql, MySqlDdlNormalizer.normalize(sql));
    }

    /**
     * {@code ENUM} and {@code SET} carry a value list, not a precision and
     * scale. The old guard tested {@code dt.name()} for ENUM/SET, but the
     * resolver reports the JDBC name {@code CHAR} for both, so every such
     * column fell into the numeric branch and logged an error. The guard is now
     * on the parse-tree class; the column still translates to String.
     */
    @Test
    public void enumAndSetValueListsAreNotReadAsPrecision() {
        assertTrue(translate("CREATE TABLE t (c ENUM('a','b'))").contains("c Nullable(String)"));
        assertTrue(translate("CREATE TABLE t (c SET('a','b'))").contains("c Nullable(String)"));
        assertTrue(translate("CREATE TABLE t (c ENUM('a','b') BINARY NOT NULL)").contains("c String NOT NULL"));
    }

    /**
     * {@code NODEGROUP = <n>} carries a NUMBER, and the grammar's rule for a
     * partition option is {@code NODEGROUP '='? uid} -- {@code uid} resolves only
     * to an identifier, never to a numeric literal. MySQL's own documentation
     * writes the value unquoted, so the statement is valid MySQL that the
     * shipped grammar refuses with "no viable alternative at input". Quoting the
     * value makes it a {@code REVERSE_QUOTE_ID}, which {@code uid} accepts.
     *
     * <p>The rewrite is loss-free because the connector drops every partition
     * option anyway ({@code isNoOpSpecification}), so the value never reaches
     * ClickHouse.</p>
     */
    @Test
    public void nodegroupNumericValueIsQuotedSoTheStatementParses() {
        assertEquals("CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "(PARTITION p0 VALUES LESS THAN (10) NODEGROUP = `2`)",
                MySqlDdlNormalizer.normalize("CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                        + "(PARTITION p0 VALUES LESS THAN (10) NODEGROUP = 2)"));
        assertTrue(translate("CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                + "(PARTITION p0 VALUES LESS THAN (10) NODEGROUP = 2)").startsWith("CREATE TABLE"));
    }

    /**
     * A value that is already an identifier or already quoted is left exactly
     * as it is, so the rewrite is idempotent and cannot corrupt a value it did
     * not create.
     */
    @Test
    public void nodegroupIdentifierValueIsLeftAlone() {
        String named = "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                + "(PARTITION p0 VALUES LESS THAN (10) NODEGROUP = grp1)";
        assertEquals(named, MySqlDdlNormalizer.normalize(named));

        String quoted = "CREATE TABLE t (id INT) PARTITION BY RANGE(id) "
                + "(PARTITION p0 VALUES LESS THAN (10) NODEGROUP = `2`)";
        assertEquals(quoted, MySqlDdlNormalizer.normalize(quoted));
    }

    /**
     * The word only counts as the keyword: a column named {@code nodegroup}
     * and a string literal that contains the word are both untouched.
     */
    @Test
    public void nodegroupRewriteDoesNotTouchIdentifiersOrLiterals() {
        String column = "CREATE TABLE t (nodegroup INT)";
        assertEquals(column, MySqlDdlNormalizer.normalize(column));

        String literal = "CREATE TABLE t (a INT) COMMENT 'NODEGROUP = 2'";
        assertEquals(literal, MySqlDdlNormalizer.normalize(literal));
    }

    /**
     * MySQL 8.0.19 added a row alias to {@code INSERT ... ON DUPLICATE KEY
     * UPDATE}, and the alias may carry its own column names
     * ({@code VALUES (...) AS new(m, n)}).
     *
     * <p>The shipped grammar reads {@code AS? uid} with no place for a
     * parenthesised list, so the documented spelling threw {@code Error parsing
     * DDL} at the {@code (}. A DML statement emits nothing -- rows arrive as
     * binlog row events -- so dropping the alias list is loss-free, and the
     * statement must simply parse.</p>
     */
    @Test
    public void insertRowAliasWithColumnNamesParses() {
        String[] statements = {
                "INSERT INTO t (a, b) VALUES (1, 2) AS new(x, y) ON DUPLICATE KEY UPDATE b = x + y",
                "INSERT INTO t VALUES (1), (2) AS new(x) ON DUPLICATE KEY UPDATE a = x",
                "INSERT INTO t SET a = 1 AS new(m) ON DUPLICATE KEY UPDATE a = m",
                "INSERT INTO t (a, b) VALUES (1, 2) AS `new`(`x`, `y`) ON DUPLICATE KEY UPDATE b = `x`",
        };
        for (String sql : statements) {
            assertTrue(translate(sql).trim().isEmpty(),
                    "a DML statement must parse and emit nothing: [" + sql + "]");
        }
    }

    /**
     * The row-alias rewrite removes only the column list, so the alias keeps
     * naming the row and a statement that already parsed is untouched.
     */
    @Test
    public void rowAliasColumnListIsDroppedAndTheAliasKept() {
        assertEquals(
                "INSERT INTO t (a, b) VALUES (1, 2) AS new ON DUPLICATE KEY UPDATE b = x + y",
                MySqlDdlNormalizer.normalize(
                        "INSERT INTO t (a, b) VALUES (1, 2) AS new(x, y) ON DUPLICATE KEY UPDATE b = x + y"));

        String alreadyParses = "INSERT INTO t (a, b) VALUES (1, 2) AS new ON DUPLICATE KEY UPDATE b = new.b";
        assertEquals(alreadyParses, MySqlDdlNormalizer.normalize(alreadyParses));
    }

    /**
     * A DDL statement has no row alias, so {@code CREATE TABLE ... AS (SELECT
     * ...)} and every other {@code AS} spelling must reach the parser verbatim.
     */
    @Test
    public void rowAliasRewriteNeverTouchesDdl() {
        String[] statements = {
                "CREATE TABLE t AS SELECT * FROM s",
                "CREATE TABLE t (a INT) AS SELECT * FROM s",
                "ALTER TABLE t RENAME AS s",
                "CREATE TABLE t (a INT, b INT AS (a + 1) STORED)",
        };
        for (String sql : statements) {
            assertEquals(sql, MySqlDdlNormalizer.normalize(sql),
                    "a DDL statement must not be rewritten by the row-alias bridge: " + sql);
        }
    }

    /*
     * AUTOEXTEND_SIZE and the SECONDARY_* family.
     *
     * Each of these is valid MySQL 8.0 that the bundled parser rejects outright,
     * and none of them has any ClickHouse effect: AUTOEXTEND_SIZE sizes an
     * InnoDB tablespace's growth step (8.0.23+), and SECONDARY_ENGINE /
     * SECONDARY_LOAD / SECONDARY_UNLOAD belong to the secondary-engine (RAPID)
     * feature. The bridge removes the clause and the statement parses.
     *
     * The end-to-end matrix carries only the AUTOEXTEND_SIZE half: this suite's
     * MySQL image is Community 8.0.36, which ships no RAPID engine, so MySQL
     * itself refuses the SECONDARY_* clauses there and no binlog event would
     * ever be written. The parse and the bridge are therefore asserted here.
     */

    /**
     * The bare form: the whole specification list is the clause, so only the
     * {@code ALTER TABLE t} header is left. That is a valid statement and
     * {@code enterAlterTable} clears it, which is what makes the removal
     * loss-free rather than a half-statement handed to the translator.
     */
    @Test
    public void autoextendSizeIsDroppedAndTheStatementStillParses() {
        String[] statements = {
                "ALTER TABLE t AUTOEXTEND_SIZE = 4M",
                "ALTER TABLE t AUTOEXTEND_SIZE 4M",
                "ALTER TABLE t AUTOEXTEND_SIZE = 8G",
                "ALTER TABLE test.t AUTOEXTEND_SIZE = 16M",
                // The quoted spellings. The manual writes the option unquoted,
                // but a quoted value is accepted by the server and appears in
                // dumps and hand-written scripts; measured against the shipped
                // parser, every one of these is "no viable alternative" unless
                // the bridge recognises it, which halts replication of every
                // table on the host.
                "ALTER TABLE t AUTOEXTEND_SIZE = '4M'",
                "ALTER TABLE t AUTOEXTEND_SIZE='4M'",
                "ALTER TABLE t AUTOEXTEND_SIZE = '4194304'",
                "ALTER TABLE test.t AUTOEXTEND_SIZE = '16M'",
        };
        for (String sql : statements) {
            String normalised = MySqlDdlNormalizer.normalize(sql);
            assertTrue(normalised.trim().endsWith("t") || normalised.contains("test.t"),
                    "only the header must remain: " + normalised);
            assertFalse(normalised.toUpperCase().contains("AUTOEXTEND_SIZE"),
                    "the clause must be gone: " + normalised);
            // This call is the assertion: parseSql throws
            // "Error parsing DDL" when the grammar rejects the statement, and
            // that is the failure that halts replication for every table. A
            // specification list that held only this clause leaves a bare
            // `ALTER TABLE t`, which the translator clears, so the output is
            // empty by design -- asserting on its text would be asserting on
            // the absence of a no-op.
            assertEquals("", translate(sql).trim(),
                    "a clause with no ClickHouse effect must emit nothing: " + sql);
        }
    }

    /**
     * The clause beside one that DOES translate, in both orders. The bridge has
     * to take the clause together with the correct comma -- the one in front of
     * it when it is not first, the one after it when it is -- so the surviving
     * specification is untouched and no leading comma is left behind.
     */
    @Test
    public void autoextendSizeBesideATranslatingClauseKeepsThatClause() {
        String trailing = MySqlDdlNormalizer.normalize(
                "ALTER TABLE t MODIFY COLUMN c BIGINT, AUTOEXTEND_SIZE = 4M");
        assertTrue(trailing.toUpperCase().contains("MODIFY COLUMN C BIGINT")
                        || trailing.contains("MODIFY COLUMN c BIGINT"),
                "the neighbouring clause must survive: " + trailing);
        assertFalse(trailing.toUpperCase().contains("AUTOEXTEND_SIZE"),
                "the clause must be gone: " + trailing);

        String leading = MySqlDdlNormalizer.normalize(
                "ALTER TABLE t AUTOEXTEND_SIZE = 4M, MODIFY COLUMN c BIGINT");
        assertTrue(leading.contains("MODIFY COLUMN c BIGINT"),
                "the neighbouring clause must survive: " + leading);
        assertFalse(leading.contains(","),
                "dropping the first clause must not leave a leading comma: " + leading);
        assertFalse(leading.toUpperCase().contains("AUTOEXTEND_SIZE"),
                "the clause must be gone: " + leading);

        // Both must still parse, which is the whole point of the bridge.
        assertTrue(translate("ALTER TABLE t AUTOEXTEND_SIZE = 4M, MODIFY COLUMN c BIGINT")
                .contains("MODIFY COLUMN"));
        assertTrue(translate("ALTER TABLE t MODIFY COLUMN c BIGINT, AUTOEXTEND_SIZE = 4M")
                .contains("MODIFY COLUMN"));
    }

    /**
     * A bridged clause sitting between two translating ones: the drop must not
     * eat either neighbour's comma in either direction.
     */
    @Test
    public void autoextendSizeBetweenTwoClausesKeepsBoth() {
        String normalised = MySqlDdlNormalizer.normalize(
                "ALTER TABLE t ADD COLUMN a INT, AUTOEXTEND_SIZE = 4M, ADD COLUMN b INT");
        assertTrue(normalised.contains("ADD COLUMN a INT"),
                "the clause before it must survive: " + normalised);
        assertTrue(normalised.contains("ADD COLUMN b INT"),
                "the clause after it must survive: " + normalised);
        assertFalse(normalised.toUpperCase().contains("AUTOEXTEND_SIZE"),
                "the clause must be gone: " + normalised);
    }

    /**
     * The {@code CREATE TABLE} twin. The option sits among the table options,
     * after the closing parenthesis of the column definitions, and the body --
     * a column DEFINITION is inside that parenthesis -- must never be touched.
     */
    @Test
    public void createTableAutoextendSizeOptionIsDropped() {
        // The unquoted form first, then the quoted spellings: same bridge, same
        // requirement, and a CREATE TABLE the parser rejects is a table that
        // never gets created on the replica.
        for (String sql : new String[]{
                "CREATE TABLE t (a INT, b VARCHAR(10)) ENGINE=InnoDB AUTOEXTEND_SIZE = 4M",
                "CREATE TABLE t (a INT, b VARCHAR(10)) ENGINE=InnoDB AUTOEXTEND_SIZE = '4M'",
                "CREATE TABLE t (a INT, b VARCHAR(10)) ENGINE=InnoDB AUTOEXTEND_SIZE='4M'",
                "CREATE TABLE t (a INT, b VARCHAR(10)) AUTOEXTEND_SIZE = '4194304'"}) {
            String normalised = MySqlDdlNormalizer.normalize(sql);
            assertFalse(normalised.toUpperCase().contains("AUTOEXTEND_SIZE"),
                    "the table option must be gone: " + normalised);
            assertTrue(normalised.contains("ENGINE=InnoDB") || normalised.contains("(a INT"),
                    "the neighbouring option and the body must survive: " + normalised);
            assertTrue(normalised.contains("b VARCHAR(10)"),
                    "the column definitions must be untouched: " + normalised);
            assertTrue(translate(sql).startsWith("CREATE TABLE"),
                    "the statement must still translate: " + sql);
        }
    }

    /**
     * A quoted value that is NOT a size is left alone.
     *
     * <p>The bridge drops the option only when the value it carries looks like
     * one, so widening the pattern to accept quotes must not turn it into a
     * blanket "delete everything after the keyword". An expression or an
     * identifier is not a size, and dropping an option with an unparsed value
     * would be guessing at what the source said.</p>
     */
    @Test
    public void autoextendSizeWithANonSizeQuotedValueIsNotDropped() {
        String[] statements = {
                "ALTER TABLE t AUTOEXTEND_SIZE = 'large'",
                "CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 'huge'",
                "ALTER TABLE t AUTOEXTEND_SIZE = CONCAT('4', 'M')",
        };
        for (String sql : statements) {
            assertEquals(sql, MySqlDdlNormalizer.normalize(sql),
                    "a value that is not a size must not be dropped: " + sql);
        }
    }

    /**
     * A column NAMED after the keyword must survive in both statement families:
     * the bridge matches a clause where a specification or a table option
     * begins, never merely where the word occurs.
     */
    @Test
    public void autoextendSizeAsAColumnNameIsNotABridge() {
        String[] statements = {
                "CREATE TABLE t (autoextend_size INT)",
                "CREATE TABLE t (a INT) COMMENT 'AUTOEXTEND_SIZE = 4M'",
                "ALTER TABLE t ADD COLUMN autoextend_size INT",
                "ALTER TABLE t MODIFY COLUMN c INT COMMENT 'AUTOEXTEND_SIZE = 4M'",
        };
        for (String sql : statements) {
            assertEquals(sql, MySqlDdlNormalizer.normalize(sql),
                    "a column or a literal that uses the word must be left alone: " + sql);
        }
    }

    /**
     * {@code SECONDARY_ENGINE = <engine>} names the engine a table loads into;
     * {@code SECONDARY_ENGINE = NULL} removes it. Neither can say anything
     * about a ClickHouse replica.
     */
    @Test
    public void secondaryEngineIsDropped() {
        for (String sql : new String[]{
                "ALTER TABLE t SECONDARY_ENGINE = RAPID",
                "ALTER TABLE t SECONDARY_ENGINE=RAPID",
                "ALTER TABLE t SECONDARY_ENGINE = NULL"}) {
            String normalised = MySqlDdlNormalizer.normalize(sql);
            assertFalse(normalised.toUpperCase().contains("SECONDARY_ENGINE"),
                    "the clause must be gone: " + normalised);
            // Parsing without throwing is the assertion: the translator then
            // clears the bare `ALTER TABLE t` that is left.
            assertEquals("", translate(sql).trim(),
                    "a clause with no ClickHouse effect must emit nothing: " + sql);
        }
    }

    /**
     * {@code SECONDARY_LOAD} and {@code SECONDARY_UNLOAD} start and stop that
     * load. They are bare clauses with no value, and both must parse.
     */
    @Test
    public void secondaryLoadAndUnloadAreDropped() {
        for (String sql : new String[]{
                "ALTER TABLE t SECONDARY_LOAD",
                "ALTER TABLE t SECONDARY_UNLOAD"}) {
            String normalised = MySqlDdlNormalizer.normalize(sql);
            assertFalse(normalised.toUpperCase().contains("SECONDARY_LOAD"),
                    "the clause must be gone: " + normalised);
            // Parsing without throwing is the assertion; see above.
            assertEquals("", translate(sql).trim(),
                    "a clause with no ClickHouse effect must emit nothing: " + sql);
        }

        String between = MySqlDdlNormalizer.normalize(
                "ALTER TABLE t ADD COLUMN a INT, SECONDARY_LOAD, ADD COLUMN b INT");
        assertTrue(between.contains("ADD COLUMN a INT") && between.contains("ADD COLUMN b INT"),
                "both neighbours must survive: " + between);
        assertFalse(between.toUpperCase().contains("SECONDARY_LOAD"),
                "the clause must be gone: " + between);
    }

    /**
     * Two or more droppable clauses IN A ROW at the head of the list, followed
     * by a clause that must survive.
     *
     * <p>This is the shape that broke: cutting each clause with the comma on
     * its own side left the comma between the last two droppables behind, so
     * the statement became {@code ALTER TABLE t , ADD COLUMN c INT}. The
     * grammar rejects a leading comma, {@link ErrorListenerImpl} turns that
     * into "Error parsing DDL", and the exception escapes to the CDC caller,
     * which halts replication for every table on the host.</p>
     *
     * <p>The separator must therefore belong to the SURVIVING list, not to the
     * removed clause, and the test is written so that a single-clause fix (the
     * shape that already worked) cannot pass it: every case has a RUN of
     * droppables, and the run length varies so a fix tuned to exactly two
     * cannot pass either.</p>
     */
    @Test
    public void aRunOfDroppableClausesLeavesNoCommaBehind() {
        String[][] cases = {
                // {statement, the clause that must survive}
                {"ALTER TABLE t SECONDARY_LOAD, SECONDARY_ENGINE = NULL, ADD COLUMN c INT",
                        "ADD COLUMN c INT"},
                {"ALTER TABLE t AUTOEXTEND_SIZE = 4M, SECONDARY_LOAD, ADD COLUMN c INT",
                        "ADD COLUMN c INT"},
                {"ALTER TABLE t SECONDARY_LOAD, SECONDARY_UNLOAD, AUTOEXTEND_SIZE = 4M,"
                        + " ADD COLUMN c INT", "ADD COLUMN c INT"},
                {"ALTER TABLE t SECONDARY_LOAD, SECONDARY_ENGINE = RAPID, MODIFY COLUMN c BIGINT",
                        "MODIFY COLUMN c BIGINT"},
                // A run in the middle, between two survivors: both must remain
                // and the comma between them must be exactly one.
                {"ALTER TABLE t ADD COLUMN a INT, SECONDARY_LOAD, SECONDARY_UNLOAD,"
                        + " ADD COLUMN b INT", "ADD COLUMN a INT"},
                // The same with a terminating semicolon, which the binlog
                // statement carries and which must not be swallowed.
                {"ALTER TABLE t SECONDARY_LOAD, SECONDARY_UNLOAD, ADD COLUMN c INT;",
                        "ADD COLUMN c INT; REFUSE"},
        };
        for (String[] testCase : cases) {
            String sql = testCase[0];
            String must = testCase[1];
            String normalised = MySqlDdlNormalizer.normalize(sql);

            assertFalse(normalised.contains(",,"),
                    "an empty specification is left behind: " + normalised);
            int clauseStart = normalised.toUpperCase().indexOf("TABLE ") + "TABLE ".length();
            int specStart = clauseStart;
            while (specStart < normalised.length()
                    && Character.isWhitespace(normalised.charAt(specStart))) {
                specStart++;
            }
            assertFalse(specStart < normalised.length() && normalised.charAt(specStart) == ',',
                    "the specification list begins with a comma the grammar rejects,"
                            + " which throws and halts replication: " + normalised);

            if (must.endsWith("REFUSE")) {
                String wanted = must.substring(0, must.length() - " REFUSE".length());
                assertTrue(normalised.contains(wanted),
                        "the surviving clause and its semicolon must be kept: " + normalised);
            } else {
                assertTrue(normalised.contains(must),
                        "the clause that has a ClickHouse effect must survive: " + normalised);
            }

            // The assertion that matters: parseSql throws "Error parsing DDL"
            // when the grammar rejects the statement. Before the fix every case
            // above threw here.
            String translated = translate(sql);
            assertFalse(translated.trim().isEmpty(),
                    "the surviving clause must reach the translator, not vanish: " + sql);
        }
    }

    /**
     * A droppable clause at the END of the list, where the terminator is.
     *
     * <p>Every other case in this file puts the droppable clause in the middle,
     * and most omit the semicolon, so none of them could see this: the
     * specification list runs to the end of the statement, so the LAST
     * specification carries the {@code ;}. The clause patterns are anchored at
     * {@code $}, so {@code AUTOEXTEND_SIZE = 4M;} did not match, the clause
     * survived, and the grammar rejected the whole statement -- the same fatal
     * outcome as the leading-comma bug, from the opposite end of the list. The
     * test that found it was the matrix corpus, which carries the semicolons a
     * real binlog statement has.</p>
     */
    @Test
    public void aTrailingDroppableClauseIsDroppedWithItsTerminator() {
        String[] statements = {
                "ALTER TABLE test.t AUTOEXTEND_SIZE = 4M;",
                "ALTER TABLE test.t MODIFY COLUMN c_mod BIGINT, AUTOEXTEND_SIZE = 8M;",
                "ALTER TABLE test.t SECONDARY_LOAD;",
                "ALTER TABLE test.t ADD COLUMN a INT, SECONDARY_UNLOAD;",
        };
        for (String sql : statements) {
            String normalised = MySqlDdlNormalizer.normalize(sql);

            assertFalse(normalised.contains("AUTOEXTEND_SIZE"),
                    "the clause with no ClickHouse effect must be dropped: " + normalised);
            assertFalse(normalised.contains("SECONDARY_LOAD"),
                    "the clause with no ClickHouse effect must be dropped: " + normalised);
            assertFalse(normalised.contains("SECONDARY_UNLOAD"),
                    "the clause with no ClickHouse effect must be dropped: " + normalised);
            assertFalse(normalised.contains(",,"),
                    "an empty specification is left behind: " + normalised);

            // The terminating semicolon belongs to the statement, never to the
            // clause being removed.
            assertTrue(normalised.trim().endsWith(";"),
                    "the statement terminator must survive the drop: " + normalised);

            // The assertion that matters: this throws "Error parsing DDL" if the
            // clause is left in, which halts replication for the whole host.
            translate(sql);
        }

        // The surviving clause must still reach the translator beside it.
        String translated = translate(
                "ALTER TABLE test.t MODIFY COLUMN c_mod BIGINT, AUTOEXTEND_SIZE = 8M;");
        assertTrue(translated.contains("MODIFY COLUMN"),
                "the clause that has a ClickHouse effect must survive: " + translated);
    }

    /**
     * The control that keeps the bridge anchored: a column CALLED
     * {@code secondary_load} is an ordinary {@code ADD COLUMN}, so the column
     * must reach the translator. A bridge anchored anywhere but the start of a
     * specification would drop the column and still pass a "does it parse"
     * test -- which is exactly the failure this case exists to catch.
     */
    @Test
    public void secondaryLoadAsAColumnNameIsKept() {
        String sql = "ALTER TABLE t ADD COLUMN secondary_load INT";
        assertEquals(sql, MySqlDdlNormalizer.normalize(sql),
                "the column must not be bridged away: " + sql);

        String translated = translate(sql);
        assertTrue(translated.contains("secondary_load"),
                "the column must reach the translator: " + translated);
    }
}
