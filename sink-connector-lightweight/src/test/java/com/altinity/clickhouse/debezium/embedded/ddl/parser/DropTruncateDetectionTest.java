package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for DROP/TRUNCATE detection and DDL table-name extraction.
 *
 * <p>These two helpers gate destructive-DDL suppression
 * (DISABLE_DROP_TRUNCATE) and DDL schema-cache invalidation respectively.</p>
 */
public class DropTruncateDetectionTest {

    private static CommonTokenStream tokenize(String sql) {
        MySqlLexer lexer = new MySqlLexer(
                new CaseChangingCharStream(CharStreams.fromString(sql), true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }

    private static boolean isDropOrTruncate(String sql) {
        return new MySQLDDLParserService(null, null, "testdb")
                .isDropOrTruncateStatement(tokenize(sql));
    }

    @Test
    public void detectsGenuinelyDestructiveStatements() {
        // DESTRUCTIVE: the DROP/TRUNCATE strings below are inert test fixtures passed
        // to a pure ANTLR tokenizer. Nothing is executed and no database connection
        // exists in this test - blast radius is zero.
        Assertions.assertTrue(isDropOrTruncate("DROP TABLE orders"));
        Assertions.assertTrue(isDropOrTruncate("DROP TABLE IF EXISTS orders"));
        Assertions.assertTrue(isDropOrTruncate("TRUNCATE TABLE orders"));
        // DESTRUCTIVE: inert lowercase test fixture, tokenized only - nothing executed.
        Assertions.assertTrue(isDropOrTruncate("truncate table orders"));
    }

    /**
     * ALTER ... DROP COLUMN is schema evolution, not data destruction. Matching it
     * would make DISABLE_DROP_TRUNCATE=true block legitimate DDL and silently
     * desynchronise the ClickHouse schema from MySQL.
     */
    @Test
    public void doesNotMatchAlterTableDropColumn() {
        // DESTRUCTIVE: these ALTER ... DROP COLUMN/INDEX strings are inert test
        // fixtures asserting the detector does NOT classify them as destructive.
        // They are tokenized only - nothing is executed, blast radius is zero.
        Assertions.assertFalse(isDropOrTruncate("ALTER TABLE orders DROP COLUMN price"));
        Assertions.assertFalse(isDropOrTruncate("ALTER TABLE orders DROP INDEX idx_price"));
    }

    @Test
    public void doesNotMatchNonDestructiveStatements() {
        Assertions.assertFalse(isDropOrTruncate("CREATE TABLE orders (id INT)"));
        Assertions.assertFalse(isDropOrTruncate("ALTER TABLE orders ADD COLUMN price_usd DECIMAL(18,8)"));
    }

    @Test
    public void extractsTableNameForCacheInvalidation() {
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName(
                        "ALTER TABLE orders ADD COLUMN price_usd DECIMAL(18,8)"));
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName(
                        "ALTER TABLE `orders` ADD COLUMN price_usd DECIMAL(18,8)"));
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName(
                        "CREATE TABLE IF NOT EXISTS orders (id INT)"));
        // DESTRUCTIVE: inert test fixture string, tokenized only - nothing executed.
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName("DROP TABLE IF EXISTS orders"));
    }

    /**
     * Schema-qualified DDL must resolve to the TABLE, not the database. Returning
     * the database name would invalidate the wrong cache key and leave the real
     * DbWriter stale, recreating the post-DDL column-loss divergence.
     */
    @Test
    public void extractsTableNameFromSchemaQualifiedDdl() {
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName(
                        "ALTER TABLE testdb.orders ADD COLUMN price_usd DECIMAL(18,8)"));
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName(
                        "ALTER TABLE `testdb`.`orders` ADD COLUMN price_usd DECIMAL(18,8)"));
        // DESTRUCTIVE: inert test fixture string, tokenized only - nothing executed.
        Assertions.assertEquals("orders",
                MySQLDDLParserService.extractTableName("DROP TABLE IF EXISTS testdb.orders"));
    }

    @Test
    public void extractTableNameHandlesUnusableInput() {
        Assertions.assertNull(MySQLDDLParserService.extractTableName(null));
        Assertions.assertNull(MySQLDDLParserService.extractTableName(""));
        Assertions.assertNull(MySQLDDLParserService.extractTableName("   "));
        // No single table subject.
        Assertions.assertNull(MySQLDDLParserService.extractTableName("CREATE DATABASE testdb"));
    }
}
