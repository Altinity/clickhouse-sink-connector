package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Gives Debezium's own change-event queue a bound in BYTES unless the operator
 * chose one (spec 01.05 §3.4 item 8).
 *
 * <p><b>The gap.</b> Debezium buffers the events it has read from the source
 * and not yet handed to the sink in an internal queue bounded by
 * {@code max.queue.size} (default 8192 EVENTS) and, optionally, by
 * {@code max.queue.size.in.bytes} -- whose Debezium default is {@code 0},
 * meaning the byte bound is OFF. Nothing in this connector or its deployment
 * templates set it. An event is one row: 8192 rows of a narrow table is a
 * hundred megabytes, 8192 rows of a table with megabyte BLOBs is many
 * gigabytes -- held in front of the sink's own hard cap, on the same fixed
 * heap, invisible to every bound the sink applies.</p>
 *
 * <p><b>The default.</b> One sixteenth of the JVM's maximum heap, floored at
 * {@link #MIN_DEFAULT_BYTES}: small next to the sink's handoff cap (one
 * quarter of the heap by default), large enough that a narrow-row source never
 * meets it. Debezium's queue blocks the reader when the bound is met, which
 * is the backpressure the sink wants to reach the binlog client anyway. An
 * operator's explicit value -- including {@code 0} to switch it off -- is
 * kept and logged.</p>
 */
public final class DebeziumQueueBytesPreflight {

    private static final Logger log = LogManager.getLogger(DebeziumQueueBytesPreflight.class);

    /** Debezium's own key for the byte bound on its change-event queue. */
    static final String PROPERTY = "max.queue.size.in.bytes";

    /** The default is never below this, whatever the heap: 64 MiB. */
    static final long MIN_DEFAULT_BYTES = 64L << 20;

    /** Fraction of the maximum heap the default claims: 1/16. */
    static final int HEAP_DIVISOR = 16;

    private DebeziumQueueBytesPreflight() {
    }

    /**
     * Applies the default to the properties the engine will be built from.
     *
     * @param props the connector properties, mutated in place.
     * @return {@code true} when this call set the property, {@code false} when
     *         the operator's value left it alone.
     */
    public static boolean apply(Properties props) {
        return apply(props, Runtime.getRuntime().maxMemory());
    }

    /** Same, for a given maximum heap; package-private for the test. */
    static boolean apply(Properties props, long maxHeapBytes) {
        String configured = props.getProperty(PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            log.info("{}={} (operator setting): Debezium's change-event queue is bounded in bytes "
                    + "by the operator's value (spec 01.05 section 3.4 item 8).", PROPERTY, configured.trim());
            return false;
        }
        long value = defaultFor(maxHeapBytes);
        props.setProperty(PROPERTY, Long.toString(value));
        log.info("{}={} (connector default, 1/{} of the {} MiB maximum heap, floor {} MiB): Debezium's "
                + "change-event queue -- the rows read from the source and not yet handed to the sink -- "
                + "is bounded in bytes, not only in events (max.queue.size). Debezium's own default is 0, "
                + "which leaves that queue bounded in events alone; on a wide-row source that is "
                + "gigabytes in front of every bound the sink applies (spec 01.05 section 3.4 item 8).",
                PROPERTY, value, HEAP_DIVISOR, maxHeapBytes >> 20, MIN_DEFAULT_BYTES >> 20);
        return true;
    }

    /** The default for a heap of the given size. */
    static long defaultFor(long maxHeapBytes) {
        if (maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE) {
            return MIN_DEFAULT_BYTES;
        }
        return Math.max(MIN_DEFAULT_BYTES, maxHeapBytes / HEAP_DIVISOR);
    }
}
