package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the behaviour of the ClickHouse metadata/connection helpers when
 * ClickHouse is not reachable.
 * <p>
 * On a cold start, {@code BaseDbWriter.createConnection} logs the failure and
 * returns null. The null connection is then passed down to DBMetadata, which
 * used to dereference it directly and fail with a NullPointerException instead
 * of a SQLException that callers already handle.
 * </p>
 */
public class ClickHouseUnavailableTest {

    /**
     * The retry loop sleeps 1000 * attempt ms, so sleeping out the default 10
     * retries would take 45s. A generous bound still shows that the retries
     * are skipped when no pool can hand out a connection.
     */
    private static final long MAX_ACCEPTABLE_MILLIS = 10_000L;

    private static ClickHouseSinkConnectorConfig emptyConfig() {
        Map<String, String> props = new HashMap<>();
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    public void testExecuteSystemQueryWithNullConnection() throws SQLException {
        int originalMaxRetries = DBMetadata.MAX_RETRIES;
        DBMetadata.setMaxRetries(10);
        // Make sure no pool exists, which is the cold start state.
        HikariDbSource.close();
        try {
            DBMetadata dbMetadata = new DBMetadata(emptyConfig());

            long start = System.currentTimeMillis();
            String result = dbMetadata.executeSystemQuery(null,
                    "create database if not exists altinity_sink_connector");
            long elapsed = System.currentTimeMillis() - start;

            Assert.assertNull(result);
            Assert.assertTrue("executeSystemQuery slept through its retry "
                            + "budget with no pool to reconnect from: " + elapsed + "ms",
                    elapsed < MAX_ACCEPTABLE_MILLIS);
        } finally {
            DBMetadata.setMaxRetries(originalMaxRetries);
        }
    }

    @Test
    public void testGetClickHouseVersionWithNullConnection() throws SQLException {
        int originalMaxRetries = DBMetadata.MAX_RETRIES;
        DBMetadata.setMaxRetries(10);
        HikariDbSource.close();
        try {
            DBMetadata dbMetadata = new DBMetadata(emptyConfig());

            long start = System.currentTimeMillis();
            String version = dbMetadata.getClickHouseVersion(null);
            long elapsed = System.currentTimeMillis() - start;

            Assert.assertNull(version);
            Assert.assertTrue("getClickHouseVersion slept through its retry "
                            + "budget with no pool to reconnect from: " + elapsed + "ms",
                    elapsed < MAX_ACCEPTABLE_MILLIS);
        } finally {
            DBMetadata.setMaxRetries(originalMaxRetries);
        }
    }

    @Test
    public void testInitiateNewConnectionWithoutPool() {
        // No pool exists for this database, which is the state after the
        // initial connection to ClickHouse failed.
        HikariDbSource.close();

        SQLException exception = assertThrows(SQLException.class,
                () -> HikariDbSource.initiateNewConnectionIfClosed("db_without_pool"));

        Assert.assertTrue(exception.getMessage().contains("db_without_pool"));
    }
}
