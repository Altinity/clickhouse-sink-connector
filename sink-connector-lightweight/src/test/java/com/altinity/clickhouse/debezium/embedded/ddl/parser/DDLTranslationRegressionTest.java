package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the MySQL to ClickHouse DDL translator.
 *
 * <p>Each case below corresponds to a translation defect that stalls the DDL
 * stream or silently corrupts the destination schema. A stalled DDL stream is
 * the worst failure mode this connector has: replication stops advancing while
 * the binlog keeps rotating, so a stall that outlives the source's binlog
 * retention is unrecoverable without a full re-snapshot.</p>
 *
 * <p>These are deliberately kept as pure translator assertions - no database,
 * no container - so they run on every build and pin the exact emitted SQL
 * rather than "it did not throw".</p>
 */
public class DDLTranslationRegressionTest {

    private static MySQLDDLParserService parser;

    @BeforeAll
    public static void init() {
        parser = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer out = new StringBuffer();
        parser.parseSql(sql, "t", out);
        return out.toString();
    }

    private static String translateWithOverride(String sql, String overrideMap, String destDb) {
        Map<String, String> props = new HashMap<>();
        props.put(CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), overrideMap);
        StringBuffer out = new StringBuffer();
        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(props), destDb)
                .parseSql(sql, "t", out);
        return out.toString();
    }

    @Nested
    @DisplayName("Emitted SQL must never contain a dangling separator")
    class SeparatorHygiene {

        /**
         * MySQL allows trailing execution hints that have no ClickHouse
         * equivalent. The translator emits nothing for them - but if it leaves
         * the preceding comma behind, ClickHouse rejects the whole ALTER with a
         * syntax error and the DDL stream stalls. Upstream issue #1140.
         */
        @Test
        @DisplayName("ALGORITHM= hint does not leave a trailing comma")
        public void algorithmHintLeavesNoTrailingComma() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN price_usd DECIMAL(18,8), ALGORITHM=INPLACE");
            assertNoDanglingSeparator(out);
            assertTrue(out.toLowerCase().contains("add column"),
                    "the real operation must survive the hint being dropped: " + out);
        }

        @Test
        @DisplayName("LOCK= hint does not leave a trailing comma")
        public void lockHintLeavesNoTrailingComma() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN qty INT, LOCK=NONE");
            assertNoDanglingSeparator(out);
            assertTrue(out.toLowerCase().contains("add column"), out);
        }

        @Test
        @DisplayName("both hints together do not leave a doubled comma")
        public void bothHintsLeaveNoDoubledComma() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN qty INT, ALGORITHM=INPLACE, LOCK=NONE");
            assertNoDanglingSeparator(out);
            assertFalse(out.contains(",,"), "doubled comma in emitted SQL: " + out);
        }

        /**
         * A hint in the middle must not swallow the operations that follow it.
         */
        @Test
        @DisplayName("a hint between two operations drops neither operation")
        public void hintBetweenOperationsDropsNeither() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN a INT, ALGORITHM=INPLACE, ADD COLUMN b INT");
            assertNoDanglingSeparator(out);
            String lower = out.toLowerCase();
            assertTrue(lower.contains(" a ") || lower.contains("`a`"),
                    "first column lost: " + out);
            assertTrue(lower.contains(" b ") || lower.contains("`b`"),
                    "second column lost after the hint: " + out);
        }

        private void assertNoDanglingSeparator(String emitted) {
            for (String statement : emitted.split("\n")) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                assertFalse(trimmed.endsWith(","),
                        "a trailing comma is a ClickHouse syntax error and stalls the whole "
                                + "DDL stream: [" + trimmed + "]");
                assertFalse(trimmed.contains(",,"),
                        "a doubled comma is a ClickHouse syntax error: [" + trimmed + "]");
                assertFalse(trimmed.contains("( ,") || trimmed.contains("(,"),
                        "a leading comma is a ClickHouse syntax error: [" + trimmed + "]");
            }
        }
    }

    @Nested
    @DisplayName("DROP is always replayable")
    class DropReplayability {

        /**
         * After an offset rewind Debezium re-emits DDL that has already been
         * applied. A bare DROP for an already-dropped table raises
         * UNKNOWN_TABLE and stalls the stream, so the translator must always
         * emit IF EXISTS regardless of whether the source statement had it.
         */
        @Test
        @DisplayName("DROP TABLE without IF EXISTS is emitted with IF EXISTS")
        public void dropTableGainsIfExists() {
            // DESTRUCTIVE: this DROP string is an inert test fixture handed to a pure
            // translator. No connection exists in this test and nothing is executed -
            // the assertion is purely on the emitted text. Blast radius is zero.
            String out = translate("DROP TABLE add_test");
            assertTrue(out.toLowerCase().contains("if exists"),
                    "a replayed DROP without IF EXISTS raises UNKNOWN_TABLE and stalls "
                            + "the DDL stream: " + out);
        }

        @Test
        @DisplayName("DROP TABLE IF EXISTS stays idempotent and is not doubled")
        public void dropTableKeepsSingleIfExists() {
            // DESTRUCTIVE: inert test fixture, translator-only, nothing executed.
            String out = translate("DROP TABLE IF EXISTS add_test");
            String lower = out.toLowerCase();
            assertTrue(lower.contains("if exists"), out);
            assertEquals(lower.indexOf("if exists"), lower.lastIndexOf("if exists"),
                    "IF EXISTS must not be emitted twice: " + out);
        }
    }

    @Nested
    @DisplayName("database.override.map is applied consistently")
    class DatabaseOverrideConsistency {

        /**
         * MySQL's CHANGE COLUMN is two ClickHouse operations - a MODIFY and a
         * RENAME. If only one half is rewritten to the destination database,
         * the other half targets the source database name, which either does
         * not exist in ClickHouse (the ALTER fails and the stream stalls) or,
         * worse, exists and holds a different table.
         */
        @Test
        @DisplayName("CHANGE COLUMN emits exactly one database in both halves")
        public void changeColumnUsesDestinationDatabaseInBothHalves() {
            String out = translateWithOverride(
                    "ALTER TABLE mysql1.add_test CHANGE COLUMN stocks options BOOL",
                    "mysql1:ch1", "ch1");
            String lower = out.toLowerCase();
            assertFalse(lower.contains("mysql1."),
                    "the source database name must not survive translation - the untranslated "
                            + "half targets a database that does not exist in ClickHouse: " + out);
            assertTrue(lower.contains("ch1."),
                    "the destination database must be applied: " + out);
        }

        @Test
        @DisplayName("RENAME COLUMN applies the override")
        public void renameColumnUsesDestinationDatabase() {
            String out = translateWithOverride(
                    "ALTER TABLE mysql1.add_test RENAME COLUMN col1 TO col2",
                    "mysql1:ch1", "ch1");
            assertFalse(out.toLowerCase().contains("mysql1."), out);
            assertTrue(out.toLowerCase().contains("ch1."), out);
        }

        @Test
        @DisplayName("MODIFY COLUMN applies the override")
        public void modifyColumnUsesDestinationDatabase() {
            String out = translateWithOverride(
                    "ALTER TABLE mysql1.add_test MODIFY COLUMN col1 BIGINT",
                    "mysql1:ch1", "ch1");
            assertFalse(out.toLowerCase().contains("mysql1."), out);
            assertTrue(out.toLowerCase().contains("ch1."), out);
        }
    }

    @Nested
    @DisplayName("Destructive-DDL detection gates the right statements")
    class DestructiveDetection {

        private boolean flaggedDestructive(String sql) {
            StringBuffer out = new StringBuffer();
            AtomicBoolean isDropOrTruncate = new AtomicBoolean(false);
            parser.parseSql(sql, "t", out, isDropOrTruncate);
            return isDropOrTruncate.get();
        }

        /**
         * The flag drives DISABLE_DROP_TRUNCATE. Under-detecting lets a DROP
         * through when the operator asked for protection; over-detecting blocks
         * legitimate schema evolution and silently desynchronises the
         * destination schema from the source, which is the more dangerous of
         * the two because it is invisible until a later INSERT fails.
         */
        @Test
        @DisplayName("genuinely destructive statements are flagged")
        public void destructiveStatementsAreFlagged() {
            // DESTRUCTIVE: inert test fixtures. These strings are only classified by the
            // detector; no connection exists in this test and nothing is executed.
            assertTrue(flaggedDestructive("DROP TABLE add_test"));
            assertTrue(flaggedDestructive("TRUNCATE TABLE add_test"));
        }

        @Test
        @DisplayName("schema evolution is not flagged as destructive")
        public void schemaEvolutionIsNotFlagged() {
            // DESTRUCTIVE: these ALTER ... DROP COLUMN/INDEX strings are inert fixtures
            // asserting the detector does NOT classify them as data destruction. Nothing
            // is executed - blast radius is zero.
            assertFalse(flaggedDestructive("ALTER TABLE add_test DROP COLUMN price"),
                    "ALTER ... DROP COLUMN is schema evolution; blocking it desynchronises "
                            + "the destination schema from the source");
            // DESTRUCTIVE: inert ALTER ... DROP INDEX fixture, classified only - nothing executed.
            assertFalse(flaggedDestructive("ALTER TABLE add_test DROP INDEX ix_price"));
            assertFalse(flaggedDestructive("ALTER TABLE add_test ADD COLUMN price DECIMAL(18,8)"));
            assertFalse(flaggedDestructive("CREATE TABLE add_test (id INT)"));
        }
    }

    @Nested
    @DisplayName("Forward compatibility: unknown syntax degrades safely")
    class UnknownSyntaxDegradation {

        /**
         * A newer MySQL release will emit clauses this translator has never
         * seen. The required behaviour is to drop the unknown clause and keep
         * the rest of the statement valid. Emitting malformed SQL instead
         * stalls the DDL stream, which is far worse than skipping one hint.
         */
        @Test
        @DisplayName("an unrecognised trailing clause still yields valid SQL")
        public void unknownTrailingClauseYieldsValidSql() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN qty INT, ALGORITHM=COPY, LOCK=SHARED");
            String trimmed = out.trim();
            assertFalse(trimmed.endsWith(","),
                    "an unknown clause must not leave the statement malformed: " + out);
            assertFalse(trimmed.isEmpty(),
                    "the known operation must still be emitted: [" + out + "]");
        }

        /**
         * Multiple ADD COLUMNs in one ALTER must all survive. Losing one
         * silently is the schema-drift failure mode: the destination table is
         * missing a column, and every later INSERT stores the DEFAULT for it.
         */
        @Test
        @DisplayName("all columns of a multi-column ALTER survive translation")
        public void multiColumnAlterKeepsEveryColumn() {
            String out = translate(
                    "ALTER TABLE add_test ADD COLUMN a INT, ADD COLUMN b VARCHAR(32), "
                            + "ADD COLUMN c DECIMAL(18,8)").toLowerCase();
            for (String col : new String[] {"a", "b", "c"}) {
                assertTrue(out.contains("`" + col + "`") || out.contains(" " + col + " "),
                        "column '" + col + "' was silently dropped; every later INSERT would "
                                + "store the ClickHouse DEFAULT for it: " + out);
            }
        }
    }
}
