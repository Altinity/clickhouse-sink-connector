package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the rows of one INSERT group into chunks bounded in rows AND in
 * estimated bytes (spec 03.06 §3.1).
 *
 * <p><b>Why bytes.</b> {@code buffer.max.records} bounds a chunk in rows, and
 * the JDBC driver renders every chunk as SQL text in memory -- the substituted
 * statement per row, then one concatenated INSERT -- before it is sent. A
 * chunk of ten thousand rows is tens of megabytes of text for a narrow table
 * and tens of gigabytes for a table of megabyte BLOBs; on a fixed heap, with
 * one such chunk in flight per worker, the row count alone bounds nothing.
 * Each row carries the estimate stamped at handoff
 * ({@link ClickHouseStruct#getEstimatedBytes()}), so the chunk can be closed
 * on bytes as well.</p>
 *
 * <p>Rules: order is preserved; a row is never split; a chunk always holds at
 * least one row, so a single row wider than the byte limit is written on its
 * own rather than refused; a limit of {@code 0} or less disables that
 * dimension; a row whose estimate is {@code 0} (never stamped) counts as zero
 * bytes, so the row limit still applies to it.</p>
 */
public final class BatchChunker {

    private BatchChunker() {
    }

    /**
     * @param records  the rows of one INSERT group, in order.
     * @param maxRows  the most rows per chunk; {@code <= 0} means no row limit.
     * @param maxBytes the most estimated bytes per chunk; {@code <= 0} means no
     *                 byte limit.
     * @return the chunks, in order, together holding exactly {@code records}.
     */
    public static List<List<ClickHouseStruct>> chunk(List<ClickHouseStruct> records, long maxRows, long maxBytes) {
        List<List<ClickHouseStruct>> chunks = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            return chunks;
        }
        List<ClickHouseStruct> current = new ArrayList<>();
        long currentBytes = 0L;
        for (ClickHouseStruct record : records) {
            long rowBytes = record == null ? 0L : Math.max(0L, record.getEstimatedBytes());
            boolean rowsFull = maxRows > 0 && current.size() >= maxRows;
            boolean bytesFull = maxBytes > 0 && !current.isEmpty() && currentBytes + rowBytes > maxBytes;
            if (rowsFull || bytesFull) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 0L;
            }
            current.add(record);
            currentBytes += rowBytes;
        }
        chunks.add(current);
        return chunks;
    }
}
