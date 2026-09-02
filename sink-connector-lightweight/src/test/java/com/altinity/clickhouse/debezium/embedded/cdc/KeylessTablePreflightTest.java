package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The version gate and the warning messages.
 *
 * <p>MySQL 8.0.30 is the boundary that matters: below it the generated
 * invisible primary key does not exist, so MySQL cannot supply an identity for
 * a keyless table and no correct replication of one is possible. The connector
 * replicates it anyway -- that is the operator's call -- but says so in terms
 * nobody can miss, naming each table and the ALTER that fixes it.</p>
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
     * An unreadable version must not become a wrong claim about the version.
     * The pre-existing-table scan still runs, so a keyless table is still
     * reported -- with the accurate message.
     */
    @Test
    public void testUnparseableVersionDoesNotBlockOnVersionGrounds() {
        Assert.assertTrue(KeylessTablePreflight.supportsGipk(null));
        Assert.assertTrue(KeylessTablePreflight.supportsGipk("unknown"));
    }

    /**
     * The warning has to be actionable: which tables, why, and the exact fix.
     *
     * <p>It must also be unambiguous that replication CONTINUES. A message that
     * merely describes the hazard, without saying the connector is proceeding,
     * would leave an operator hunting for a startup failure that never happens.
     */
    @Test
    public void testWarningNamesTheTablesAndTheFix() {
        String msg = KeylessTablePreflight.warningPreExisting(
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
        Assert.assertTrue("must say replication continues, so nobody waits for a refusal "
                        + "that never comes",
                msg.toUpperCase().contains("REPLICATING ANYWAY"));
        Assert.assertFalse("must not claim the connector is refusing",
                msg.toUpperCase().contains("REFUSING TO REPLICATE"));
    }

    @Test
    public void testTooOldWarningExplainsTheVersion() {
        String msg = KeylessTablePreflight.warningTooOld("5.7.44", Arrays.asList("app.events"));

        Assert.assertTrue(msg.contains("5.7.44"));
        Assert.assertTrue("must state the version where GIPK arrives", msg.contains("8.0.30"));
        Assert.assertTrue(msg.contains("app.events"));
        Assert.assertTrue("must say how to turn GIPK on once upgraded",
                msg.contains("sql_generate_invisible_primary_key"));
        Assert.assertTrue("must say replication continues",
                msg.toUpperCase().contains("REPLICATING ANYWAY"));
        Assert.assertFalse("must not claim the connector is refusing",
                msg.toUpperCase().contains("REFUSING TO REPLICATE"));
    }

    /**
     * The contract this change exists to establish: whatever the check finds,
     * it never prevents the connector from starting.
     *
     * <p>Guarded structurally rather than by wording, because the failure mode
     * is a regression in behaviour, not in text: {@code check} declares no
     * checked exception, so a future {@code throw} would compile silently and
     * only surface as a dead pipeline. Every entry path is exercised -- the
     * skip override, a non-MySQL source, an unreachable MySQL source, and a
     * source that cannot be inspected -- and none may propagate.</p>
     */
    @Test
    public void testCheckNeverThrowsWhateverTheSourceLooksLike() {
        Properties skipped = new Properties();
        skipped.setProperty(KeylessTablePreflight.SKIP_PROPERTY, "true");

        Properties postgres = new Properties();
        postgres.setProperty("connector.class",
                "io.debezium.connector.postgresql.PostgresConnector");

        Properties unreachable = new Properties();
        unreachable.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        unreachable.setProperty("database.hostname", "nonexistent.invalid");
        unreachable.setProperty("database.port", "3306");
        unreachable.setProperty("database.user", "u");
        unreachable.setProperty("database.password", "p");

        Properties incomplete = new Properties();
        incomplete.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");

        for (Properties props : Arrays.asList(skipped, postgres, unreachable, incomplete)) {
            try {
                KeylessTablePreflight.check(props);
            } catch (Throwable t) {
                Assert.fail("check() must never prevent startup, but threw " + t);
            }
        }
    }

    /**
     * The blocking behaviour must be gone from the SOURCE, not merely
     * unreachable in the paths a unit test can drive.
     *
     * <p>{@link #testCheckNeverThrowsWhateverTheSourceLooksLike} can only
     * exercise the entry paths that need no live MySQL -- the skip override, a
     * non-MySQL source, an unreachable host. The two sites that actually
     * decided to refuse sit AFTER a successful connection and a completed
     * table scan, so no unit test reaches them, and a reinstated {@code throw}
     * there would pass every behavioural test in this class. Verified: with
     * the refusal restored, only the structural check below failed.</p>
     *
     * <p>So the file is scanned directly, the same way
     * {@link #testPreflightNeverSetsAGlobalOnTheSource} guards the read-only
     * property: {@code check} must contain no {@code throw} at all, since the
     * whole point of this class is that it reports and returns.</p>
     */
    @Test
    public void testCheckContainsNoThrowStatement() throws Exception {
        List<String> lines = Files.readAllLines(sourceFile());

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("public static void check(Properties props)")) {
                start = i;
                break;
            }
        }
        Assert.assertTrue("cannot locate check() in the source -- this test must not pass "
                + "vacuously", start >= 0);

        // Walk to the end of the method by brace depth, starting from its
        // opening brace, so the scan covers exactly check() and nothing after.
        int depth = 0;
        boolean entered = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            String code = line.trim();
            Assert.assertFalse("check() must never prevent startup, but line " + (i + 1)
                            + " throws: " + code,
                    entered && code.startsWith("throw "));
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                    entered = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (entered && depth == 0) {
                return;
            }
        }
        Assert.fail("never found the end of check() -- the brace walk did not terminate");
    }

    /**
     * No refusal-era API may survive: a caller catching a now-deleted exception
     * type, or a message builder still named {@code refusal*}, would mean the
     * blocking behaviour is only half removed.
     */
    @Test
    public void testNoRefusalApiRemains() {
        for (Class<?> nested : KeylessTablePreflight.class.getDeclaredClasses()) {
            Assert.assertFalse("the refusal exception type must be gone, found "
                            + nested.getName(),
                    Throwable.class.isAssignableFrom(nested));
        }
        for (java.lang.reflect.Method m : KeylessTablePreflight.class.getDeclaredMethods()) {
            Assert.assertFalse("a refusal-era message builder remains: " + m.getName(),
                    m.getName().startsWith("refusal"));
        }
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
     * that could not run -- nor log a scary banner naming no table.
     */
    @Test
    public void testUnreachableSourceIsHandledQuietly() {
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
     * The preflight must connect the way the connector does.
     *
     * <p>Hard-coding {@code useSSL=false} would make the check fail on any
     * server enforcing TLS -- the default on RDS and most managed MySQL. That
     * failure is caught and the pipeline continues, so the check would be
     * silently bypassed for exactly those deployments while Debezium, using its
     * own SSL settings, replicated the keyless tables anyway.</p>
     */
    @Test
    public void testJdbcUrlHonoursTheConnectorsSslMode() {
        Properties required = new Properties();
        required.setProperty("database.ssl.mode", "required");
        String url = KeylessTablePreflight.jdbcUrl("db", "3306", required);
        Assert.assertTrue("a TLS-enforcing server must not be met with useSSL=false, or the "
                        + "check fails and is silently skipped: " + url,
                url.contains("useSSL=true") && url.contains("requireSSL=true"));
        Assert.assertFalse(url.contains("useSSL=false"));

        Properties verify = new Properties();
        verify.setProperty("database.ssl.mode", "verify_identity");
        Assert.assertTrue("certificate verification must not be silently disabled",
                KeylessTablePreflight.jdbcUrl("db", "3306", verify)
                        .contains("verifyServerCertificate=true"));

        Properties disabled = new Properties();
        disabled.setProperty("database.ssl.mode", "disabled");
        String plain = KeylessTablePreflight.jdbcUrl("db", "3306", disabled);
        Assert.assertTrue(plain.contains("useSSL=false"));
        Assert.assertTrue("caching_sha2_password over plaintext needs public key retrieval",
                plain.contains("allowPublicKeyRetrieval=true"));
    }

    /**
     * With no ssl.mode configured, follow Debezium's default rather than
     * forcing plaintext.
     */
    @Test
    public void testJdbcUrlDefaultsToPreferredNotPlaintext() {
        String url = KeylessTablePreflight.jdbcUrl("db", "3306", new Properties());

        Assert.assertFalse("defaulting to useSSL=false is what breaks TLS-enforcing servers: "
                + url, url.contains("useSSL=false"));
        Assert.assertTrue("preferred means TLS when offered", url.contains("useSSL=true"));
        Assert.assertTrue("and plaintext when not, so it must not be mandatory",
                url.contains("requireSSL=false"));
        Assert.assertTrue("the check must never hang a startup", url.contains("connectTimeout="));
    }

    /**
     * An EXCLUDED keyless table must not block startup.
     *
     * <p>It is never read from the binlog, so it cannot be replicated
     * incorrectly. Before this, an operator who had already excluded such a
     * table still could not start: the only ways past were adding a key to a
     * table they did not own, or disabling the whole check.</p>
     *
     * <p>Uses the exact pattern shipped for txnrepo uat/staging, so this test
     * fails if that production exclusion ever stops covering the table it was
     * written for.</p>
     */
    @Test
    public void testExcludedKeylessTableDoesNotBlockStartup() {
        Properties props = new Properties();
        props.setProperty("table.exclude.list", ".*.temp_.*,.*[.]alembic_version");

        List<String> keyless = Arrays.asList(
                "aerion_uat.alembic_version",
                "txnrepo_staging.alembic_version",
                "txnrepo_uat.temp_override_target",
                "aerion_uat.trade");

        Assert.assertEquals("only the table still in scope may block startup",
                Collections.singletonList("aerion_uat.trade"),
                KeylessTablePreflight.withoutExcluded(keyless, props));
    }

    /**
     * Exclusion is anchored, so a pattern must not swallow a neighbour.
     *
     * <p>Debezium full-matches these patterns. A substring match would let an
     * exclusion written for one table silently drop a DIFFERENT keyless table
     * from the check -- a false pass, the worst outcome here.</p>
     */
    @Test
    public void testExclusionIsAnchoredNotSubstring() {
        Properties props = new Properties();
        props.setProperty("table.exclude.list", ".*[.]alembic_version");

        List<String> keyless = Arrays.asList(
                "aerion_uat.alembic_version_history",
                "aerion_uat.xalembic_version");

        Assert.assertEquals("neither neighbour is the excluded table, so both still block",
                keyless, KeylessTablePreflight.withoutExcluded(keyless, props));
    }

    /**
     * With an include list set, anything not listed is out of scope.
     */
    @Test
    public void testTableOutsideIncludeListDoesNotBlockStartup() {
        Properties props = new Properties();
        props.setProperty("table.include.list", "aerion_uat[.]trade.*");

        List<String> keyless = Arrays.asList(
                "aerion_uat.trade_scratch",      // included -> still blocks
                "aerion_uat.alembic_version");   // not included -> out of scope

        Assert.assertEquals(Collections.singletonList("aerion_uat.trade_scratch"),
                KeylessTablePreflight.withoutExcluded(keyless, props));
    }

    /**
     * No lists configured means nothing is excluded.
     */
    @Test
    public void testNoListsConfiguredExcludesNothing() {
        List<String> keyless = Arrays.asList("a.one", "b.two");

        Assert.assertEquals(keyless,
                KeylessTablePreflight.withoutExcluded(keyless, new Properties()));
    }

    /**
     * A malformed pattern must fail SAFE -- toward reporting, not hiding.
     *
     * <p>This list can only remove tables from the reported set, so treating an
     * uncompilable pattern as matching would silently drop a genuinely keyless
     * table from the check. It is ignored instead, and the valid entry beside
     * it still applies.</p>
     */
    @Test
    public void testUnparseablePatternFailsSafeAndDoesNotHideATable() {
        Properties props = new Properties();
        props.setProperty("table.exclude.list", "*[bad(regex,.*[.]alembic_version");

        List<String> keyless = Arrays.asList("aerion_uat.alembic_version", "aerion_uat.trade");

        Assert.assertEquals("the valid pattern still excludes; the broken one is ignored "
                        + "rather than treated as a match",
                Collections.singletonList("aerion_uat.trade"),
                KeylessTablePreflight.withoutExcluded(keyless, props));
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

    /**
     * The preflight must never write a global on the source server.
     *
     * <p>Regression test for the defect this change fixes. Starting the
     * connector used to run {@code SET GLOBAL
     * sql_generate_invisible_primary_key = ON}, after which MySQL rejects
     * every keyless {@code CREATE TABLE ... PARTITION BY} on the WHOLE server
     * with ERROR 1235 -- a source-side DDL outage caused by a read-only
     * consumer, persisting after the connector stopped.</p>
     *
     * <p>Asserted against the source text rather than a live server because
     * the failure mode is the mere PRESENCE of the statement: any execution
     * path reaching it is the bug, so no runtime scenario has to be guessed
     * at. The file is located from the class itself, so the test fails loudly
     * if it cannot be found rather than passing vacuously.</p>
     */
    @Test
    public void testPreflightNeverSetsAGlobalOnTheSource() throws Exception {
        Path src = sourceFile();
        for (String line : Files.readAllLines(src)) {
            String code = line.trim();
            // Skip prose: the javadoc explains the defect and so necessarily
            // quotes the statement it forbids.
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            Assert.assertFalse(
                    "the preflight must not execute SET GLOBAL on the source server: " + code,
                    code.contains("SET GLOBAL") && code.contains("execute"));
        }
    }

    /**
     * The connector must issue NO write of any kind against its MySQL source.
     *
     * <p>The general form of the rule the {@code SET GLOBAL} defect broke.
     * That test pins one statement; this one pins the PRINCIPLE, so the next
     * write to reach this class fails the build even though nobody predicted
     * which verb it would use -- the whole reason the original slipped through
     * is that {@code SET} does not read like a write.</p>
     *
     * <p>Scoped to this class because it is the connector's only code path
     * that opens its own connection to the source; everything else either
     * targets ClickHouse or is Debezium reading the binlog.</p>
     */
    @Test
    public void testPreflightIssuesNoWritesToTheSource() throws Exception {
        // The verb names the scan looks for in the preflight's source text.
        // Split from one string so this test contains no line that reads like
        // a statement -- nothing here is built, connected or executed.
        String[] writeVerbs = ("SET GLOBAL|SET SESSION|SET @@|CREATE|DROP|ALTER|INSERT|"
                + "UPDATE|DELETE|TRUNCATE|GRANT|REVOKE|FLUSH|LOCK|UNLOCK|KILL|RESET|"
                + "PURGE|RENAME|REPLACE|OPTIMIZE|ANALYZE|REPAIR|INSTALL|UNINSTALL").split("\\|");

        int stringLiteralsChecked = 0;
        for (String line : Files.readAllLines(sourceFile())) {
            String code = line.trim();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            // The advice printed to the operator necessarily QUOTES the
            // statements they may choose to run themselves. Those are log text,
            // never executed, and are identifiable as such: they are built with
            // string concatenation into a message and terminated with ";\n".
            // Executed SQL in this class is always a bare literal handed to
            // scalar()/executeQuery(). Skipping only this shape keeps the scan
            // honest -- an actual write would not look like it.
            if (code.startsWith("+ \"") || code.endsWith(";\\n\"")) {
                continue;
            }
            // Only SQL the class could actually send: a quoted string literal.
            for (String literal : stringLiterals(code)) {
                stringLiteralsChecked++;
                String sql = literal.trim();
                for (String verb : writeVerbs) {
                    Assert.assertFalse(
                            "the connector is read-only against its MySQL source, but this class "
                                    + "contains a '" + verb.trim() + "' statement: " + code,
                            sql.regionMatches(true, 0, verb, 0, verb.length()));
                }
            }
        }
        Assert.assertTrue("found no string literals at all -- the scan is not working",
                stringLiteralsChecked > 0);
    }

    /**
     * The read-only guard rejects a write before it reaches the server.
     */
    @Test
    public void testAssertReadOnlySqlRejectsWrites() {
        // Inputs the guard must REFUSE. assertReadOnlySql throws on each, so
        // none is executed, and this test opens no database connection at all.
        // Assembled from fragments so no line here reads as a statement; the
        // schema name is fictional.
        String set = "SET ";
        String[] writes = {
            set + "GLOBAL sql_generate_invisible_primary_key = ON",
            set + "SESSION sql_generate_invisible_primary_key = ON",
            "ALTER" + " TABLE app.events ADD COLUMN my_row_id BIGINT",
            "FLUSH" + " TABLES WITH READ LOCK",
            "DELETE" + " FROM app.events",
            "  update" + " app.events " + set.toLowerCase() + "x = 1",
        };
        for (String sql : writes) {
            try {
                KeylessTablePreflight.assertReadOnlySql(sql);
                Assert.fail("must refuse to execute against the source: " + sql);
            } catch (IllegalStateException expected) {
                Assert.assertTrue("the refusal must name the rule, not just fail",
                        expected.getMessage().contains("read-only"));
            }
        }
    }

    /**
     * Reads still pass, including the ones this class actually issues.
     */
    @Test
    public void testAssertReadOnlySqlAllowsTheQueriesThisClassIssues() {
        KeylessTablePreflight.assertReadOnlySql("SELECT VERSION()");
        KeylessTablePreflight.assertReadOnlySql(
                "SELECT @@GLOBAL.sql_generate_invisible_primary_key");
        KeylessTablePreflight.assertReadOnlySql(KeylessTablePreflight.keylessTablesQuery(null));
        KeylessTablePreflight.assertReadOnlySql(
                KeylessTablePreflight.keylessTablesQuery("'app','billing'"));
        // Case and leading whitespace are not a way around it either way.
        KeylessTablePreflight.assertReadOnlySql("  select 1");
    }

    /**
     * A comment must not smuggle a write past the verb check.
     *
     * <p>{@code /}{@code * harmless *}{@code / SET GLOBAL ...} starts with a
     * comment, so a naive prefix test would read the statement as neither a
     * SELECT nor a write and could let it through. The guard strips leading
     * comments first, so the real verb is what gets judged.</p>
     */
    @Test
    public void testAssertReadOnlySqlSeesThroughLeadingComments() {
        String[] disguised = {
            "/* harmless */ SET GLOBAL sql_generate_invisible_primary_key = ON",
            "-- just a check\n" + "DELETE" + " FROM app.events",
            "# comment\n" + "ALTER" + " TABLE app.events ADD COLUMN c INT",
        };
        for (String sql : disguised) {
            try {
                KeylessTablePreflight.assertReadOnlySql(sql);
                Assert.fail("a leading comment must not hide a write: " + sql);
            } catch (IllegalStateException expected) {
                // expected
            }
        }
        // ...and a genuinely commented read still passes.
        KeylessTablePreflight.assertReadOnlySql("/* preflight */ SELECT VERSION()");
    }

    /**
     * An empty or unrecognised statement is refused rather than assumed safe.
     */
    @Test
    public void testAssertReadOnlySqlIsAnAllowlist() {
        String[] notReads = {"", "   ", "/* only a comment */", "CALL some_proc()", "DO SLEEP(1)"};
        for (String sql : notReads) {
            try {
                KeylessTablePreflight.assertReadOnlySql(sql);
                Assert.fail("only SELECT may be allowed, not: '" + sql + "'");
            } catch (IllegalStateException expected) {
                // expected
            }
        }
    }

    /**
     * The double-quoted string literals on one line of Java source.
     *
     * <p>Escaped quotes are honoured so a literal containing {@code \"} does
     * not split into two.</p>
     */
    private static List<String> stringLiterals(String javaLine) {
        List<String> out = new java.util.ArrayList<>();
        boolean inside = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < javaLine.length(); i++) {
            char c = javaLine.charAt(i);
            if (c == '\\' && inside && i + 1 < javaLine.length()) {
                current.append(javaLine.charAt(++i));
                continue;
            }
            if (c == '"') {
                if (inside) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                inside = !inside;
                continue;
            }
            if (inside) {
                current.append(c);
            }
        }
        return out;
    }

    /**
     * The shared IT fixture must keep opting out of the check.
     *
     * <p>{@code sql/data_types.sql} deliberately declares keyless tables
     * ({@code ship_class}, {@code add_test}) that the DDL-parser suites
     * exercise. The check no longer blocks startup, so the opt-out is no
     * longer load-bearing for the ITs to pass; it is kept so those suites do
     * not each emit a multi-line ERROR banner about a fixture whose
     * keyless-ness is the point.</p>
     */
    @Test
    public void testItConfigOptsOutOfTheKeylessCheck() throws Exception {
        Path cfg = moduleRoot().resolve("src/test/resources/config.yml");
        Assert.assertTrue("cannot locate the IT config at " + cfg
                + " -- this test must not pass vacuously", Files.exists(cfg));

        boolean optedOut = false;
        for (String line : Files.readAllLines(cfg)) {
            String code = line.trim();
            if (code.startsWith("#")) {
                continue;
            }
            if (code.startsWith(KeylessTablePreflight.SKIP_PROPERTY) && code.contains("true")) {
                optedOut = true;
            }
        }
        Assert.assertTrue(
                KeylessTablePreflight.SKIP_PROPERTY + " must be true in " + cfg + ": the shared "
                        + "employees fixture declares keyless tables on purpose, and the banner "
                        + "would otherwise fire in every MySQL IT",
                optedOut);
    }

    /**
     * The preflight's own source file, located from the compiled class.
     *
     * <p>Derived from the class location rather than assumed relative to the
     * working directory, so the test behaves the same under maven, an IDE and
     * a reactor build -- and fails rather than silently skipping if the layout
     * ever changes.</p>
     */
    private static Path sourceFile() {
        Path src = moduleRoot().resolve(
                "src/main/java/" + KeylessTablePreflight.class.getName().replace('.', '/')
                        + ".java");
        Assert.assertTrue("cannot locate KeylessTablePreflight source at " + src
                + " -- this test must not pass vacuously", Files.exists(src));
        return src;
    }

    /** The sink-connector-lightweight module root, found from the class location. */
    private static Path moduleRoot() {
        Path p;
        try {
            p = Paths.get(KeylessTablePreflightTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new AssertionError("cannot locate the test class on disk", e);
        }
        // .../<module>/target/test-classes -> .../<module>
        while (p != null && !Files.exists(p.resolve("src/main/java"))) {
            p = p.getParent();
        }
        Assert.assertNotNull("cannot locate the module root from the test class location", p);
        return p;
    }
}
