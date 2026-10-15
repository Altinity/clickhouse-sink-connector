package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The retry delay for a failing batch doubles from the initial value to the
 * cap and stays there; a different batch or a success restarts it (spec 10.02).
 *
 * <p>Before this change a retriable failure re-ran the same batch on every
 * scheduled tick, i.e. every {@code buffer.flush.time.ms} (30 ms), against a
 * server that had just reported backpressure.</p>
 */
public class RetryBackoffTest {

    @Test
    @DisplayName("Consecutive failures of one batch back off 500, 1000, ... up to the 30 s cap")
    public void delaySequenceDoublesToCap() {
        RetryBackoff backoff = new RetryBackoff(500L, 30000L);
        Object batch = new Object();

        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            delays.add(backoff.nextDelayMs(batch));
        }

        assertEquals(Arrays.asList(500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
                delays, "delay(n) = min(500 * 2^(n-1), 30000)");
        assertEquals(8, backoff.consecutiveFailures());
    }

    @Test
    @DisplayName("A failure of a DIFFERENT batch restarts the sequence at the initial delay")
    public void differentBatchRestartsTheSequence() {
        RetryBackoff backoff = new RetryBackoff(500L, 30000L);
        Object first = new Object();
        Object second = new Object();

        backoff.nextDelayMs(first);
        backoff.nextDelayMs(first);
        assertEquals(2000L, backoff.nextDelayMs(first));

        assertEquals(500L, backoff.nextDelayMs(second), "a new batch starts at the initial delay");
        assertEquals(1, backoff.consecutiveFailures());
    }

    @Test
    @DisplayName("reset() after a successful write clears the sequence")
    public void resetClearsTheSequence() {
        RetryBackoff backoff = new RetryBackoff(500L, 30000L);
        Object batch = new Object();

        backoff.nextDelayMs(batch);
        backoff.nextDelayMs(batch);
        backoff.reset();

        assertEquals(0, backoff.consecutiveFailures());
        assertEquals(500L, backoff.nextDelayMs(batch));
    }

    @Test
    @DisplayName("delayFor saturates at the cap and never overflows, whatever the attempt count")
    public void delayForNeverOverflows() {
        assertEquals(0L, RetryBackoff.delayFor(0, 500L, 30000L));
        assertEquals(500L, RetryBackoff.delayFor(1, 500L, 30000L));
        assertEquals(30000L, RetryBackoff.delayFor(Integer.MAX_VALUE, 500L, 30000L));
        assertEquals(Long.MAX_VALUE, RetryBackoff.delayFor(200, 1L, Long.MAX_VALUE));
        assertEquals(100L, RetryBackoff.delayFor(3, 500L, 100L), "the initial delay is capped too");
    }
}
