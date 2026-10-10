package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates the heap a handed-off row occupies until it is acknowledged
 * (spec 01.05 §3.4 item 7).
 *
 * <p><b>Why an estimate in bytes.</b> Every bound on the handoff was in rows or
 * in batches: the per-queue capacity counts batches of any size, and the hard
 * cap counts rows. Neither knows how wide a row is, and a source table with
 * BLOB or JSON columns makes one row a megabyte. On a fixed heap the reader
 * must be paused by the bytes it has handed off, not by a count that means
 * something different for every table.</p>
 *
 * <p><b>What is measured.</b> The Debezium envelope the row still pins:
 * key and value payload, walked field by field ({@code Struct}, nested
 * {@code Struct}, strings, byte arrays and buffers, collections, maps,
 * boxed scalars). The walk sums PAYLOAD bytes; the retained heap is larger --
 * boxed values, {@code Object[]} per {@code Struct}, both row images of an
 * UPDATE, the Connect wrapper objects -- so the payload is scaled by
 * {@link #RETENTION_FACTOR} and a fixed {@link #PER_RECORD_OVERHEAD_BYTES} is
 * added for the row's own bookkeeping objects. The result is deliberately on
 * the high side: a cap that under-estimates lets the heap fill; one that
 * over-estimates pauses the reader a little early, which costs throughput
 * and nothing else.</p>
 *
 * <p><b>Cost.</b> A group is sampled ONCE PER TABLE: the first row of each
 * table (topic) in the group is walked and the result is charged to every
 * row of that table in the group. Rows of one table in one batch are alike
 * in width, and the walk is a few hundred field reads, so the estimate costs
 * nothing measurable at any batch rate. Sampling per table rather than per
 * group matters in single-threaded (legacy) mode, where one group is the
 * WHOLE batch across every table it touches: sampling the group's first row
 * charged a narrow table's width to every row of a wide table that followed
 * it, and the byte cap could not see the heap those rows pinned.</p>
 *
 * <p>Deterministic and JVM-independent by design: no reflection over object
 * graphs, no dependence on compressed oops or a HotSpot layout, so a unit
 * test can assert exact values.</p>
 */
public final class RecordSizeEstimator {

    /** Fixed heap charged to every row for its own objects, whatever its payload. */
    static final long PER_RECORD_OVERHEAD_BYTES = 512L;

    /** Retained-heap multiplier over the summed payload bytes (see class comment). */
    static final long RETENTION_FACTOR = 3L;

    /** Object header plus a reference: charged per boxed scalar and per container element. */
    private static final long OBJECT_OVERHEAD = 16L;

    /** A {@code String}: object, hash, array header, then two bytes per char. */
    private static final long STRING_OVERHEAD = 40L;

    /** A {@code Struct}: object, schema reference, values array header. */
    private static final long STRUCT_OVERHEAD = 32L;

    private RecordSizeEstimator() {
    }

    /**
     * Estimated retained bytes of one row.
     *
     * @param record the row; {@code null} costs nothing.
     * @return the estimate, never below {@link #PER_RECORD_OVERHEAD_BYTES} for a
     *         non-null row.
     */
    public static long estimate(ClickHouseStruct record) {
        if (record == null) {
            return 0L;
        }
        long payload = 0L;
        SourceRecord source = record.getSourceRecord() == null ? null : record.getSourceRecord().value();
        if (source != null) {
            payload += payloadBytes(source.key());
            payload += payloadBytes(source.value());
        } else {
            // No envelope (a synthetic or already-stripped row): the images
            // the sink kept are all there is to measure.
            payload += payloadBytes(record.getBeforeStruct());
            payload += payloadBytes(record.getAfterStruct());
        }
        return PER_RECORD_OVERHEAD_BYTES + payload * RETENTION_FACTOR;
    }

    /**
     * Estimates a group by sampling the first row of each table in it, stamps
     * every row with its table's per-row estimate
     * ({@link ClickHouseStruct#getEstimatedBytes()}), and returns the group
     * total.
     *
     * <p>A routed group holds the rows of one table, so it is sampled once. A
     * legacy-mode group is the whole Debezium batch and may hold several
     * tables; each is sampled on its own first row, so a narrow table at the
     * head of the batch cannot hide the bytes of a wide table behind it. The
     * per-table sample map lives for this call only and has at most one entry
     * per table in the group.</p>
     *
     * @param group the rows of one Debezium batch (one table in routed mode,
     *              any number of tables in legacy mode), non-null.
     * @return the sum of every row's stamped estimate; {@code 0} for an empty
     *         group.
     */
    public static long estimateGroup(List<ClickHouseStruct> group) {
        if (group == null || group.isEmpty()) {
            return 0L;
        }
        Map<String, Long> perRowByTable = new HashMap<>();
        long total = 0L;
        for (ClickHouseStruct record : group) {
            if (record == null) {
                continue;
            }
            String table = record.getTopic() == null ? "" : record.getTopic();
            Long perRow = perRowByTable.get(table);
            if (perRow == null) {
                perRow = estimate(record);
                perRowByTable.put(table, perRow);
            }
            record.setEstimatedBytes(perRow);
            total += perRow;
        }
        return total;
    }

    /**
     * Payload bytes of one value, recursively. Package-private for the test.
     */
    static long payloadBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Struct) {
            Struct struct = (Struct) value;
            long total = STRUCT_OVERHEAD;
            for (Field field : struct.schema().fields()) {
                total += OBJECT_OVERHEAD + payloadBytes(struct.get(field));
            }
            return total;
        }
        if (value instanceof CharSequence) {
            return STRING_OVERHEAD + 2L * ((CharSequence) value).length();
        }
        if (value instanceof byte[]) {
            return OBJECT_OVERHEAD + ((byte[]) value).length;
        }
        if (value instanceof ByteBuffer) {
            return OBJECT_OVERHEAD + ((ByteBuffer) value).remaining();
        }
        if (value instanceof BigDecimal) {
            // BigDecimal + BigInteger + magnitude array: 16 + 32 + the digits.
            return 48L + OBJECT_OVERHEAD + ((BigDecimal) value).unscaledValue().bitLength() / 8;
        }
        if (value instanceof Map) {
            long total = OBJECT_OVERHEAD;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                total += 2L * OBJECT_OVERHEAD + payloadBytes(entry.getKey()) + payloadBytes(entry.getValue());
            }
            return total;
        }
        if (value instanceof Collection) {
            long total = OBJECT_OVERHEAD;
            for (Object element : (Collection<?>) value) {
                total += OBJECT_OVERHEAD + payloadBytes(element);
            }
            return total;
        }
        // Boxed scalars, dates, and anything else small: one object.
        return OBJECT_OVERHEAD;
    }
}
