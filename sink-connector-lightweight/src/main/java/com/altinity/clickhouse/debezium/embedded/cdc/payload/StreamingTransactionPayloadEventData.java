package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;

import java.util.ArrayList;

/**
 * The data of one {@code Transaction_payload} event whose inner events are
 * decoded on demand, one at a time, while they are iterated (spec 01.08 §3.2).
 *
 * <p>The binlog client's own {@link TransactionPayloadEventData} carries the
 * inner events as a fully materialized {@code ArrayList} and its sizes as
 * {@code int}: it cannot represent a transaction whose uncompressed payload
 * exceeds {@link Integer#MAX_VALUE} bytes, and representing one that fits costs
 * the whole uncompressed payload plus every parsed event in heap. This subclass
 * keeps only the compressed bytes (bounded by the source's replication packet
 * limit, 1 GiB) and hands out a {@link StreamedPayloadEvents} whose iterator
 * decompresses and parses as it goes, so the heap held for a transaction is its
 * compressed size plus ONE inner event, whatever its uncompressed size.</p>
 *
 * <p>The uncompressed size is a 64-bit quantity here
 * ({@link #getUncompressedSizeLong()}); {@link #getUncompressedSize()} keeps the
 * base class's {@code int} contract for callers that only log it, saturating at
 * {@link Integer#MAX_VALUE}.</p>
 */
public final class StreamingTransactionPayloadEventData extends TransactionPayloadEventData {

    private static final long serialVersionUID = 1L;

    private final long uncompressedSizeLong;
    private final long payloadSizeLong;
    private final transient StreamedPayloadEvents events;

    public StreamingTransactionPayloadEventData(long payloadSize, int compressionType, long uncompressedSize,
                                                byte[] payload, StreamedPayloadEvents events) {
        this.payloadSizeLong = payloadSize;
        this.uncompressedSizeLong = uncompressedSize;
        this.events = events;
        super.setPayloadSize(saturate(payloadSize));
        super.setCompressionType(compressionType);
        super.setUncompressedSize(saturate(uncompressedSize));
        super.setPayload(payload);
        super.setUncompressedEvents(events);
    }

    /** The declared uncompressed size of the payload, in bytes, without the {@code int} limit. */
    public long getUncompressedSizeLong() {
        return uncompressedSizeLong;
    }

    /** The compressed payload size, in bytes. */
    public long getPayloadSizeLong() {
        return payloadSizeLong;
    }

    /**
     * The inner events as a streamed list: iterate it (for-each, {@code forEach},
     * {@code stream()}); indexing and sizing it are refused loudly because they
     * would require materializing the whole transaction.
     */
    @Override
    public ArrayList<Event> getUncompressedEvents() {
        return events;
    }

    /** The streamed list is fixed at construction; replacing it would silently change what is dispatched. */
    @Override
    public void setUncompressedEvents(ArrayList<Event> uncompressedEvents) {
        if (uncompressedEvents != events) {
            throw new UnsupportedOperationException(
                    "the inner events of a streamed Transaction_payload are fixed at construction (spec 01.08 §3.2)");
        }
        super.setUncompressedEvents(uncompressedEvents);
    }

    @Override
    public String toString() {
        // Never iterate here: a toString must not decompress a multi-GiB payload.
        return "StreamingTransactionPayloadEventData{payloadSize=" + payloadSizeLong
                + ", compressionType=" + getCompressionType()
                + ", uncompressedSize=" + uncompressedSizeLong
                + ", uncompressedEvents=<streamed>}";
    }

    private static int saturate(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
