package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.03 section 3.5: {@code enableMetrics} becomes true only after the Prometheus HTTP server is bound, is reset
 * by {@code stop()}, and a {@code stop()} that follows {@code initialize()} immediately never races a detached
 * server-start thread ({@code IllegalStateException: server in wrong state}).
 */
public class MetricsBindLifecycleTest {

    private Thread.UncaughtExceptionHandler previousHandler;
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void captureUncaught() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.add(throwable));
    }

    @AfterEach
    public void restore() {
        Metrics.stop();
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("a successful bind enables metrics and stop() disables them again")
    public void enabledOnlyBetweenBindAndStop() throws IOException {
        assertFalse(Metrics.isEnabled(), "disabled before initialize");

        Metrics.initialize("true", String.valueOf(freePort()));
        assertTrue(Metrics.isEnabled(), "enabled once the server is bound");
        assertTrue(Metrics.isRegistryOpen());

        Metrics.stop();
        assertFalse(Metrics.isEnabled(), "stop() resets the flag");
        assertFalse(Metrics.isRegistryOpen());
    }

    @Test
    @DisplayName("a bind failure leaves metrics disabled, releases the registry, and does not throw")
    public void bindFailureLeavesMetricsDisabled() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int busyPort = occupied.getLocalPort();

            assertDoesNotThrow(() -> Metrics.initialize("true", String.valueOf(busyPort)));

            assertFalse(Metrics.isEnabled(), "the flag must not claim metrics are served when the bind failed");
            assertFalse(Metrics.isRegistryOpen(), "the half-built registry is released");
            // An update after the failed start is dropped, never thrown.
            assertDoesNotThrow(() -> Metrics.updateCounters("topic-a", 1));
        }
    }

    @Test
    @DisplayName("a later initialize on a free port recovers from an earlier bind failure")
    public void recoversAfterBindFailure() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0)) {
            Metrics.initialize("true", String.valueOf(occupied.getLocalPort()));
            assertFalse(Metrics.isEnabled());
        }
        Metrics.initialize("true", String.valueOf(freePort()));
        assertTrue(Metrics.isEnabled());
    }

    @Test
    @DisplayName("metrics requested false never enable, even after an earlier enabled run")
    public void disabledFlagStaysDisabled() throws IOException {
        Metrics.initialize("true", String.valueOf(freePort()));
        assertTrue(Metrics.isEnabled());

        Metrics.initialize("false", String.valueOf(freePort()));
        assertFalse(Metrics.isEnabled());
    }

    @Test
    @DisplayName("initialize() immediately followed by stop() leaves no background thread failing with 'server in wrong state'")
    public void startStopRaceLeavesNoUncaughtException() throws Exception {
        for (int i = 0; i < 25; i++) {
            Metrics.initialize("true", String.valueOf(freePort()));
            Metrics.stop();
        }
        // Give any detached thread (the pre-fix start-up path) time to fail.
        Thread.sleep(200);

        assertTrue(uncaught.isEmpty(), "uncaught exceptions on background threads: " + uncaught);
    }
}
