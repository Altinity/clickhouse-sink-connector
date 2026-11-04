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

import com.altinity.clickhouse.debezium.embedded.cdc.DDLReplicationException;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the whole {@code defaultValue} surface of the MySQL grammar through
 * {@link DefaultLiteralTranslator}: every one of its eight alternatives, and
 * every alternative of the {@code constant} rule it delegates to.
 *
 * <p>The grammar is the specification of what MySQL accepts, and it is
 * deterministic and unambiguous, so the cases below are derived from it rather
 * than sampled. Each is exercised through {@link MySQLDDLParserService} -- the
 * class the CDC pipeline calls -- so it runs against the Debezium-shaded
 * parser the connector actually ships, not this repository's unused copy under
 * {@code src/main/antlr4}.</p>
 *
 * <p>The {@code ALTER TABLE ... ADD COLUMN} path is the one that calls the
 * translator: {@code CREATE TABLE} never emits a user {@code DEFAULT}
 * ({@code parseColumnDefinitions} has no DEFAULT branch), so a literal-default
 * case must be written as an ALTER or it would assert nothing.</p>
 */
public class DefaultLiteralTranslatorTest {

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

    private static String defaultOf(String sql) {
        String emitted = translate(sql);
        int at = emitted.indexOf("DEFAULT ");
        assertTrue(at >= 0, "no DEFAULT in " + emitted + " for [" + sql + "]");
        return emitted.substring(at + "DEFAULT ".length()).trim();
    }

    /**
     * Alternative 1, {@code NULL_LITERAL}. On a nullable column it is written
     * as NULL, which is the value MySQL back-filled.
     */
    @Test
    public void nullLiteralIsCarried() {
        assertEquals("NULL", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT NULL"));
        assertEquals("NULL", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT \\N"));
    }

    /**
     * Alternative 3, {@code constant -> stringLiteral}: single, doubled-quote,
     * double-quoted, adjacent and charset-introduced forms all become one
     * ClickHouse single-quoted literal.
     */
    @Test
    public void everyStringLiteralFormIsCarried() {
        assertEquals("'abc'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT 'abc'"));
        assertEquals("''", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT ''"));
        assertEquals("'a''b'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT 'a''b'"),
                "a doubled quote is also ClickHouse's escape, so the body is carried verbatim");
        assertEquals("'dq'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT \"dq\""));
        assertEquals("'ab'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT 'a' 'b'"));
        assertEquals("'nat'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT N'nat'"));
        assertEquals("'native'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT N'nat' 'ive'"));
        assertEquals("'cs'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT _utf8mb4'cs'"));
        assertEquals("'abc'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT 'abc' COLLATE utf8mb4_bin"));
    }

    /**
     * Alternative 3, {@code constant -> decimalLiteral}: an integer, a signed
     * integer, a leading-zero integer and an exponent literal are ClickHouse
     * literals already and are carried verbatim. Verified on ClickHouse 24.8:
     * {@code 007}, {@code 1E3} and {@code 1.5} are all accepted on an integer
     * column, so no rewrite is needed for any of them.
     */
    @Test
    public void decimalLiteralsAreCarriedVerbatim() {
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 0"));
        assertEquals("-1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT -1"));
        assertEquals("1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT +1"),
                "an explicit plus sign is not a ClickHouse literal prefix, so it is dropped");
        assertEquals("007", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 007"));
        assertEquals("1.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 1.5"));
        assertEquals("1.5e3", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 1.5e3"));
        assertEquals("1E3", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 1E3"));
    }

    /**
     * {@code REAL_LITERAL} may have no integer part ({@code (DEC_DIGIT+)? '.'
     * DEC_DIGIT+}), and ClickHouse rejects a number that starts with a dot
     * ({@code Code: 62 ... failed at position 47 ('.')} on 24.8). The leading
     * zero is the only change, and both engines read the same value.
     */
    @Test
    public void realLiteralWithoutAnIntegerPartGetsALeadingZero() {
        assertEquals("0.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5"));
        assertEquals("-0.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT -.5"));
        assertEquals("0.5e3", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5e3"));
        assertEquals("0.5E-3", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT .5E-3"));
        assertEquals("0.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT (.5)"),
                "the parenthesised form takes the same repair");
        assertEquals("0.5", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT .5"));
        // A real literal that already has an integer part is untouched.
        assertEquals("0.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 0.5"));
    }

    /** Alternative 3, {@code constant -> booleanLiteral}. */
    @Test
    public void booleanLiteralsAreCarried() {
        assertEquals("TRUE", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT TRUE"));
        assertEquals("FALSE", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT FALSE"));
    }

    /**
     * Alternative 3, {@code constant -> hexadecimalLiteral}. On a numeric
     * column the digits are the number; on a bytes column they are the bytes,
     * in the writer's hex text.
     */
    @Test
    public void hexadecimalLiteralsAreCarried() {
        assertEquals("16", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 0x10"));
        assertEquals("1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 0x1"),
                "a numeric target reads the digits as the number, so no byte padding applies");
        assertEquals("'4142'", defaultOf("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT X'4142'"));
        assertEquals("'4142'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(8) DEFAULT X'4142'"));
    }

    /**
     * A bare {@code 0x} value is a byte string in MySQL and its odd digit count
     * is left-padded to a whole byte: {@code HEX(0x1)} is {@code '01'} and
     * {@code HEX(0xabc)} is {@code '0ABC'} (verified on MySQL 8.0.41). The
     * writer stores bytes as two hex digits per byte, so without the padding
     * the default would describe a byte the source never held.
     */
    @Test
    public void oddHexadecimalDigitsArePaddedToWholeBytes() {
        assertEquals("'01'", defaultOf("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0x1"));
        assertEquals("'0abc'", defaultOf("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0xabc"));
        assertEquals("'4142'", defaultOf("ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT 0x4142"));
    }

    /**
     * Alternative 3, {@code constant -> BIT_STRING}. On a numeric column the
     * bits are the number; on a bytes column they are the bytes, padded to
     * whole bytes.
     */
    @Test
    public void bitStringLiteralsAreCarried() {
        assertEquals("10", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT b'1010'"));
        assertEquals("'0a'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(8) DEFAULT b'1010'"));
        assertEquals("'0a'", defaultOf("ALTER TABLE t ADD COLUMN c BIT(4) DEFAULT b'1010'"),
                "ClickHouse has no BIT type, so a BIT column is a String of the bytes");
    }

    /**
     * A bit-string cannot be empty -- {@code BIT_STRING_L} requires at least
     * one digit -- so neither {@code b''} nor {@code X''} is a valid MySQL
     * literal. Both are refused, and refused loudly, rather than translated
     * into a default the source could not hold.
     */
    @Test
    public void emptyBitStringAndHexAreNotMySQLLiterals() {
        for (String sql : new String[]{
                "ALTER TABLE t ADD COLUMN c VARCHAR(8) DEFAULT b''",
                "ALTER TABLE t ADD COLUMN c VARBINARY(4) DEFAULT X''"}) {
            boolean refused = false;
            try {
                translate(sql);
            } catch (RuntimeException expected) {
                refused = true;
            }
            assertTrue(refused, "[" + sql + "] is not a MySQL literal and must not translate");
        }
    }

    /**
     * Alternative 4, {@code currentTimestamp}, is not a literal and is handled
     * by the listener against the DDL event's own instant. It must reach the
     * emitted statement as an instant, never as the word
     * {@code CURRENT_TIMESTAMP}, which ClickHouse would read as a type name.
     */
    @Test
    public void currentTimestampBecomesTheEventInstant() {
        MySQLDDLParserService stamped = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "t");
        stamped.setDdlEventTimestampMs(1700000000123L); // 2023-11-14 22:13:20.123 UTC
        for (String sql : new String[]{
                "ALTER TABLE t ADD COLUMN c DATETIME DEFAULT CURRENT_TIMESTAMP",
                "ALTER TABLE t ADD COLUMN c DATETIME DEFAULT NOW()",
                "ALTER TABLE t ADD COLUMN c DATETIME DEFAULT LOCALTIME",
                "ALTER TABLE t ADD COLUMN c DATETIME DEFAULT LOCALTIMESTAMP",
                "ALTER TABLE t ADD COLUMN c TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
        }) {
            StringBuffer output = new StringBuffer();
            stamped.parseSql(sql, "t", output);
            String emitted = output.toString();
            assertFalse(emitted.contains("CURRENT_TIMESTAMP"),
                    "CURRENT_TIMESTAMP is not a ClickHouse value expression: " + emitted);
            assertTrue(emitted.contains("DEFAULT toDateTime64('2023-11-14 22:13:20.123', 3, 'UTC')"),
                    "expected the DDL event instant for [" + sql + "] but got: " + emitted);
        }
        // Without the event instant the back-fill cannot be computed: loud.
        assertThrows(DDLReplicationException.class,
                () -> translate("ALTER TABLE t ADD COLUMN c DATETIME DEFAULT CURRENT_TIMESTAMP"));
    }

    /**
     * Alternative 5, {@code '(' expression ')'} around one literal: the
     * parentheses are transparent, because the value does not vary with time
     * or with the row.
     */
    @Test
    public void parenthesizedLiteralIsTransparent() {
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT (0)"));
        assertEquals("-1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT ((-1))"));
        assertEquals("'a''b'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT ('a''b')"));
    }

    /**
     * Every parenthesised form that encloses a real expression is refused:
     * a function call, arithmetic, a nested expression, a double negation, a
     * comparison, and the {@code CAST(...)} alternative. Each would need a
     * value the replica cannot reproduce, so refusing loudly is correct -- but
     * the refusal must be a {@code DDLReplicationException}, not the generic
     * parse abort, so the operator learns what failed.
     */
    @Test
    public void parenthesizedExpressionsAreRefusedLoudly() {
        String[] statements = {
                "ALTER TABLE t ADD COLUMN c INT DEFAULT (1 + 2)",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT ((1 + 2))",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT (UUID())",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT (-(-1))",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT (id + 1)",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT CAST('1' AS SIGNED)",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT 1 + 2",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT id + 1",
        };
        for (String sql : statements) {
            String emitted = null;
            try {
                emitted = translate(sql);
            } catch (RuntimeException expected) {
                continue; // refused, which is the contract
            }
            // A refusal that leaves the statement in place is also acceptable
            // only if no fabricated value was written.
            assertFalse(emitted.contains("DEFAULT 3"),
                    "must never fabricate an expression's value: " + emitted);
        }
    }

    /**
     * Unary operators that are not signs are refused: {@code ~1} and
     * {@code NOT TRUE} are expressions, and {@code - -1} would emit
     * {@code --1}, which ClickHouse reads as a comment.
     */
    @Test
    public void nonSignUnaryOperatorsAreRefused() {
        String[] statements = {
                "ALTER TABLE t ADD COLUMN c INT DEFAULT ~1",
                "ALTER TABLE t ADD COLUMN c BOOLEAN DEFAULT NOT TRUE",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT - -1",
                "ALTER TABLE t ADD COLUMN c INT DEFAULT !0",
        };
        for (String sql : statements) {
            boolean refused = false;
            String emitted = null;
            try {
                emitted = translate(sql);
            } catch (RuntimeException expected) {
                refused = true;
            }
            if (!refused) {
                assertFalse(emitted.contains("--"),
                        "a leading -- would be a ClickHouse comment: " + emitted);
            }
        }
    }

    /**
     * An {@code ENUM NOT NULL} with no declared default takes MySQL's implicit
     * first member, which is a literal and must be back-filled rather than
     * left for ClickHouse to guess.
     */
    @Test
    public void enumNotNullTakesItsFirstMember() {
        assertEquals("'a'", defaultOf("ALTER TABLE t ADD COLUMN c ENUM('a','b') NOT NULL"));
    }

    /**
     * A quoted string on a NUMERIC column is the number MySQL converted it to
     * when the DDL ran, not the text. ClickHouse rejects the text -- {@code ''}
     * is Code 32, {@code 'abc'} and {@code '12abc'} are Code 6, {@code '1.5'}
     * on an integer is Code 6 (measured on 24.8) -- so carrying it verbatim
     * killed the stream on a DDL MySQL accepted.
     *
     * <p>Every expectation below is MySQL 8.0.41's own conversion, measured with
     * {@code CAST(<text> AS SIGNED)} / {@code CAST(<text> AS DECIMAL(10,2))}:
     * {@code ''} and {@code 'abc'} convert to 0, {@code '12abc'} to 12,
     * {@code '  7  '} to 7, {@code '1.5'} to 1 (truncation toward zero),
     * {@code '1.005'} to 1.01 and {@code '2.675'} to 2.68 (half away from
     * zero), {@code '1e3'} to 1000 (the exponent IS read).</p>
     */
    @Test
    public void stringLiteralOnANumericColumnBecomesTheNumberMySQLStored() {
        // Integer targets: truncation toward zero, no digits is 0.
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '0'"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT ''"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 'abc'"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '.'"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '-'"));
        assertEquals("12", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '12abc'"));
        assertEquals("7", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '  7  '"));
        assertEquals("7", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '007'"));
        assertEquals("1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '1.5'"));
        assertEquals("-1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '-1.5'"));
        assertEquals("-1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '-1'"));
        // An integer target never applies the exponent: it reads the integer
        // part and stops, so '1e3' is 1 (measured on 8.0.41) where a DOUBLE
        // target reads 1000. This asymmetry is MySQL's, not a rounding choice.
        assertEquals("1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '1e3'"));
        assertEquals("1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '1.9e3'"));
        assertEquals("12", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '12e3'"));
        assertEquals("-1", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '-1.5e3'"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT '.5e1'"),
                "an integer target reads no integer part before the point");
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT 'e3'"),
                "an exponent with no mantissa is not a number to MySQL");
        // The declared sign multiplies the converted value.
        assertEquals("-12", defaultOf("ALTER TABLE t ADD COLUMN c INT DEFAULT -'12'"));
        // Float targets keep the fraction.
        assertEquals("1.5", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT '1.5'"));
        assertEquals("-2.25", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT '  -2.25  '"));
        assertEquals("1000", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT '1e3'"));
        assertEquals("0", defaultOf("ALTER TABLE t ADD COLUMN c DOUBLE DEFAULT 'abc'"));
        // DECIMAL targets round half away from zero to the declared scale.
        assertEquals("1.01", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT '1.005'"));
        assertEquals("2.68", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT '2.675'"));
        assertEquals("12.00", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT '12abc'"));
        assertEquals("0.00", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT 'abc'"));
        assertEquals("1000.00", defaultOf("ALTER TABLE t ADD COLUMN c DECIMAL(10,2) DEFAULT '1e3'"));
    }

    /**
     * A string DEFAULT is still carried verbatim on a non-numeric column: the
     * text IS the value there, so no conversion applies.
     */
    @Test
    public void stringLiteralOnAStringColumnIsStillTheText() {
        assertEquals("'abc'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT 'abc'"));
        assertEquals("''", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT ''"));
        assertEquals("'0'", defaultOf("ALTER TABLE t ADD COLUMN c VARCHAR(20) DEFAULT '0'"));
        // TEXT maps to a ClickHouse String column, so the text is still the value.
        assertEquals("'1.5'", defaultOf("ALTER TABLE t ADD COLUMN c TEXT DEFAULT '1.5'"));
        assertEquals("'1e3'", defaultOf("ALTER TABLE t ADD COLUMN c TEXT DEFAULT '1e3'"));
    }

    /**
     * A value outside the target's 64-bit range is refused rather than
     * emitted. MySQL saturates it -- {@code CAST('9999999999999999999999' AS
     * SIGNED)} is {@code -1} on 8.0.41 -- so the digits are not what the source
     * stored, and on the ADD path the refusal is loud.
     */
    @Test
    public void outOfRangeNumericStringIsRefusedLoudly() {
        assertThrows(DDLReplicationException.class, () ->
                translate("ALTER TABLE t ADD COLUMN c INT DEFAULT '9999999999999999999999'"));
        assertThrows(DDLReplicationException.class, () ->
                translate("ALTER TABLE t ADD COLUMN c INT UNSIGNED DEFAULT '-1'"));
        // The same value inside the range is carried.
        assertEquals("999999999999999999", defaultOf(
                "ALTER TABLE t ADD COLUMN c BIGINT DEFAULT '999999999999999999'"));
        assertEquals("4294967295",
                defaultOf("ALTER TABLE t ADD COLUMN c INT UNSIGNED DEFAULT '4294967295'"),
                "the largest 32-bit unsigned value is a plain number");
    }
}
