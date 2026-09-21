package com.altinity.clickhouse.sink.connector.model;

import java.util.Objects;

/**
 * The position of a change event in the source database's transaction log,
 * comparable in COMMIT order.
 *
 * <p>Debezium delivers events in log order. The one time an event with a lower
 * position follows one with a higher position is a redelivery after an offset
 * rewind (engine retry, connector restart). The version-sequence logic in the
 * lightweight engine therefore uses this position to tell the two apart:
 * a position beyond the high-water mark is a FIRST delivery and the commit-order
 * invariant applies to it; a position at or below the mark is a redelivery.</p>
 *
 * <p>Supported sources:</p>
 * <ul>
 *   <li><b>MySQL / MariaDB</b>: {@code (binlog file, position, row)}. The file
 *   name is ordered by its numeric suffix ({@code mysql-bin.000123}), so a
 *   binary log rotation - which resets {@code pos} - compares correctly.</li>
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

    private SourcePosition(long fileSequence, String file, long position, long row) {
        this.fileSequence = fileSequence;
        this.file = file;
        this.filePrefix = fileSequence >= 0 ? file.substring(0, file.lastIndexOf('.')) : file;
        this.position = position;
        this.row = row;
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
     * fileSequence | file, position, row)}), so it is transitive even when numerically
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
        c = Long.compare(position, other.position);
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
        return Objects.hash(filePrefix, fileSequence, fileSequence >= 0 ? "" : file, position, row);
    }

    @Override
    public String toString() {
        return file.isEmpty()
                ? "lsn=" + position
                : file + ":" + position + ":" + row;
    }
}
