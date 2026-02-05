package com.altinity.clickhouse.sink.connector.transaction;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for transaction support functionality.
 * 
 * Tests cover:
 * - Transaction boundary detection (BEGIN, COMMIT, ROLLBACK)
 * - Transaction atomicity guarantees
 * - Buffer size limits
 * - Timeout handling
 * - Concurrent transaction tracking
 * - Edge cases (empty transactions, orphaned transactions, etc.)
 */
public class TransactionSupportTest {

    private TransactionBoundaryTracker tracker;
    private static final int DEFAULT_BUFFER_SIZE = 10000;
    private static final long DEFAULT_TIMEOUT_MS = 300000; // 5 minutes

    @BeforeEach
    public void setUp() {
        tracker = new TransactionBoundaryTracker(DEFAULT_BUFFER_SIZE, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Test 1: Simple transaction with BEGIN, DML, and COMMIT.
     * Verifies basic transaction tracking and completion.
     */
    @Test
    public void testSimpleTransactionCommit() {
        String txId = "tx-001";
        
        // BEGIN transaction
        SinkRecord beginRecord = createTransactionControlRecord(txId, "BEGIN");
        TransactionBatch beginBatch = tracker.processRecord(beginRecord, null);
        assertNull(beginBatch, "BEGIN should not return a batch");
        
        // INSERT operation within transaction
        SinkRecord insertRecord = createTransactionDataRecord(txId, "INSERT");
        ClickHouseStruct chStruct = createMockClickHouseStruct();
        TransactionBatch inProgressBatch = tracker.processRecord(insertRecord, chStruct);
        assertNull(inProgressBatch, "In-progress transaction should not return a batch");
        
        // COMMIT transaction
        SinkRecord commitRecord = createTransactionControlRecord(txId, "COMMIT");
        TransactionBatch commitBatch = tracker.processRecord(commitRecord, null);
        
        assertNotNull(commitBatch, "COMMIT should return a batch");
        assertTrue(commitBatch.isCommitted(), "Batch should be committed");
        assertEquals(1, commitBatch.size(), "Batch should contain 1 record");
        assertEquals(txId, commitBatch.getTransactionId(), "Transaction ID should match");
    }

    /**
     * Test 2: Transaction ROLLBACK detection.
     * Verifies that rolled-back transactions are discarded.
     */
    @Test
    public void testTransactionRollback() {
        String txId = "tx-002";
        
        // BEGIN transaction
        SinkRecord beginRecord = createTransactionControlRecord(txId, "BEGIN");
        tracker.processRecord(beginRecord, null);
        
        // INSERT operation
        SinkRecord insertRecord = createTransactionDataRecord(txId, "INSERT");
        ClickHouseStruct chStruct = createMockClickHouseStruct();
        tracker.processRecord(insertRecord, chStruct);
        
        // ROLLBACK transaction
        SinkRecord rollbackRecord = createTransactionControlRecord(txId, "ROLLBACK");
        TransactionBatch rollbackBatch = tracker.processRecord(rollbackRecord, null);
        
        assertNotNull(rollbackBatch, "ROLLBACK should return a batch");
        assertFalse(rollbackBatch.isCommitted(), "Batch should not be committed");
        assertTrue(rollbackBatch.isEmpty(), "Rolled-back batch should be empty");
        assertEquals(txId, rollbackBatch.getTransactionId(), "Transaction ID should match");
    }

    /**
     * Test 3: Multi-statement transaction atomicity.
     * Verifies that all operations in a transaction are grouped together.
     */
    @Test
    public void testMultiStatementTransaction() {
        String txId = "tx-003";
        
        // BEGIN
        tracker.processRecord(createTransactionControlRecord(txId, "BEGIN"), null);
        
        // Multiple DML operations
        tracker.processRecord(createTransactionDataRecord(txId, "UPDATE"), 
            createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId, "UPDATE"), 
            createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId, "INSERT"), 
            createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId, "DELETE"), 
            createMockClickHouseStruct());
        
        // COMMIT
        TransactionBatch commitBatch = tracker.processRecord(
            createTransactionControlRecord(txId, "COMMIT"), null);
        
        assertNotNull(commitBatch, "COMMIT should return a batch");
        assertEquals(4, commitBatch.size(), "Batch should contain all 4 operations");
        assertTrue(commitBatch.isCommitted(), "Batch should be committed");
    }

    /**
     * Test 4: Non-transactional records.
     * Verifies that records without transaction context are processed immediately.
     */
    @Test
    public void testNonTransactionalRecords() {
        // Record without transaction ID
        SinkRecord record = createNonTransactionalRecord();
        ClickHouseStruct chStruct = createMockClickHouseStruct();
        
        TransactionBatch batch = tracker.processRecord(record, chStruct);
        
        assertNotNull(batch, "Non-transactional record should return a batch immediately");
        assertTrue(batch.isCommitted(), "Batch should be committed");
        assertEquals(1, batch.size(), "Batch should contain 1 record");
        assertFalse(batch.isTransactional(), "Batch should not be transactional");
    }

    /**
     * Test 5: Transaction buffer size limit enforcement.
     * Verifies that large transactions are force-committed when buffer limit is reached.
     */
    @Test
    public void testTransactionBufferSizeLimit() {
        // Create tracker with small buffer size
        TransactionBoundaryTracker smallTracker = new TransactionBoundaryTracker(10, 300000);
        String txId = "tx-004";
        
        // BEGIN
        smallTracker.processRecord(createTransactionControlRecord(txId, "BEGIN"), null);
        
        // Add records up to the limit
        TransactionBatch forcedBatch = null;
        for (int i = 0; i < 11; i++) {
            SinkRecord record = createTransactionDataRecord(txId, "INSERT");
            ClickHouseStruct chStruct = createMockClickHouseStruct();
            TransactionBatch batch = smallTracker.processRecord(record, chStruct);
            
            if (batch != null) {
                forcedBatch = batch;
                break;
            }
        }
        
        assertNotNull(forcedBatch, "Transaction should be force-committed when buffer limit exceeded");
        assertTrue(forcedBatch.isCommitted(), "Force-committed batch should be marked as committed");
        assertEquals(10, forcedBatch.size(), "Batch should contain buffer size limit records");
    }

    /**
     * Test 6: Transaction timeout handling.
     * Verifies that long-running transactions are cleaned up.
     */
    @Test
    public void testTransactionTimeout() throws InterruptedException {
        // Create tracker with very short timeout
        TransactionBoundaryTracker shortTimeoutTracker = 
            new TransactionBoundaryTracker(10000, 100); // 100ms timeout
        String txId = "tx-005";
        
        // BEGIN transaction
        shortTimeoutTracker.processRecord(createTransactionControlRecord(txId, "BEGIN"), null);
        
        // Add a record
        shortTimeoutTracker.processRecord(
            createTransactionDataRecord(txId, "INSERT"), 
            createMockClickHouseStruct());
        
        assertEquals(1, shortTimeoutTracker.getActiveTransactionCount(), 
            "Should have 1 active transaction");
        
        // Wait for timeout
        Thread.sleep(150);
        
        // Cleanup stale transactions
        int cleaned = shortTimeoutTracker.cleanupStaleTransactions();
        
        assertEquals(1, cleaned, "Should have cleaned up 1 stale transaction");
        assertEquals(0, shortTimeoutTracker.getActiveTransactionCount(), 
            "Should have no active transactions after cleanup");
    }

    /**
     * Test 7: Concurrent transactions.
     * Verifies that multiple simultaneous transactions are tracked independently.
     */
    @Test
    public void testConcurrentTransactions() {
        String txId1 = "tx-006";
        String txId2 = "tx-007";
        String txId3 = "tx-008";
        
        // Start three transactions
        tracker.processRecord(createTransactionControlRecord(txId1, "BEGIN"), null);
        tracker.processRecord(createTransactionControlRecord(txId2, "BEGIN"), null);
        tracker.processRecord(createTransactionControlRecord(txId3, "BEGIN"), null);
        
        assertEquals(3, tracker.getActiveTransactionCount(), "Should have 3 active transactions");
        
        // Add operations to each transaction
        tracker.processRecord(createTransactionDataRecord(txId1, "INSERT"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId2, "UPDATE"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId2, "UPDATE"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionDataRecord(txId3, "DELETE"), createMockClickHouseStruct());
        
        // Commit tx2
        TransactionBatch batch2 = tracker.processRecord(
            createTransactionControlRecord(txId2, "COMMIT"), null);
        assertNotNull(batch2);
        assertEquals(2, batch2.size(), "Transaction 2 should have 2 records");
        assertEquals(2, tracker.getActiveTransactionCount(), "Should have 2 active transactions");
        
        // Rollback tx1
        TransactionBatch batch1 = tracker.processRecord(
            createTransactionControlRecord(txId1, "ROLLBACK"), null);
        assertNotNull(batch1);
        assertFalse(batch1.isCommitted(), "Transaction 1 should be rolled back");
        assertEquals(1, tracker.getActiveTransactionCount(), "Should have 1 active transaction");
        
        // Commit tx3
        TransactionBatch batch3 = tracker.processRecord(
            createTransactionControlRecord(txId3, "COMMIT"), null);
        assertNotNull(batch3);
        assertEquals(1, batch3.size(), "Transaction 3 should have 1 record");
        assertEquals(0, tracker.getActiveTransactionCount(), "Should have no active transactions");
    }

    /**
     * Test 8: Empty transaction handling.
     * Verifies that transactions with no DML operations are handled correctly.
     */
    @Test
    public void testEmptyTransaction() {
        String txId = "tx-009";
        
        // BEGIN and immediate COMMIT (no DML)
        tracker.processRecord(createTransactionControlRecord(txId, "BEGIN"), null);
        TransactionBatch commitBatch = tracker.processRecord(
            createTransactionControlRecord(txId, "COMMIT"), null);
        
        // Empty transaction should return null or empty batch
        assertTrue(commitBatch == null || commitBatch.isEmpty(), 
            "Empty transaction should not return records");
    }

    /**
     * Test 9: Orphaned transaction (no explicit BEGIN).
     * Verifies that records with transaction ID but no BEGIN are handled.
     */
    @Test
    public void testOrphanedTransaction() {
        String txId = "tx-010";
        
        // DML without BEGIN
        SinkRecord record = createTransactionDataRecord(txId, "INSERT");
        ClickHouseStruct chStruct = createMockClickHouseStruct();
        TransactionBatch inProgressBatch = tracker.processRecord(record, chStruct);
        
        assertNull(inProgressBatch, "Orphaned transaction should be buffered");
        assertEquals(1, tracker.getActiveTransactionCount(), 
            "Should auto-create transaction context");
        
        // COMMIT
        TransactionBatch commitBatch = tracker.processRecord(
            createTransactionControlRecord(txId, "COMMIT"), null);
        
        assertNotNull(commitBatch, "COMMIT should return a batch");
        assertEquals(1, commitBatch.size(), "Batch should contain the orphaned record");
    }

    /**
     * Test 10: Transaction metrics tracking.
     * Verifies that metrics are properly maintained.
     */
    @Test
    public void testTransactionMetrics() {
        // Commit 2 transactions
        String txId1 = "tx-011";
        tracker.processRecord(createTransactionControlRecord(txId1, "BEGIN"), null);
        tracker.processRecord(createTransactionDataRecord(txId1, "INSERT"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionControlRecord(txId1, "COMMIT"), null);
        
        String txId2 = "tx-012";
        tracker.processRecord(createTransactionControlRecord(txId2, "BEGIN"), null);
        tracker.processRecord(createTransactionDataRecord(txId2, "UPDATE"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionControlRecord(txId2, "COMMIT"), null);
        
        // Rollback 1 transaction
        String txId3 = "tx-013";
        tracker.processRecord(createTransactionControlRecord(txId3, "BEGIN"), null);
        tracker.processRecord(createTransactionDataRecord(txId3, "DELETE"), createMockClickHouseStruct());
        tracker.processRecord(createTransactionControlRecord(txId3, "ROLLBACK"), null);
        
        // Process 3 non-transactional records
        tracker.processRecord(createNonTransactionalRecord(), createMockClickHouseStruct());
        tracker.processRecord(createNonTransactionalRecord(), createMockClickHouseStruct());
        tracker.processRecord(createNonTransactionalRecord(), createMockClickHouseStruct());
        
        Map<String, Long> metrics = tracker.getMetrics();
        
        assertEquals(2L, metrics.get("total_committed"), "Should have 2 committed transactions");
        assertEquals(1L, metrics.get("total_rolled_back"), "Should have 1 rolled back transaction");
        assertEquals(3L, metrics.get("total_non_transactional"), "Should have 3 non-transactional records");
        assertEquals(0L, metrics.get("active_transactions"), "Should have no active transactions");
    }

    // Helper methods

    private SinkRecord createTransactionControlRecord(String txId, String status) {
        Schema sourceSchema = SchemaBuilder.struct()
            .field("txId", Schema.STRING_SCHEMA)
            .field("gtid", Schema.OPTIONAL_STRING_SCHEMA)
            .build();
        
        Schema valueSchema = SchemaBuilder.struct()
            .field("source", sourceSchema)
            .field("transaction_status", Schema.STRING_SCHEMA)
            .build();
        
        Struct source = new Struct(sourceSchema)
            .put("txId", txId)
            .put("gtid", "mysql-bin.000001:12345");
        
        Struct value = new Struct(valueSchema)
            .put("source", source)
            .put("transaction_status", status);
        
        return new SinkRecord(
            "test.db.table",
            0,
            null,
            null,
            valueSchema,
            value,
            0L
        );
    }

    private SinkRecord createTransactionDataRecord(String txId, String operation) {
        Schema sourceSchema = SchemaBuilder.struct()
            .field("txId", Schema.STRING_SCHEMA)
            .build();
        
        Schema valueSchema = SchemaBuilder.struct()
            .field("source", sourceSchema)
            .field("op", Schema.STRING_SCHEMA)
            .build();
        
        Struct source = new Struct(sourceSchema)
            .put("txId", txId);
        
        Struct value = new Struct(valueSchema)
            .put("source", source)
            .put("op", operation.toLowerCase().substring(0, 1)); // "c", "u", "d"
        
        return new SinkRecord(
            "test.db.table",
            0,
            null,
            null,
            valueSchema,
            value,
            0L
        );
    }

    private SinkRecord createNonTransactionalRecord() {
        Schema valueSchema = SchemaBuilder.struct()
            .field("op", Schema.STRING_SCHEMA)
            .build();
        
        Struct value = new Struct(valueSchema)
            .put("op", "c");
        
        return new SinkRecord(
            "test.db.table",
            0,
            null,
            null,
            valueSchema,
            value,
            0L
        );
    }

    private ClickHouseStruct createMockClickHouseStruct() {
        // Create a minimal ClickHouseStruct for testing
        // In real usage, this would be created by ClickHouseConverter
        ClickHouseStruct struct = new ClickHouseStruct(
            100L,                                           // kafkaOffset
            "test-topic",                                   // topic
            null,                                           // key (Struct)
            0,                                              // kafkaPartition
            System.currentTimeMillis(),                     // timestamp
            null,                                           // beforeStruct
            null,                                           // afterStruct
            new HashMap<>(),                                // metadata
            com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE  // operation
        );
        return struct;
    }
}
