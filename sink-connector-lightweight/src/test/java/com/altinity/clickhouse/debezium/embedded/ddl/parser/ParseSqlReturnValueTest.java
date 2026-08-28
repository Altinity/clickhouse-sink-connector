package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link DDLParserService#parseSql} declares "@return The parsed SQL query as
 * a String" on both overloads, but {@link MySQLDDLParserService} never
 * honoured it: each overload declared a local {@code clickHouseResult}
 * initialised to {@code null}, walked the parse tree, and returned that same
 * untouched {@code null}. The translation reached the caller only through the
 * {@code parsedQuery} StringBuffer passed in by reference, so the declared
 * return value was dead code -- {@code null} for every input, valid or not.
 *
 * <p>GitHub issue #1298.</p>
 *
 * <p>A null return is indistinguishable from "the parser produced nothing",
 * which is a real outcome here (an ALTER whose every clause is inapplicable to
 * ClickHouse emits nothing at all). A caller that trusted the documented
 * contract would therefore read "no translation" for every statement,
 * including the ones that translated perfectly well.</p>
 *
 * <p>Both production callers -- {@code DebeziumEmbeddedRestApi} and
 * {@code DebeziumChangeEventCapture} -- discard the return value and read the
 * StringBuffer, so no caller depends on the null and the return can be made
 * meaningful without touching them. The CONTROL test below is the load-bearing
 * half of this class: it pins the StringBuffer side-channel byte-for-byte, so
 * the existing callers are provably unaffected.</p>
 */
public class ParseSqlReturnValueTest {

    private static MySQLDDLParserService mySQLDDLParserService;

    /** An ALTER that translates to exactly one ClickHouse statement. */
    private static final String ALTER_DDL =
            "ALTER TABLE employees.add_test ADD COLUMN foo INT";

    /** A CREATE TABLE, the largest translation the parser emits. */
    private static final String CREATE_DDL =
            "CREATE TABLE employees.contacts (id INT PRIMARY KEY, name VARCHAR(50))";

    /** A DROP, which also drives the four-argument overload's flag. */
    private static final String DROP_DDL = "DROP TABLE employees.contacts";

    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    @Test
    @DisplayName("three-argument parseSql returns the translation, not null")
    public void testThreeArgOverloadReturnsTranslation() {
        StringBuffer parsedQuery = new StringBuffer();
        String returned =
                mySQLDDLParserService.parseSql(ALTER_DDL, "add_test", parsedQuery);

        Assert.assertNotNull(
                "parseSql documents a String return but handed back null", returned);
        Assert.assertEquals(
                "the returned String must be the translation the StringBuffer carries",
                parsedQuery.toString(), returned);
        Assert.assertFalse(
                "the representative DDL must actually translate to something, "
                        + "otherwise this test would pass on an empty return",
                returned.trim().isEmpty());
    }

    @Test
    @DisplayName("three-argument parseSql returns a CREATE TABLE translation")
    public void testThreeArgOverloadReturnsCreateTableTranslation() {
        StringBuffer parsedQuery = new StringBuffer();
        String returned =
                mySQLDDLParserService.parseSql(CREATE_DDL, "contacts", parsedQuery);

        Assert.assertNotNull(
                "parseSql documents a String return but handed back null", returned);
        Assert.assertEquals(parsedQuery.toString(), returned);
        Assert.assertFalse(returned.trim().isEmpty());
    }

    @Test
    @DisplayName("four-argument parseSql returns the translation, not null")
    public void testFourArgOverloadReturnsTranslation() {
        StringBuffer parsedQuery = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);
        String returned = mySQLDDLParserService.parseSql(
                DROP_DDL, "contacts", parsedQuery, isDropOrTruncate);

        Assert.assertNotNull(
                "parseSql documents a String return but handed back null", returned);
        Assert.assertEquals(
                "the returned String must be the translation the StringBuffer carries",
                parsedQuery.toString(), returned);
        Assert.assertFalse(returned.trim().isEmpty());
        Assert.assertTrue(
                "the four-argument overload must still classify DROP",
                isDropOrTruncate.get());
    }

    /**
     * CONTROL. Every existing caller reads the StringBuffer and ignores the
     * return, so the StringBuffer is the compatibility surface. These are the
     * exact bytes the parser emitted before the change, and they must keep
     * being the exact bytes it emits after it.
     */
    @Test
    @DisplayName("CONTROL: the StringBuffer side-channel is byte-identical")
    public void testStringBufferSideChannelUnchanged() {
        StringBuffer alter = new StringBuffer();
        mySQLDDLParserService.parseSql(ALTER_DDL, "add_test", alter);

        StringBuffer create = new StringBuffer();
        mySQLDDLParserService.parseSql(CREATE_DDL, "contacts", create);

        StringBuffer drop = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);
        mySQLDDLParserService.parseSql(DROP_DDL, "contacts", drop, isDropOrTruncate);
        Assert.assertTrue(isDropOrTruncate.get());

        Assert.assertEquals(
                "ALTER TABLE employees.add_test "
                        + "ADD COLUMN IF NOT EXISTS foo Nullable(Int32)",
                alter.toString());
        Assert.assertEquals(
                "CREATE TABLE if not exists employees.contacts("
                        + "id Int32 NOT NULL ,name Nullable(String),"
                        + "`_version` UInt64,`is_deleted` UInt8) "
                        + "Engine=ReplacingMergeTree(_version,is_deleted) "
                        + "ORDER BY id",
                create.toString());
        Assert.assertEquals("DROP TABLE employees.contacts", drop.toString());
    }

    /**
     * CONTROL. A statement with nothing ClickHouse-applicable emits nothing.
     * After the change that is an EMPTY string rather than null, and it must
     * stay distinguishable from a real translation.
     */
    @Test
    @DisplayName("CONTROL: a statement that translates to nothing stays empty")
    public void testUntranslatableStatementStaysEmpty() {
        String sql = "ALTER TABLE line_items DROP FOREIGN KEY `fk_line_items_order`";
        StringBuffer parsedQuery = new StringBuffer();
        String returned =
                mySQLDDLParserService.parseSql(sql, "line_items", parsedQuery);

        Assert.assertEquals("", parsedQuery.toString().trim());
        Assert.assertEquals(parsedQuery.toString(), returned);
    }
}
