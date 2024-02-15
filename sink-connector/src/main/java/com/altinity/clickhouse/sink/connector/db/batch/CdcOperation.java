package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;

public class CdcOperation {
    /**
     * Function to get the CDC Operation
     *
     * @param operation
     * @return
     */
    public static CdcRecordState getCdcSectionBasedOnOperation(ClickHouseConverter.CDC_OPERATION operation) {
        CdcRecordState state = CdcRecordState.CDC_RECORD_STATE_AFTER;

        if(operation == null || operation.getOperation() == null) {
            return state;
        }
        if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.CREATE.getOperation()) ||
                operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.READ.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_AFTER;
        } else if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BEFORE;
        } else if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BOTH;
        }

        return state;
    }
}
