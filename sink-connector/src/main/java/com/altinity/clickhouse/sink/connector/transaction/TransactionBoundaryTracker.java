package com.altinity.clickhouse.sink.connector.transaction;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks MySQL transaction boundaries from Debezium CDC events.
 * Detects BEGIN, COMMIT, ROLLBACK to group records into transactions.
 * 
 * <p>This class is thread-safe and supports concurrent transaction tracking.</p>
 * 
 * <p>Fixes BUG-TX-1: No Transaction Atomicity Guarantee</p>
 * <p>Fixes BUG-TX-2: ROLLBACK Not Handled</p>
 */
public class TransactionBoundaryTracker {
    
    private static final Logger log = LogManager.getLogger(TransactionBoundaryTracker.class);
    
    // Debezium transaction metadata fields
    private static final String TRANSACTION_ID_FIELD = "transaction_id";
    private static final String TRANSACTION_STATUS_FIELD = "transaction_status";
    private static final String SOURCE_FIELD = "source";
    private static final String TX_ID_FIELD = "txId";
    private static final String GTID_FIELD = "gtid";
    
    // Active transactions: txId -> TransactionContext
    private final Map<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();
    
    // Configuration
    private final int maxTransactionBufferSize;
    private final long transactionTimeoutMs;
    
    // Metrics
    private long totalTransactionsCommitted = 0;
    private long totalTransactionsRolledBack = 0;
    private long totalNonTransactionalRecords = 0;
    
    /**
     * Transaction states based on Debezium events.
     */
    private enum TransactionState {
        BEGIN,         // Transaction started
        IN_PROGRESS,   // Regular DML within transaction
        COMMIT,        // Transaction committed
        ROLLBACK       // Transaction rolled back
    }
    
    /**
     * Constructs a TransactionBoundaryTracker with default settings.
     */
    public TransactionBoundaryTracker() {
        this(10000, 300000); // Default: 10000 records, 5 minutes timeout
    }
    
    /**
     * Constructs a TransactionBoundaryTracker with custom settings.
     * 
     * @param maxTransactionBufferSize Maximum number of records per transaction
     * @param transactionTimeoutMs     Maximum time to buffer a transaction (milliseconds)
     */
    public TransactionBoundaryTracker(int maxTransactionBufferSize, long transactionTimeoutMs) {
        this.maxTransactionBufferSize = maxTransactionBufferSize;
        this.transactionTimeoutMs = transactionTimeoutMs;
        log.info("Transaction tracker initialized: maxBufferSize={}, timeout={}ms", 
            maxTransactionBufferSize, transactionTimeoutMs);
    }
    
    /**
     * Process a SinkRecord and determine transaction boundaries.
     * 
     * @param record      SinkRecord from Debezium
     * @param chStruct    Converted ClickHouseStruct (can be null for transaction control events)
     * @return TransactionBatch if transaction completed, null if still in progress
     */
    public TransactionBatch processRecord(SinkRecord record, ClickHouseStruct chStruct) {
        String txId = extractTransactionId(record);
        
        if (txId == null) {
            // Not part of a transaction - process immediately
            totalNonTransactionalRecords++;
            if (chStruct != null) {
                return TransactionBatch.singleRecord(chStruct);
            }
            return null;
        }
        
        TransactionState state = getTransactionState(record);
        
        switch (state) {
            case BEGIN:
                return handleBegin(txId, record);
                
            case IN_PROGRESS:
                return handleInProgress(txId, chStruct);
                
            case COMMIT:
                return handleCommit(txId);
                
            case ROLLBACK:
                return handleRollback(txId);
                
            default:
                log.warn("Unknown transaction state for txId: {}", txId);
                return null;
        }
    }
    
    /**
     * Handle transaction BEGIN event.
     */
    private TransactionBatch handleBegin(String txId, SinkRecord record) {
        String gtid = extractGtid(record);
        TransactionContext ctx = new TransactionContext(txId, gtid, transactionTimeoutMs);
        activeTransactions.put(txId, ctx);
        log.debug("Transaction BEGIN: txId={}, gtid={}", txId, gtid);
        return null;
    }
    
    /**
     * Handle regular DML operation within transaction.
     */
    private TransactionBatch handleInProgress(String txId, ClickHouseStruct chStruct) {
        if (chStruct == null) {
            return null;
        }
        
        TransactionContext ctx = activeTransactions.computeIfAbsent(
            txId, 
            id -> {
                log.warn("Transaction {} not explicitly started, creating context", id);
                return new TransactionContext(id, null, transactionTimeoutMs);
            }
        );
        
        ctx.addRecord(chStruct);
        log.trace("Added record to transaction {}: {} total records", txId, ctx.getRecordCount());
        
        // Check buffer size limit
        if (ctx.getRecordCount() >= maxTransactionBufferSize) {
            log.warn("Transaction {} exceeded buffer size limit ({}), forcing commit", 
                txId, maxTransactionBufferSize);
            return forceCommit(txId);
        }
        
        // Check timeout
        if (ctx.isTimedOut()) {
            log.warn("Transaction {} exceeded timeout ({}ms), forcing commit", 
                txId, transactionTimeoutMs);
            return forceCommit(txId);
        }
        
        return null;
    }
    
    /**
     * Handle transaction COMMIT event.
     */
    private TransactionBatch handleCommit(String txId) {
        TransactionContext ctx = activeTransactions.remove(txId);
        
        if (ctx == null) {
            log.warn("COMMIT for unknown transaction: {}", txId);
            return null;
        }
        
        List<ClickHouseStruct> records = ctx.getRecords();
        totalTransactionsCommitted++;
        
        log.info("Transaction COMMIT: txId={}, records={}, duration={}ms", 
            txId, records.size(), ctx.getDurationMs());
        
        if (records.isEmpty()) {
            log.debug("Empty transaction committed: {}", txId);
            return null;
        }
        
        return new TransactionBatch(txId, records, true);
    }
    
    /**
     * Handle transaction ROLLBACK event.
     */
    private TransactionBatch handleRollback(String txId) {
        TransactionContext ctx = activeTransactions.remove(txId);
        
        if (ctx != null) {
            totalTransactionsRolledBack++;
            log.info("Transaction ROLLBACK: txId={}, discarding {} records, duration={}ms", 
                txId, ctx.getRecordCount(), ctx.getDurationMs());
            
            // Return empty batch with committed=false to signal rollback
            return new TransactionBatch(txId, Collections.emptyList(), false);
        } else {
            log.warn("ROLLBACK for unknown transaction: {}", txId);
        }
        
        return null;
    }
    
    /**
     * Force commit a transaction (due to size or timeout limits).
     */
    private TransactionBatch forceCommit(String txId) {
        TransactionContext ctx = activeTransactions.remove(txId);
        
        if (ctx != null) {
            totalTransactionsCommitted++;
            log.warn("Transaction {} force-committed with {} records", txId, ctx.getRecordCount());
            return new TransactionBatch(txId, ctx.getRecords(), true);
        }
        
        return null;
    }
    
    /**
     * Extract transaction ID from Debezium record.
     */
    private String extractTransactionId(SinkRecord record) {
        try {
            if (record.value() instanceof Struct) {
                Struct struct = (Struct) record.value();
                
                // Try source.txId field (Debezium metadata)
                if (struct.schema().field(SOURCE_FIELD) != null) {
                    Struct source = struct.getStruct(SOURCE_FIELD);
                    if (source != null && source.schema().field(TX_ID_FIELD) != null) {
                        return source.getString(TX_ID_FIELD);
                    }
                }
                
                // Try top-level transaction_id field
                if (struct.schema().field(TRANSACTION_ID_FIELD) != null) {
                    return struct.getString(TRANSACTION_ID_FIELD);
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract transaction ID from record: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Extract GTID from Debezium record.
     */
    private String extractGtid(SinkRecord record) {
        try {
            if (record.value() instanceof Struct) {
                Struct struct = (Struct) record.value();
                if (struct.schema().field(SOURCE_FIELD) != null) {
                    Struct source = struct.getStruct(SOURCE_FIELD);
                    if (source != null && source.schema().field(GTID_FIELD) != null) {
                        return source.getString(GTID_FIELD);
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract GTID from record: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Determine transaction state from record metadata.
     */
    private TransactionState getTransactionState(SinkRecord record) {
        try {
            if (record.value() instanceof Struct) {
                Struct struct = (Struct) record.value();
                
                // Check for transaction status field
                if (struct.schema().field(TRANSACTION_STATUS_FIELD) != null) {
                    String status = struct.getString(TRANSACTION_STATUS_FIELD);
                    if (status != null) {
                        switch (status.toUpperCase()) {
                            case "BEGIN":
                                return TransactionState.BEGIN;
                            case "COMMIT":
                                return TransactionState.COMMIT;
                            case "ROLLBACK":
                                return TransactionState.ROLLBACK;
                        }
                    }
                }
                
                // Check operation type - 'b' for begin, 'e' for end/commit
                if (struct.schema().field("op") != null) {
                    String op = struct.getString("op");
                    if ("b".equals(op)) {
                        return TransactionState.BEGIN;
                    } else if ("e".equals(op)) {
                        return TransactionState.COMMIT;
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Error determining transaction state: {}", e.getMessage());
        }
        
        // Default: regular DML operation within transaction
        return TransactionState.IN_PROGRESS;
    }
    
    /**
     * Clean up stale transactions that have exceeded timeout.
     * Should be called periodically.
     * 
     * @return Number of transactions cleaned up
     */
    public int cleanupStaleTransactions() {
        int cleaned = 0;
        Iterator<Map.Entry<String, TransactionContext>> iterator = 
            activeTransactions.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, TransactionContext> entry = iterator.next();
            TransactionContext ctx = entry.getValue();
            
            if (ctx.isTimedOut()) {
                log.warn("Cleaning up stale transaction: txId={}, age={}ms, records={}", 
                    entry.getKey(), ctx.getDurationMs(), ctx.getRecordCount());
                iterator.remove();
                cleaned++;
            }
        }
        
        if (cleaned > 0) {
            log.info("Cleaned up {} stale transactions", cleaned);
        }
        
        return cleaned;
    }
    
    /**
     * Get metrics for monitoring.
     */
    public Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("active_transactions", (long) activeTransactions.size());
        metrics.put("total_committed", totalTransactionsCommitted);
        metrics.put("total_rolled_back", totalTransactionsRolledBack);
        metrics.put("total_non_transactional", totalNonTransactionalRecords);
        return metrics;
    }
    
    /**
     * Get count of active transactions.
     */
    public int getActiveTransactionCount() {
        return activeTransactions.size();
    }
}
