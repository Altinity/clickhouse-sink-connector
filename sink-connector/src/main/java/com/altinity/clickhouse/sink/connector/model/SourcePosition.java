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
    private final String file;
    private final long position;
    private final long row;

    private SourcePosition(long fileSequence, String file, long position, long row) {
        this.fileSequence = fileSequence;
        this.file = file;
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
        return new SourcePosition(0L, "", lsn, 0L);
    }

    /**
     * Numeric suffix of a binlog file name ({@code mysql-bin.000123} -> 123).
     * A name without a numeric suffix yields -1 and falls back to a lexical
     * comparison of the whole name, which is still deterministic.
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

    @Override
    public int compareTo(SourcePosition other) {
        if (fileSequence >= 0 && other.fileSequence >= 0) {
            int c = Long.compare(fileSequence, other.fileSequence);
            if (c != 0) {
                return c;
            }
        } else {
            int c = file.compareTo(other.file);
            if (c != 0) {
                return c;
            }
        }
        int c = Long.compare(position, other.position);
        if (c != 0) {
            return c;
        }
        return Long.compare(row, other.row);
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
        return Objects.hash(fileSequence >= 0 ? fileSequence : file.hashCode(), position, row);
    }

    @Override
    public String toString() {
        return file.isEmpty()
                ? "lsn=" + position
                : file + ":" + position + ":" + row;
    }
}
