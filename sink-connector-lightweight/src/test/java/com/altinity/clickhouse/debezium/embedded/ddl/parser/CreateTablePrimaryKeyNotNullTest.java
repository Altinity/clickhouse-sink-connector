package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * MySQL forces every PRIMARY KEY column to NOT NULL, silently, even when the
 * column declaration says nothing about nullability. Verified on MySQL 8.0.36:
 *
 *   CREATE TABLE t1 (id INT AUTO_INCREMENT, x INT, PRIMARY KEY (id));
 *   SHOW COLUMNS FROM t1;   ->  id  int  Null=NO  Key=PRI
 *
 * The DDL parser did not apply that rule. It set NOT NULL only from an
 * explicit constraint in the column declaration itself: a column-level
 * PRIMARY KEY clears the flag (parseColumnDefinitions, the
 * PrimaryKeyColumnConstraintContext branch), but a TABLE-level
 * {@code PRIMARY KEY (id)} is parsed in a different place entirely -- the
 * PrimaryKeyTableConstraintContext branch of parseCreateTable -- which
 * appends the column to the sorting key and never revisits its nullability.
 *
 * The result is a Nullable column in the ORDER BY of a ReplacingMergeTree,
 * and ClickHouse rejects it outright. Measured on ClickHouse 24.8.14.10547:
 *
 *   CREATE TABLE nk(id Nullable(Int32), ..., _version UInt64, is_deleted UInt8)
 *     Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (id)
 *   Code: 44. DB::Exception: Sorting key contains nullable columns, but merge
 *   tree setting `allow_nullable_key` is disabled. (ILLEGAL_COLUMN)
 *
 * So the emitted DDL is not merely questionable, it does not execute: the
 * table is never created and replication of it cannot start.
 *
 * <p>Fixing the NULLABILITY rather than the sorting key is what makes this
 * safe to ship. The ORDER BY clause is byte-for-byte unchanged -- only the
 * column type changes, from {@code Nullable(T)} to {@code T NOT NULL}, which
 * is what the source column has always been. Wrapping the key instead
 * ({@code ifNull(id,'')}, {@code assumeNotNull(id)}) would change the emitted
 * ORDER BY for every table of this shape, and a sorting key cannot be altered
 * after creation, so tables already in the field could not adopt it without a
 * rebuild. It would also enshrine a workaround for a value that cannot occur.
 *
 * <p>This is the same judgement the connector already makes one step away: a
 * UNIQUE key spanning nullable columns is REFUSED as a sorting key
 * (MySQlDDLParserListenerImpl, the uniqueKeyIsNotNull check) precisely because
 * MySQL does not treat NULLs as equal there, so such an index is not a row
 * identity. A PRIMARY KEY is the opposite case -- MySQL guarantees it is NOT
 * NULL -- so the consistent action is to record that guarantee, not to defend
 * against a NULL the source cannot produce.
 */
public class CreateTablePrimaryKeyNotNullTest {

    private static MySQLDDLParserService mySQLDDLParserService;

    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private String parse(String createQuery, String table) {
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, table, clickHouseQuery);
        return clickHouseQuery.toString();
    }

    /**
     * The reported shape: a table-level PRIMARY KEY over a column with no
     * explicit NOT NULL. ClickHouse refuses the DDL this produced.
     */
    @Test
    @DisplayName("A table-level PRIMARY KEY column must not be emitted Nullable")
    public void testTableLevelPrimaryKeyColumnIsNotNullable() {
        String query = parse(
                "CREATE TABLE IF NOT EXISTS pk_tbl (id INT AUTO_INCREMENT, x INT, "
                        + "PRIMARY KEY (id)) ENGINE = InnoDB;", "pk_tbl");

        Assert.assertFalse(
                "the sorting key column was emitted Nullable, which ClickHouse "
                        + "rejects with Code: 44 ILLEGAL_COLUMN. Was: " + query,
                query.contains("id Nullable(Int32)"));
        Assert.assertTrue("id must be NOT NULL, was: " + query,
                query.contains("id Int32 NOT NULL"));

        // The sorting key itself is unchanged -- this fix moves no columns in
        // or out of the ORDER BY.
        Assert.assertTrue("ORDER BY must still name id, was: " + query,
                query.contains("ORDER BY (id)"));

        // And the fix must not have leaked NOT NULL onto a non-key column.
        Assert.assertTrue("x is not part of the key and must stay Nullable, was: "
                + query, query.contains("x Nullable(Int32)"));
    }

    /**
     * A COMPOSITE table-level PRIMARY KEY: every one of its columns is NOT
     * NULL in MySQL, so every one of them must be non-nullable here. A fix
     * that only handled the first column would leave the DDL just as
     * unexecutable.
     */
    @Test
    @DisplayName("Every column of a composite table-level PRIMARY KEY is NOT NULL")
    public void testCompositeTableLevelPrimaryKeyColumnsAreNotNullable() {
        String query = parse(
                "CREATE TABLE IF NOT EXISTS pk_comp (a INT, b INT, v VARCHAR(64), "
                        + "PRIMARY KEY (a, b)) ENGINE = InnoDB;", "pk_comp");

        Assert.assertFalse("a was emitted Nullable, was: " + query,
                query.contains("a Nullable(Int32)"));
        Assert.assertFalse("b was emitted Nullable, was: " + query,
                query.contains("b Nullable(Int32)"));
        Assert.assertTrue("a must be NOT NULL, was: " + query,
                query.contains("a Int32 NOT NULL"));
        Assert.assertTrue("b must be NOT NULL, was: " + query,
                query.contains("b Int32 NOT NULL"));

        Assert.assertTrue("v is not part of the key and must stay Nullable, was: "
                + query, query.contains("v Nullable(String)"));
    }

    /**
     * Backtick-quoted key columns must be matched too -- MySQL emits the
     * PRIMARY KEY clause quoted in the binlog DDL, which is the form the
     * connector actually receives in production.
     */
    @Test
    @DisplayName("A backtick-quoted PRIMARY KEY column is recognised")
    public void testQuotedTableLevelPrimaryKeyColumnIsNotNullable() {
        String query = parse(
                "CREATE TABLE IF NOT EXISTS `pk_q` (\n"
                        + "  `emp_no` int,\n"
                        + "  `note` varchar(64),\n"
                        + "  PRIMARY KEY (`emp_no`)\n"
                        + ") ENGINE=InnoDB;", "pk_q");

        Assert.assertFalse("emp_no was emitted Nullable, was: " + query,
                query.contains("`emp_no` Nullable(Int32)"));
        Assert.assertTrue("emp_no must be NOT NULL, was: " + query,
                query.contains("`emp_no` Int32 NOT NULL"));
        Assert.assertTrue("note must stay Nullable, was: " + query,
                query.contains("`note` Nullable(String)"));
    }

    /**
     * Control: an explicit NOT NULL PRIMARY KEY, the shape that already
     * worked, must be completely unaffected. This is the common production
     * case, so any change to it would be a regression.
     */
    @Test
    @DisplayName("Control: an explicitly NOT NULL PRIMARY KEY is unchanged")
    public void testExplicitNotNullPrimaryKeyUnchanged() {
        String query = parse(
                "CREATE TABLE IF NOT EXISTS pk_explicit (id INT NOT NULL, col1 varchar(255), "
                        + "col2 int, PRIMARY KEY (id)) ENGINE = InnoDB;", "pk_explicit");

        Assert.assertTrue("id must be NOT NULL, was: " + query,
                query.contains("id Int32 NOT NULL"));
        Assert.assertTrue("col1 must stay Nullable, was: " + query,
                query.contains("col1 Nullable(String)"));
        Assert.assertTrue("col2 must stay Nullable, was: " + query,
                query.contains("col2 Nullable(Int32)"));
        Assert.assertTrue("ORDER BY must still name id, was: " + query,
                query.contains("ORDER BY (id)"));
    }

    /**
     * Control: a column-level PRIMARY KEY already cleared the nullable flag
     * on the path through parseColumnDefinitions. It must keep doing so.
     */
    @Test
    @DisplayName("Control: a column-level PRIMARY KEY is still NOT NULL")
    public void testColumnLevelPrimaryKeyUnchanged() {
        String query = parse(
                "CREATE TABLE IF NOT EXISTS pk_col (id INT PRIMARY KEY, v INT) ENGINE = InnoDB;",
                "pk_col");

        Assert.assertFalse("id was emitted Nullable, was: " + query,
                query.contains("id Nullable(Int32)"));
        Assert.assertTrue("v must stay Nullable, was: " + query,
                query.contains("v Nullable(Int32)"));
    }

    /**
     * Control: a table with NO primary key must be untouched -- no column may
     * acquire NOT NULL, and the keyless handling (ORDER BY tuple()) stands.
     */
    @Test
    @DisplayName("Control: a table with no PRIMARY KEY gains no NOT NULL columns")
    public void testNoPrimaryKeyTableUnchanged() {
        String query = parse(
                "create table if not exists nopk_plain(id int, class_name varchar(100))",
                "nopk_plain");

        Assert.assertTrue("id must stay Nullable, was: " + query,
                query.contains("id Nullable(Int32)"));
        Assert.assertTrue("class_name must stay Nullable, was: " + query,
                query.contains("class_name Nullable(String)"));
        Assert.assertTrue("keyless handling must be unchanged, was: " + query,
                query.contains("ORDER BY tuple()"));
    }
}
