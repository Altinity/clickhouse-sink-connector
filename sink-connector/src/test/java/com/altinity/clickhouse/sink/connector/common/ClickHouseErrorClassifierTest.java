package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier.ErrorCategory;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClickHouseErrorClassifierTest {

    @Test
    public void testExtractErrorCode() {
        assertEquals(252, ClickHouseErrorClassifier.extractErrorCode(
                new RuntimeException("Code: 252. DB::Exception: Too many parts (300).")));

        // Extracts from cause chain
        Exception inner = new RuntimeException("Code: 516. DB::Exception: Authentication failed.");
        assertEquals(516, ClickHouseErrorClassifier.extractErrorCode(
                new RuntimeException("ClickHouse write failed", inner)));

        // Multiple "Code:" patterns uses first
        assertEquals(60, ClickHouseErrorClassifier.extractErrorCode(
                new RuntimeException("Code: 60. DB::Exception: ... caused by Code: 210.")));

        // No code or null
        assertEquals(-1, ClickHouseErrorClassifier.extractErrorCode(
                new RuntimeException("Connection timeout")));
        assertEquals(-1, ClickHouseErrorClassifier.extractErrorCode(null));
    }

    @Test
    public void testClassifyFatal() {
        int[] fatalCodes = {516, 497, 60, 81, 53, 50, 16, 396, 27};
        String[] messages = {
                "Code: 516. DB::Exception: Authentication failed: password is incorrect.",
                "Code: 497. DB::Exception: Access denied.",
                "Code: 60. DB::Exception: Table default.nonexistent doesn't exist.",
                "Code: 81. DB::Exception: Database nodb doesn't exist.",
                "Code: 53. DB::Exception: Type mismatch in IN or VALUES section.",
                "Code: 50. DB::Exception: Number of columns doesn't match.",
                "Code: 16. DB::Exception: No such column 'foo' in table.",
                "Code: 396. DB::Exception: Too many partitions.",
                "Code: 27. DB::Exception: Cannot parse text.",
        };

        for (int i = 0; i < messages.length; i++) {
            assertEquals(ErrorCategory.FATAL,
                    ClickHouseErrorClassifier.classify(new RuntimeException(messages[i])),
                    "Expected FATAL for code " + fatalCodes[i]);
        }
    }

    @Test
    public void testClassifyRetriable() {
        assertEquals(ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(
                new RuntimeException("Code: 210. DB::NetException: Connection refused.")));
        assertEquals(ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(
                new RuntimeException("Code: 159. DB::Exception: Timeout exceeded.")));
    }

    /**
     * 252 TOO_MANY_PARTS is ClickHouse insert backpressure: it clears as
     * background merges catch up, so the same batch succeeds on retry. It must
     * be RETRIABLE, not FATAL -- halting the whole connector on a transient,
     * self-healing condition (and requiring a manual restart) is the regression
     * this asserts against. Retrying is safe because offsets never advance past
     * an unwritten batch.
     */
    @Test
    public void testTooManyPartsIsRetriableBackpressure() {
        assertEquals(ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(
                new RuntimeException("Code: 252. DB::Exception: Too many parts (300). "
                        + "Merges are processing significantly slower than inserts.")));
        assertFalse(ClickHouseErrorClassifier.isFatal(252),
                "TOO_MANY_PARTS is transient backpressure and must not be fatal");
    }

    /**
     * 241 MEMORY_LIMIT_EXCEEDED is usually the per-query / per-user / server
     * memory budget tripping under CONCURRENT load (merges, other inserts,
     * other queries); the same batch succeeds once that pressure passes. It
     * must be RETRIABLE (with backoff), not FATAL: classifying it fatal killed
     * the worker on a self-healing condition. The genuinely deterministic case
     * (one batch larger than the budget) surfaces as an unbounded, logged
     * retry of one batch, never as silence.
     */
    @Test
    public void testMemoryLimitExceededIsRetriable() {
        assertEquals(ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(
                new RuntimeException("Code: 241. DB::Exception: Memory limit (total) exceeded: "
                        + "would use 28.01 GiB (attempt to allocate chunk of 4194304 bytes), "
                        + "maximum: 28.00 GiB.")));
        assertFalse(ClickHouseErrorClassifier.isFatal(241),
                "MEMORY_LIMIT_EXCEEDED is usually transient memory pressure and must not be fatal");
    }

    @Test
    public void testClassifyUnknownAndNull() {
        assertEquals(ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(
                new RuntimeException("Some random Java exception without ClickHouse error code")));
        assertEquals(ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(null));
    }

    @Test
    public void testIsFatal() {
        assertTrue(ClickHouseErrorClassifier.isFatal(516));
        assertTrue(ClickHouseErrorClassifier.isFatal(60));

        // 252 TOO_MANY_PARTS is transient backpressure -- retriable, not fatal.
        assertFalse(ClickHouseErrorClassifier.isFatal(252));
        // 241 MEMORY_LIMIT_EXCEEDED is usually transient memory pressure -- retriable.
        assertFalse(ClickHouseErrorClassifier.isFatal(241));
        assertFalse(ClickHouseErrorClassifier.isFatal(210));
        assertFalse(ClickHouseErrorClassifier.isFatal(159));
        assertFalse(ClickHouseErrorClassifier.isFatal(999));
    }

    @Test
    public void testClassifyWrappedCause() {
        Exception inner = new RuntimeException("Code: 60. DB::Exception: Table doesn't exist.");
        Exception mid = new RuntimeException("Insert batch failed", inner);
        Exception outer = new RuntimeException("ClickHouseBatchRunnable error", mid);
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(outer));
    }

    /**
     * A value the source holds that cannot be stored under the current
     * ClickHouse column type ({@code DebeziumConverter.ValueOutOfRangeException},
     * thrown by the loud-clamp default, spec 07.03 section 3.3) is raised by the
     * converter, not by ClickHouse, so it carries no {@code Code: NNN}. Left to
     * code extraction it classified UNKNOWN and the same batch was retried with
     * backoff forever: the unit stayed outstanding, every DDL drain waited on
     * it, and nothing was ever acknowledged again. Retrying can never succeed
     * without widening the column, so it is FATAL like an unknown table
     * (spec 10.01 section 3.1) -- wrapped or as the root -- while an ordinary
     * exception without a code stays UNKNOWN.
     */
    @Test
    public void valueOutOfRangeIsFatalRegardlessOfCode() {
        Exception root = new DebeziumConverter.ValueOutOfRangeException(
                "Value -57896044618658100000000000000000000000000000000000000000000000000000000000000000000000000 "
                        + "in column amount is outside the ClickHouse Decimal128 range");

        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(root),
                "an unrepresentable value as the root exception is terminal");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(
                        new RuntimeException("ClickHouseBatchRunnable error",
                                new RuntimeException("Insert batch failed", root))),
                "an unrepresentable value anywhere in the cause chain is terminal");
        assertEquals(-1, ClickHouseErrorClassifier.extractErrorCode(root),
                "sanity: the converter's exception carries no ClickHouse error code");

        assertEquals(ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(
                        new RuntimeException("ClickHouseBatchRunnable error",
                                new RuntimeException("Insert batch failed"))),
                "a plain exception without a code is still UNKNOWN (retried), not terminal");
    }
}
