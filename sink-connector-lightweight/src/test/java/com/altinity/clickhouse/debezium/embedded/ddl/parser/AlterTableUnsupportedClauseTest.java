package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * An ALTER TABLE whose every sub-clause is inapplicable to ClickHouse must
 * translate to NOTHING, not to a bare {@code ALTER TABLE db.tbl}.
 *
 * <p>The translation is a text-level strip: the prefix is emitted as soon as
 * the table name is seen and each recognised sub-clause appends after it.
 * MySQL has whole families of sub-clause that ClickHouse has no equivalent
 * for -- foreign keys, secondary-index DDL, index visibility -- and those
 * append nothing. When the statement carries only such clauses, stripping
 * empties it and what is left is the prefix on its own, which is not a
 * statement at all. ClickHouse rejects it:</p>
 *
 * <pre>
 *   Code: 62. DB::Exception: Syntax error: failed at position 32
 *   (end of query)
 * </pre>
 *
 * <p>DDL is retried indefinitely, so one such statement stalls the entire
 * replication stream rather than just skipping the change it could not
 * translate. Observed across multiple production deployments over 18 months
 * on dozens of distinct tables, driven by ordinary schema maintenance -- the
 * four shapes pinned below are the ones seen live. One deployment emitted the
 * prefix with a SURVIVING TRAILING COMMA, so the empty-statement case and the
 * dangling-separator case are both pinned.</p>
 *
 * <p>The correct behaviour is to skip the statement: ClickHouse cannot express
 * a foreign key or a secondary index in an ALTER, so there is nothing to apply
 * and nothing is lost by not applying it. What must NOT happen is emitting a
 * fragment that cannot parse.</p>
 *
 * <p>The control cases matter as much as the defect cases: a statement that
 * MIXES an applicable clause with an inapplicable one must still apply the
 * applicable half, and every clause family that works today must be
 * byte-identical after the change.</p>
 */
public class AlterTableUnsupportedClauseTest {

    private static MySQLDDLParserService mySQLDDLParserService;

    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String mysqlDdl, String tableName) {
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(mysqlDdl, tableName, clickHouseQuery);
        return clickHouseQuery.toString();
    }

    /**
     * Asserts the translation produced no statement at all.
     *
     * <p>Deliberately stricter than "does not equal the bare prefix": anything
     * left behind here is a fragment, and a fragment is exactly what
     * ClickHouse rejects.</p>
     */
    private static void assertNothingEmitted(String mysqlDdl, String translated) {
        Assert.assertEquals(
                "an ALTER with no ClickHouse-applicable clause must emit nothing, "
                        + "but emitted [" + translated + "] for: " + mysqlDdl,
                "", translated.trim());
    }

    @Test
    @DisplayName("ADD FOREIGN KEY emits nothing, not a bare ALTER TABLE")
    public void testAddForeignKeyEmitsNothing() {
        String sql = "ALTER TABLE line_items ADD CONSTRAINT `fk_line_items_order` "
                + "FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)";
        assertNothingEmitted(sql, translate(sql, "line_items"));
    }

    @Test
    @DisplayName("DROP FOREIGN KEY emits nothing, not a bare ALTER TABLE")
    public void testDropForeignKeyEmitsNothing() {
        String sql = "ALTER TABLE line_items DROP FOREIGN KEY `fk_line_items_order`";
        assertNothingEmitted(sql, translate(sql, "line_items"));
    }

    @Test
    @DisplayName("ADD INDEX with execution hints emits nothing, not a bare ALTER TABLE")
    public void testAddIndexEmitsNothing() {
        String sql = "ALTER TABLE line_items ADD INDEX idx_order_id (order_id), "
                + "ALGORITHM=INPLACE, LOCK=NONE";
        assertNothingEmitted(sql, translate(sql, "line_items"));
    }

    @Test
    @DisplayName("ALTER INDEX ... INVISIBLE emits nothing, not a bare ALTER TABLE")
    public void testAlterIndexVisibilityEmitsNothing() {
        String sql = "ALTER TABLE line_items ALTER INDEX idx_order_id INVISIBLE";
        assertNothingEmitted(sql, translate(sql, "line_items"));
    }

    /**
     * The trailing-separator variant, pinned separately.
     *
     * <p>A comma is emitted eagerly between clauses, so a statement whose
     * clauses all emit nothing can leave the separator behind -- one
     * deployment produced a prefix ending in a comma. Skipping the statement
     * covers this, but the assertion is explicit so a future partial fix that
     * restores the prefix cannot quietly reintroduce it.</p>
     */
    @Test
    @DisplayName("multiple inapplicable clauses leave no dangling comma")
    public void testMultipleInapplicableClausesLeaveNoDanglingComma() {
        String sql = "ALTER TABLE line_items DROP FOREIGN KEY `fk_a`, DROP FOREIGN KEY `fk_b`";
        String translated = translate(sql, "line_items");
        Assert.assertFalse("a dangling separator must never survive, was: [" + translated + "]",
                translated.trim().endsWith(","));
        assertNothingEmitted(sql, translated);
    }

    /**
     * CONTROL: a statement mixing an applicable clause with an inapplicable one
     * must still apply the applicable half. This works today and must keep
     * working -- the fix is for the case where stripping empties the
     * statement, not for stripping itself.
     */
    @Test
    @DisplayName("CONTROL: mixed ADD COLUMN + ADD INDEX still applies the column half")
    public void testMixedAddColumnAndAddIndexStillAppliesColumnHalf() {
        String sql = "ALTER TABLE line_items ADD COLUMN order_id INT, ADD INDEX idx_order_id (order_id)";
        String translated = translate(sql, "line_items");
        Assert.assertEquals(
                "ALTER TABLE employees.line_items ADD COLUMN IF NOT EXISTS order_id Nullable(Int32)",
                translated.trim());
    }

    /** CONTROL: plain ADD COLUMN is unchanged. */
    @Test
    @DisplayName("CONTROL: ADD COLUMN unchanged")
    public void testAddColumnUnchanged() {
        String translated = translate("ALTER TABLE add_test ADD COLUMN foo INT;", "add_test");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.add_test ADD COLUMN IF NOT EXISTS foo Nullable(Int32)"));
    }

    /** CONTROL: DROP COLUMN is unchanged. */
    @Test
    @DisplayName("CONTROL: DROP COLUMN unchanged")
    public void testDropColumnUnchanged() {
        String translated = translate("ALTER TABLE add_test DROP COLUMN foo;", "add_test");
        Assert.assertTrue("was: [" + translated + "]", translated.trim().equalsIgnoreCase(
                "ALTER TABLE employees.add_test DROP COLUMN IF EXISTS foo"));
    }

    /** CONTROL: MODIFY COLUMN is unchanged. */
    @Test
    @DisplayName("CONTROL: MODIFY COLUMN unchanged")
    public void testModifyColumnUnchanged() {
        String translated = translate("ALTER TABLE employees.add_test MODIFY COLUMN col1 INT;", "add_test");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.add_test MODIFY COLUMN col1 Nullable(Int32)"));
    }

    /** CONTROL: RENAME COLUMN is unchanged, existence guard included. */
    @Test
    @DisplayName("CONTROL: RENAME COLUMN unchanged")
    public void testRenameColumnUnchanged() {
        String translated = translate("ALTER TABLE add_test RENAME COLUMN foo TO bar;", "add_test");
        Assert.assertTrue("the rename must still be emitted, was: [" + translated + "]",
                translated.toLowerCase().contains("rename column"));
        Assert.assertTrue("the qualified table must still be named, was: [" + translated + "]",
                translated.toLowerCase().contains("employees.add_test"));
    }

    /** CONTROL: RENAME TABLE is unchanged. */
    @Test
    @DisplayName("CONTROL: ALTER TABLE ... RENAME TO unchanged")
    public void testRenameTableUnchanged() {
        String translated = translate("ALTER TABLE add_test RENAME TO add_test_v2;", "add_test");
        Assert.assertTrue("was: [" + translated + "]",
                translated.toLowerCase().contains("employees.add_test")
                        && translated.toLowerCase().contains("employees.add_test_v2"));
    }

    /** CONTROL: DROP CONSTRAINT is unchanged. */
    @Test
    @DisplayName("CONTROL: DROP CONSTRAINT unchanged")
    public void testDropConstraintUnchanged() {
        String translated = translate("alter table employees drop CONSTRAINT employees_ibfk_2", "employees");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.employees DROP CONSTRAINT IF EXISTS employees_ibfk_2"));
    }

    /**
     * CONTROL: ADD CHECK CONSTRAINT is unchanged.
     *
     * <p>This one is structurally important. The check-constraint clause is not
     * handled where the other clauses are -- it arrives on a separate listener
     * callback, AFTER the clause walk has finished. Any fix that decides
     * "emit nothing" at the end of the walk must therefore still let this
     * clause emit its prefix, or a working translation regresses to silence.</p>
     */
    @Test
    @DisplayName("CONTROL: ADD CHECK CONSTRAINT unchanged")
    public void testAddCheckConstraintUnchanged() {
        String translated = translate(
                "ALTER TABLE orders ADD CONSTRAINT check_revenue_positive CHECK (revenue >= 0);", " ");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.orders ADD CONSTRAINT check_revenue_positive CHECK ( revenue >=0 ) "));
    }

    /** CONTROL: multiple ADD COLUMN clauses keep their separator. */
    @Test
    @DisplayName("CONTROL: multiple ADD COLUMN unchanged")
    public void testMultipleAddColumnsUnchanged() {
        String translated = translate(
                "ALTER TABLE employees.employees add column ssn_number varchar(100), "
                        + "add column home_address varchar(20)", "employees");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.employees ADD COLUMN IF NOT EXISTS ssn_number Nullable(String), "
                        + "ADD COLUMN IF NOT EXISTS home_address Nullable(String)"));
    }

    /** CONTROL: ADD COLUMN followed by trailing execution hints keeps no dangling comma. */
    @Test
    @DisplayName("CONTROL: ADD COLUMN with trailing hints unchanged")
    public void testAddColumnWithTrailingHintsUnchanged() {
        String translated = translate(
                "ALTER TABLE test_lot ADD COLUMN only_col INTEGER, ALGORITHM=INPLACE, LOCK=NONE", "test_lot");
        Assert.assertTrue("was: [" + translated + "]", translated.equalsIgnoreCase(
                "ALTER TABLE employees.test_lot ADD COLUMN IF NOT EXISTS only_col Nullable(Int32)"));
    }
}
