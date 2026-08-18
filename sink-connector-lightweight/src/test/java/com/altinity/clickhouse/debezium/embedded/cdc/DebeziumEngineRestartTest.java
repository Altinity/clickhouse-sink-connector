package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Guards the engine-restart wiring in {@link DebeziumChangeEventCapture}.
 *
 * <p>When the Debezium engine stops with an error, its completion callback is
 * the only thing that can bring it back: {@code setup()} calls
 * {@code setupDebeziumEventCapture()} exactly once and returns. A revision
 * that removed the retry from that callback left the connector permanently
 * down after the first binlog communication failure — replication simply
 * stopped and every later change was never applied, with no further error.
 *
 * <p>The restart must also not run on the callback thread. Rebuilding the
 * engine inline stacks the replacement engine underneath the failed one's
 * frame, so repeated failures nest until the stack overflows. The restart is
 * therefore dispatched to a dedicated single-thread executor, which lets the
 * callback unwind first and serialises attempts.
 *
 * <p>These tests pin the structural invariants that keep both properties
 * true. They deliberately do not start an engine — doing so would need a live
 * MySQL and ClickHouse — so they assert on the wiring rather than on a
 * simulated failure.
 */
public class DebeziumEngineRestartTest {

    private static Object field(Object target, String name) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    @DisplayName("A dedicated restart executor exists and is distinct from the event executor")
    public void testRestartExecutorIsSeparate() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        Object restart = field(capture, "debeziumRestartExecutor");
        Object event = field(capture, "singleThreadDebeziumEventExecutor");

        assertNotNull("restart executor must be created in the constructor", restart);
        assertNotNull(event);
        // Sharing the event executor would deadlock: the restart task would
        // queue behind engine.run(), which only returns once the engine stops.
        assertNotSame("restart must not reuse the Debezium event executor", event, restart);
    }

    @Test
    @DisplayName("stop() shuts the restart executor down so no retry outlives the connector")
    public void testStopShutsDownRestartExecutor() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ExecutorService restart = (ExecutorService) field(capture, "debeziumRestartExecutor");

        assertFalse("restart executor should be live before stop()", restart.isShutdown());

        capture.stop();

        // A retry sleeping through its back-off must be cancelled, not allowed
        // to wake up and rebuild an engine on a connector that is shutting down.
        assertTrue("stop() must shut the restart executor down", restart.isShutdown());
    }

    @Test
    @DisplayName("The retry budget is bounded by MAX_RETRIES rather than unbounded")
    public void testRetryBudgetIsBounded() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        // numRetries starts fresh; the callback compares it against MAX_RETRIES
        // before scheduling, so an engine that can never start stops retrying
        // instead of looping forever.
        assertTrue("MAX_RETRIES must be positive", DebeziumChangeEventCapture.MAX_RETRIES > 0);
        assertTrue("numRetries must start at zero", capture.numRetries == 0);
    }

    /**
     * Loads the config.properties that ships inside the jar.
     *
     * <p>Read from the module source rather than the classpath on purpose: the
     * test resources contain their own config.properties, which shadows the
     * shipped one during tests. Asserting against the classpath copy would
     * therefore check the fixture instead of the file that reaches users.</p>
     */
    private static Properties shippedConfig() throws Exception {
        Properties shipped = new Properties();
        Path path = Paths.get("src/main/resources/config.properties");
        assertTrue("shipped config.properties not found at " + path.toAbsolutePath(),
                Files.exists(path));
        try (InputStream in = Files.newInputStream(path)) {
            shipped.load(in);
        }
        return shipped;
    }

    @Test
    @DisplayName("The shipped replica.status.view default keeps both %s placeholders")
    public void testShippedViewDefaultIsParameterised() throws Exception {
        String view = shippedConfig().getProperty("replica.status.view");
        assertNotNull("replica.status.view must have a shipped default", view);

        // createViewForShowReplicaStatus does String.format(view, dbName,
        // dbName + "." + tableName). A format string with no placeholders
        // silently discards both arguments, so the view would be created
        // against whatever database the literal names regardless of config.
        assertEquals("replica.status.view default must keep both %s placeholders",
                2, view.split("%s", -1).length - 1);
    }

    @Test
    @DisplayName("The initial-snapshot guard is advisory unless explicitly opted in")
    public void testInitialSnapshotGuardIsOptIn() throws Exception {
        Properties shipped = shippedConfig();

        // The guard throws only when this is true. It must not ship enabled:
        // snapshot.mode=initial is the default, so a connector that has already
        // been replicating would refuse to restart and stay down until someone
        // logs into the host and touches a file.
        assertFalse("the initial-snapshot guard must not be fatal by default",
                Boolean.parseBoolean(
                        shipped.getProperty("snapshot.initial.require.confirmation", "false")));
    }
}
