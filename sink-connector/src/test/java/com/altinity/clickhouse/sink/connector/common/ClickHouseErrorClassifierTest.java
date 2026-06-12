package java.com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier.ErrorCategory;
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
        int[] fatalCodes = {252, 516, 497, 60, 81, 53, 50, 16, 241, 396, 27};
        String[] messages = {
                "Code: 252. DB::Exception: Too many parts (300).",
                "Code: 516. DB::Exception: Authentication failed: password is incorrect.",
                "Code: 497. DB::Exception: Access denied.",
                "Code: 60. DB::Exception: Table default.nonexistent doesn't exist.",
                "Code: 81. DB::Exception: Database nodb doesn't exist.",
                "Code: 53. DB::Exception: Type mismatch in IN or VALUES section.",
                "Code: 50. DB::Exception: Number of columns doesn't match.",
                "Code: 16. DB::Exception: No such column 'foo' in table.",
                "Code: 241. DB::Exception: Memory limit exceeded.",
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

    @Test
    public void testClassifyUnknownAndNull() {
        assertEquals(ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(
                new RuntimeException("Some random Java exception without ClickHouse error code")));
        assertEquals(ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(null));
    }

    @Test
    public void testIsFatal() {
        assertTrue(ClickHouseErrorClassifier.isFatal(252));
        assertTrue(ClickHouseErrorClassifier.isFatal(516));
        assertTrue(ClickHouseErrorClassifier.isFatal(60));

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
}
