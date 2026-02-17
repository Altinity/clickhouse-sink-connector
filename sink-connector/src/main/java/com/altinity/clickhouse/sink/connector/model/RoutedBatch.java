package com.altinity.clickhouse.sink.connector.model;

import java.util.List;

/**
 * Wrapper class for batches that includes thread assignment for hash-based routing.
 * Ensures all records for the same table are processed by the same thread.
 */
public class RoutedBatch {
    
    /**
     * The batch of ClickHouseStruct records.
     */
    private final List<ClickHouseStruct> batch;
    
    /**
     * The thread ID this batch is assigned to (based on table name hash).
     */
    private final int assignedThreadId;
    
    /**
     * The table name used for routing (extracted from topic).
     */
    private final String tableName;
    
    /**
     * Constructs a RoutedBatch.
     * 
     * @param batch The list of ClickHouseStruct records
     * @param assignedThreadId The thread ID this batch should be processed by
     * @param tableName The table name used for routing
     */
    public RoutedBatch(List<ClickHouseStruct> batch, int assignedThreadId, String tableName) {
        this.batch = batch;
        this.assignedThreadId = assignedThreadId;
        this.tableName = tableName;
    }
    
    /**
     * Gets the batch of records.
     * 
     * @return The list of ClickHouseStruct records
     */
    public List<ClickHouseStruct> getBatch() {
        return batch;
    }
    
    /**
     * Gets the assigned thread ID.
     * 
     * @return The thread ID
     */
    public int getAssignedThreadId() {
        return assignedThreadId;
    }
    
    /**
     * Gets the table name.
     * 
     * @return The table name
     */
    public String getTableName() {
        return tableName;
    }
    
    /**
     * Calculates the thread ID for a given table name.
     * Uses consistent hashing to ensure the same table always routes to the same thread.
     * 
     * @param tableName The table name
     * @param threadPoolSize The total number of threads
     * @return The thread ID (0 to threadPoolSize-1)
     */
    public static int calculateThreadId(String tableName, int threadPoolSize) {
        if (tableName == null || threadPoolSize <= 0) {
            return 0;
        }
        // Use Math.abs to ensure positive value, then modulo to get thread assignment
        return Math.abs(tableName.hashCode()) % threadPoolSize;
    }
    
    /**
     * Extracts the table name from a topic name.
     * Topic format: server.database.table
     * 
     * @param topicName The topic name
     * @return The table name, or the full topic if parsing fails
     */
    public static String extractTableName(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return "";
        }
        
        String[] parts = topicName.split("\\.");
        if (parts.length >= 3) {
            return parts[2]; // Table name is the third part
        }
        
        // If format doesn't match, return the whole topic as fallback
        return topicName;
    }
    
    /**
     * Creates a key for routing that combines database and table.
     * This ensures that the same table in different databases can be routed differently if needed.
     * 
     * @param topicName The topic name (server.database.table)
     * @return The routing key (database.table)
     */
    public static String createRoutingKey(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return "";
        }
        
        String[] parts = topicName.split("\\.");
        if (parts.length >= 3) {
            return parts[1] + "." + parts[2]; // database.table
        }
        
        return topicName;
    }
}

