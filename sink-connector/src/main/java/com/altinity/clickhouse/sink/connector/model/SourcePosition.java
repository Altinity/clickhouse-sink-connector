package com.altinity.clickhouse.sink.connector.model;

import java.util.Map;
import java.util.Objects;

/**
 * The position of a change event in the source database's transaction log,
 * comparable in COMMIT order.
 *
 * <p>Debezium delivers events in log order. Within one coordinate system, a
 * position beyond the high-water mark is a FIRST delivery and the commit-order
 * invariant applies; earlier events are redeliveries. Restart cursors preserve
 * that order through event-header wrap within one transaction. They cannot
 * disambiguate a BEGIN anchor that itself wraps from an offset rewind; neither
 * can the legacy envelope-only fallback. No backward position is unwrapped.</p>
 *
 * <p>Supported sources:</p>
 * <ul>
 *   <li><b>MySQL / MariaDB</b>: prefer Debezium's restart cursor
 *   {@code (file, anchor position, event ordinal, row)}. Unlike the event-header
 *   position, the event ordinal does not wrap inside a transaction larger than
 *   4 GiB. Without usable offsets, retain {@code (binlog file, position, row)}.
 *   File names are ordered by numeric suffix so rotations compare correctly.</li>
 *   <li><b>PostgreSQL</b>: {@code lsn}.</li>
 * </ul>
 *
 * <p>Records that carry no usable position (heartbeats, MongoDB, synthetic
 * records) have none; callers must treat {@code null} as "unknown" and keep
 * the position-agnostic behaviour for them.</p>
 */
public final class SourcePosition implements Comparable<SourcePosition> {

    private final long fileSequence;
    /** File name up to the numeric suffix ({@code mysql-bin} for {@code mysql-bin.000123}); the whole name when there is no numeric suffix. */
    private final String filePrefix;
    private final String file;
    private final long position;
    private final long row;
    /** Completed events since the restart anchor; -1 denotes an envelope coordinate. */
    private final long event;
    /**
     * Transaction number of the event's MySQL GTID (the last {@code :}-separated
     * segment of {@code source.gtid}), or {@link #NO_GTID}. Metadata about the
     * transaction at this position, NOT part of the position's identity: it takes
     * no part in {@link #compareTo}, {@link #equals} or {@link #hashCode}.
     */
    private final long gtidTransaction;

    /** {@link #gtidTransaction()} of a position whose event carries no GTID. */
    public static final long NO_GTID = -1L;

    private SourcePosition(long fileSequence, String file, long position, long row) {
        this(fileSequence, file, position, row, -1L, NO_GTID);
    }

    private SourcePosition(long fileSequence, String file, long position, long row, long event) {
        this(fileSequence, file, position, row, event, NO_GTID);
    }

    private SourcePosition(long fileSequence, String file, long position, long row, long event,
                           long gtidTransaction) {
        this.event = event;
        this.fileSequence = fileSequence;
        this.file = file;
        this.filePrefix = fileSequence >= 0 ? file.substring(0, file.lastIndexOf('.')) : file;
        this.position = position;
        this.row = row;
        this.gtidTransaction = gtidTransaction;
    }

    /**
     * The same position, carrying the GTID transaction number of its event.
     *
     * <p>The lightweight dispatch loop passes only a timestamp and a position to the
     * version sequence, while the {@code _version} of a GTID row is built from the
     * GTID transaction number (spec 02.01 section 3.1, path 1). Carrying the number on
     * the position lets the sequence keep GTID versions in commit order inside one
     * effective millisecond (FM-02.01-2) and keep rows without a GTID above earlier
     * GTID rows (FM-02.01-1) -- spec 02.01 section 3.1.2.</p>
     *
     * @param transaction the transaction number ({@code >= 0}), or {@link #NO_GTID}
     * @return a position equal to this one that carries {@code transaction}
     */
    public SourcePosition withGtidTransaction(long transaction) {
        long normalized = transaction >= 0 ? transaction : NO_GTID;
        if (normalized == gtidTransaction) {
            return this;
        }
        return new SourcePosition(fileSequence, file, position, row, event, normalized);
    }

    /**
     * @return the GTID transaction number of the event at this position, or
     *         {@link #NO_GTID} when it carries none
     */
    public long gtidTransaction() {
        return gtidTransaction;
    }

    /**
     * Whether this position lies in an EARLIER-numbered file of the same binary log
     * than {@code mark}: same basename, both names numerically suffixed, and a lower
     * file sequence ({@code mysql-bin.000020} against {@code mysql-bin.000500}).
     *
     * <p>Inside one engine run Debezium reads the log forward, so this happens only
     * when the log itself restarted lower: a failover where the binlog client
     * reconnects to a new primary whose log has the same basename and a lower number,
     * or {@code RESET MASTER}. The events are new commits, not redeliveries, so the
     * version sequence classifies them as first deliveries and moves its mark to the
     * new log (spec 01.02 section 3.1.1, spec 02.02 section 3.1, FM-02.02-6). Engine
     * restarts clear the mark before replay, so an offset rewind never reaches this
     * predicate. A position earlier in the SAME file is still a redelivery.</p>
     *
     * @param mark the high-water position, not null
     * @return true if this position is in a lower-numbered file of the same log
     */
    public boolean inEarlierFileOfSameLog(SourcePosition mark) {
        return sameLog(mark) && fileSequence >= 0 && mark.fileSequence >= 0
                && fileSequence < mark.fileSequence;
    }

    /**
     * Position of a MySQL/MariaDB binlog event.
     *
     * @param file binlog file name, e.g. {@code mysql-bin.000123}
     * @param pos  event position within that file
     * @param row  row index within the event
     * @return the position, or {@code null} when {@code file} is null/empty or
     *         {@code pos} is not positive (no binlog coordinates on the record)
     */
    public static SourcePosition ofBinlog(String file, Long pos, Integer row) {
        if (file == null || file.isEmpty() || pos == null || pos <= 0) {
            return null;
        }
        return new SourcePosition(binlogFileSequence(file), file, pos, row == null ? 0 : row);
    }

    /**
     * Debezium's binlog restart cursor (spec 01.02 section 3.4). During a
     * transaction pos stays at BEGIN while event advances even if source.pos
     * wraps at 4 GiB. Debezium omits zero event/row components from the map.
     * This does not unwrap a restart anchor that itself moves backward.
     *
     * @param offset the original SourceRecord offset map
     * @return a cursor, or null for absent/malformed binlog offset metadata
     */
    static SourcePosition ofBinlogOffset(Map<String, ?> offset) {
        if (offset == null || !(offset.get(SinkRecordColumns.BINLOG_FILE) instanceof String)) {
            return null;
        }
        String file = (String) offset.get(SinkRecordColumns.BINLOG_FILE);
        Long pos = integralOffsetValue(offset.get(SinkRecordColumns.BINLOG_POS));
        // "event" is BinlogOffsetContext.EVENTS_TO_SKIP_OFFSET_KEY in the Debezium offset protocol.
        Long event = offset.containsKey("event") ? integralOffsetValue(offset.get("event")) : Long.valueOf(0L);
        Long row = offset.containsKey(SinkRecordColumns.ROW)
                ? integralOffsetValue(offset.get(SinkRecordColumns.ROW)) : Long.valueOf(0L);
        if (file.isEmpty() || pos == null || pos <= 0 || event == null || event < 0 || row == null || row < 0) {
            return null;
        }
        return new SourcePosition(binlogFileSequence(file), file, pos, row, event);
    }

    private static Long integralOffsetValue(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return null;
    }

    /**
     * Whether both positions use the same coordinate system. A restart anchor
     * and an event-header byte offset must not be compared as physical positions.
     */
    public boolean sameCoordinateKind(SourcePosition other) {
        return (event >= 0) == (other.event >= 0);
    }

    /**
     * Position of a PostgreSQL WAL record.
     *
     * @param lsn log sequence number
     * @return the position, or {@code null} when {@code lsn} is null or not positive
     */
    public static SourcePosition ofLsn(Long lsn) {
        if (lsn == null || lsn <= 0) {
            return null;
        }
        // No file name: fileSequence -1 keeps the (empty) prefix equal to the name.
        return new SourcePosition(-1L, "", lsn, 0L);
    }

    /**
     * Numeric suffix of a binlog file name ({@code mysql-bin.000123} -> 123).
     * A name without a numeric suffix yields -1; such names order lexically,
     * after every numerically-suffixed name sharing their prefix (see
     * {@link #compareTo}).
     */
    static long binlogFileSequence(String file) {
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) {
            return -1L;
        }
        String suffix = file.substring(dot + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return -1L;
            }
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * A total order (lexicographic on the tuple {@code (filePrefix, hasNumericSuffix,
     * fileSequence | file, coordinate kind, position, event, row)}), so it is transitive even when numerically
     * and non-numerically suffixed names are mixed: names are grouped by prefix first,
     * within a prefix the numerically suffixed ones order by their number and come
     * before any non-numeric ones, which order lexically among themselves.
     */
    @Override
    public int compareTo(SourcePosition other) {
        int c = filePrefix.compareTo(other.filePrefix);
        if (c != 0) {
            return c;
        }
        boolean numeric = fileSequence >= 0;
        boolean otherNumeric = other.fileSequence >= 0;
        if (numeric && otherNumeric) {
            c = Long.compare(fileSequence, other.fileSequence);
        } else if (numeric != otherNumeric) {
            c = numeric ? -1 : 1;
        } else {
            c = file.compareTo(other.file);
        }
        if (c != 0) {
            return c;
        }
        // Kind order is deterministic, not physical log order (see sameCoordinateKind).
        c = Boolean.compare(event >= 0, other.event >= 0);
        if (c != 0) {
            return c;
        }
        c = Long.compare(position, other.position);
        if (c != 0) {
            return c;
        }
        c = Long.compare(event, other.event);
        if (c != 0) {
            return c;
        }
        return Long.compare(row, other.row);
    }

    /**
     * Whether this position and {@code other} belong to the same binary log, i.e.
     * share the file prefix ({@code mysql-bin} for {@code mysql-bin.000123}).
     *
     * <p>{@link #compareTo} orders by prefix first so that it is a total order, but
     * across a log basename change -- {@code log_bin} reconfigured, a failover to a
     * differently named log, or a {@code RESET MASTER} with the engine re-created in
     * the same JVM -- that part of the order is just string order and says nothing
     * about commit order: every position of the new log ranks entirely above or
     * entirely below every position of the old one. The version sequence therefore
     * compares positions only within one log and treats the first position of a
     * differently named log as a first delivery, resetting its high-water mark
     * (spec 01.02 section 3.1.1). PostgreSQL positions carry no file name and are
     * always in the same log.</p>
     *
     * @param other another position, not null
     * @return true if both positions come from the same binary log
     */
    public boolean sameLog(SourcePosition other) {
        return filePrefix.equals(other.filePrefix);
    }

    /**
     * Whether this position and {@code other} name the same binary log EVENT:
     * same log, file, position and coordinate kind, whatever the row index.
     * Restart cursors additionally require the same event ordinal: the BEGIN
     * anchor alone identifies a transaction, not one rows event within it.
     *
     * <p>The row index is not part of an event's identity; it counts rows
     * INSIDE the event, and it restarts at 0 for every rows event. A MySQL
     * transaction written with {@code binlog_transaction_compression=ON}
     * reaches the connector as ONE {@code Transaction_payload_event}, and
     * Debezium stamps every row of every statement inside it with that one
     * event's position (spec 01.08 section 3.2): the rows of the second
     * statement carry the same file and position as the first statement's
     * rows, with a row index that has restarted at 0, so under
     * {@link #compareTo} they rank BELOW the last row of the first statement.
     * The version sequence must not read that as a redelivery; it uses this
     * predicate to recognise the rest of the same event (spec 02.02 section
     * 3.1.2). PostgreSQL positions compare on the LSN alone.</p>
     *
     * @param other another position, not null
     * @return true if both positions name the same event of the same log
     */
    public boolean sameLogPosition(SourcePosition other) {
        return sameLog(other) && file.equals(other.file) && position == other.position && event == other.event;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourcePosition)) {
            return false;
        }
        return compareTo((SourcePosition) o) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePrefix, fileSequence, fileSequence >= 0 ? "" : file, position, event, row);
    }

    @Override
    public String toString() {
        String coordinates = file.isEmpty()
                ? "lsn=" + position
                : file + ":" + position + (event >= 0 ? ":event=" + event : "") + ":" + row;
        return gtidTransaction >= 0 ? coordinates + ":gtid=" + gtidTransaction : coordinates;
    }
}
