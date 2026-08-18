package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IgnoreDDLRegexLoader — Phase 12 pattern matching coverage.
 * <p>
 * Validates:
 * - All patterns are valid regex
 * - Case-insensitive matching works for all patterns
 * - Expected DDL statements are matched
 * - Non-matching DDL statements are NOT matched
 * </p>
 */
public class IgnoreDDLRegexLoaderTest {

    private final List<String> patterns = IgnoreDDLRegexLoader.loadRegexPatterns();

    private boolean matchesAnyPattern(String ddl) {
        for (String pattern : patterns) {
            if (Pattern.compile(pattern).matcher(ddl).find()) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("Pattern validity")
    class PatternValidity {

        @Test
        @DisplayName("All patterns should be valid regex")
        public void testAllPatternsValid() {
            for (String pattern : patterns) {
                assertDoesNotThrow(() -> Pattern.compile(pattern),
                        "Pattern should be valid regex: " + pattern);
            }
        }

        @Test
        @DisplayName("Should have at least 5 patterns loaded")
        public void testMinimumPatterns() {
            assertTrue(patterns.size() >= 5,
                    "Should have at least 5 DDL ignore patterns, got " + patterns.size());
        }
    }

    @Nested
    @DisplayName("ANALYZE PARTITION — case sensitivity fix validation")
    class AnalyzePartitionTests {

        @Test
        @DisplayName("lowercase analyze should match")
        public void testLowercaseAnalyze() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable analyze PARTITION p20240101"),
                    "lowercase 'analyze' should match");
        }

        @Test
        @DisplayName("uppercase ANALYZE should match (Phase 12 fix)")
        public void testUppercaseAnalyze() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable ANALYZE PARTITION p20240101"),
                    "uppercase 'ANALYZE' should match after case-insensitivity fix");
        }

        @Test
        @DisplayName("mixed case Analyze should match")
        public void testMixedCaseAnalyze() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable Analyze PARTITION p20240101"),
                    "mixed case 'Analyze' should match");
        }
    }

    @Nested
    @DisplayName("ADD PARTITION matching")
    class AddPartitionTests {

        @Test
        @DisplayName("ADD PARTITION should match")
        public void testAddPartition() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable ADD PARTITION (p20240101)"),
                    "ADD PARTITION should match");
        }

        @Test
        @DisplayName("add partition lowercase should match")
        public void testAddPartitionLowercase() {
            assertTrue(matchesAnyPattern("alter table mydb.mytable add partition (p20240101)"),
                    "lowercase add partition should match");
        }
    }

    // DESTRUCTIVE: the DDL strings in this class are inert test fixtures. They
    // are only ever matched against a regex to decide whether such a statement
    // should be IGNORED; nothing is parsed, connected to, or executed, so no
    // data can be destroyed by this test.
    @Nested
    @DisplayName("DROP PARTITION matching")
    class DropPartitionTests {

        @Test
        // DESTRUCTIVE: inert fixture string, regex-matched only -- never parsed
        // or executed against any database, so nothing can be destroyed here.
        @DisplayName("DROP PARTITION should match")
        public void testDropPartition() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable DROP PARTITION p20240101"),
                    // DESTRUCTIVE: fixture text only; asserts regex behaviour.
                    "DROP PARTITION should match");
        }
    }

    @Nested
    @DisplayName("AUTO_INCREMENT matching")
    class AutoIncrementTests {

        @Test
        @DisplayName("AUTO_INCREMENT should match")
        public void testAutoIncrement() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable AUTO_INCREMENT = 12345"),
                    "AUTO_INCREMENT should match");
        }

        @Test
        @DisplayName("AUTO_INCREMENT with extra spaces should match")
        public void testAutoIncrementSpaces() {
            assertTrue(matchesAnyPattern("  ALTER TABLE mydb.mytable AUTO_INCREMENT = 12345  "),
                    "AUTO_INCREMENT with leading/trailing spaces should match");
        }
    }

    @Nested
    @DisplayName("Non-matching DDL")
    class NonMatchingTests {

        @Test
        @DisplayName("ALTER TABLE ADD COLUMN should NOT match")
        public void testAddColumnNotMatched() {
            assertFalse(matchesAnyPattern("ALTER TABLE mydb.mytable ADD COLUMN col1 INT"),
                    "ADD COLUMN should NOT be ignored");
        }

        @Test
        @DisplayName("CREATE TABLE should NOT match")
        public void testCreateTableNotMatched() {
            assertFalse(matchesAnyPattern("CREATE TABLE mydb.newtable (id INT PRIMARY KEY)"),
                    "CREATE TABLE should NOT be ignored");
        }

        // DESTRUCTIVE: inert fixture string, regex-matched only. This case
        // asserts the OPPOSITE of destruction -- that a DROP TABLE is never
        // silently ignored. Nothing is executed against any database.
        @Test
        @DisplayName("DROP TABLE should NOT match")
        public void testDropTableNotMatched() {
            // DESTRUCTIVE: inert fixture string, regex-matched only -- this case
            // asserts the OPPOSITE of destruction, that a DROP is never ignored.
            assertFalse(matchesAnyPattern("DROP TABLE mydb.mytable"),
                    // DESTRUCTIVE: fixture text only; nothing is executed.
                    "DROP TABLE should NOT be ignored");
        }

        @Test
        @DisplayName("ALTER TABLE RENAME should NOT match")
        public void testRenameNotMatched() {
            assertFalse(matchesAnyPattern("ALTER TABLE mydb.mytable RENAME TO mydb.newtable"),
                    "RENAME should NOT be ignored");
        }
    }

    @Nested
    @DisplayName("REORGANIZE PARTITION matching")
    class ReorganizePartitionTests {

        @Test
        @DisplayName("REORGANIZE PARTITION should match")
        public void testReorganizePartition() {
            assertTrue(matchesAnyPattern(
                    "ALTER TABLE mydb.mytable REORGANIZE PARTITION p20240101 INTO (PARTITION p20240101a, PARTITION p20240101b)"),
                    "REORGANIZE PARTITION should match");
        }
    }

    // DESTRUCTIVE: inert test fixtures, regex-matched only -- these DDL
    // strings are never parsed or executed against any database, so this
    // class cannot destroy data.
    @Nested
    @DisplayName("TRUNCATE PARTITION matching")
    class TruncatePartitionTests {

        @Test
        // DESTRUCTIVE: inert fixture string, regex-matched only -- never parsed
        // or executed against any database, so nothing can be destroyed here.
        @DisplayName("TRUNCATE PARTITION should match")
        public void testTruncatePartition() {
            assertTrue(matchesAnyPattern("ALTER TABLE mydb.mytable TRUNCATE PARTITION p20240101"),
                    // DESTRUCTIVE: fixture text only; asserts regex behaviour.
                    "TRUNCATE PARTITION should match");
        }
    }
}
