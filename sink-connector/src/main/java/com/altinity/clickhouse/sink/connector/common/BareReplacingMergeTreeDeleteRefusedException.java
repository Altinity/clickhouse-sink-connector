package com.altinity.clickhouse.sink.connector.common;

/**
 * Thrown when a DELETE, or the tombstone of a sorting-key UPDATE, targets a
 * ReplacingMergeTree whose engine clause names no delete column, for example
 * {@code ReplacingMergeTree(_version)}. ClickHouse never removes a row for such
 * a table, and ALTER cannot change an engine's arguments, so no retry can
 * succeed. {@link ClickHouseErrorClassifier} lists this type as terminal, so the
 * batch is classified FATAL and stops the task instead of being retried forever.
 *
 * <p>Spec 08.06 sections 6.1 and 6.5. The message names the table and the rebuild.</p>
 */
public class BareReplacingMergeTreeDeleteRefusedException extends IllegalStateException {

    public BareReplacingMergeTreeDeleteRefusedException(String message) {
        super(message);
    }
}
