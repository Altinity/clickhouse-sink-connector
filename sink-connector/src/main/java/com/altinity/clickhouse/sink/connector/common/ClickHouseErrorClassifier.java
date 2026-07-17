package com.altinity.clickhouse.sink.connector.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies ClickHouse errors as retriable or fatal based on error codes
 * extracted from exception messages.
 *
 * Fatal errors are deterministic — retrying the same batch will never succeed
 * without external intervention (config change, schema fix, etc.).
 * The connector should stop the task on fatal errors instead of retrying
 * indefinitely, which blocks binlog advancement and causes silent data loss
 * across all tables.
 *
 * @see <a href="https://github.com/Altinity/clickhouse-sink-connector/issues/1310">Issue #1310</a>
 */
public class ClickHouseErrorClassifier {

    private static final Logger log = LogManager.getLogger(ClickHouseErrorClassifier.class);

    /**
     * Pattern to extract ClickHouse error codes from exception messages.
     * ClickHouse errors follow the format: "Code: NNN. DB::Exception: ..."
     */
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("Code:\\s*(\\d+)");

    /**
     * ClickHouse error codes that are fatal — retrying will never succeed.
     *
     * Sources:
     * - https://github.com/ClickHouse/ClickHouse/blob/master/src/Common/ErrorCodes.cpp
     */
    private static final Set<Integer> FATAL_ERROR_CODES = new HashSet<>();

    static {
        // Authentication / authorization
        FATAL_ERROR_CODES.add(516);  // AUTHENTICATION_FAILED
        FATAL_ERROR_CODES.add(497);  // ACCESS_DENIED

        // Schema / type mismatches
        FATAL_ERROR_CODES.add(50);   // NUMBER_OF_COLUMNS_DOESNT_MATCH
        FATAL_ERROR_CODES.add(53);   // TYPE_MISMATCH
        FATAL_ERROR_CODES.add(60);   // UNKNOWN_TABLE
        FATAL_ERROR_CODES.add(81);   // UNKNOWN_DATABASE
        FATAL_ERROR_CODES.add(16);   // NO_SUCH_COLUMN_IN_TABLE

        // Configuration / resource limits (deterministic for a given batch)
        FATAL_ERROR_CODES.add(252);  // TOO_MANY_PARTS
        FATAL_ERROR_CODES.add(241);  // MEMORY_LIMIT_EXCEEDED
        FATAL_ERROR_CODES.add(396);  // TOO_MANY_PARTITIONS

        // Data format errors
        FATAL_ERROR_CODES.add(27);   // CANNOT_PARSE_TEXT
        FATAL_ERROR_CODES.add(33);   // CANNOT_READ_ALL_DATA
        FATAL_ERROR_CODES.add(69);   // ARGUMENT_OUT_OF_BOUND
        FATAL_ERROR_CODES.add(349);  // INVALID_PARTITION_VALUE
    }

    /**
     * Deterministic client-side data-conversion exceptions thrown while mapping a
     * source value onto a ClickHouse column (e.g. the JDBC driver parsing a
     * non-numeric string into an integer column). These never carry a ClickHouse
     * "Code:" and will never succeed on retry, so they are treated as fatal.
     */
    private static final List<Class<? extends Throwable>> FATAL_CLIENT_SIDE_EXCEPTIONS = Arrays.asList(
            NumberFormatException.class,
            java.time.format.DateTimeParseException.class,
            java.time.DateTimeException.class,
            ArithmeticException.class);

    public enum ErrorCategory {
        /** Error is transient — retry may succeed (network, timeout, etc.) */
        RETRIABLE,
        /** Error is deterministic — retry will never succeed without intervention */
        FATAL,
        /** Could not determine error category — treat as retriable for safety */
        UNKNOWN
    }

    /**
     * Classify an exception based on the ClickHouse error code in its message.
     *
     * @param e the exception to classify
     * @return the error category
     */
    public static ErrorCategory classify(Exception e) {
        if (e == null) {
            return ErrorCategory.UNKNOWN;
        }

        // Deterministic client-side conversion errors have no ClickHouse "Code:"
        // but retrying the same batch will never succeed.
        if (isFatalClientSideException(e)) {
            return ErrorCategory.FATAL;
        }

        int errorCode = extractErrorCode(e);
        if (errorCode < 0) {
            // Could not parse error code — treat as retriable to avoid
            // stopping on unexpected exception formats
            return ErrorCategory.UNKNOWN;
        }

        if (FATAL_ERROR_CODES.contains(errorCode)) {
            return ErrorCategory.FATAL;
        }

        return ErrorCategory.RETRIABLE;
    }

    /**
     * Extract the ClickHouse error code from an exception's message chain.
     * Checks both the exception message and its cause chain.
     *
     * @param e the exception
     * @return the error code, or -1 if not found
     */
    public static int extractErrorCode(Exception e) {
        // Check the exception and its cause chain
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = ERROR_CODE_PATTERN.matcher(message);
                if (matcher.find()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException nfe) {
                        // Should not happen given the regex, but be safe
                    }
                }
            }
            current = current.getCause();
        }
        return -1;
    }

    /**
     * Check if a given ClickHouse error code is classified as fatal.
     *
     * @param errorCode the ClickHouse error code
     * @return true if the error is fatal
     */
    public static boolean isFatal(int errorCode) {
        return FATAL_ERROR_CODES.contains(errorCode);
    }

    /**
     * Check whether the exception (or anything in its cause chain) is a
     * deterministic client-side data-conversion error that will never succeed
     * on retry.
     *
     * @param e the exception
     * @return true if the exception chain contains a fatal client-side conversion error
     */
    public static boolean isFatalClientSideException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            for (Class<? extends Throwable> fatalType : FATAL_CLIENT_SIDE_EXCEPTIONS) {
                if (fatalType.isInstance(current)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
