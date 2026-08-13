package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

/**
 * End-to-end proof that BOTH bundled JDBC driver implementations (legacy V1
 * = 0.6.x behavior, and the new V2 default) can connect, create tables,
 * insert with PreparedStatement batches, and read data back — against a
 * real ClickHouse server. This is the backward/forward-compatibility gate
 * for the clickhouse.jdbc.v1 runtime toggle: identical data must come back
 * through either driver, with no loss.
 */
@Testcontainers
public class DualJdbcDriverEndToEndIT {

    @Container
    private static final ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8")
                    .withInitScript("./init_clickhouse.sql");

    @BeforeAll
    public static void init() {
        clickHouseContainer.start();
    }

    @AfterAll
    public static void cleanup() {
        HikariDbSource.close();
    }

    private Connection connect(boolean useV1Driver, boolean poolDisabled) {
        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();

        HashMap<String, String> raw = new HashMap<>();
        raw.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        raw.put(ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString(),
                String.valueOf(useV1Driver));
        raw.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(),
                String.valueOf(poolDisabled));
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(raw);

        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, "default");
        Connection conn = BaseDbWriter.createConnection(jdbcUrl,
                BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                BaseDbWriter.SYSTEM_DB, config);
        Assert.assertNotNull("connection must be established (useV1Driver="
                + useV1Driver + ", poolDisabled=" + poolDisabled + ")", conn);
        return conn;
    }

    /** Round-trips rows through the given driver and verifies content. */
    private void roundTrip(boolean useV1Driver, String tableName) throws SQLException {
        // Pool disabled: exercises SinkConnectorDataSource.getConnection(user, pass)
        // directly, which is the path BaseDbWriter uses for both drivers.
        Connection conn = connect(useV1Driver, true);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS default." + tableName
                    + " (id Int32, name String, amount Float64)"
                    + " ENGINE = MergeTree ORDER BY id");
        }

        String insert = "INSERT INTO default." + tableName
                + " (id, name, amount) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (int i = 1; i <= 100; i++) {
                ps.setInt(1, i);
                ps.setString(2, "row-" + i);
                ps.setDouble(3, i * 1.5);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        int count = 0;
        long idSum = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, amount FROM default." + tableName
                             + " ORDER BY id")) {
            while (rs.next()) {
                count++;
                idSum += rs.getInt(1);
                Assert.assertEquals("row-" + rs.getInt(1), rs.getString(2));
                Assert.assertEquals(rs.getInt(1) * 1.5, rs.getDouble(3), 0.0001);
            }
        }
        Assert.assertEquals("all 100 rows must survive the round trip "
                + "(driver=" + (useV1Driver ? "V1" : "V2") + ")", 100, count);
        Assert.assertEquals("id checksum must match (no corruption)", 5050, idSum);
        conn.close();
    }

    @Test
    @Tag("IntegrationTest")
    public void testV2DriverRoundTrip() throws SQLException {
        roundTrip(false, "dual_jdbc_v2_rt");
    }

    @Test
    @Tag("IntegrationTest")
    public void testV1DriverRoundTrip() throws SQLException {
        roundTrip(true, "dual_jdbc_v1_rt");
    }

    /**
     * Cross-driver compatibility: data written by one driver must be fully
     * readable by the other (backward AND forward compatibility). This is
     * the "no disruption on driver switch" guarantee — an operator can flip
     * clickhouse.jdbc.v1 on a live pipeline and previously written data
     * stays intact and consistent.
     */
    @Test
    @Tag("IntegrationTest")
    public void testCrossDriverReadback() throws SQLException {
        String tableName = "dual_jdbc_cross";
        Connection v2 = connect(false, true);
        try (Statement stmt = v2.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS default." + tableName
                    + " (id Int32, src String) ENGINE = MergeTree ORDER BY id");
        }
        try (PreparedStatement ps = v2.prepareStatement(
                "INSERT INTO default." + tableName + " (id, src) VALUES (?, ?)")) {
            for (int i = 1; i <= 50; i++) {
                ps.setInt(1, i);
                ps.setString(2, "written-by-v2");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        v2.close();

        // Old driver writes the second half.
        Connection v1 = connect(true, true);
        try (PreparedStatement ps = v1.prepareStatement(
                "INSERT INTO default." + tableName + " (id, src) VALUES (?, ?)")) {
            for (int i = 51; i <= 100; i++) {
                ps.setInt(1, i);
                ps.setString(2, "written-by-v1");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        // Old driver reads everything back (forward compatibility).
        int v1Seen = 0;
        try (Statement stmt = v1.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count() FROM default." + tableName)) {
            if (rs.next()) {
                v1Seen = rs.getInt(1);
            }
        }
        v1.close();
        Assert.assertEquals("V1 driver must see all rows from both writers", 100, v1Seen);

        // New driver reads everything back (backward compatibility).
        Connection v2b = connect(false, true);
        int v2Seen = 0;
        try (Statement stmt = v2b.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count() FROM default." + tableName)) {
            if (rs.next()) {
                v2Seen = rs.getInt(1);
            }
        }
        v2b.close();
        Assert.assertEquals("V2 driver must see all rows from both writers", 100, v2Seen);
    }

    /**
     * The pooled path (HikariCP) must work for both drivers too — this is the
     * default production configuration.
     */
    @Test
    @Tag("IntegrationTest")
    public void testPooledConnectionBothDrivers() throws SQLException {
        for (boolean v1 : new boolean[]{false, true}) {
            Connection conn = connect(v1, false);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                Assert.assertTrue(rs.next());
                Assert.assertEquals(1, rs.getInt(1));
            }
            conn.close();
            // Each driver variant needs its own pool; close between variants
            // because the pool is keyed by database name only.
            HikariDbSource.close();
        }
    }
}
