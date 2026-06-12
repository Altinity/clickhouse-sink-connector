package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClickHouseErrorClassifier}.
 */
public class ClickHouseErrorClassifierTest {

    @Test
    public void testExtractErrorCodeFromSimpleMessage() {
        Exception e = new RuntimeException("Code: 252. DB::Exception: Too many parts (300).");
        assertEquals(252, ClickHouseErrorClassifier.extractErrorCode(e));
    }

    @Test
    public void testExtractErrorCodeFromCauseChain() {
        Exception inner = new RuntimeException("Code: 516. DB::Exception: Authentication failed.");
        Exception outer = new RuntimeException("ClickHouse write failed", inner);
        assertEquals(516, ClickHouseErrorClassifier.extractErrorCode(outer));
    }

    @Test
    public void testExtractErrorCodeNoCode() {
        Exception e = new RuntimeException("Connection timeout");
        assertEquals(-1, ClickHouseErrorClassifier.extractErrorCode(e));
    }

    @Test
    public void testExtractErrorCodeNullException() {
        assertEquals(-1, ClickHouseErrorClassifier.extractErrorCode(null));
    }

    @Test
    public void testClassifyFatalTooManyParts() {
        Exception e = new RuntimeException("Code: 252. DB::Exception: Too many parts (300). Merges are processing significantly slower than inserts.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalAuthenticationFailed() {
        Exception e = new RuntimeException("Code: 516. DB::Exception: Authentication failed: password is incorrect.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalAccessDenied() {
        Exception e = new RuntimeException("Code: 497. DB::Exception: Access denied.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalUnknownTable() {
        Exception e = new RuntimeException("Code: 60. DB::Exception: Table default.nonexistent doesn't exist.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalUnknownDatabase() {
        Exception e = new RuntimeException("Code: 81. DB::Exception: Database nodb doesn't exist.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalTypeMismatch() {
        Exception e = new RuntimeException("Code: 53. DB::Exception: Type mismatch in IN or VALUES section.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalColumnCountMismatch() {
        Exception e = new RuntimeException("Code: 50. DB::Exception: Number of columns doesn't match.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalNoSuchColumn() {
        Exception e = new RuntimeException("Code: 16. DB::Exception: No such column 'foo' in table.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalMemoryLimitExceeded() {
        Exception e = new RuntimeException("Code: 241. DB::Exception: Memory limit exceeded.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalTooManyPartitions() {
        Exception e = new RuntimeException("Code: 396. DB::Exception: Too many partitions.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyFatalCannotParseText() {
        Exception e = new RuntimeException("Code: 27. DB::Exception: Cannot parse text.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyRetriableNetworkError() {
        // Code 210 = NETWORK_ERROR, not in fatal set
        Exception e = new RuntimeException("Code: 210. DB::NetException: Connection refused.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyRetriableTimeout() {
        // Code 159 = TIMEOUT_EXCEEDED, not in fatal set
        Exception e = new RuntimeException("Code: 159. DB::Exception: Timeout exceeded.");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.RETRIABLE, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyUnknownNoCode() {
        Exception e = new RuntimeException("Some random Java exception without ClickHouse error code");
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(e));
    }

    @Test
    public void testClassifyNull() {
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(null));
    }

    @Test
    public void testIsFatalTrue() {
        assertTrue(ClickHouseErrorClassifier.isFatal(252));   // TOO_MANY_PARTS
        assertTrue(ClickHouseErrorClassifier.isFatal(516));   // AUTHENTICATION_FAILED
        assertTrue(ClickHouseErrorClassifier.isFatal(60));    // UNKNOWN_TABLE
    }

    @Test
    public void testIsFatalFalse() {
        assertFalse(ClickHouseErrorClassifier.isFatal(210));  // NETWORK_ERROR
        assertFalse(ClickHouseErrorClassifier.isFatal(159));  // TIMEOUT_EXCEEDED
        assertFalse(ClickHouseErrorClassifier.isFatal(999));  // Unknown code
    }

    @Test
    public void testClassifyWrappedCause() {
        // Fatal error buried in cause chain should still be detected
        Exception inner = new RuntimeException("Code: 60. DB::Exception: Table doesn't exist.");
        Exception mid = new RuntimeException("Insert batch failed", inner);
        Exception outer = new RuntimeException("ClickHouseBatchRunnable error", mid);
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(outer));
    }

    @Test
    public void testExtractErrorCodeMultipleCodesUsesFirst() {
        // If exception has multiple "Code:" patterns, use the first one found
        Exception e = new RuntimeException("Code: 60. DB::Exception: ... caused by Code: 210.");
        assertEquals(60, ClickHouseErrorClassifier.extractErrorCode(e));
    }
}
