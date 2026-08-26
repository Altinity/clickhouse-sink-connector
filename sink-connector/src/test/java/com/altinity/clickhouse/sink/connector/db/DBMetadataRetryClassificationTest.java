package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry classification for {@link DBMetadata#executeSystemQuery}.
 *
 * <p>Before this classification existed, every SQLException was retried with a
 * linear backoff up to {@code errors.max.retries}. A deterministic failure such
 * as {@code Code: 57 TABLE_ALREADY_EXISTS} therefore blocked the calling thread
 * for the whole budget while the outcome could never change. In
 * replication-history mode that thread is the CDC/DDL thread, so every change
 * event arriving during the sleep was dropped -- silent data loss whose only
 * symptom was a burst of "Error executing query: Retrying" lines.</p>
 */
public class DBMetadataRetryClassificationTest {

    @Test
    @DisplayName("TABLE_ALREADY_EXISTS is permanent and must not be retried")
    public void testTableAlreadyExistsIsNotRetryable() {
        SQLException sqle = new SQLException(
                "Code: 57. DB::Exception: Table binlog_history.heartbeat already exists. "
                        + "(TABLE_ALREADY_EXISTS) (version 24.8.14.10547.altinitystable (altinity build))");
        assertFalse(DBMetadata.isRetryable(sqle),
                "Code 57 can never succeed on a retry; retrying it burns the budget and drops events");
    }

    @Test
    @DisplayName("The driver wraps the server error in BatchUpdateException; the cause chain must be searched")
    public void testNonRetryableDetectedThroughCauseChain() {
        SQLException root = new SQLException(
                "Code: 57. DB::Exception: Table binlog_history.heartbeat already exists. (TABLE_ALREADY_EXISTS)");
        BatchUpdateException wrapper = new BatchUpdateException("batch failed", new int[0], root);
        assertFalse(DBMetadata.isRetryable(wrapper),
                "clickhouse-jdbc reports DDL failures as BatchUpdateException wrapping the server error");
    }

    @Test
    @DisplayName("Other permanent conditions are also classified as non-retryable")
    public void testOtherPermanentCodes() {
        assertFalse(DBMetadata.isRetryable(new SQLException(
                "Code: 82. DB::Exception: Database binlog_history already exists. (DATABASE_ALREADY_EXISTS)")));
        assertFalse(DBMetadata.isRetryable(new SQLException(
                "Code: 60. DB::Exception: Table x.y does not exist. (UNKNOWN_TABLE)")));
        assertFalse(DBMetadata.isRetryable(new SQLException(
                "Code: 62. DB::Exception: Syntax error. (SYNTAX_ERROR)")));
        assertFalse(DBMetadata.isRetryable(new SQLException(
                "Code: 47. DB::Exception: Unknown expression identifier. (UNKNOWN_IDENTIFIER)")));
    }

    @Test
    @DisplayName("Transient conditions stay retryable")
    public void testTransientRemainsRetryable() {
        assertTrue(DBMetadata.isRetryable(new SQLException(
                "Code: 210. DB::NetException: Connection refused (ch-host:9000). (NETWORK_ERROR)")),
                "a network error is exactly what the retry loop exists for");
        assertTrue(DBMetadata.isRetryable(new SQLException(
                "Code: 252. DB::Exception: Too many parts. (TOO_MANY_PARTS)")));
        assertTrue(DBMetadata.isRetryable(new SQLException("connection reset by peer")),
                "a message with no ClickHouse code must default to retryable");
        assertTrue(DBMetadata.isRetryable(new SQLException((String) null)),
                "a null message must not be treated as permanent");
    }

    @Test
    @DisplayName("A null exception is not retryable")
    public void testNullIsNotRetryable() {
        assertFalse(DBMetadata.isRetryable(null));
    }

    @Test
    @DisplayName("Error code parsing tolerates spacing and absence")
    public void testErrorCodeParsing() {
        assertEquals(Integer.valueOf(57), DBMetadata.parseClickHouseErrorCode("Code: 57. DB::Exception: x"));
        assertEquals(Integer.valueOf(57), DBMetadata.parseClickHouseErrorCode("Code:57. DB::Exception: x"));
        assertEquals(Integer.valueOf(210), DBMetadata.parseClickHouseErrorCode("Code:   210. NetException"));
        assertNull(DBMetadata.parseClickHouseErrorCode("no code here"));
        assertNull(DBMetadata.parseClickHouseErrorCode(null));
    }

    @Test
    @DisplayName("A code that merely contains 57 as a substring is not code 57")
    public void testNoSubstringFalsePositive() {
        // 570 must not be read as 57.
        assertEquals(Integer.valueOf(570), DBMetadata.parseClickHouseErrorCode("Code: 570. DB::Exception: x"));
        assertTrue(DBMetadata.isRetryable(new SQLException("Code: 570. DB::Exception: something else")),
                "570 is not in the permanent set and must stay retryable");
    }
}
