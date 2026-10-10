package com.altinity.clickhouse.sink.connector.executor;

/**
 * Paces the retries of a batch that failed to reach ClickHouse (spec 10.02).
 *
 * <p>The delay after the {@code n}-th consecutive failure of the SAME batch is
 * {@code min(initial * 2^(n-1), max)}. A failure of a different batch
 * (identity {@code !=}) restarts the sequence; a successful write resets it.
 * There is deliberately no attempt cap: offsets never advance past an
 * unwritten batch, so retrying forever is safe, and the stall is visible in
 * the logs and as replication lag rather than traded for a stopped connector.</p>
 *
 * <p>Not thread-safe; each worker owns one instance.</p>
 */
final class RetryBackoff {

    private final long initialMs;
    private final long maxMs;

    private Object failingBatch;
    private int consecutiveFailures;

    RetryBackoff(long initialMs, long maxMs) {
        if (initialMs < 0 || maxMs < 0) {
            throw new IllegalArgumentException("backoff delays must not be negative");
        }
        this.initialMs = initialMs;
        this.maxMs = maxMs;
    }

    /**
     * Records a failed attempt of {@code batch} and returns how long to wait
     * before the next attempt.
     *
     * @param batch the batch that failed (compared by identity).
     * @return the delay in milliseconds.
     */
    long nextDelayMs(Object batch) {
        if (batch != failingBatch) {
            failingBatch = batch;
            consecutiveFailures = 0;
        }
        consecutiveFailures++;
        return delayFor(consecutiveFailures, initialMs, maxMs);
    }

    /** Number of consecutive failures recorded for the current batch. */
    int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** Forgets the failing batch; call after a successful write. */
    void reset() {
        failingBatch = null;
        consecutiveFailures = 0;
    }

    /**
     * The delay for the {@code attempt}-th consecutive failure:
     * {@code min(initialMs * 2^(attempt-1), maxMs)}, computed without overflow.
     *
     * @param attempt   1-based consecutive failure count; {@code <= 0} yields 0.
     * @param initialMs delay for the first failure.
     * @param maxMs     cap.
     * @return the delay in milliseconds.
     */
    static long delayFor(int attempt, long initialMs, long maxMs) {
        if (attempt <= 0) {
            return 0L;
        }
        long delay = Math.min(initialMs, maxMs);
        for (int i = 1; i < attempt && delay < maxMs; i++) {
            delay = delay >= maxMs / 2 ? maxMs : delay * 2;
        }
        return delay;
    }
}
