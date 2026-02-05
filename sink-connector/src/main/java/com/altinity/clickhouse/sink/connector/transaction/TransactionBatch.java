package com.altinity.clickhouse.sink.connector.transaction;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import java.util.Collections;
import java.util.List;

/**
 * Represents a completed transaction batch ready for processing.
 * Contains all records that should be processed atomically.
 */
public class TransactionBatch {
    
    private final String transactionId;
    private final List<ClickHouseStruct> records;
    private final boolean committed;
    
    /**
     * Creates a new transaction batch.
     * 
     * @param transactionId Transaction ID (can be null for non-transactional records)
     * @param records       List of records to process
     * @param committed     true if transaction was committed, false if rolled back
     */
    public TransactionBatch(String transactionId, List<ClickHouseStruct> records, boolean committed) {
        this.transactionId = transactionId;
        this.records = records != null ? records : Collections.emptyList();
        this.committed = committed;
    }
    
    /**
     * Creates a single-record batch for non-transactional records.
     * 
     * @param record Single record to process
     * @return TransactionBatch containing one record
     */
    public static TransactionBatch singleRecord(ClickHouseStruct record) {
        return new TransactionBatch(null, Collections.singletonList(record), true);
    }
    
    /**
     * Checks if this transaction was committed.
     * 
     * @return true if committed, false if rolled back
     */
    public boolean isCommitted() {
        return committed;
    }
    
    /**
     * Gets the records in this batch.
     * 
     * @return List of ClickHouseStruct records
     */
    public List<ClickHouseStruct> getRecords() {
        return records;
    }
    
    /**
     * Gets the transaction ID.
     * 
     * @return Transaction ID or null for non-transactional batches
     */
    public String getTransactionId() {
        return transactionId;
    }
    
    /**
     * Checks if this batch is part of a transaction.
     * 
     * @return true if transactional, false otherwise
     */
    public boolean isTransactional() {
        return transactionId != null;
    }
    
    /**
     * Checks if this batch is empty.
     * 
     * @return true if no records, false otherwise
     */
    public boolean isEmpty() {
        return records.isEmpty();
    }
    
    /**
     * Gets the size of this batch.
     * 
     * @return Number of records
     */
    public int size() {
        return records.size();
    }
    
    @Override
    public String toString() {
        return String.format("TransactionBatch{txId=%s, records=%d, committed=%s}", 
            transactionId, records.size(), committed);
    }
}
