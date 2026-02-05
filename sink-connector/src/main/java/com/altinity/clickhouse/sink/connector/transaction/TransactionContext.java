package com.altinity.clickhouse.sink.connector.transaction;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import java.util.ArrayList;
import java.util.List;

/**
 * Context for tracking an active transaction.
 * Contains all records that are part of a single MySQL transaction.
 */
public class TransactionContext {
    
    private final String transactionId;
    private final String gtid;
    private final List<ClickHouseStruct> records;
    private final long startTime;
    private final long timeoutMs;
    
    /**
     * Creates a new transaction context.
     * 
     * @param transactionId MySQL transaction ID
     * @param gtid          MySQL GTID (can be null)
     * @param timeoutMs     Transaction timeout in milliseconds
     */
    public TransactionContext(String transactionId, String gtid, long timeoutMs) {
        this.transactionId = transactionId;
        this.gtid = gtid;
        this.records = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.timeoutMs = timeoutMs;
    }
    
    /**
     * Adds a record to this transaction.
     * 
     * @param record The ClickHouseStruct to add
     */
    public void addRecord(ClickHouseStruct record) {
        this.records.add(record);
    }
    
    /**
     * Gets all records in this transaction.
     * 
     * @return List of ClickHouseStruct records
     */
    public List<ClickHouseStruct> getRecords() {
        return new ArrayList<>(records);
    }
    
    /**
     * Gets the number of records in this transaction.
     * 
     * @return Record count
     */
    public int getRecordCount() {
        return records.size();
    }
    
    /**
     * Gets the transaction ID.
     * 
     * @return Transaction ID
     */
    public String getTransactionId() {
        return transactionId;
    }
    
    /**
     * Gets the GTID.
     * 
     * @return GTID or null
     */
    public String getGtid() {
        return gtid;
    }
    
    /**
     * Gets the duration this transaction has been active.
     * 
     * @return Duration in milliseconds
     */
    public long getDurationMs() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Checks if this transaction has exceeded the timeout.
     * 
     * @return true if timed out, false otherwise
     */
    public boolean isTimedOut() {
        return getDurationMs() > timeoutMs;
    }
    
    @Override
    public String toString() {
        return String.format("TransactionContext{txId=%s, gtid=%s, records=%d, age=%dms}", 
            transactionId, gtid, records.size(), getDurationMs());
    }
}
