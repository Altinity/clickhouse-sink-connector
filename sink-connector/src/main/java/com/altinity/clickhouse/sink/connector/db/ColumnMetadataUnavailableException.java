package com.altinity.clickhouse.sink.connector.db;

/**
 * Raised when the column list of a ClickHouse table cannot be read after the
 * configured number of retries.
 *
 * <p>An empty column map means "the table has no columns". A read that failed
 * is a different fact, and returning it as an empty map lets the caller treat
 * every source column as absent: the staleness check then keeps the cached
 * map, and the INSERT is built without the columns that were never checked.
 * The failure is therefore raised, and it is classified by the cause it
 * carries, so a transient error is retried and a terminal one stops the
 * connector.</p>
 */
public class ColumnMetadataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message describes the table and the number of attempts made.
     * @param cause   the last failure reported by ClickHouse or the driver.
     */
    public ColumnMetadataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
