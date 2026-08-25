package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Properties;

/**
 * The version gate and the refusal messages.
 *
 * <p>MySQL 8.0.30 is the boundary that matters: below it the generated
 * invisible primary key does not exist, so MySQL cannot supply an identity for
 * a keyless table and no correct replication of one is possible. The connector
 * refuses rather than producing a ClickHouse copy that silently disagrees with
 * its source.</p>
 */
public class KeylessTablePreflightTest {

    @Test
    public void testVersionsAtOrAboveTheGipkReleaseAreSupported() {
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("8.0.30"));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("8.0.36"));
        // Real servers append a suffix.
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("8.0.36-log"));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("8.0.40-0ubuntu0.22.04.1"));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("8.4.0"));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("9.0.0"));
    }

    @Test
    public void testVersionsBelowTheGipkReleaseAreNot() {
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("8.0.29"));
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("8.0.0"));
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("5.7.44"));
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("5.6.51-log"));
    }

    /**
     * MariaDB reports a MySQL-shaped version but has no GIPK, so treating it as
     * supported would let a keyless table through on the strength of a setting
     * that does not exist there.
     */
    @Test
    public void testMariaDbIsNotTreatedAsSupported() {
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("10.11.6-MariaDB"));
        Assert.assertFalse(KeylessTablePreflight.supportsGipk("11.4.2-MariaDB-log"));
    }

    /**
     * An unreadable version must not become a silent refusal. The
     * pre-existing-table scan still runs, so a keyless table is still caught --
     * with the accurate message rather than a wrong claim about the version.
     */
    @Test
    public void testUnparseableVersionDoesNotBlockOnVersionGrounds() {
        Assert.assertTrue(KeylessTablePreflight.supportsGipk(null));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("unknown"));
    }

    /**
     * The refusal has to be actionable: which tables, why, and the exact fix.
     */
    @Test
    public void testRefusalNamesTheTablesAndTheFix() {
        String msg = KeylessTablePreflight.refusalPreExisting(
                Arrays.asList("app.events", "app.audit"));

        Assert.assertTrue("must name every offending table", msg.contains("app.events"));
        Assert.assertTrue(msg.contains("app.audit"));
        Assert.assertTrue("must be an unmissable banner, not one log line", msg.contains("!!"));
        Assert.assertTrue("must say this is bad practice", msg.toUpperCase().contains("BAD PRACTICE"));
        Assert.assertTrue("must carry the exact fix",
                msg.contains("ADD COLUMN my_row_id BIGINT UNSIGNED NOT NULL"));
        Assert.assertTrue("must state that enabling GIPK does not fix existing tables",
                msg.contains("NOT retroactive"));
        Assert.assertTrue("must state the override exists, so nobody has to guess",
                msg.contains(KeylessTablePreflight.SKIP_PROPERTY));
    }

    @Test
    public void testTooOldRefusalExplainsTheVersion() {
        String msg = KeylessTablePreflight.refusalTooOld("5.7.44", Arrays.asList("app.events"));

        Assert.assertTrue(msg.contains("5.7.44"));
        Assert.assertTrue("must state the version where GIPK arrives", msg.contains("8.0.30"));
        Assert.assertTrue(msg.contains("app.events"));
        Assert.assertTrue("must say how to turn GIPK on once upgraded",
                msg.contains("sql_generate_invisible_primary_key"));
    }

    /**
     * A non-MySQL source must pass through untouched: this check is about
     * MySQL's binlog row identity and says nothing about Postgres or Mongo.
     */
    @Test
    public void testNonMysqlConnectorIsNotChecked() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("database.hostname", "nonexistent.invalid");
        props.setProperty("database.user", "u");

        // Returns without attempting any connection.
        KeylessTablePreflight.check(props);
    }

    /**
     * An unreachable source must not stop a pipeline on the strength of a check
     * that could not run. The refusal is for a source proven unsafe, not for
     * one that could not be inspected.
     */
    @Test
    public void testUnreachableSourceDoesNotRefuse() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "nonexistent.invalid");
        props.setProperty("database.port", "3306");
        props.setProperty("database.user", "u");
        props.setProperty("database.password", "p");

        KeylessTablePreflight.check(props);
    }

    /**
     * A UNIQUE index counts only when EVERY column in it is NOT NULL.
     *
     * <p>MySQL does not treat NULLs as equal for uniqueness, so
     * {@code UNIQUE(a, b)} with a nullable {@code b} accepts {@code (1, NULL)}
     * twice -- verified on MySQL 8.0.36. Testing merely "some column of the
     * index is NOT NULL" would pass such a table as keyed while it has no
     * identity at all, which is the silent collapse this check exists to
     * prevent.</p>
     */
    @Test
    public void testUniqueIndexCountsOnlyWhenEveryColumnIsNotNull() {
        String sql = KeylessTablePreflight.keylessTablesQuery(null);

        Assert.assertTrue("the nullable test must be per index, not per column",
                sql.contains("GROUP BY s.index_name"));
        Assert.assertTrue("an index with any nullable member must not count as an identity",
                sql.contains("HAVING SUM(CASE WHEN c.is_nullable = 'YES' THEN 1 ELSE 0 END) = 0"));
        Assert.assertFalse("the any-column form passes a partially nullable composite UNIQUE "
                        + "key as keyed, which is the false negative being fixed",
                sql.contains("AND c.is_nullable = 'NO')"));
    }

    /**
     * A literal include list narrows the scan; a regex one must NOT.
     *
     * <p>Debezium matches database.include.list as regular expressions, so
     * {@code app.*} names no schema literally. Rendering it as
     * {@code IN ('app.*')} would match nothing and report a clean source while
     * the replicated schemas went uninspected -- a false PASS. Falling back to
     * scanning everything can only surface a genuinely keyless table.</p>
     */
    @Test
    public void testLiteralIncludeListNarrowsTheScan() {
        Properties props = new Properties();
        props.setProperty("database.include.list", "app, billing");

        Assert.assertEquals("'app','billing'", KeylessTablePreflight.databaseFilter(props));
    }

    @Test
    public void testRegexIncludeListScansEverythingRatherThanMatchingNothing() {
        for (String pattern : new String[]{"app.*", "app[0-9]+", "^app$", "app|billing"}) {
            Properties props = new Properties();
            props.setProperty("database.include.list", pattern);
            Assert.assertNull("a regex entry (" + pattern + ") must widen the scan, never be "
                            + "compared literally with SQL IN",
                    KeylessTablePreflight.databaseFilter(props));
        }
    }

    @Test
    public void testAbsentIncludeListScansEverything() {
        Assert.assertNull(KeylessTablePreflight.databaseFilter(new Properties()));
    }

    /**
     * The override works, so an operator who accepts the risk is not blocked.
     */
    @Test
    public void testSkipPropertyBypassesTheCheck() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "nonexistent.invalid");
        props.setProperty("database.user", "u");
        props.setProperty(KeylessTablePreflight.SKIP_PROPERTY, "true");

        KeylessTablePreflight.check(props);
    }
}
