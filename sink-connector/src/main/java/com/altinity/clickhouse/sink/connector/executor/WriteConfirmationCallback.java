package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import java.util.List;

/**
 * Callback interface for notifying about successful writes to ClickHouse.
 * Used for at-least-once delivery.
 */
public interface WriteConfirmationCallback {
    
    /**
     * Called when records have been successfully written to ClickHouse.
     * 
     * @param successfulRecords List of records that were successfully written
     */
    void onWriteSuccess(List<ClickHouseStruct> successfulRecords);
} 