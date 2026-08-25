package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * Every generated schema-change statement must survive being applied twice.
 *
 * <p>Debezium flushes offsets periodically, so a connector restart re-delivers
 * every DDL event committed since the last flush -- including events already
 * applied to ClickHouse. Because DDL is retried indefinitely, ONE unguarded
 * replay does not merely fail its own table: it stalls the ENTIRE replication
 * stream, and the ClickHouse copy silently falls behind its source.</p>
 *
 * <p>Observed end to end: a connector restarted after a {@code DROP COLUMN}
 * retried the replayed statement 20 times and stopped applying row events
 * altogether. The three failing forms, measured on ClickHouse 24.8.14:</p>
 *
 * <pre>
 *   replayed ADD COLUMN     Code: 15 DUPLICATE_COLUMN
 *                           "column with this name already exists"
 *   replayed DROP COLUMN    Code: 10 NOT_FOUND_COLUMN_IN_BLOCK
 *                           "Cannot find column `x` to drop"
 *   replayed RENAME COLUMN  Code: 10 NOT_FOUND_COLUMN_IN_BLOCK
 *                           "Cannot find column `x` to rename"
 *   replayed MODIFY COLUMN  succeeds -- already idempotent
 * </pre>
 *
 * <p>These tests pin the guard on each generated statement. They are string
 * assertions rather than a live replay because the defect is in what the
 * connector EMITS: an unguarded statement is unsafe regardless of whether any
 * particular server run happens to replay it.</p>
 */
@DisplayName("Generated DDL must be safe to replay after a connector restart")
public class DdlReplayIdempotencyTest {

    private static MySQLDDLParserService parser;

    @BeforeAll
    static void init() {
        parser = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
    }

    private static String parse(String mysqlDdl, String table) {
        StringBuffer out = new StringBuffer();
        parser.parseSql(mysqlDdl, table, out);
        return out.toString().toLowerCase();
    }

    /**
     * A replayed unguarded drop returns Code: 10 and stalls the stream. This
     * is the form that was actually observed failing in production-shaped
     * testing.
     */
    @Test
    public void testDropColumnIsGuarded() {
        // DESTRUCTIVE: none. This parses a DDL string and asserts on the
        // generated text; no database is contacted and nothing is dropped.
        // The drop wording is the subject under test.
        String q = parse("alter table t drop column gone", "t");

        // DESTRUCTIVE: none -- substring check on a generated string.
        Assert.assertTrue("DROP COLUMN must carry IF EXISTS, or a replayed drop "
                + "fails with Code: 10 and the retry loop stalls replication: " + q,
                q.contains("drop column if exists"));
    }

    /**
     * A replayed unguarded add returns Code: 15, which stalls the stream just
     * as effectively as the drop case.
     */
    @Test
    public void testAddColumnIsGuarded() {
        String q = parse("alter table t add column c1 varchar(20)", "t");

        Assert.assertTrue("ADD COLUMN must carry IF NOT EXISTS, or a replayed add "
                + "fails with Code: 15 DUPLICATE_COLUMN: " + q,
                q.contains("add column if not exists"));
    }

    /**
     * A rename is not self-idempotent: once applied the old name is gone, so
     * the replay cannot find it.
     */
    @Test
    public void testRenameColumnIsGuarded() {
        String q = parse("alter table t rename column old_name to new_name", "t");

        Assert.assertTrue("RENAME COLUMN must carry IF EXISTS, or a replayed rename "
                + "fails with Code: 10 because the old name no longer exists: " + q,
                q.contains("rename column if exists"));
    }

    /**
     * Every clause of a multi-clause statement must be guarded. Guarding only
     * the first leaves the second able to stall the stream, which is the same
     * defect with a narrower trigger.
     */
    @Test
    public void testEveryClauseOfAMultiClauseStatementIsGuarded() {
        String adds = parse("alter table t add column a int, add column b varchar(10)", "t");
        Assert.assertEquals("both ADD clauses must be guarded, was: " + adds,
                2, countOccurrences(adds, "add column if not exists"));

        String renames = parse(
                "alter table employees.t rename column a to b, rename column b to c", "t");
        Assert.assertEquals("both RENAME clauses must be guarded, was: " + renames,
                2, countOccurrences(renames, "rename column if exists"));
    }

    /**
     * MODIFY COLUMN is deliberately left unguarded.
     *
     * <p>Re-applying the same type is already a no-op, so it needs no guard --
     * verified on 24.8.14. Adding IF EXISTS would additionally swallow a
     * modification of a column that genuinely does not exist, converting a real
     * schema divergence into silence. This test pins that deliberate asymmetry
     * so it is not "tidied up" later.</p>
     */
    @Test
    public void testModifyColumnIsDeliberatelyNotGuarded() {
        String q = parse("alter table t modify column c1 bigint", "t");

        Assert.assertTrue("MODIFY COLUMN must still be emitted: " + q,
                q.contains("modify column"));
        Assert.assertFalse("MODIFY is already idempotent; guarding it would hide a "
                + "modification of a column that does not exist: " + q,
                q.contains("modify column if exists"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx != -1) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
