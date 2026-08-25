package com.altinity.clickhouse.debezium.embedded.api;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Properties;

/**
 * {@code DebeziumEmbeddedRestApi.app} is static and {@code startRestApi()}
 * called {@code Javalin.create().start(port)} unconditionally, so a second
 * start in the same JVM threw
 * {@code JavalinBindException: Port already in use. Make sure no other process
 * is using port 7000 and try again.}
 *
 * <p>The integration suite runs several tests that each bring up the embedded
 * application inside one forked JVM, so the first bind won and every later one
 * died in a pool thread -- failing the job on a port collision rather than on
 * whatever the test was actually asserting. Observed on CI run 32568334759,
 * job java-tests-lightweight, repeated across pool-53/60/85/92/99.</p>
 *
 * <p>These tests exercise the start/stop lifecycle directly rather than
 * standing up Debezium, so they need no Docker and run in the unit suite.</p>
 */
public class DebeziumEmbeddedRestApiDoubleStartTest {

    @AfterEach
    public void tearDown() {
        DebeziumEmbeddedRestApi.stop();
    }

    /** An ephemeral free port, so the test never fights a real service. */
    private static String freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return String.valueOf(socket.getLocalPort());
        }
    }

    private static Properties propsOn(String port) {
        Properties props = new Properties();
        props.setProperty("cli.port", port);
        return props;
    }

    /**
     * The defect: calling startRestApi() twice must not throw. Before the fix
     * the second call raised JavalinBindException.
     */
    @Test
    public void startingTwiceDoesNotThrowBindException() throws Exception {
        String port = freePort();

        DebeziumEmbeddedRestApi.startRestApi(propsOn(port), null, null, new Properties());
        Javalin first = DebeziumEmbeddedRestApi.app();
        Assertions.assertNotNull(first, "first start must bind a server");

        Assertions.assertDoesNotThrow(
                () -> DebeziumEmbeddedRestApi.startRestApi(
                        propsOn(port), null, null, new Properties()),
                "a second startRestApi() in the same JVM must reuse the running "
                        + "server, not bind the port again");

        Assertions.assertSame(first, DebeziumEmbeddedRestApi.app(),
                "the second call must reuse the same server instance");
    }

    /**
     * stop() must clear the static reference, otherwise the already-running
     * check would short-circuit forever and the REST API would stay dead after
     * any stop/start cycle.
     */
    @Test
    public void stopThenStartBindsAFreshServer() throws Exception {
        String port = freePort();

        DebeziumEmbeddedRestApi.startRestApi(propsOn(port), null, null, new Properties());
        Javalin first = DebeziumEmbeddedRestApi.app();

        DebeziumEmbeddedRestApi.stop();
        Assertions.assertNull(DebeziumEmbeddedRestApi.app(),
                "stop() must clear the reference so a restart can bind again");

        DebeziumEmbeddedRestApi.startRestApi(propsOn(port), null, null, new Properties());
        Assertions.assertNotNull(DebeziumEmbeddedRestApi.app(),
                "the REST API must come back after a stop/start cycle");
        Assertions.assertNotSame(first, DebeziumEmbeddedRestApi.app(),
                "a restart must produce a new server instance");
    }

    /** stop() on a server that was never started must be a no-op, not an NPE. */
    @Test
    public void stopWithoutStartIsSafe() {
        Assertions.assertDoesNotThrow(DebeziumEmbeddedRestApi::stop);
    }
}
