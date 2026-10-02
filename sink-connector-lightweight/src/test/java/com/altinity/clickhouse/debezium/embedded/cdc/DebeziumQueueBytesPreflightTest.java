package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debezium's change-event queue gets a byte bound unless the operator chose
 * one (spec 01.05 §3.4 item 8).
 *
 * <p><b>The gap.</b> Debezium bounds its internal queue in events
 * ({@code max.queue.size}, default 8192) and only optionally in bytes
 * ({@code max.queue.size.in.bytes}, default {@code 0} = off). The connector
 * never set the byte bound, so on a wide-row source that queue holds
 * gigabytes in front of every bound the sink applies, on the same fixed heap.
 * The preflight pins a default derived from the maximum heap and leaves an
 * operator's explicit value -- including {@code 0} -- alone.</p>
 */
public class DebeziumQueueBytesPreflightTest {

    private static final long GIB = 1L << 30;

    @Test
    @DisplayName("absent: set to 1/16 of the maximum heap, floored at 64 MiB")
    public void absentGetsTheHeapDerivedDefault() {
        Properties props = new Properties();
        assertTrue(DebeziumQueueBytesPreflight.apply(props, 128 * GIB), "the preflight set it");
        assertEquals(Long.toString(8 * GIB), props.getProperty(DebeziumQueueBytesPreflight.PROPERTY),
                "128 GiB heap -> 8 GiB queue bound");

        Properties small = new Properties();
        DebeziumQueueBytesPreflight.apply(small, 256L << 20);
        assertEquals(Long.toString(DebeziumQueueBytesPreflight.MIN_DEFAULT_BYTES),
                small.getProperty(DebeziumQueueBytesPreflight.PROPERTY), "256 MiB heap -> the 64 MiB floor");
    }

    @Test
    @DisplayName("an unknown or unlimited heap gets the floor, never zero and never an overflow")
    public void unknownHeapGetsTheFloor() {
        assertEquals(DebeziumQueueBytesPreflight.MIN_DEFAULT_BYTES, DebeziumQueueBytesPreflight.defaultFor(Long.MAX_VALUE));
        assertEquals(DebeziumQueueBytesPreflight.MIN_DEFAULT_BYTES, DebeziumQueueBytesPreflight.defaultFor(0));
        assertEquals(DebeziumQueueBytesPreflight.MIN_DEFAULT_BYTES, DebeziumQueueBytesPreflight.defaultFor(-1));
    }

    @Test
    @DisplayName("an operator's value is kept, including 0 (off) and a blank that is treated as absent")
    public void operatorValueIsKept() {
        Properties explicit = new Properties();
        explicit.setProperty(DebeziumQueueBytesPreflight.PROPERTY, "1073741824");
        assertFalse(DebeziumQueueBytesPreflight.apply(explicit, 128 * GIB));
        assertEquals("1073741824", explicit.getProperty(DebeziumQueueBytesPreflight.PROPERTY));

        Properties off = new Properties();
        off.setProperty(DebeziumQueueBytesPreflight.PROPERTY, "0");
        assertFalse(DebeziumQueueBytesPreflight.apply(off, 128 * GIB), "0 is a choice, not an absence");
        assertEquals("0", off.getProperty(DebeziumQueueBytesPreflight.PROPERTY));

        Properties blank = new Properties();
        blank.setProperty(DebeziumQueueBytesPreflight.PROPERTY, "  ");
        assertTrue(DebeziumQueueBytesPreflight.apply(blank, 128 * GIB), "blank is absent");
        assertEquals(Long.toString(8 * GIB), blank.getProperty(DebeziumQueueBytesPreflight.PROPERTY));
    }

    @Test
    @DisplayName("the real entry point reads the running JVM's maximum heap and always yields a positive bound")
    public void realEntryPointIsPositive() {
        Properties props = new Properties();
        assertTrue(DebeziumQueueBytesPreflight.apply(props));
        long value = Long.parseLong(props.getProperty(DebeziumQueueBytesPreflight.PROPERTY));
        assertTrue(value >= DebeziumQueueBytesPreflight.MIN_DEFAULT_BYTES, "at least the floor: " + value);
    }
}
