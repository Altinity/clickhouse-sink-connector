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
     * The handoff sequence of the unit this group belongs to: assigned on the
     * Debezium thread when the unit was handed to the writers, i.e. binlog
     * order. Offsets are acknowledged strictly in this order (spec 09.01);
     * every group of one Debezium batch carries the same sequence.
     */
    private final long handoffSequence;

    /**
     * Constructs a RoutedBatch.
     *
     * @param batch The list of ClickHouseStruct records
     * @param assignedThreadId The thread ID this batch should be processed by
     * @param tableName The table name used for routing
     * @param handoffSequence The handoff sequence of the unit this group belongs to
     */
    public RoutedBatch(List<ClickHouseStruct> batch, int assignedThreadId, String tableName,
                       long handoffSequence) {
        this.batch = batch;
        this.assignedThreadId = assignedThreadId;
        this.tableName = tableName;
        this.handoffSequence = handoffSequence;
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
     * Gets the handoff sequence of the unit this group belongs to.
     *
     * @return The handoff sequence
     */
    public long getHandoffSequence() {
        return handoffSequence;
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
        // floorMod, not Math.abs(hash) % n: Math.abs(Integer.MIN_VALUE) is
        // Integer.MIN_VALUE, so that hash produced a NEGATIVE index and the
        // queue lookup threw IndexOutOfBoundsException on the Debezium thread.
        return Math.floorMod(tableName.hashCode(), threadPoolSize);
    }

    /**
     * Extracts the table name from a topic name.
     * Topic format: server.database.table
     * <p>
     * Splits on the first two dots only ({@code limit=3}), not an unbounded
     * split (PR #1437 review, Low item 2). A MySQL table name may itself
     * contain a dot (e.g. table {@code a.b}, a name MySQL accepts in
     * backticks); Debezium's topic for it is still exactly three
     * dot-separated segments, {@code server.database.a.b}, because the table
     * segment is not re-split. An unbounded {@code split("\\.")} does not
     * know that and breaks the topic into FOUR parts, returning {@code "a"}
     * for the table name -- the same value a sibling table literally named
     * {@code a} would produce. Two source tables then collapse onto the same
     * routing group, which {@code DebeziumChangeEventCapture
     * .appendToRecordsWithHashRouting} and the write path both assume is
     * single-table.
     * </p>
     *
     * @param topicName The topic name
     * @return The table name, or the full topic if parsing fails
     */
    public static String extractTableName(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return "";
        }

        String[] parts = topicName.split("\\.", 3);
        if (parts.length >= 3) {
            return parts[2]; // Table name is everything after the 2nd dot
        }

        // If format doesn't match, return the whole topic as fallback
        return topicName;
    }

    /**
     * Creates a key for routing that combines database and table.
     * This ensures that the same table in different databases can be routed differently if needed.
     * <p>
     * Splits on the first two dots only ({@code limit=3}); see
     * {@link #extractTableName} for why an unbounded split breaks a dotted
     * table name.
     * </p>
     *
     * @param topicName The topic name (server.database.table)
     * @return The routing key (database.table)
     */
    public static String createRoutingKey(String topicName) {
        if (topicName == null || topicName.isEmpty()) {
            return "";
        }

        String[] parts = topicName.split("\\.", 3);
        if (parts.length >= 3) {
            return parts[1] + "." + parts[2]; // database.table
        }

        return topicName;
    }

    /** The routing token for one record. When key routing is on and the record
     *  carries a usable row key, the token is db.table + '\u0001' + rowKey, so the
     *  same row always maps to the same worker (serialized, in binlog order) while
     *  different rows of the table spread across workers -- MySQL WRITESET's
     *  "same key serialized, disjoint keys parallel" rule (spec 03.07). When key
     *  routing is off, or the record has no usable key (a no-primary-key table, a
     *  TRUNCATE row event, a tombstone without a key), the token falls back to
     *  db.table, i.e. the table-level single-worker routing = MySQL's
     *  has_missing_keys -> COMMIT_ORDER fallback. */
    public static String createShardKey(ClickHouseStruct record, boolean keyRoutingEnabled) {
        String tableKey = createRoutingKey(record.getTopic());
        if (!keyRoutingEnabled) {
            return tableKey;
        }
        if (record.getCdcOperation() == com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.TRUNCATE) {
            return tableKey;
        }
        String rowKey = record.getKey();
        java.util.List<String> pk = record.getPrimaryKey();
        if (rowKey == null || rowKey.isEmpty() || pk == null || pk.isEmpty()) {
            return tableKey;
        }
        return tableKey + '\u0001' + rowKey;
    }
}
