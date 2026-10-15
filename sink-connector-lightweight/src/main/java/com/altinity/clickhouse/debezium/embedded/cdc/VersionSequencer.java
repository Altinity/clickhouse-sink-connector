package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Assigns the ReplacingMergeTree {@code _version} for every record of the lightweight
 * streaming path: the {@code effectiveTs * 1_000_000 + counter} formula, its floor,
 * its intra-window counter and its source-log high-water mark (specs 02.01, 02.02,
 * 02.03, 02.04 section 3.1-3.5).
 *
 * <p>Stateless logic over process-wide mutable state; extracted from
 * {@link DebeziumChangeEventCapture} so it is owned and tested on its own. The
 * statics are shared across engine restarts within one JVM -- deliberately: see
 * {@link #sequenceMaxSourceTs} and {@link #sequenceHighWaterPosition}. The formal
 * model of the floor is {@code Replication.VersionFloor}
 * (formal_specs/lean/Replication/VersionFloor.lean).</p>
 */
final class VersionSequencer {

    /**
     * Logger for VersionSequencer class.
     */
    private static final Logger log = LogManager.getLogger(VersionSequencer.class);

    private VersionSequencer() {
    }

    /**
     * Starting sequence number for versioning.
     */
    public static final long SEQUENCE_START = 1000000000;

    /**
     * Initial starting sequence number, used ONLY for the first batch after the
     * connector starts/resumes (500 million, half of {@link #SEQUENCE_START}).
     *
     * <p>On a resume, Debezium re-publishes every event after the last committed
     * offset — including events that were already delivered and written before the
     * shutdown/crash with counters in the {@code SEQUENCE_START} (1&nbsp;billion) range.
     * Seeding the post-resume counter at 500 million guarantees every re-published
     * event in the same source second receives a strictly LOWER {@code _version} than
     * any pre-restart write of that second, so a re-published duplicate can never
     * supersede a row that was already correctly written. The first source-clock
     * advance of more than one second resets the counter to {@code SEQUENCE_START},
     * returning to the normal domain. Values stay inside the 2.8.0 numeric domain
     * ({@code ts_ms * 1_000_000 + counter}) in both phases.</p>
     */
    public static final long SEQUENCE_START_INITIAL = 500000000;

    /**
         * Global sequence number.
    */
    public static long sequenceNumber = SEQUENCE_START;

    /**
     * Source-timestamp anchor for the intra-second sequence counter.
     *
     * <p>The counter resets only when the SOURCE commit clock advances by more than one
     * second past this anchor, and the anchor never moves backward. Because the counter is
     * keyed exclusively on the source commit time - never on the binlog file name or
     * position - it is preserved across binary log rotations: two commits in the same
     * second on either side of a rotation keep incrementing the same counter and can never
     * receive an identical or inverted {@code _version} (see issue #1346).</p>
     */
    public static long sequenceAnchorTs = 0L;

    /**
     * High-water mark of the source-log position ({@link SourcePosition}) versioned in
     * this run.
     *
     * <p>Debezium delivers events in log order, and log order IS commit order. The only
     * time a lower position follows a higher one is a redelivery after an offset rewind.
     * The mark therefore separates the two cases the version sequence has to treat
     * differently:</p>
     * <ul>
     *   <li><b>first delivery</b> (position above the mark): the event committed after
     *   every event already versioned, so its {@code _version} must rank above all of
     *   them - whatever its source timestamp says (see {@link #sequenceMaxSourceTs});</li>
     *   <li><b>redelivery</b> (position at or below the mark, or no position at all):
     *   the redelivery-stable, source-timestamp anchored assignment of issue #1346 is
     *   kept unchanged.</li>
     * </ul>
     *
     * <p>The mark is comparable only within one binary log: a positioned record from a
     * differently named log ({@link SourcePosition#sameLog} false -- the basename
     * changed) is a first delivery and replaces the mark (spec 01.02 section 3.1.1).
     * It is reset by a process restart; a new run starts with an empty mark and a
     * floor seeded from the durable high-water mark (spec 02.02 section 3.5).</p>
     */
    public static SourcePosition sequenceHighWaterPosition = null;

    /**
     * The effective (floored) timestamp assigned to the first delivery that set
     * {@link #sequenceHighWaterPosition}. The remaining rows of that transaction
     * arrive at the SAME position (Debezium stamps every row event of a MySQL
     * transaction with the transaction's position) and are floored at this value,
     * so the rows of one transaction never invert (spec 02.02 section 3.1.2).
     */
    public static long sequenceHighWaterEffectiveTs = 0L;

    /**
     * Highest effective timestamp (ms) versioned in this run - the floor applied to the
     * timestamp component of every first delivery.
     *
     * <p>It is raised by every record that goes through the sequence - row or DDL,
     * positioned or not (a record that moves the anchor and resets the counter must
     * move the floor with it) - and it is applied only to first deliveries; redeliveries
     * keep their own timestamp. Control records (heartbeats, transaction metadata) do
     * NOT go through the sequence: they carry the connector's wall clock, not a source
     * time, and produce no row, so letting them in pinned the floor to the connector
     * clock and made the restart window lag-sized (spec 02.02 section 3.2).</p>
     *
     * <p>It is seeded at engine start from the durable high-water mark
     * ({@link #seedVersionFloor}), so the first deliveries of a new run rank above
     * every version the previous run assigned (spec 02.02 section 3.5).</p>
     *
     * <p>On MySQL {@code source.ts_ms} is the timestamp of the STATEMENT that produced
     * the row event, not of the commit. A transaction that stays open while others
     * commit reaches the binlog after them but with an OLDER timestamp. Without a floor
     * such an event would be versioned as {@code olderTs * 1e6 + counter} - and if the
     * counter had been reset by the newer-timestamped events in between, its version
     * ranked BELOW the earlier write of the same key (same source second, higher
     * counter). ReplacingMergeTree then kept the stale row and the later UPDATE was
     * lost, with row counts still matching on both sides. Clamping the timestamp
     * component of every first delivery to this floor keeps {@code _version}
     * monotonic in commit order, which is the only order ReplacingMergeTree needs.</p>
     */
    public static long sequenceMaxSourceTs = 0L;

    /**
     * Assigns the next ReplacingMergeTree {@code _version} for a record whose source
     * timestamp is {@code recordTs} (ms) and whose source-log position is
     * {@code position} ({@code null} when the record carries none).
     *
     * <p>The emitted value keeps the 2.8.0 formula, {@code ts * 1_000_000 + counter},
     * so upgrades AND downgrades remain safe:</p>
     * <ul>
     *   <li>The counter is keyed on the source commit clock: it resets to
     *   {@link #SEQUENCE_START} only when the clock advances by more than one second
     *   past {@link #sequenceAnchorTs}, and the anchor never moves backward - so it is
     *   kept across binlog rotations and a redelivered older event cannot re-arm the
     *   reset (the duplicate-{@code _version} race).</li>
     *   <li>The first record after start/resume seeds the counter at
     *   {@link #SEQUENCE_START_INITIAL} (500m), so events re-published from the last
     *   committed offset rank strictly below any pre-restart write of the same source
     *   second (which carried counters in the 1000m range).</li>
     *   <li>A FIRST delivery - a position above {@link #sequenceHighWaterPosition} -
     *   committed after every event already versioned in this run, so its timestamp
     *   component is floored at {@link #sequenceMaxSourceTs}. On MySQL
     *   {@code source.ts_ms} is the statement time, not the commit time: a long or
     *   concurrent transaction reaches the binlog AFTER transactions that committed
     *   while it was open, carrying an OLDER timestamp. Versioning it by that older
     *   timestamp put it below the earlier write of the same key whenever the counter
     *   had been reset in between, and ReplacingMergeTree discarded the newer row.</li>
     *   <li>A redelivery - a position at or below the mark - or a row without a
     *   position keeps the source-timestamp anchored assignment of issue #1346
     *   unchanged: identical on every redelivery, so a replayed DELETE can never
     *   out-rank the later re-INSERT.</li>
     *   <li>After a restart the mark is empty and the floor is seeded from the durable
     *   high-water mark ({@link #seedVersionFloor}), so the events Debezium re-publishes
     *   are first deliveries to the new run, clamped above everything the previous run
     *   wrote (spec 02.04 section 3.2).</li>
     * </ul>
     *
     * @param recordTs source timestamp of the record in ms (envelope timestamp for
     *                 records without one)
     * @param position source-log position of the record, or {@code null}
     * @return the {@code _version} to store with the record
     */
    static synchronized long nextSequenceNumber(long recordTs, SourcePosition position) {
        return nextVersionAssignment(recordTs, position).sequenceNumber;
    }

    /**
     * The outcome of one step of the version sequence: the sequence-domain
     * {@code _version} and the clamped timestamp it was built from. The timestamp
     * is what the GTID (snowflake) version must use instead of the raw statement
     * time, so that the commit-order floor governs both paths (spec 02.01 section 3.1).
     */
    static final class VersionAssignment {
        /** {@code effectiveTs * 1_000_000 + counter}. */
        final long sequenceNumber;
        /** The record's source timestamp, clamped up to the floor when a first delivery. */
        final long effectiveTs;

        VersionAssignment(long sequenceNumber, long effectiveTs) {
            this.sequenceNumber = sequenceNumber;
            this.effectiveTs = effectiveTs;
        }
    }

    /**
     * Same assignment as {@link #nextSequenceNumber}, also returning the clamped
     * timestamp. See that method for the rules.
     *
     * @param recordTs source timestamp of the record in ms
     * @param position source-log position of the record, or {@code null}
     * @return the version and the effective timestamp it encodes
     */
    static synchronized VersionAssignment nextVersionAssignment(long recordTs, SourcePosition position) {
        if (sequenceAnchorTs == 0L) {
            sequenceAnchorTs = recordTs;
            sequenceNumber = SEQUENCE_START_INITIAL;
        }
        long effectiveTs = recordTs;
        // A position is comparable with the mark only inside one binary log. After
        // a log basename change (log_bin reconfigured, failover to a differently
        // named log, RESET MASTER with the engine re-created in this JVM) the
        // prefix order is string order and would have classified the whole new
        // log as a redelivery ("binlog" < "mysql-bin"), never clamping it. The
        // first position of a differently named log is a first delivery and
        // becomes the mark (spec 01.02 section 3.1.1, 02.02 section 3.1).
        // The rest of the event that set the mark: same log, same file, same
        // byte position, ANY row index (SourcePosition.sameLogPosition). The
        // row index restarts at 0 for every rows event, so it is not part of
        // an event's identity and must not decide first delivery vs
        // redelivery. Under binlog_transaction_compression a whole MySQL
        // transaction is ONE Transaction_payload_event and Debezium stamps
        // every row of every statement inside it with that event's position
        // (spec 01.08 section 3.2): the second statement's rows arrive at the
        // mark's file and position with a row index below the mark's, and
        // compareTo ranks them BELOW it. Before this predicate they fell
        // through to the redelivery branch and kept their raw statement time
        // while the first statement's rows had been floored: an INSERT
        // out-ranked its own UPDATE and DELETE (spec 02.02 section 3.1.2).
        boolean restOfMarkedEvent = position != null && sequenceHighWaterPosition != null
                && position.sameLogPosition(sequenceHighWaterPosition);
        if (position != null && !restOfMarkedEvent
                && (sequenceHighWaterPosition == null
                        || !position.sameLog(sequenceHighWaterPosition)
                        || position.compareTo(sequenceHighWaterPosition) > 0)) {
            if (sequenceHighWaterPosition != null && !position.sameLog(sequenceHighWaterPosition)) {
                log.warn("Binary log identity changed: high-water position {} is replaced by {} from a "
                        + "differently named log; its first record is versioned as a first delivery",
                        sequenceHighWaterPosition, position);
            }
            sequenceHighWaterPosition = position;
            if (effectiveTs < sequenceMaxSourceTs) {
                effectiveTs = sequenceMaxSourceTs;
            }
            sequenceHighWaterEffectiveTs = effectiveTs;
        } else if (restOfMarkedEvent) {
            // The SAME binlog event as the mark. Debezium stamps every row of a
            // MySQL transaction's rows events with the event's position -- and
            // under binlog_transaction_compression every row of every
            // statement of the transaction with the payload event's position
            // -- while the row index restarts at 0 for every rows event. The
            // rows that follow the event's first row are first deliveries of
            // that same event, not redeliveries: floor them at the effective
            // timestamp the first row received. Without this only the first
            // row was floored and the rest kept their older statement time,
            // so an INSERT ranked above its own UPDATE and DELETE and the
            // deleted row stayed live on the replica (spec 02.02 section
            // 3.1.2). A redelivery of the event takes the same clamp, so the
            // assignment stays redelivery-stable (spec 02.04).
            if (effectiveTs < sequenceHighWaterEffectiveTs) {
                effectiveTs = sequenceHighWaterEffectiveTs;
            }
        }
        // Every record that can move the anchor (and so reset the counter) also
        // raises the floor - including rows without a position. Otherwise the
        // counter reset would happen without the floor following it, and the
        // next late first delivery would again be versioned in its own older
        // second. Control records never reach this method (see the dispatch loop).
        if (effectiveTs > sequenceMaxSourceTs) {
            sequenceMaxSourceTs = effectiveTs;
        }
        int diff = (int) ((effectiveTs - sequenceAnchorTs) / 1000);
        if (diff > 1) {
            sequenceNumber = SEQUENCE_START;
            sequenceAnchorTs = effectiveTs;
        } else {
            sequenceNumber++;
        }
        return new VersionAssignment(effectiveTs * 1_000_000L + sequenceNumber, effectiveTs);
    }

    /**
     * Seeds the version floor from a high-water version at or above everything the
     * previous run handed to the writers (spec 02.02 section 3.5): the floor becomes
     * {@code floorDiv(highWaterVersion, 1_000_000) + 1}, the first whole-millisecond
     * slot whose versions all exceed it. Because a first delivery is versioned at
     * least {@code floor * 1_000_000 + 1}, every first delivery of this run then ranks
     * above every version of the previous run ({@code Replication.VersionFloor
     * .restart_boundary}). The floor is only ever raised; the anchor and counter keep
     * their start-of-run rules.
     *
     * @param highWaterVersion the persisted high-water version; {@code <= 0} is ignored
     * @return the floor in force after the call
     */
    static synchronized long seedVersionFloor(long highWaterVersion) {
        if (highWaterVersion <= 0) {
            return sequenceMaxSourceTs;
        }
        return raiseVersionFloor(VersionHighWaterMark.sequenceFloor(highWaterVersion));
    }

    /**
     * Raises the version floor to {@code floorMs} if it is higher than the floor in
     * force; never lowers it.
     *
     * @param floorMs the floor in ms
     * @return the floor in force after the call
     */
    static synchronized long raiseVersionFloor(long floorMs) {
        if (floorMs > sequenceMaxSourceTs) {
            sequenceMaxSourceTs = floorMs;
        }
        return sequenceMaxSourceTs;
    }

    /**
     * Adds a version (sequence number) to every record.
     * <p>
     * Same assignment as the streaming loop - see {@link #nextSequenceNumber}: anchored
     * on the SOURCE commit timestamp when available (redelivery-stable, issue #1346),
     * falling back to the processing timestamp for records without one, and floored at
     * the newest first-delivery timestamp for records whose log position proves them
     * to be first deliveries.
     * </p>
     *
     * @param chStructs The list of {@link ClickHouseStruct} records.
     */
    static void addVersion(List<ClickHouseStruct> chStructs) {
        if (chStructs.isEmpty()) {
            return;
        }
        for (ClickHouseStruct chStruct : chStructs) {
            long recordTs = chStruct.getTs_ms() > 0
                    ? chStruct.getTs_ms() : chStruct.getDebezium_ts_ms();
            VersionAssignment assignment = nextVersionAssignment(recordTs, chStruct.getSourcePosition());
            chStruct.setSequenceNumber(assignment.sequenceNumber);
            chStruct.setVersionTs(assignment.effectiveTs);
        }
    }
}
