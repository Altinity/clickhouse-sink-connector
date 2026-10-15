package com.altinity.clickhouse.sink.connector.db.batch;

/**
 * Raised when the incoming record carries a source column that the ClickHouse
 * table does not have, and the connector is not permitted to add it
 * ({@code schema.evolution=false}) or the attempt to add it did not produce a
 * writable column.
 *
 * <p>The column is neither {@code ALIAS} (nothing stored, nothing to diverge)
 * nor {@code MATERIALIZED} (converted to {@code DEFAULT} instead): it is simply
 * absent from the replica. Writing the row without it would succeed with
 * matching row counts while the value MySQL holds is lost -- the exact shape of
 * divergence that only a value-level checksum finds. Failing the batch keeps
 * the offset from advancing past the row until the replica can store it, and
 * names the column and the configuration knob so the operator can act.</p>
 *
 * <p>Unchecked so it propagates through the batch path without widening
 * signatures, like {@link StaleSchemaCacheException}.</p>
 */
public class MissingTargetColumnException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description naming the database, table, column and the
     *                configuration that governs automatic addition.
     */
    public MissingTargetColumnException(String message) {
        super(message);
    }
}
