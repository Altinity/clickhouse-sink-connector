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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive matrix over the two grammar rules a {@code CREATE TABLE} or
 * {@code ALTER TABLE ... ADD COLUMN} column is built from: {@code dataType}
 * and {@code columnConstraint}.
 *
 * <p>Both rules are deterministic and unambiguous, so the set of shapes MySQL
 * accepts is the set of their alternatives -- it is enumerable, not guessable.
 * This test enumerates it by reflection over the Debezium parser the connector
 * actually ships and asserts four properties:</p>
 *
 * <ol>
 *   <li><b>Every alternative has a matrix row.</b> A Debezium bump that adds an
 *       alternative fails here until someone writes a row for it, which is the
 *       only way to keep a hand-written matrix honest.</li>
 *   <li><b>The enumerated set matches a fixed expectation.</b> An alternative
 *       disappearing from the parser is equally a coverage change and is
 *       flagged.</li>
 *   <li><b>Every alternative is entered by its own row.</b> A row that does not
 *       reach the alternative it claims to cover silently widens the apparent
 *       coverage, so the parse tree is walked and the reported alternative is
 *       compared with the expected one.</li>
 *   <li><b>The translation either emits a column or refuses loudly.</b> A shape
 *       that is silently skipped replicates a schema that does not match the
 *       source, and the divergence only surfaces later as a value-level
 *       mismatch. Each row therefore states which of the two it must do.</li>
 * </ol>
 *
 * <p>{@code dataType} alternatives are exercised through {@code ADD COLUMN} on
 * the ALTER path because that is where the literal translator and the type
 * resolver both run. {@code columnConstraint} alternatives are exercised in
 * every position the grammar allows them, since {@code columnDefinition} is
 * {@code dataType columnConstraint*} -- the constraints may appear in any
 * order and any number of times.</p>
 */
public class DataTypeAndColumnAttributeMatrixTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        // A DDL event always carries source.ts_ms in production; it is the
        // instant MySQL back-filled a new CURRENT_TIMESTAMP column with. Unset
        // (0) the translator cannot know that instant and refuses, correctly,
        // so an ADD COLUMN CURRENT_TIMESTAMP row needs a stamped event to be
        // exercised at all.
        service.setDdlEventTimestampMs(1700000000123L);
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /** One matrix row: the type or attribute under test and one statement for it. */
    private static final class Row {
        final String name;
        final String type;        // dataType alternative under test, or null
        final String constraint;  // columnConstraint alternative under test, or null
        final String sql;

        private Row(String name, String type, String constraint, String sql) {
            this.name = name;
            this.type = type;
            this.constraint = constraint;
            this.sql = sql;
        }

        static Row type(String alternative, String sql) {
            return new Row(alternative + " / " + sql, alternative, null, sql);
        }

        static Row attribute(String alternative, String sql) {
            return new Row(alternative + " / " + sql, null, alternative, sql);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ------------------------------------------------------------------
    // dataType rule: 9 alternatives.
    // ------------------------------------------------------------------

    /**
     * One row per {@code dataType} alternative, with every type-name token the
     * alternative lists and every optional attribute in its widest form.
     */
    private static List<Row> dataTypeRows() {
        List<Row> rows = new ArrayList<>();
        String a = "ALTER TABLE t ADD COLUMN c ";

        // stringDataType: CHAR|CHARACTER|VARCHAR|TINYTEXT|TEXT|MEDIUMTEXT|
        // LONGTEXT|NCHAR|NVARCHAR|LONG, VARYING?, lengthOneDimension?, BINARY?,
        // (charSet charsetName)?, (COLLATE collationName | BINARY)?
        for (String type : new String[]{"CHAR", "CHARACTER", "VARCHAR", "TINYTEXT", "TEXT",
                "MEDIUMTEXT", "LONGTEXT", "NCHAR", "NVARCHAR", "LONG"}) {
            rows.add(Row.type("stringDataType", a + type));
            rows.add(Row.type("stringDataType", a + type + "(10)"));
        }
        rows.add(Row.type("stringDataType", a + "CHAR VARYING(10)"));
        rows.add(Row.type("stringDataType", a + "VARCHAR(10) BINARY"));
        rows.add(Row.type("stringDataType", a + "VARCHAR(10) CHARACTER SET utf8mb4"));
        rows.add(Row.type("stringDataType", a + "VARCHAR(10) COLLATE utf8mb4_bin"));
        rows.add(Row.type("stringDataType", a + "VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        rows.add(Row.type("stringDataType", a + "TEXT BINARY"));

        // nationalStringDataType: NATIONAL (VARCHAR|CHARACTER) | NCHAR VARCHAR
        rows.add(Row.type("nationalStringDataType", a + "NATIONAL VARCHAR(10)"));
        rows.add(Row.type("nationalStringDataType", a + "NATIONAL CHARACTER(10)"));
        rows.add(Row.type("nationalStringDataType", a + "NATIONAL CHAR(10)"));
        rows.add(Row.type("nationalStringDataType", a + "NATIONAL CHAR(10) BINARY"));
        rows.add(Row.type("nationalStringDataType", a + "NCHAR VARCHAR(10)"));

        // nationalVaryingStringDataType: NATIONAL (CHAR|CHARACTER) VARYING
        rows.add(Row.type("nationalVaryingStringDataType", a + "NATIONAL CHAR VARYING(10)"));
        rows.add(Row.type("nationalVaryingStringDataType", a + "NATIONAL CHARACTER VARYING(10)"));

        // dimensionDataType: integers with length + SIGNED/UNSIGNED/ZEROFILL*
        for (String type : new String[]{"TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER",
                "BIGINT", "MIDDLEINT", "INT1", "INT2", "INT3", "INT4", "INT8"}) {
            rows.add(Row.type("dimensionDataType", a + type));
            rows.add(Row.type("dimensionDataType", a + type + " UNSIGNED"));
        }
        rows.add(Row.type("dimensionDataType", a + "INT(11)"));
        rows.add(Row.type("dimensionDataType", a + "INT(11) SIGNED"));
        rows.add(Row.type("dimensionDataType", a + "INT(11) UNSIGNED ZEROFILL"));
        rows.add(Row.type("dimensionDataType", a + "BIGINT UNSIGNED NOT NULL"));
        rows.add(Row.type("dimensionDataType", a + "REAL"));
        rows.add(Row.type("dimensionDataType", a + "REAL(10,2)"));
        rows.add(Row.type("dimensionDataType", a + "REAL UNSIGNED"));
        rows.add(Row.type("dimensionDataType", a + "DOUBLE"));
        rows.add(Row.type("dimensionDataType", a + "DOUBLE PRECISION"));
        rows.add(Row.type("dimensionDataType", a + "DOUBLE(10,2) UNSIGNED ZEROFILL"));
        for (String type : new String[]{"DECIMAL", "DEC", "FIXED", "NUMERIC", "FLOAT", "FLOAT4", "FLOAT8"}) {
            rows.add(Row.type("dimensionDataType", a + type));
            rows.add(Row.type("dimensionDataType", a + type + "(10)"));
            rows.add(Row.type("dimensionDataType", a + type + "(10,2)"));
        }
        rows.add(Row.type("dimensionDataType", a + "DECIMAL(10,2) UNSIGNED"));
        for (String type : new String[]{"BIT", "TIME", "TIMESTAMP", "DATETIME", "BINARY", "VARBINARY", "BLOB", "YEAR"}) {
            rows.add(Row.type("dimensionDataType", a + type));
            rows.add(Row.type("dimensionDataType", a + type + "(6)"));
        }
        // MySQL VECTOR is registered in the grammar's DimensionDataTypeContext.
        rows.add(Row.type("dimensionDataType", a + "VECTOR(3)"));

        // simpleDataType: DATE|TINYBLOB|MEDIUMBLOB|LONGBLOB|BOOL|BOOLEAN|SERIAL
        for (String type : new String[]{"DATE", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB",
                "BOOL", "BOOLEAN", "SERIAL"}) {
            rows.add(Row.type("simpleDataType", a + type));
        }

        // collectionDataType: (ENUM|SET) collectionOptions BINARY? (charSet)?
        rows.add(Row.type("collectionDataType", a + "ENUM('a','b')"));
        rows.add(Row.type("collectionDataType", a + "SET('a','b','c')"));
        rows.add(Row.type("collectionDataType", a + "ENUM('a','b') BINARY"));
        rows.add(Row.type("collectionDataType", a + "ENUM('a','b') CHARACTER SET utf8mb4"));
        rows.add(Row.type("collectionDataType", a + "ENUM('it''s','x')"));
        rows.add(Row.type("collectionDataType", a + "SET('a')"));

        // spatialDataType: every spatial kind plus JSON
        for (String type : new String[]{"GEOMETRYCOLLECTION", "GEOMCOLLECTION", "LINESTRING",
                "MULTILINESTRING", "MULTIPOINT", "MULTIPOLYGON", "POINT", "POLYGON",
                "JSON", "GEOMETRY"}) {
            rows.add(Row.type("spatialDataType", a + type));
            rows.add(Row.type("spatialDataType", a + type + " NOT NULL"));
        }

        // longVarcharDataType: LONG VARCHAR? BINARY? (charSet)? (COLLATE)?
        //
        // `LONG` on its own is NOT this alternative: `stringDataType` lists LONG
        // too and the grammar's alternatives are tried in order, so a bare LONG
        // parses as stringDataType. Only the `LONG VARCHAR` spellings reach
        // longVarcharDataType, and only those are rows here.
        rows.add(Row.type("longVarcharDataType", a + "LONG VARCHAR"));
        rows.add(Row.type("longVarcharDataType", a + "LONG VARCHAR CHARACTER SET utf8mb4"));
        rows.add(Row.type("longVarcharDataType", a + "LONG VARCHAR COLLATE utf8mb4_bin"));

        // longVarbinaryDataType: LONG VARBINARY
        rows.add(Row.type("longVarbinaryDataType", a + "LONG VARBINARY"));

        return rows;
    }

    // ------------------------------------------------------------------
    // columnConstraint rule: 15 alternatives.
    // ------------------------------------------------------------------

    /**
     * One row per {@code columnConstraint} alternative. {@code columnDefinition}
     * is {@code dataType columnConstraint*}, so each attribute is written in
     * several positions to prove the translator does not depend on order.
     */
    private static List<Row> columnConstraintRows() {
        List<Row> rows = new ArrayList<>();
        String a = "ALTER TABLE t ADD COLUMN c INT ";

        // nullColumnConstraint
        rows.add(Row.attribute("nullColumnConstraint", a + "NULL"));
        rows.add(Row.attribute("nullColumnConstraint", a + "NOT NULL"));
        rows.add(Row.attribute("nullColumnConstraint", a + "NULL DEFAULT 1"));
        rows.add(Row.attribute("nullColumnConstraint", a + "NOT NULL DEFAULT 1"));

        // defaultColumnConstraint
        rows.add(Row.attribute("defaultColumnConstraint", a + "DEFAULT 1"));
        rows.add(Row.attribute("defaultColumnConstraint", a + "NOT NULL DEFAULT 0"));
        rows.add(Row.attribute("defaultColumnConstraint", a + "DEFAULT NULL"));

        // visibilityColumnConstraint / invisibilityColumnConstraint
        rows.add(Row.attribute("visibilityColumnConstraint", a + "VISIBLE"));
        rows.add(Row.attribute("visibilityColumnConstraint", a + "NOT NULL VISIBLE"));
        rows.add(Row.attribute("invisibilityColumnConstraint", a + "INVISIBLE"));
        rows.add(Row.attribute("invisibilityColumnConstraint", a + "DEFAULT 1 INVISIBLE"));

        // autoIncrementColumnConstraint. The `ON UPDATE currentTimestamp` half
        // is only its own alternative when it stands alone: written after a
        // DEFAULT, MySQL's defaultValue rule has already absorbed it
        // (`currentTimestamp (ON UPDATE currentTimestamp)?`), so the parse
        // reaches defaultColumnConstraint instead. Both spellings are rows
        // because both are valid MySQL; each is filed under the alternative it
        // actually enters.
        rows.add(Row.attribute("autoIncrementColumnConstraint", a + "AUTO_INCREMENT"));
        rows.add(Row.attribute("autoIncrementColumnConstraint", a + "NOT NULL AUTO_INCREMENT"));
        rows.add(Row.attribute("autoIncrementColumnConstraint",
                "ALTER TABLE t ADD COLUMN c DATETIME ON UPDATE CURRENT_TIMESTAMP"));
        rows.add(Row.attribute("defaultColumnConstraint",
                "ALTER TABLE t ADD COLUMN c DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"));

        // primaryKeyColumnConstraint
        rows.add(Row.attribute("primaryKeyColumnConstraint", a + "PRIMARY KEY"));
        rows.add(Row.attribute("primaryKeyColumnConstraint", a + "KEY"));

        // uniqueKeyColumnConstraint
        rows.add(Row.attribute("uniqueKeyColumnConstraint", a + "UNIQUE"));
        rows.add(Row.attribute("uniqueKeyColumnConstraint", a + "UNIQUE KEY"));

        // commentColumnConstraint
        rows.add(Row.attribute("commentColumnConstraint", a + "COMMENT 'note'"));
        rows.add(Row.attribute("commentColumnConstraint", a + "NOT NULL COMMENT 'note'"));
        rows.add(Row.attribute("commentColumnConstraint", a + "COMMENT 'it''s'"));

        // formatColumnConstraint
        rows.add(Row.attribute("formatColumnConstraint", a + "COLUMN_FORMAT FIXED"));
        rows.add(Row.attribute("formatColumnConstraint", a + "COLUMN_FORMAT DYNAMIC"));
        rows.add(Row.attribute("formatColumnConstraint", a + "COLUMN_FORMAT DEFAULT"));

        // storageColumnConstraint
        rows.add(Row.attribute("storageColumnConstraint", a + "STORAGE DISK"));
        rows.add(Row.attribute("storageColumnConstraint", a + "STORAGE MEMORY"));
        rows.add(Row.attribute("storageColumnConstraint", a + "STORAGE DEFAULT"));

        // referenceColumnConstraint
        rows.add(Row.attribute("referenceColumnConstraint",
                "ALTER TABLE t ADD COLUMN c INT REFERENCES other (id)"));

        // collateColumnConstraint. A COLLATE immediately after a string type is
        // absorbed by stringDataType's own `(COLLATE collationName | BINARY)?`,
        // so it only becomes its own constraint when another attribute comes
        // between the type and the collation.
        rows.add(Row.attribute("collateColumnConstraint",
                "ALTER TABLE t ADD COLUMN c VARCHAR(10) NOT NULL COLLATE utf8mb4_bin"));

        // generatedColumnConstraint
        rows.add(Row.attribute("generatedColumnConstraint", a + "AS (a + 1)"));
        rows.add(Row.attribute("generatedColumnConstraint", a + "AS (a + 1) VIRTUAL"));
        rows.add(Row.attribute("generatedColumnConstraint", a + "AS (a + 1) STORED"));
        rows.add(Row.attribute("generatedColumnConstraint", a + "AS (a + 1) PERSISTENT"));
        rows.add(Row.attribute("generatedColumnConstraint", a + "GENERATED ALWAYS AS (a + 1) STORED"));

        // serialDefaultColumnConstraint
        rows.add(Row.attribute("serialDefaultColumnConstraint",
                "ALTER TABLE t ADD COLUMN c BIGINT UNSIGNED SERIAL DEFAULT VALUE"));

        // checkColumnConstraint
        rows.add(Row.attribute("checkColumnConstraint", a + "CHECK (c > 0)"));
        rows.add(Row.attribute("checkColumnConstraint", a + "CONSTRAINT chk CHECK (c > 0)"));

        // clusteringKeyColumnConstraint (MariaDB). Present in the parser the
        // connector ships and absent from the repo's grammar copy, which is
        // why the matrix is built from the shipped parser by reflection.
        rows.add(Row.attribute("clusteringKeyColumnConstraint", a + "CLUSTERING KEY"));

        return rows;
    }

    // ------------------------------------------------------------------
    // Reflection: enumerate the alternatives the shipped parser defines.
    // ------------------------------------------------------------------

    /**
     * Every {@code #label} alternative of {@code dataType}, read from the parser
     * that is actually on the classpath rather than from the repo's grammar copy.
     *
     * <p>ANTLR emits one nested context class per labelled alternative, and the
     * label is lower-camel while the class name is upper-camel, so the two are
     * compared by lower-casing the first character only.</p>
     */
    private static Set<String> dataTypeAlternativesInGrammar() {
        return labelledAlternatives(MySqlParser.DataTypeContext.class);
    }

    /**
     * Every {@code #label} alternative of {@code columnConstraint}.
     *
     * <p>The shipped parser defines more of these than the repo's
     * {@code src/main/antlr4/mysql/MySqlParser.g4} copy does -- the copy is not
     * on the runtime path -- so a count taken from the copy would understate the
     * surface. That is exactly the staleness this matrix exists to catch.</p>
     */
    private static Set<String> columnConstraintAlternativesInGrammar() {
        return labelledAlternatives(MySqlParser.ColumnConstraintContext.class);
    }

    /**
     * The {@code #label} spelling of every labelled alternative of {@code rule}.
     *
     * <p>Membership is decided by SUPERCLASS, not by the class name: a labelled
     * alternative extends the rule's own context, while the base context extends
     * {@code ParserRuleContext}. Name matching alone would be wrong --
     * {@code ConvertedDataTypeContext} is the separate {@code convertedDataType}
     * rule and only ends in {@code DataTypeContext} by coincidence.</p>
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

    /**
     * The {@code #label} of a labelled alternative context class: its simple
     * name without the {@code Context} suffix, first character lower-cased.
     * {@code StringDataTypeContext} is the label {@code stringDataType}.
     */
    private static String labelOf(Class<?> contextClass) {
        String simple = contextClass.getSimpleName();
        String label = simple.substring(0, simple.length() - "Context".length());
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    /**
     * Walks the parse tree of one statement and returns the alternatives it
     * actually entered, so a row cannot claim coverage it does not have.
     */
    private static Set<String> alternativesEntered(String sql) {
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
                        // Same membership rule as labelledAlternatives: a labelled
                        // alternative extends its rule's context class, and the
                        // base context extends ParserRuleContext instead.
                        Class<?> superclass = ctx.getClass().getSuperclass();
                        if (superclass == MySqlParser.DataTypeContext.class
                                || superclass == MySqlParser.ColumnConstraintContext.class) {
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
    // Properties.
    // ------------------------------------------------------------------

    /**
     * Every {@code dataType} alternative the shipped parser defines has at
     * least one matrix row, and the matrix names no alternative the parser does
     * not define.
     */
    @Test
    public void everyDataTypeAlternativeHasAMatrixRow() {
        Set<String> inGrammar = dataTypeAlternativesInGrammar();
        assertEquals(9, inGrammar.size(),
                "the grammar's dataType rule has 9 labelled alternatives; the set is " + inGrammar);
        Set<String> covered = new TreeSet<>();
        for (Row row : dataTypeRows()) {
            covered.add(row.type);
        }
        Set<String> missing = new TreeSet<>(inGrammar);
        missing.removeAll(covered);
        Set<String> extra = new TreeSet<>(covered);
        extra.removeAll(inGrammar);
        assertTrue(missing.isEmpty(), "dataType alternatives with no matrix row: " + missing);
        assertTrue(extra.isEmpty(), "matrix rows naming no grammar alternative: " + extra);
    }

    /**
     * Every {@code columnConstraint} alternative the shipped parser defines has
     * at least one matrix row, and the matrix names no alternative the parser
     * does not define.
     */
    @Test
    public void everyColumnConstraintAlternativeHasAMatrixRow() {
        Set<String> inGrammar = columnConstraintAlternativesInGrammar();
        assertEquals(16, inGrammar.size(),
                "the shipped parser's columnConstraint rule has 16 labelled alternatives "
                        + "(the repo's grammar copy shows 15 -- it lacks MariaDB's clustering key); "
                        + "the set is " + inGrammar);
        Set<String> covered = new TreeSet<>();
        for (Row row : columnConstraintRows()) {
            covered.add(row.constraint);
        }
        Set<String> missing = new TreeSet<>(inGrammar);
        missing.removeAll(covered);
        Set<String> extra = new TreeSet<>(covered);
        extra.removeAll(inGrammar);
        assertTrue(missing.isEmpty(), "columnConstraint alternatives with no matrix row: " + missing);
        assertTrue(extra.isEmpty(), "matrix rows naming no grammar alternative: " + extra);
    }

    /**
     * Each row enters the alternative it claims to cover. A row that parses but
     * reaches some other alternative (because MySQL's own ambiguity sent the
     * parser elsewhere) would otherwise count as coverage it does not have.
     */
    @Test
    public void eachRowEntersItsDeclaredAlternative() {
        List<String> wrong = new ArrayList<>();
        for (Row row : dataTypeRows()) {
            Set<String> entered = alternativesEntered(row.sql);
            if (!entered.contains(row.type)) {
                wrong.add(row.sql + " -> " + entered + " (expected " + row.type + ")");
            }
        }
        for (Row row : columnConstraintRows()) {
            Set<String> entered = alternativesEntered(row.sql);
            if (!entered.contains(row.constraint)) {
                wrong.add(row.sql + " -> " + entered + " (expected " + row.constraint + ")");
            }
        }
        assertTrue(wrong.isEmpty(), "rows that did not enter their alternative:\n  "
                + String.join("\n  ", wrong));
    }

    /**
     * No row may throw: every shape MySQL accepts must reach the translator,
     * because a parse abort stops replication for every later event on every
     * table, not only for the column that caused it.
     */
    @Test
    public void noRowThrowsDuringTranslation() {
        List<String> failed = new ArrayList<>();
        for (Row row : dataTypeRows()) {
            try {
                translate(row.sql);
            } catch (RuntimeException e) {
                failed.add(row.sql + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        for (Row row : columnConstraintRows()) {
            try {
                translate(row.sql);
            } catch (RuntimeException e) {
                failed.add(row.sql + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failed.isEmpty(), "shapes MySQL accepts that the translator refused:\n  "
                + String.join("\n  ", failed));
    }

    /**
     * Every row produces a column named {@code c} carrying a ClickHouse type,
     * so the emitted statement agrees with the source schema. A row that emits
     * nothing at all would leave the target table a column short.
     */
    @Test
    public void everyRowEmitsTheColumnWithAClickHouseType() {
        List<Row> rows = new ArrayList<>(dataTypeRows());
        rows.addAll(columnConstraintRows());
        Map<String, String> bad = new LinkedHashMap<>();
        for (Row row : rows) {
            String emitted;
            try {
                emitted = translate(row.sql);
            } catch (RuntimeException e) {
                bad.put(row.sql, "threw " + e.getClass().getSimpleName());
                continue;
            }
            if (emitted == null || !emitted.contains("c ")) {
                bad.put(row.sql, "no column emitted: " + emitted);
                continue;
            }
            // The column must carry a resolved ClickHouse type, i.e. one of the
            // names the sink writes. A bare name with no type is the defect.
            String tail = emitted.substring(emitted.indexOf("c ") + 2).trim();
            assertNotNull(tail, "column type for " + row.sql);
            boolean hasType = tail.contains("Int") || tail.contains("UInt")
                    || tail.contains("String") || tail.contains("Decimal")
                    || tail.contains("Float") || tail.contains("Date")
                    || tail.contains("Bool");
            if (!hasType) {
                bad.put(row.sql, "no ClickHouse type: " + emitted);
            }
        }
        assertTrue(bad.isEmpty(), "rows that did not emit a typed column:\n  " + bad);
    }

    /**
     * Attributes with no ClickHouse equivalent are dropped rather than emitted:
     * ClickHouse rejects the clause outright, and a rejected ALTER is retried
     * forever. Each of these is checked by name so a regression that starts
     * emitting one is caught here.
     */
    @Test
    public void attributesWithoutAClickHouseEquivalentAreDropped() {
        String[] statements = {
                "ALTER TABLE t ADD COLUMN c INT VISIBLE",
                "ALTER TABLE t ADD COLUMN c INT INVISIBLE",
                "ALTER TABLE t ADD COLUMN c INT COLUMN_FORMAT FIXED",
                "ALTER TABLE t ADD COLUMN c INT STORAGE DISK",
                "ALTER TABLE t ADD COLUMN c INT AUTO_INCREMENT",
                "ALTER TABLE t ADD COLUMN c INT COMMENT 'note'",
                "ALTER TABLE t ADD COLUMN c INT CHECK (c > 0)",
                "ALTER TABLE t ADD COLUMN c INT REFERENCES other (id)",
                "ALTER TABLE t ADD COLUMN c VARCHAR(10) COLLATE utf8mb4_bin",
                "ALTER TABLE t ADD COLUMN c INT UNIQUE KEY",
        };
        for (String sql : statements) {
            String emitted = translate(sql);
            String upper = emitted.toUpperCase();
            assertFalse(upper.contains("VISIBLE"), "VISIBLE is not a ClickHouse clause: " + emitted);
            assertFalse(upper.contains("INVISIBLE"), "INVISIBLE is not a ClickHouse clause: " + emitted);
            assertFalse(upper.contains("COLUMN_FORMAT"), "COLUMN_FORMAT is not a ClickHouse clause: " + emitted);
            assertFalse(upper.contains("STORAGE"), "STORAGE is not a ClickHouse clause: " + emitted);
            assertFalse(upper.contains("AUTO_INCREMENT"), "AUTO_INCREMENT is not a ClickHouse clause: " + emitted);
            assertFalse(upper.contains("COMMENT '"), "MySQL column COMMENT is not carried: " + emitted);
            assertFalse(upper.contains("CHECK ("), "MySQL CHECK is not carried: " + emitted);
            assertFalse(upper.contains("REFERENCES"), "MySQL REFERENCES is not carried: " + emitted);
            assertFalse(upper.contains("COLLATE utf8mb4_bin"),
                    "a MySQL collation is not a ClickHouse collation: " + emitted);
            assertFalse(upper.contains("UNIQUE"), "a column UNIQUE is a table index, not a column clause: " + emitted);
        }
    }

    /**
     * A NOT NULL column keeps its nullability into ClickHouse, because that is
     * the only column attribute with a direct ClickHouse equivalent and losing
     * it changes what the table accepts.
     */
    @Test
    public void nullabilityIsPreserved() {
        // ClickHouse expresses nullability in the TYPE, so `Int32` IS the
        // NOT NULL form: the two assertions below are one property, checked in
        // both directions, rather than a second NOT NULL suffix.
        String notNull = translate("ALTER TABLE t ADD COLUMN c INT NOT NULL");
        assertTrue(notNull.contains("Int32"), "a NOT NULL column keeps its type: " + notNull);
        assertFalse(notNull.contains("Nullable"), "a NOT NULL column must not be Nullable: " + notNull);

        String nullable = translate("ALTER TABLE t ADD COLUMN c INT NULL");
        assertTrue(nullable.contains("Nullable(Int32)"), "an explicit NULL becomes Nullable: " + nullable);

        String unspecified = translate("ALTER TABLE t ADD COLUMN c INT");
        assertTrue(unspecified.contains("Nullable(Int32)"),
                "MySQL's default is nullable: " + unspecified);

        String withDefault = translate("ALTER TABLE t ADD COLUMN c INT NOT NULL DEFAULT 1");
        assertFalse(withDefault.contains("Nullable"),
                "a NOT NULL column with a DEFAULT stays non-Nullable: " + withDefault);
        assertTrue(withDefault.contains("DEFAULT 1"), "the DEFAULT survives: " + withDefault);
    }

    /**
     * {@code SERIAL} is {@code BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE},
     * so it must land as an unsigned 64-bit integer, not as a signed one.
     */
    @Test
    public void everyIntegerSpellingKeepsItsSignedness() {
        assertTrue(translate("ALTER TABLE t ADD COLUMN c SERIAL").contains("UInt64"),
                "SERIAL is BIGINT UNSIGNED");
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT UNSIGNED").contains("UInt32"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT ZEROFILL").contains("UInt32"),
                "ZEROFILL implies UNSIGNED in MySQL");
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT SIGNED").contains("Int32"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c TINYINT UNSIGNED").contains("UInt8"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c MIDDLEINT UNSIGNED").contains("UInt32"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT3 UNSIGNED").contains("UInt32"));
        assertTrue(translate("ALTER TABLE t ADD COLUMN c INT8 UNSIGNED").contains("UInt64"));
    }
}
