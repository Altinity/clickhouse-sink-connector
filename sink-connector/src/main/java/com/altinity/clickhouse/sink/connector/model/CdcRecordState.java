package com.altinity.clickhouse.sink.connector.model;

/**
 * This enum holds the state of which section of the CDC records
 * need to be read based on Engine type and CDC operation.
 */
public enum CdcRecordState {

    CDC_RECORD_STATE_BEFORE,

    CDC_RECORD_STATE_AFTER,

    CDC_RECORD_STATE_BOTH

}
