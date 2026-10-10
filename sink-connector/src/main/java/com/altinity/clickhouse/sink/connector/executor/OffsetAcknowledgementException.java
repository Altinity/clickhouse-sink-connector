package com.altinity.clickhouse.sink.connector.executor;

/**
 * Raised when {@link DebeziumOffsetManagement#reportWritten} fails to report
 * one or more already-written groups to the handoff FIFO (PR #1437 review,
 * Low item 1).
 *
 * <p><b>Why this is its own type.</b> The rows this failure is about are
 * already durably in ClickHouse (WRITTEN-ONCE, spec 09.01 section 3.2) by the
 * time it can be thrown; what failed is offset bookkeeping -- a Debezium
 * {@code markBatchFinished()} call against a closed or unreachable offset
 * store, or one of {@link DebeziumOffsetManagement}'s own FIFO-corruption
 * checks -- not a write to ClickHouse. {@code ClickHouseErrorClassifier} sees
 * no ClickHouse error code in either case and defaults the category to
 * {@code UNKNOWN}, so before this type existed {@code ClickHouseBatchRunnable
 * .run()} logged every such failure as "Retriable ClickHouse error (Code: -1,
 * Category: UNKNOWN)" -- indistinguishable from an actual ClickHouse outage,
 * even though no ClickHouse server was involved and the data is safe. Giving
 * this failure its own exception type lets {@code run()}'s catch block (see
 * {@code isOffsetAcknowledgementFailure}) log it under its own name instead
 * of guessing from the message text of whatever offset-store exception
 * happened to be thrown.</p>
 *
 * <p>Unchecked, and deliberately not swallowed: the next drain (triggered by
 * any later write, on any worker) retries the acknowledgement (see
 * {@code OffsetAcknowledgementFailureTest}), but the failure must still
 * surface to the operator here so it is visible as what it is -- an offset
 * store problem -- rather than silently retried with no trace.</p>
 */
public class OffsetAcknowledgementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description of what failed to be reported.
     * @param cause   the underlying failure (a Debezium commit exception, or
     *                an {@code IllegalStateException} from the FIFO's own
     *                consistency checks).
     */
    public OffsetAcknowledgementException(String message, Throwable cause) {
        super(message, cause);
    }
}
