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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code DEFAULT} of a {@code CREATE TABLE} column reaches the ClickHouse
 * statement.
 *
 * <p>The defect this defends against was silent, not loud: the CREATE path
 * handled NULL, PRIMARY KEY, AUTO_INCREMENT, UNIQUE and GENERATED but had no
 * {@code DefaultColumnConstraintContext} branch at all, so {@code a INT DEFAULT
 * 7} was emitted as a bare {@code Nullable(Int32)}. No error, no log line --
 * but every row that omitted the column then took ClickHouse's zero value
 * (0) instead of MySQL's (7), and only a value-level comparison would ever
 * show it. Measured on the pre-fix build: all 25 CREATE statements of the
 * expression probe dropped their DEFAULT.</p>
 *
 * <p>The cases are derived from the grammar rather than sampled. The shipped
 * rule is</p>
 *
 * <pre>
 * defaultValue
 *   : NULL_LITERAL
 *   | CAST '(' expression AS convertedDataType ')'
 *   | unaryOperator? constant
 *   | currentTimestamp (ON UPDATE currentTimestamp)?
 *   | '(' expression ')'
 *   | (LASTVAL | NEXTVAL) '(' fullId ')'
 *   | '(' (PREVIOUS | NEXT) VALUE FOR fullId ')'
 *   | expression
 * </pre>
 *
 * <p>The MySQL manual narrows the last alternative: "With one exception,
 * enclose expression default values within parentheses" (8.0.13+), the
 * exception being bare {@code CURRENT_TIMESTAMP} on a temporal column. Those
 * unparenthesised inputs are covered by their own test below, which pins that
 * they stay unparseable -- MySQL cannot emit them, so accepting them would be
 * inventing a dialect.</p>
 */
public class CreateTableDefaultEmissionTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString().trim();
    }

    /** One column-declaration body from the emitted CREATE TABLE. */
    private static String columnDeclaration(String emitted, String column) {
        int open = emitted.indexOf('(');
        int close = emitted.lastIndexOf(')');
        String body = emitted.substring(open + 1, close);
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            char c = i < body.length() ? body.charAt(i) : ',';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String entry = body.substring(start, i).trim();
                if (entry.startsWith(column + " ") || entry.startsWith(column + "(")) {
                    return entry;
                }
                start = i + 1;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // The literal default reaches ClickHouse.
    // ------------------------------------------------------------------

    /**
     * Every literal form of the grammar, through the CREATE path, must carry
     * its value. Before the fix every one of these emitted no DEFAULT.
     */
    @Test
    public void everyLiteralDefaultIsCarried() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("INT DEFAULT 7", "DEFAULT 7");
        expected.put("INT DEFAULT -1", "DEFAULT -1");
        expected.put("VARCHAR(10) DEFAULT 'z'", "DEFAULT 'z'");
        expected.put("DECIMAL(10,2) DEFAULT 1.5", "DEFAULT 1.5");
        expected.put("DECIMAL(10,2) DEFAULT -1.5", "DEFAULT -1.5");
        expected.put("DOUBLE DEFAULT 1.5", "DEFAULT 1.5");
        expected.put("INT DEFAULT 0x1F", "DEFAULT 31");
        expected.put("INT DEFAULT 0b1010", "DEFAULT 10");
        // An exponent literal is passed through as written; ClickHouse
        // evaluates it, so the stored value is 1000.
        expected.put("INT DEFAULT 1e3", "DEFAULT 1e3");

        for (Map.Entry<String, String> each : expected.entrySet()) {
            String emitted = translate("CREATE TABLE t (id INT PRIMARY KEY, a " + each.getKey() + ")");
            String column = columnDeclaration(emitted, "a");
            assertTrue(column.contains(each.getValue()),
                    "DEFAULT of [" + each.getKey() + "] must be carried, got column [" + column + "]");
        }
    }

    /**
     * {@code DEFAULT NULL} on a nullable column is implied by the type and is
     * NOT emitted (Spec 06.04 §3.2.3).
     *
     * <p>It changes no stored value -- an insert that omits the column stores
     * NULL either way -- but ClickHouse validates the default expression on
     * every later ALTER, so carrying it turns an ordinary
     * {@code MODIFY COLUMN a String} into Code: 70 CANNOT_CONVERT_TYPE
     * ("Cannot convert NULL to a non-nullable type ... default expression and
     * column type are incompatible"). MySQL renders a nullable column with no
     * default as {@code DEFAULT NULL} in SHOW CREATE TABLE, so the shape
     * arrives constantly.</p>
     */
    @Test
    public void explicitNullDefaultOnANullableColumnIsNotEmitted() {
        assertEquals("a Nullable(Int32)",
                columnDeclaration(translate("CREATE TABLE t (id INT PRIMARY KEY, a INT DEFAULT NULL)"), "a"));
    }

    /** A NOT NULL column's {@code DEFAULT NULL} is not implied by anything, so it is kept. */
    @Test
    public void nullDefaultOnANotNullColumnIsStillCarried() {
        assertEquals("a Int32 NOT NULL DEFAULT NULL",
                columnDeclaration(translate("CREATE TABLE t (id INT PRIMARY KEY, a INT NOT NULL DEFAULT NULL)"), "a"));
    }

    /** A NOT NULL column keeps its DEFAULT; the two clauses coexist. */
    @Test
    public void notNullColumnKeepsItsDefault() {
        String column = columnDeclaration(
                translate("CREATE TABLE t (id INT PRIMARY KEY, a INT NOT NULL DEFAULT 3)"), "a");
        assertTrue(column.contains("NOT NULL"), "NOT NULL must survive, got: " + column);
        assertTrue(column.contains("DEFAULT 3"), "DEFAULT must survive, got: " + column);
    }

    /**
     * A parenthesised literal is the literal, not an expression: MySQL reads
     * {@code DEFAULT (0)} and {@code DEFAULT 0} as the same value.
     */
    @Test
    public void parenthesizedLiteralIsUnwrapped() {
        assertEquals("DEFAULT 0",
                columnBody("CREATE TABLE t (id INT PRIMARY KEY, a INT DEFAULT (0))"));
        assertEquals("DEFAULT 'x'",
                columnBody("CREATE TABLE t (id INT PRIMARY KEY, a VARCHAR(5) DEFAULT ('x'))"));
    }

    private static String columnBody(String sql) {
        String column = columnDeclaration(translate(sql), "a");
        int at = column.indexOf("DEFAULT");
        return at < 0 ? "" : column.substring(at).trim();
    }

    // ------------------------------------------------------------------
    // The two implicit defaults.
    // ------------------------------------------------------------------

    /**
     * {@code CURRENT_TIMESTAMP} is the grammar's one unparenthesised
     * non-literal, and ClickHouse derives the same value itself. A MySQL
     * {@code DATETIME} replicates as {@code DateTime64}, whose scale is 3, so
     * the derive is {@code now64(3)} and not the bare {@code now()}.
     */
    @Test
    public void currentTimestampDefaultIsCarried() {
        assertTrue(columnDeclaration(
                        translate("CREATE TABLE t (id INT PRIMARY KEY, a DATETIME DEFAULT CURRENT_TIMESTAMP)"), "a")
                        .contains("DEFAULT now64(3)"),
                "CURRENT_TIMESTAMP must become ClickHouse's own derive-on-insert expression");
    }

    /** The fractional scale is carried so the column's precision is preserved. */
    @Test
    public void currentTimestampDefaultFollowsTheColumnScale() {
        assertTrue(columnDeclaration(
                        translate("CREATE TABLE t (id INT PRIMARY KEY, a DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))"), "a")
                        .contains("DEFAULT now64(3)"),
                "a scale-3 column must derive at scale 3");
    }

    /**
     * {@code ENUM ... NOT NULL} without a DEFAULT has MySQL's implicit first
     * member as its default -- on CREATE too, not only on ADD COLUMN. Emitted
     * without it, ClickHouse's zero value for the String column is {@code ''},
     * which is not a member of the ENUM at all.
     */
    @Test
    public void enumNotNullWithoutDefaultGetsTheFirstMember() {
        assertTrue(columnDeclaration(
                        translate("CREATE TABLE t (id INT PRIMARY KEY, a ENUM('p','q') NOT NULL)"), "a")
                        .contains("DEFAULT 'p'"),
                "MySQL's implicit ENUM default is the first member");
    }

    /** A nullable ENUM has no implicit member default, so none is invented. */
    @Test
    public void nullableEnumGetsNoImplicitDefault() {
        assertFalse(columnDeclaration(
                        translate("CREATE TABLE t (id INT PRIMARY KEY, a ENUM('p','q'))"), "a")
                        .contains("DEFAULT"),
                "a nullable ENUM defaults to NULL, not to the first member");
    }

    // ------------------------------------------------------------------
    // Expression defaults: dropped, never guessed.
    // ------------------------------------------------------------------

    /**
     * MySQL's parenthesised expression defaults are per-row computations
     * ClickHouse cannot reproduce faithfully. A CREATE back-fills no row, so
     * dropping one diverges nothing: every replicated row carries the value
     * MySQL computed. It must NOT be silently turned into a wrong constant.
     */
    @Test
    public void expressionDefaultsAreDroppedNotGuessed() {
        String[] expressions = {
                "a INT DEFAULT (1 + 2)",
                "a INT DEFAULT (1 & 3)",
                "a INT DEFAULT ((1 + 2) * 3)",
                "a VARCHAR(10) DEFAULT (CONCAT('a','b'))",
                "a INT DEFAULT (ABS(-7))",
                "a INT DEFAULT (CAST('5' AS SIGNED))",
                "a JSON DEFAULT (JSON_OBJECT('a', 1))",
        };
        for (String column : expressions) {
            String emitted = translate("CREATE TABLE t (id INT PRIMARY KEY, " + column + ")");
            assertFalse(emitted.contains("DEFAULT"),
                    "an expression default must be dropped, never guessed: [" + column + "] -> " + emitted);
        }
    }

    /**
     * The unparenthesised operator forms are NOT valid MySQL: the manual
     * requires expression defaults in parentheses. The connector rejects them,
     * and that is correct -- it must not accept a dialect the source cannot
     * produce. Pinned so a later "fix" cannot quietly start accepting them.
     */
    @Test
    public void unparenthesizedExpressionDefaultsStayRejected() {
        String[] invalid = {
                "a INT DEFAULT 1 + 2",
                "a INT DEFAULT (1 + 2) * 3",
                "a INT DEFAULT -(5)",
                "a INT DEFAULT 1 & 3",
                "a INT DEFAULT 1 | 2",
                "a INT DEFAULT 1 ^ 3",
                "a INT DEFAULT 1 << 2",
                "a INT DEFAULT 8 >> 1",
        };
        for (String column : invalid) {
            try {
                String emitted = translate("CREATE TABLE t (id INT PRIMARY KEY, " + column + ")");
                assertFalse(emitted.contains("DEFAULT"),
                        "invalid MySQL must never produce a DEFAULT: [" + column + "]");
            } catch (RuntimeException expected) {
                // The parse rejection is the contract.
            }
        }
    }

    // ------------------------------------------------------------------
    // No regression of the neighbouring clauses.
    // ------------------------------------------------------------------

    /** A generated column still emits its expression, and no DEFAULT of its own. */
    @Test
    public void generatedColumnIsUnaffected() {
        assertEquals("a Nullable(Int32) DEFAULT id*2",
                columnDeclaration(translate(
                        "CREATE TABLE t (id INT PRIMARY KEY, a INT GENERATED ALWAYS AS (id * 2) VIRTUAL)"), "a"));
    }

    /** A column with neither clause gains no DEFAULT and stays Nullable. */
    @Test
    public void plainColumnGainsNothing() {
        assertEquals("a Nullable(Int32)",
                columnDeclaration(translate("CREATE TABLE t (id INT PRIMARY KEY, a INT)"), "a"));
    }

    /** The DEFAULT is part of the column, before the separating comma. */
    @Test
    public void defaultIsInsideTheColumnDeclaration() {
        String emitted = translate("CREATE TABLE t (id INT PRIMARY KEY, a INT DEFAULT 7, b INT)");
        assertEquals("a Nullable(Int32) DEFAULT 7", columnDeclaration(emitted, "a"));
        assertEquals("b Nullable(Int32)", columnDeclaration(emitted, "b"));
    }
}
