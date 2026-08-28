package com.altinity.clickhouse.sink.connector.db.operations;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regression test for issue #1260 -- the column-drop branch of
 * createAlterTableSyntax emitted SQL ClickHouse cannot parse.
 *
 * <p>Two defects in one statement, both rejected with SYNTAX_ERROR
 * (Code: 62) by clickhouse-format:
 *
 * <pre>
 * ALTER TABLE `employees` delete column `amount` Float64
 *   failed at position 32 ('column'): Expected one of: IN PARTITION, WHERE
 *
 * ALTER TABLE `employees` DROP COLUMN `amount` Float64
 *   failed at position 46 ('Float64'): Expected one of: token, Dot, Comma, ...
 * </pre>
 *
 * <p>DELETE is the lightweight-delete clause and expects IN PARTITION or
 * WHERE next, so the column name terminated the parse. Correcting only the
 * keyword is not enough: DROP COLUMN names a column and stops there, while
 * the emitter appended the data type carried in the map.
 *
 * <p>Scope: createAlterTableSyntax is currently only ever invoked with
 * ADD (ClickHouseAlterTable:137), so no deployment issues these statements
 * today. This is a latent defect on a path that is reachable through the
 * public method and is exercised by the tests -- one of which previously
 * asserted the unparseable string as expected output, which is what kept
 * the defect in place.
 */
public class ClickHouseAlterTableTest
        extends com.altinity.clickhouse.sink.connector.db.operations
        .ClickHouseAutoCreateTableTest {

    /** A small ordered map, so the emitted clause order is deterministic. */
    private static Map<String, String> twoColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("amount", "Float64");
        columns.put("occupation", "String");
        return columns;
    }

    @Test
    @DisplayName("#1260 dropping columns emits DROP COLUMN, not 'delete column'")
    public void dropColumn_usesDropColumnKeyword() {
        String query = new ClickHouseAlterTable().createAlterTableSyntax(
                "employees", twoColumns(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.REMOVE);

        Assert.assertFalse(
                "'delete column' is not ClickHouse syntax -- DELETE expects "
                        + "IN PARTITION or WHERE next, so the column name is "
                        + "a SYNTAX_ERROR (Code: 62). Query was: " + query,
                query.toLowerCase().contains("delete column"));
        Assert.assertTrue(
                "the drop clause must use DROP COLUMN, was: " + query,
                query.contains("DROP COLUMN"));
    }

    @Test
    @DisplayName("#1260 a dropped column carries no data type")
    public void dropColumn_omitsTheDataType() {
        String query = new ClickHouseAlterTable().createAlterTableSyntax(
                "employees", twoColumns(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.REMOVE);

        Assert.assertEquals(
                "DROP COLUMN names a column and stops; appending the type is "
                        + "rejected at the type token (Code: 62)",
                "ALTER TABLE `employees` DROP COLUMN `amount`,"
                        + "DROP COLUMN `occupation`",
                query);
    }

    /**
     * Control: the ADD path declares a column, so it must keep emitting the
     * data type. Correcting the drop branch must not strip it here.
     */
    @Test
    @DisplayName("#1260 control -- the add path still declares the data type")
    public void addColumn_keepsTheDataType() {
        String query = new ClickHouseAlterTable().createAlterTableSyntax(
                "employees", twoColumns(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);

        Assert.assertEquals(
                "ALTER TABLE `employees` add column `amount` Float64,"
                        + "add column `occupation` String",
                query);
    }

    /**
     * Control: the identifier quoting added for #1264 applies to both
     * branches. A hyphenated table name is a SYNTAX_ERROR unquoted, and the
     * drop path must not have lost the quoting while being corrected.
     */
    @Test
    @DisplayName("#1260 control -- the drop path still quotes identifiers")
    public void dropColumn_stillQuotesIdentifiers() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("my-col", "Int32");

        String query = new ClickHouseAlterTable().createAlterTableSyntax(
                "my-tbl", one,
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.REMOVE);

        Assert.assertEquals("ALTER TABLE `my-tbl` DROP COLUMN `my-col`",
                query);
    }
}
