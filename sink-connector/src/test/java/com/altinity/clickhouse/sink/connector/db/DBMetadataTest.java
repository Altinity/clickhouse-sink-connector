package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.commons.lang3.tuple.MutablePair;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DBMetadata — Phase 12 edge case coverage.
 * <p>
 * Validates:
 * - Engine detection from response strings
 * - Version column parsing for ReplacingMergeTree variants
 * - Sign column parsing for CollapsingMergeTree
 * - New ReplacingMergeTree version check
 * </p>
 */
@Testcontainers
public class DBMetadataTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8")
            .withInitScript("./init_clickhouse.sql").withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml");

    @AfterAll
    public static void cleanup() {
        HikariDbSource.close();
    }

    private DBMetadata createMetadata() {
        Properties props = new Properties();
        props.put("clickhouse.server.url", "localhost");
        props.put("clickhouse.server.port", "8123");
        props.put("clickhouse.server.user", "test");
        props.put("clickhouse.server.password", "test");
        return new DBMetadata(props);
    }

    @Nested
    @DisplayName("getEngineFromResponse")
    class EngineDetectionTests {

        @Test
        @DisplayName("CollapsingMergeTree engine should be detected")
        public void testCollapsingMergeTree() {
            DBMetadata meta = createMetadata();
            var result = meta.getEngineFromResponse("CollapsingMergeTree(sign)");
            assertEquals(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, result.left);
        }

        @Test
        @DisplayName("ReplacingMergeTree engine should be detected")
        public void testReplacingMergeTree() {
            DBMetadata meta = createMetadata();
            var result = meta.getEngineFromResponse("ReplacingMergeTree(ver)");
            assertEquals(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, result.left);
        }

        @Test
        @DisplayName("ReplicatedReplacingMergeTree should be detected")
        public void testReplicatedReplacingMergeTree() {
            DBMetadata meta = createMetadata();
            var result = meta.getEngineFromResponse(
                    "ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/test', '{replica}', ver)");
            assertEquals(DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE, result.left);
        }

        @Test
        @DisplayName("MergeTree engine should be detected")
        public void testMergeTree() {
            DBMetadata meta = createMetadata();
            var result = meta.getEngineFromResponse("MergeTree()");
            assertEquals(DBMetadata.TABLE_ENGINE.MERGE_TREE, result.left);
        }

        @Test
        @DisplayName("Unknown engine should return DEFAULT")
        public void testUnknownEngine() {
            DBMetadata meta = createMetadata();
            var result = meta.getEngineFromResponse("TinyLog()");
            assertEquals(DBMetadata.TABLE_ENGINE.DEFAULT, result.left);
        }
    }

    @Nested
    @DisplayName("getSignColumnForCollapsingMergeTree")
    class SignColumnTests {

        @Test
        @DisplayName("Standard CollapsingMergeTree should extract sign column")
        public void testStandardSign() {
            DBMetadata meta = createMetadata();
            String result = meta.getSignColumnForCollapsingMergeTree(
                    "CREATE TABLE ... CollapsingMergeTree(sign_col)");
            assertEquals("sign_col", result);
        }

        @Test
        @DisplayName("Non-CollapsingMergeTree should return default 'sign'")
        public void testNonCollapsingMergeTree() {
            DBMetadata meta = createMetadata();
            String result = meta.getSignColumnForCollapsingMergeTree(
                    "CREATE TABLE ... MergeTree()");
            assertEquals("sign", result);
        }
    }

    @Nested
    @DisplayName("getVersionColumnForReplacingMergeTree")
    class VersionColumnTests {

        @Test
        @DisplayName("Simple ReplacingMergeTree should extract version column")
        public void testSimpleVersion() {
            DBMetadata meta = createMetadata();
            String result = meta.getVersionColumnForReplacingMergeTree(
                    "CREATE TABLE ... ReplacingMergeTree(ver)");
            assertEquals("ver", result);
        }

        @Test
        @DisplayName("ReplicatedReplacingMergeTree with 3 params should extract version")
        public void testReplicatedVersion3Params() {
            DBMetadata meta = createMetadata();
            String result = meta.getVersionColumnForReplacingMergeTree(
                    "CREATE TABLE ... ReplicatedReplacingMergeTree('/path', '{replica}', ver)");
            assertEquals("ver", result);
        }

        @Test
        @DisplayName("ReplicatedReplacingMergeTree with 4 params should extract version+deleted")
        public void testReplicatedVersion4Params() {
            DBMetadata meta = createMetadata();
            String result = meta.getVersionColumnForReplacingMergeTree(
                    "CREATE TABLE ... ReplicatedReplacingMergeTree('/path', '{replica}', ver, is_deleted)");
            // Each parameter is trimmed and re-joined with a bare comma (no space).
            // DbWriter.configureReplacingMergeTreeColumns splits this on "," and
            // trims each part, so both column names resolve correctly either way.
            assertEquals("ver,is_deleted", result);
        }
    }

    @Nested
    @DisplayName("checkIfNewReplacingMergeTree")
    class VersionCheckTests {

        @Test
        @DisplayName("Version 23.3 should be new ReplacingMergeTree")
        public void testNewVersion() throws Exception {
            DBMetadata meta = createMetadata();
            assertTrue(meta.checkIfNewReplacingMergeTree("23.3.1.1"));
        }

        @Test
        @DisplayName("Version 22.8 should NOT be new ReplacingMergeTree")
        public void testOldVersion() throws Exception {
            DBMetadata meta = createMetadata();
            assertFalse(meta.checkIfNewReplacingMergeTree("22.8.1.1"));
        }

        @Test
        @DisplayName("Version 23.2 should be new ReplacingMergeTree (boundary)")
        public void testBoundaryVersion() throws Exception {
            DBMetadata meta = createMetadata();
            assertTrue(meta.checkIfNewReplacingMergeTree("23.2.0.0"));
        }
    }

    @Nested
    @DisplayName("MAX_RETRIES configuration")
    class RetryConfigTests {

        @Test
        @DisplayName("setMaxRetries should update the retry count")
        public void testSetMaxRetries() {
            int original = DBMetadata.MAX_RETRIES;
            try {
                DBMetadata.setMaxRetries(5);
                assertEquals(5, DBMetadata.MAX_RETRIES);
            } finally {
                DBMetadata.setMaxRetries(original);
            }
        }
    }

    /**
     * Regression test for the underscore wildcard defect in
     * getColumnsDataTypesForTable().
     * <p>
     * The previous implementation used
     * {@code DatabaseMetaData.getColumns(null, database, tableName, null)},
     * whose tableNamePattern argument is a JDBC LIKE pattern: '_' matches any
     * single character. A table named {@code under_score_t} therefore also
     * matched {@code underXscore_t}, and the columns of both tables were
     * merged into one map. A wrong column map produces a wrong INSERT column
     * list, i.e. silent data corruption.
     * <p>
     * Verified live against ClickHouse 24.8.8 with clickhouse-jdbc 0.9.8 on
     * BOTH driver generations. Reverting the fix in DBMetadata makes this test
     * fail with 4 columns instead of 2.
     */
    @Test
    @Tag("IntegrationTest")
    public void testGetColumnsDataTypesForTableWithUnderscoreDoesNotMatchSiblingTable()
            throws SQLException {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String database = "wildcard_db";

        // Use an unpooled connection so this test cannot disturb the Hikari
        // pools shared by the other tests in this class.
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        ClickHouseSinkConnectorConfig unpooled = new ClickHouseSinkConnectorConfig(props);

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, "system");
        Connection conn = DbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                userName, password, "system", unpooled);

        conn.createStatement().execute("CREATE DATABASE IF NOT EXISTS " + database);
        // DESTRUCTIVE: drops the two fixture tables this test creates below, and
        // nothing else. Blast radius is bounded to the throwaway `wildcard_db`
        // database inside the ephemeral testcontainers ClickHouse instance,
        // which is destroyed when the container stops. Needed so a re-run
        // starts from a known schema.
        conn.createStatement().execute("DROP TABLE IF EXISTS " + database + ".under_score_t");
        // DESTRUCTIVE: drops the sibling fixture table created below; same
        // bounded blast radius as above (throwaway `wildcard_db` inside the
        // ephemeral test container only).
        conn.createStatement().execute("DROP TABLE IF EXISTS " + database + ".underXscore_t");
        // target table: exactly two columns
        conn.createStatement().execute("CREATE TABLE " + database + ".under_score_t"
                + " (id Int32, v String) ENGINE=MergeTree ORDER BY id");
        // sibling table matched only by the '_' LIKE wildcard: different columns
        conn.createStatement().execute("CREATE TABLE " + database + ".underXscore_t"
                + " (decoy_a Int64, decoy_b String) ENGINE=MergeTree ORDER BY decoy_a");

        Map<String, String> columns = new DBMetadata(unpooled)
                .getColumnsDataTypesForTable(conn, "under_score_t", database);

        Assert.assertEquals(2, columns.size());
        Assert.assertEquals("Int32", columns.get("id"));
        Assert.assertEquals("String", columns.get("v"));
        Assert.assertFalse(columns.containsKey("decoy_a"));
        Assert.assertFalse(columns.containsKey("decoy_b"));

        // The sibling table must still resolve to its own columns.
        Map<String, String> sibling = new DBMetadata(unpooled)
                .getColumnsDataTypesForTable(conn, "underXscore_t", database);
        Assert.assertEquals(2, sibling.size());
        Assert.assertEquals("Int64", sibling.get("decoy_a"));

        // A table that genuinely does not exist must yield an empty map, not
        // the columns of some same-named table in another database.
        Map<String, String> missing = new DBMetadata(unpooled)
                .getColumnsDataTypesForTable(conn, "under_score_t", "no_such_db");
        Assert.assertTrue(missing.isEmpty());
    }
}
