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
 * Spec 06.06 section 3.4: MySQL bit operators in a generation expression are
 * translated to ClickHouse bit functions; everything else is passed through.
 *
 * <p>ClickHouse has no {@code & | ^ << >> ~} operators, so a generated column
 * such as {@code (attrs & (1 << 2)) > 0} copied verbatim into the
 * {@code DEFAULT} made ClickHouse refuse the replicated CREATE TABLE or
 * ALTER TABLE ... ADD COLUMN with a syntax error (Code 62), which halts the
 * pipeline on a statement MySQL accepted.</p>
 *
 * <p>The translation: {@code bitAnd/bitOr/bitXor/bitShiftLeft/bitShiftRight/
 * bitNot} over {@code toUInt64} operands (MySQL evaluates bit operators on
 * BIGINT UNSIGNED), MySQL operator precedence (the parser's tree gives every
 * bit operator the same, higher-than-arithmetic precedence, which is not
 * MySQL's), a shift by 64 or more yields 0 (ClickHouse wraps the count), and a
 * NULL operand yields NULL. Literal-only operations are folded.</p>
 */
public class GeneratedColumnBitOperatorTest {

    private static MySQLDDLParserService parser;

    @BeforeAll
    static void init() {
        parser = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db1");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String mysqlDdl) {
        StringBuffer out = new StringBuffer();
        parser.parseSql(mysqlDdl, "t1", out);
        return out.toString();
    }

    /** The DEFAULT a single-column ALTER TABLE ... ADD COLUMN g ... AS (expr) translates to. */
    private static String alterDefault(String expression) {
        return translate("ALTER TABLE t1 ADD COLUMN g BIGINT GENERATED ALWAYS AS (" + expression + ") VIRTUAL");
    }

    private static void assertDefault(String expression, String expected) {
        String q = alterDefault(expression);
        assertTrue(q.contains("DEFAULT " + expected), "MySQL (" + expression + ")\n expected DEFAULT "
                + expected + "\n got: " + q);
    }

    private static void assertNoMySqlBitOperator(String q) {
        String afterDefault = q.substring(q.indexOf("DEFAULT"));
        for (String op : new String[]{"&", "|", "^", "<<", ">>", "~"}) {
            assertFalse(afterDefault.contains(op), "MySQL operator " + op + " reached ClickHouse: " + q);
        }
    }

    @Test
    @DisplayName("CREATE TABLE: a flag column over a bit mask is translated, not copied verbatim")
    public void createTableFlagColumnIsTranslated() {
        String q = translate("CREATE TABLE t1 (id INT NOT NULL PRIMARY KEY, attrs BIGINT, "
                + "flag TINYINT GENERATED ALWAYS AS ((attrs & (1 << 2)) > 0) VIRTUAL, note VARCHAR(10))");
        assertTrue(q.contains("DEFAULT (bitAnd(toUInt64(attrs), toUInt64((4))))>0,"), q);
        assertNoMySqlBitOperator(q);
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN: the same expression is translated the same way")
    public void alterAddColumnFlagColumnIsTranslated() {
        String q = translate("ALTER TABLE t1 ADD COLUMN flag TINYINT GENERATED ALWAYS AS "
                + "((attrs & (1 << 2)) > 0) VIRTUAL");
        assertTrue(q.contains("DEFAULT (bitAnd(toUInt64(attrs), toUInt64((4))))>0"), q);
        assertNoMySqlBitOperator(q);
    }

    @Test
    @DisplayName("each operator maps to its ClickHouse function over toUInt64 operands")
    public void eachOperatorMapsToItsFunction() {
        assertDefault("a & b", "bitAnd(toUInt64(a), toUInt64(b))");
        assertDefault("a | b", "bitOr(toUInt64(a), toUInt64(b))");
        assertDefault("a ^ b", "bitXor(toUInt64(a), toUInt64(b))");
        assertDefault("~a", "bitNot(toUInt64(a))");
        // ClickHouse takes the shift count modulo 64; MySQL yields 0 from 64 on.
        // The 0 is bitAnd(x, 0) so that a NULL operand still yields NULL.
        assertDefault("a << b", "if(toUInt64(b) >= 64, bitAnd(toUInt64(a), toUInt64(0)), "
                + "bitShiftLeft(toUInt64(a), toUInt64(b)))");
        assertDefault("a >> b", "if(toUInt64(b) >= 64, bitAnd(toUInt64(a), toUInt64(0)), "
                + "bitShiftRight(toUInt64(a), toUInt64(b)))");
    }

    @Test
    @DisplayName("MySQL precedence: ^ over * / over + - over << >> over & over |")
    public void mysqlPrecedenceIsApplied() {
        // MySQL: a | (b & c); the parse tree says (a | b) & c.
        assertDefault("a | b & c", "bitOr(toUInt64(a), toUInt64(bitAnd(toUInt64(b), toUInt64(c))))");
        // MySQL: a << (b + 1); the parse tree says (a << b) + 1.
        assertDefault("a << b + 1", "if(toUInt64((b + 1)) >= 64, bitAnd(toUInt64(a), toUInt64(0)), "
                + "bitShiftLeft(toUInt64(a), toUInt64((b + 1))))");
        // MySQL: (a ^ b) * c.
        assertDefault("a ^ b * c", "(bitXor(toUInt64(a), toUInt64(b)) * c)");
        // MySQL: (a & b) | c, left to right within a level.
        assertDefault("a & b | c", "bitOr(toUInt64(bitAnd(toUInt64(a), toUInt64(b))), toUInt64(c))");
        // Unary ~ binds tightest.
        assertDefault("~a & 255", "bitAnd(toUInt64(bitNot(toUInt64(a))), toUInt64(255))");
    }

    @Test
    @DisplayName("operations over integer literals only are folded with MySQL semantics")
    public void literalOperationsAreFolded() {
        assertDefault("a & (1 << 3)", "bitAnd(toUInt64(a), toUInt64((8)))");
        assertDefault("a | (1 << 64)", "bitOr(toUInt64(a), toUInt64((0)))");
        assertDefault("a & (255 >> 4)", "bitAnd(toUInt64(a), toUInt64((15)))");
        assertDefault("a & ~0", "bitAnd(toUInt64(a), toUInt64(18446744073709551615))");
    }

    @Test
    @DisplayName("bit operators nested inside functions and comparisons are translated in place")
    public void nestedBitOperatorsAreTranslatedInPlace() {
        assertDefault("IF((attrs & 1) = 1, 1, 0)", "IF((bitAnd(toUInt64(attrs), toUInt64(1)))=1,1,0)");
    }

    @Test
    @DisplayName("expressions without bit operators are passed through exactly as before")
    public void expressionsWithoutBitOperatorsAreUnchanged() {
        assertDefault("a + b * 2", "a+b*2");
        assertDefault("concat(first_name, ' ', last_name)", "concat(first_name,' ',last_name)");
    }
}
