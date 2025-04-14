package com.altinity.clickhouse.sink.connector.model;

/**
 * The SinkRecordColumns class defines constants for the column names used
 * in sink records. These column names represent various attributes of
 * the record and are used in processing change data capture (CDC) events
 * in the sink connector.
 * <p>
 * Each constant in this class represents a field in the CDC record that is
 * expected to be part of the sink operation, such as operation type, source
 * metadata, binlog information, and other relevant details.
 * </p>
 */
public class SinkRecordColumns {

    /**
     * Column representing the operation type (e.g., insert, update, delete).
     */
    public static final String OPERATION = "op";

    /**
     * Column representing the "before" state of the record (for updates or deletes).
     */
    public static final String BEFORE = "before";

    /**
     * Column representing the "after" state of the record (for inserts or updates).
     */
    public static final String AFTER = "after";

    /**
     * Column representing the source metadata of the record.
     */
    public static final String SOURCE = "source";

    /**
     * Column representing the timestamp in milliseconds for the record.
     */
    public static final String TS_MS = "ts_ms";

    /**
     * Column indicating if the record is a snapshot.
     */
    public static final String SNAPSHOT = "snapshot";

    /**
     * Column representing the server ID from the source system.
     */
    public static final String SERVER_ID = "server_id";

    /**
     * Column representing the binlog file name.
     */
    public static final String BINLOG_FILE = "file";

    /**
     * Column representing the binlog position.
     */
    public static final String BINLOG_POS = "pos";

    /**
     * Column representing the row number in the binlog.
     */
    public static final String ROW = "row";

    /**
     * Column representing the server thread ID from the source system.
     */
    public static final String SERVER_THREAD = "thread";

    /**
     * Column representing the GTID (Global Transaction Identifier).
     */
    public static final String GTID = "gtid";

    /**
     * Column representing the LSN (Log Sequence Number) for the record.
     */
    public static final String LSN = "lsn";

    /**
     * Column representing the database name.
     */
    public static final String DATABASE = "db";
}
