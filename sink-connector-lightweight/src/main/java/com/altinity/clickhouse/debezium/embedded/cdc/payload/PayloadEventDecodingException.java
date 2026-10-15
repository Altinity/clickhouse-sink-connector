package com.altinity.clickhouse.debezium.embedded.cdc.payload;

/**
 * A {@code Transaction_payload} could not be decoded while it was being
 * iterated: a corrupt or truncated zstd frame, an inner event cut short, a
 * decompressed size that disagrees with the payload header, or an inner event
 * body that does not parse (spec 01.08 §3.2). Unchecked because it surfaces
 * from {@link java.util.Iterator#next()} and {@link LazyPayloadEvent#getData()};
 * Debezium treats it as an error processing the payload event and stops the
 * connector, so the transaction is never partially skipped.
 */
public final class PayloadEventDecodingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PayloadEventDecodingException(String message) {
        super(message);
    }

    public PayloadEventDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
