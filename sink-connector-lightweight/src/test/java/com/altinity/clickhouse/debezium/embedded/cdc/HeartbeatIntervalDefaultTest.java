package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit coverage for the heartbeat default that makes issue #1379's fix
 * reachable.
 *
 * <p>The control-record offset commit added for #1379 can only act on a
 * heartbeat that Debezium actually emits. {@code heartbeat.interval.ms}
 * defaults to 0 -- heartbeats disabled -- and the connector never set it, so
 * on a source that goes idle after the initial snapshot there was nothing to
 * commit and {@code snapshot_completed} stayed false forever. On restart that
 * makes Debezium re-run the entire snapshot.</p>
 *
 * <p>{@code PostgresSnapshotCompletionIT} proves the end-to-end behaviour
 * against real containers; this pins the property contract cheaply, including
 * the escape hatch.</p>
 */
public class HeartbeatIntervalDefaultTest {

    @Test
    @DisplayName("#1379: an absent heartbeat interval is defaulted, so heartbeats are emitted")
    public void testAbsentIntervalGetsDefault() {
        Properties props = new Properties();

        DebeziumChangeEventCapture.ensureHeartbeatInterval(props);

        assertEquals("heartbeats must be enabled by default, otherwise the "
                        + "end-of-snapshot state can never be committed on an idle source",
                DebeziumChangeEventCapture.DEFAULT_HEARTBEAT_INTERVAL_MS,
                props.getProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS));
    }

    @Test
    @DisplayName("A user-configured interval is never overridden")
    public void testConfiguredIntervalWins() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS, "30000");

        DebeziumChangeEventCapture.ensureHeartbeatInterval(props);

        assertEquals("30000",
                props.getProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS));
    }

    /**
     * Explicitly disabling heartbeats stays possible. It reintroduces the
     * #1379 symptom on an idle source, which the connector warns about, but
     * it is the user's call to make.
     */
    @Test
    @DisplayName("An explicit 0 is honoured -- the escape hatch stays open")
    public void testExplicitZeroIsHonoured() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS, "0");

        DebeziumChangeEventCapture.ensureHeartbeatInterval(props);

        assertEquals("0",
                props.getProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS));
    }

    /**
     * A blank value is not a configured value. Treating it as one would
     * silently reinstate the bug for anyone whose config templating renders
     * an empty string.
     */
    @Test
    @DisplayName("A blank value is treated as unset and defaulted")
    public void testBlankIntervalIsDefaulted() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS, "   ");

        DebeziumChangeEventCapture.ensureHeartbeatInterval(props);

        assertEquals(DebeziumChangeEventCapture.DEFAULT_HEARTBEAT_INTERVAL_MS,
                props.getProperty(DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS));
    }

    /** Null properties are inert: this runs on the startup path. */
    @Test
    @DisplayName("Null properties do not throw")
    public void testNullPropertiesAreInert() {
        DebeziumChangeEventCapture.ensureHeartbeatInterval(null);
    }

    /**
     * The default must be a positive integer of milliseconds. Debezium parses
     * this as a duration; a non-numeric or zero default would either fail
     * startup or silently disable heartbeats again.
     */
    @Test
    @DisplayName("The default is a positive integer number of milliseconds")
    public void testDefaultIsSaneDuration() {
        long ms = Long.parseLong(DebeziumChangeEventCapture.DEFAULT_HEARTBEAT_INTERVAL_MS);
        org.junit.Assert.assertTrue("default heartbeat interval must be > 0, got " + ms, ms > 0);
        org.junit.Assert.assertTrue("default heartbeat interval should stay well under a "
                + "minute so snapshot completion is observable promptly, got " + ms,
                ms <= 30_000L);
    }

    /** The property name must match Debezium's, or the default silently does nothing. */
    @Test
    @DisplayName("The property key is Debezium's heartbeat.interval.ms")
    public void testPropertyKeyMatchesDebezium() {
        assertEquals("heartbeat.interval.ms",
                DebeziumChangeEventCapture.HEARTBEAT_INTERVAL_MS);
        assertNull(new Properties().getProperty("heartbeat.interval.ms"));
    }
}
