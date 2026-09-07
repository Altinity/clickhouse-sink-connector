package com.altinity.clickhouse.sink.connector.db.operations;

/**
 * Thrown when a direct column type override in the connector configuration
 * does not match the actual column type of an existing ClickHouse table.
 *
 * <p>This is a {@link RuntimeException} so that it propagates up the call
 * chain and halts the connector, forcing the operator to resolve the
 * mismatch before data replication continues.
 */
public class ColumnTypeOverrideMismatchException extends RuntimeException {

    /**
     * Constructs a new mismatch exception with the specified detail message.
     *
     * @param message a human-readable description of the mismatch, including
     *                fix instructions.
     */
    public ColumnTypeOverrideMismatchException(String message) {
        super(message);
    }
}
