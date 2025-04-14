package com.altinity.clickhouse.sink.connector.model;

/**
 * This enum represents the state of the CDC record section to be used,
 * based on the engine type and the CDC operation.
 *
 * <p>The possible states are:
 * <ul>
 *   <li>{@code CDC_RECORD_STATE_BEFORE}: Use the state of the record before
 *   the change,typically for DELETE operations.</li>
 *   <li>{@code CDC_RECORD_STATE_AFTER}: Use the state of the record after
 *   the change,typically for CREATE or READ operations.</li>
 *   <li>{@code CDC_RECORD_STATE_BOTH}: Use both the before and after
 *   states,typically for UPDATE operations.</li>
 * </ul>
 */
public enum CdcRecordState {

    /**
     * Indicates that the CDC record state before the change should be used.
     */
    CDC_RECORD_STATE_BEFORE,

    /**
     * Indicates that the CDC record state after the change should be used.
     */
    CDC_RECORD_STATE_AFTER,

    /**
     * Indicates that both the before and after states of the CDC record
     * should be used.
     */
    CDC_RECORD_STATE_BOTH

}
