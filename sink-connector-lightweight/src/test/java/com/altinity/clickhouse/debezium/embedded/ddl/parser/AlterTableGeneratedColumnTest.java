package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: ALTER TABLE ADD/MODIFY COLUMN with a
 * {@code GENERATED ALWAYS AS (expr)} clause must translate to a column of its
 * DECLARED type with a {@code DEFAULT} expression — never a column whose "type"
 * is the raw generation-expression text.
 *
 * <p>The ALTER path lacked a {@code GeneratedColumnConstraintContext} branch, so
 * the clause fell into the catch-all that treats any unrecognised child as the
 * column type, overwriting the real type with the expression text and emitting
 * malformed DDL like {@code ADD COLUMN c AS(a+b)}. The CREATE TABLE path always
 * handled this; the ALTER path did not.</p>
 */
public class AlterTableGeneratedColumnTest {

    private static MySQLDDLParserService parser;

    @BeforeAll
    static void init() {
        parser = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String mysqlDdl) {
        StringBuffer out = new StringBuffer();
        parser.parseSql(mysqlDdl, "test", out);
        return out.toString();
    }

    @Test
    @DisplayName("ADD COLUMN ... GENERATED ALWAYS AS keeps the declared type and maps the expression to DEFAULT")
    public void addGeneratedColumnKeepsTypeAndUsesDefault() {
        String q = translate("ALTER TABLE t ADD COLUMN c INT GENERATED ALWAYS AS (a + b)");

        // The declared type must be preserved (bug: it was overwritten by the
        // expression text, so "Int32" was absent).
        assertTrue(q.contains("Int32"),
                "the column must keep its declared type Int32; got: " + q);
        // The generation expression must be emitted as a DEFAULT (bug: no
        // DEFAULT was produced at all on the ALTER path).
        assertTrue(q.toUpperCase().contains("DEFAULT"),
                "the generation expression must map to a DEFAULT clause; got: " + q);
        // The type must never be the raw expression text.
        assertFalse(q.replace(" ", "").toUpperCase().contains("CAS(A+B)"),
                "the column type must not be the raw generation expression; got: " + q);
    }

    @Test
    @DisplayName("ADD COLUMN ... AS (short form) is handled the same way")
    public void addGeneratedColumnShortForm() {
        String q = translate("ALTER TABLE t ADD COLUMN c INT AS (a + b)");
        assertTrue(q.contains("Int32"), "declared type Int32 must be preserved; got: " + q);
        assertTrue(q.toUpperCase().contains("DEFAULT"),
                "the generation expression must map to a DEFAULT clause; got: " + q);
    }
}
