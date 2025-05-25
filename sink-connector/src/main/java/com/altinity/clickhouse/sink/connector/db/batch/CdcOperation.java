package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;

/**
 * Utility class for determining the CDC (Change Data Capture)
 * operation section based on a given CDC operation.
 *
 * <p>This class provides a method to map a
 * {@link ClickHouseConverter.CDC_OPERATION} value to its corresponding
 * {@link CdcRecordState} value.
 */
public class CdcOperation {

    /**
     * Determines the CDC record state based on the specified CDC
     * operation.
     *
     * <p>The method maps a CDC operation to a {@code CdcRecordState}
     * as follows:
     * <ul>
     *   <li>If {@code operation} is null or its operation string is
     *       null, {@link CdcRecordState#CDC_RECORD_STATE_AFTER} is returned.
     *   </li>
     *   <li>If the operation is "CREATE" or "READ" (case-insensitive),
     *       {@link CdcRecordState#CDC_RECORD_STATE_AFTER} is returned.
     *   </li>
     *   <li>If the operation is "DELETE" (case-insensitive),
     *       {@link CdcRecordState#CDC_RECORD_STATE_BEFORE} is returned.
     *   </li>
     *   <li>If the operation is "UPDATE" (case-insensitive),
     *       {@link CdcRecordState#CDC_RECORD_STATE_BOTH} is returned.
     *   </li>
     * </ul>
     *
     * @param operation the CDC operation from
     *        {@link ClickHouseConverter.CDC_OPERATION}; may be null.
     * @return the corresponding {@link CdcRecordState} based on the
     *         operation; if the operation is null or unrecognized,
     *         {@link CdcRecordState#CDC_RECORD_STATE_AFTER} is returned.
     */
    public static CdcRecordState getCdcSectionBasedOnOperation(
            ClickHouseConverter.CDC_OPERATION operation) {
        CdcRecordState state =
                CdcRecordState.CDC_RECORD_STATE_AFTER;

        if (operation == null || operation.getOperation() == null) {
            return state;
        }
        if (operation.getOperation().equalsIgnoreCase(
                ClickHouseConverter.CDC_OPERATION.CREATE.getOperation())
                || operation.getOperation().equalsIgnoreCase(
                ClickHouseConverter.CDC_OPERATION.READ.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_AFTER;
        } else if (operation.getOperation().equalsIgnoreCase(
                ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BEFORE;
        } else if (operation.getOperation().equalsIgnoreCase(
                ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BOTH;
        }

        return state;
    }
}
