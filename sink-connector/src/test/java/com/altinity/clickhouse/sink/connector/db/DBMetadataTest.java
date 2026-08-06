package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
public class DBMetadataTest {

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
}
