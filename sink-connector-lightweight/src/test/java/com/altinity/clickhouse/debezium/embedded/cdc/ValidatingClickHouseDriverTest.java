package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the six-hour CI hang in java-tests-lightweight.
 *
 * <p>Debezium's {@code RetriableConnection#executeWithRetry} only applies
 * {@code retry.max.attempts} / {@code wait.retry.delay.ms} on its reconnect
 * branch, which it reaches only when {@code isOpen()} is false — i.e. when
 * obtaining a connection FAILED. The clickhouse-jdbc 0.9.8 V2 driver returns
 * a lazily-connected object for an unreachable server (verified: connect
 * succeeds in ~150ms and {@code isClosed()} returns false), so that branch is
 * never taken and the loop spins on the statement-failure branch with no delay
 * and without incrementing {@code attempt}. Measured with Debezium's own class
 * against a dead port: 10,468 failed calls in 25s and still running, versus
 * termination in 12.0s on the V1 driver.
 *
 * <p>These tests pin the contract that keeps the bounded branch reachable:
 * connecting to an unreachable server must THROW rather than yield a
 * connection object.
 */
public class ValidatingClickHouseDriverTest {

    /**
     * The failure the hang depends on: a connection attempt against a server
     * that is not listening must fail, not return a usable-looking handle.
     * Reverting the validation in
     * {@link ValidatingClickHouseDriver#connect(String, Properties)} makes
     * this fail, because the V2 driver returns a connection for a dead port.
     */
    @Test
    @DisplayName("connect() to an unreachable server throws instead of returning a lazy connection")
    public void testConnectToDeadServerThrows() throws Exception {
        int deadPort = reserveClosedPort();
        String url = ValidatingClickHouseDriver.URL_PREFIX
                + "//localhost:" + deadPort + "/default?jdbc_ignore_unsupported_values=true";

        ValidatingClickHouseDriver driver = new ValidatingClickHouseDriver();
        Properties props = new Properties();
        props.setProperty("user", "default");
        props.setProperty("password", "");

        assertThrows(SQLException.class, () -> driver.connect(url, props),
                "an unreachable server must surface as a failure, otherwise Debezium's "
                        + "RetriableConnection never reaches its bounded reconnect branch");
    }

    /**
     * The stock driver must NOT claim the checked scheme, otherwise
     * DriverManager could route around the validation and reintroduce the
     * hang. Verified against clickhouse-jdbc 0.9.8.
     */
    @Test
    @DisplayName("the ClickHouse driver does not accept the checked scheme")
    public void testStockDriverDoesNotAcceptCheckedScheme() throws Exception {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        Driver stock = (Driver) Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
                .getDeclaredConstructor().newInstance();

        assertTrue(stock.acceptsURL("jdbc:clickhouse://localhost:8123/default"));
        assertFalse(stock.acceptsURL(ValidatingClickHouseDriver.URL_PREFIX + "//localhost:8123/default"),
                "if the stock driver accepted this scheme the validation could be bypassed");
        assertTrue(new ValidatingClickHouseDriver()
                .acceptsURL(ValidatingClickHouseDriver.URL_PREFIX + "//localhost:8123/default"));
    }

    /**
     * A URL this driver does not own must yield null so DriverManager can go
     * on to the next candidate driver.
     */
    @Test
    @DisplayName("connect() returns null for a foreign URL")
    public void testConnectReturnsNullForForeignUrl() throws SQLException {
        assertNull(new ValidatingClickHouseDriver()
                .connect("jdbc:mysql://localhost:3306/test", new Properties()));
    }

    @Test
    @DisplayName("URL rewriting round-trips and is idempotent")
    public void testUrlRewriting() {
        String original = "jdbc:clickhouse://localhost:8123/default?jdbc_ignore_unsupported_values=true";
        String checked = ValidatingClickHouseDriver.toCheckedUrl(original);

        assertEquals(ValidatingClickHouseDriver.URL_PREFIX
                        + "//localhost:8123/default?jdbc_ignore_unsupported_values=true", checked);
        assertEquals(original, ValidatingClickHouseDriver.toDelegateUrl(checked));
        assertEquals(checked, ValidatingClickHouseDriver.toCheckedUrl(checked),
                "rewriting twice must not double the prefix");
        assertNull(ValidatingClickHouseDriver.toCheckedUrl(null));
        assertEquals("jdbc:mysql://localhost:3306/t",
                ValidatingClickHouseDriver.toCheckedUrl("jdbc:mysql://localhost:3306/t"),
                "non-ClickHouse URLs must be left alone");
    }

    /**
     * Only Debezium's two internal storage URLs are rerouted; anything else in
     * the property set — in particular the sink's own connection settings —
     * must be untouched.
     */
    @Test
    @DisplayName("only the targeted Debezium storage URL is rerouted")
    public void testUseValidatingDriverScope() throws SQLException {
        Properties props = new Properties();
        props.setProperty("schema.history.internal.jdbc.url", "jdbc:clickhouse://ch:8123/default");
        props.setProperty("offset.storage.jdbc.url", "jdbc:clickhouse://ch:8123/default");
        props.setProperty("clickhouse.server.url", "jdbc:clickhouse://ch:8123/default");
        props.setProperty("database.hostname", "mysql");

        DebeziumChangeEventCapture.useValidatingDriver(props, "schema.history.internal.jdbc.url");
        DebeziumChangeEventCapture.useValidatingDriver(props, "offset.storage.jdbc.url");

        assertTrue(props.getProperty("schema.history.internal.jdbc.url")
                .startsWith(ValidatingClickHouseDriver.URL_PREFIX));
        assertTrue(props.getProperty("offset.storage.jdbc.url")
                .startsWith(ValidatingClickHouseDriver.URL_PREFIX));
        assertEquals("jdbc:clickhouse://ch:8123/default", props.getProperty("clickhouse.server.url"),
                "the sink data path must not be routed through the validating driver");
        assertEquals("mysql", props.getProperty("database.hostname"));

        // The rewritten URL must be resolvable, or Debezium would fail with
        // "No suitable driver" instead of connecting.
        assertTrue(DriverManager.getDriver(props.getProperty("offset.storage.jdbc.url"))
                        instanceof ValidatingClickHouseDriver);
    }

    @Test
    @DisplayName("useValidatingDriver is a no-op for absent or non-ClickHouse URLs")
    public void testUseValidatingDriverNoOp() {
        Properties props = new Properties();
        props.setProperty("offset.storage.jdbc.url", "jdbc:mysql://localhost:3306/t");

        DebeziumChangeEventCapture.useValidatingDriver(props, "offset.storage.jdbc.url");
        DebeziumChangeEventCapture.useValidatingDriver(props, "schema.history.internal.jdbc.url");

        assertEquals("jdbc:mysql://localhost:3306/t", props.getProperty("offset.storage.jdbc.url"));
        assertNull(props.getProperty("schema.history.internal.jdbc.url"));
    }

    /**
     * Returns a port that is bound and then released, so nothing is listening
     * on it — the same condition a stopped testcontainer leaves behind.
     */
    private static int reserveClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
