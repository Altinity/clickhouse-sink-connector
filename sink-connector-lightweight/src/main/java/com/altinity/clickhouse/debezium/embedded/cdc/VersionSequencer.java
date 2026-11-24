package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
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
 * floor, anchor, counter and assigned-version maximum survive engine restarts
 * within one JVM. The position gate is cleared by {@link #beginEngineRun} only
 * after successful seeding. The formal
 * model of the floor is {@code Replication.VersionFloor}
 * (formal_specs/lean/Replication/VersionFloor.lean).</p>
 *
 * <p><b>Both version domains.</b> A GTID row's {@code _version} is the snowflake
 * {@code SnowFlakeId.generate(versionTs, gtid)}; a row without one is the sequence
 * value. The position handed in carries the GTID transaction number
 * ({@link SourcePosition#gtidTransaction}), so for every first delivery the sequence
 * keeps the FINAL version above the highest final version assigned before it: a GTID
 * row's {@code versionTs} is moved forward by the least amount needed (22-bit wrap,
 * server UUID change), a row without a GTID is carried above earlier snowflake
 * versions, and from that carry on the sequence values (which the durable horizon
 * covers) dominate the GTID versions too (spec 02.01 section 3.1.2, spec 02.02
 * section 3.5.1; {@code Replication.VersionFloor.Domains}).</p>
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
     * Highest sequence-domain value assigned in this counter lifecycle. Guarded
     * by the class monitor and cleared when an unset anchor initializes the
     * counter. Retains carry that a reset would otherwise discard (spec 02.03
     * section 3.2.1); this is not a new persisted version domain.
     */
    private static long sequenceMaxAssignedVersion = 0L;

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
     * <p>Debezium delivers events in log order, and log order IS commit order.
     * MySQL positions prefer the restart anchor/event/row cursor, which survives
     * header-position wrap inside a transaction (spec 01.02 section 3.4). An
     * anchor that itself wraps is still ambiguous with replay; no wrap is inferred.
     * Within comparable coordinates the mark separates the two cases:</p>
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
     * It is reset by every successfully seeded engine start; a new run starts with an empty mark and a
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
     * Highest FINAL {@code _version} given to a first delivery (or the rest of its
     * marked event) in this counter lifecycle, across both version domains: the
     * snowflake {@code SnowFlakeId.generate(versionTs, gtid)} for a position that
     * carries a GTID transaction number, the sequence value otherwise (spec 02.01
     * section 3.1.2). Guarded by the class monitor; cleared with
     * {@link #sequenceMaxAssignedVersion} when an unset anchor initializes the counter.
     */
    private static long sequenceMaxFinalVersion = 0L;

    /**
     * GTID transaction number of the record that set {@link #sequenceMaxFinalVersion},
     * or {@link SourcePosition#NO_GTID} when a row without a GTID set it. A GTID row
     * of the SAME transaction may tie with it (the rows of one transaction share one
     * snowflake version, spec 02.01 section 3.1.1); any other row must exceed it.
     */
    private static long sequenceMaxFinalGtid = SourcePosition.NO_GTID;

    /**
     * {@code true} once a row without a GTID had to be carried above a GTID
     * (snowflake) version in this counter lifecycle: from then on the sequence values
     * -- the values the durable horizon covers -- are kept at or above every final
     * version, GTID rows included, so a restart can seed above all of them (spec
     * 02.02 section 3.5.1).
     */
    private static boolean sequenceDomainsMerged = false;

    /**
     * Version floor seeded at engine start from a durable mark that lies in the
     * snowflake domain ({@link VersionHighWaterMark#seedFloor}, spec 02.02 section
     * 3.5.1): every first delivery of the run is versioned above it in either domain.
     * {@code 0} when the mark was an ordinary sequence-domain mark. Unlike the
     * in-run maxima it survives the counter initialization of the first record of a
     * process; it is only ever raised.
     */
    static long seededVersionFloor = 0L;

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
        /**
         * The timestamp the record's {@code versionTs} must carry: its source
         * timestamp, clamped up to the floor when a first delivery, and for a GTID
         * first delivery moved forward by the least amount that keeps its snowflake
         * version above the previous one (spec 02.01 section 3.1.2).
         */
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
            sequenceMaxAssignedVersion = 0L;
            sequenceMaxFinalVersion = 0L;
            sequenceMaxFinalGtid = SourcePosition.NO_GTID;
            sequenceDomainsMerged = false;
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
        boolean incompatibleCoordinates = position != null && sequenceHighWaterPosition != null
                && position.sameLog(sequenceHighWaterPosition)
                && !position.sameCoordinateKind(sequenceHighWaterPosition);
        if (incompatibleCoordinates) {
            log.warn("Source coordinate kind changed from {} to {}; keeping the high-water mark and "
                    + "raw source timestamp because restart anchors and event-header positions are not comparable",
                    sequenceHighWaterPosition, position);
        }
        boolean restOfMarkedEvent = position != null && sequenceHighWaterPosition != null
                && position.sameLogPosition(sequenceHighWaterPosition);
        // A lower-numbered file of the same basename inside one engine run is a log
        // that restarted lower -- a failover to a new primary whose log is also called
        // mysql-bin, or RESET MASTER -- never a redelivery: Debezium reads forward
        // within a run and every engine start clears the mark before replay. Its
        // events are new commits, so they are first deliveries and the mark moves to
        // the new log (FM-02.02-6, spec 02.02 section 3.1).
        boolean logRestartedLower = position != null && sequenceHighWaterPosition != null
                && !incompatibleCoordinates
                && position.inEarlierFileOfSameLog(sequenceHighWaterPosition);
        boolean firstDelivery = position != null && !incompatibleCoordinates && !restOfMarkedEvent
                && (sequenceHighWaterPosition == null
                        || !position.sameLog(sequenceHighWaterPosition)
                        || logRestartedLower
                        || position.compareTo(sequenceHighWaterPosition) > 0);
        if (firstDelivery) {
            if (sequenceHighWaterPosition != null && !position.sameLog(sequenceHighWaterPosition)) {
                log.warn("Binary log identity changed: high-water position {} is replaced by {} from a "
                        + "differently named log; its first record is versioned as a first delivery",
                        sequenceHighWaterPosition, position);
            } else if (logRestartedLower) {
                log.warn("Binary log restarted lower: high-water position {} is replaced by {} from an "
                        + "earlier-numbered file of the same log (failover or RESET MASTER); its records are "
                        + "versioned as first deliveries", sequenceHighWaterPosition, position);
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
        long timestampComponent = effectiveTs * 1_000_000L;
        long version = timestampComponent + sequenceNumber;
        long versionTs = effectiveTs;
        if (firstDelivery || restOfMarkedEvent) {
            long gtid = position.gtidTransaction();
            long finalFloor = Math.max(sequenceMaxFinalVersion, seededVersionFloor);
            boolean merged = sequenceDomainsMerged || seededVersionFloor > 0L;
            long finalVersion;
            if (gtid != SourcePosition.NO_GTID) {
                // GTID row: its _version is SnowFlakeId.generate(versionTs, gtid),
                // whose low 22 bits are the transaction number. Inside one effective
                // millisecond that number is commit order only for one server UUID
                // and between two wraps of the field (FM-02.01-2). Move the
                // timestamp field forward by the least amount that keeps the version
                // above every earlier first delivery (a tie only with the rows of
                // the same transaction). The floor itself is not raised.
                long reach = gtid == sequenceMaxFinalGtid ? finalFloor : Math.addExact(finalFloor, 1L);
                versionTs = SnowFlakeId.timestampReaching(effectiveTs, gtid, reach);
                finalVersion = SnowFlakeId.generate(versionTs, gtid, false);
                if (versionTs > effectiveTs) {
                    // The durable horizon covers sequence values: keep the bumped
                    // millisecond inside them so a restart seeds above it.
                    version = Math.max(version, Math.addExact(Math.multiplyExact(versionTs, 1_000_000L), 1L));
                }
                if (merged) {
                    // Once the domains merged the horizon must cover the snowflake
                    // versions themselves (spec 02.02 section 3.5.1).
                    version = Math.max(version, finalVersion);
                }
            } else {
                // Row without a GTID: its _version is the sequence value. After GTID
                // rows it lies in a domain BELOW every snowflake version (FM-02.01-1)
                // and must be carried above the highest final version.
                if (version <= finalFloor) {
                    if (finalFloor > sequenceMaxAssignedVersion && !merged) {
                        log.warn("A row without a GTID at {} follows GTID rows: its sequence version {} is "
                                + "carried above the GTID version {} so that commit order is kept "
                                + "(spec 02.01 section 3.1.2)", position, version, finalFloor);
                    }
                    if (finalFloor > sequenceMaxAssignedVersion) {
                        sequenceDomainsMerged = true;
                    }
                    version = Math.addExact(finalFloor, 1L);
                }
                finalVersion = version;
            }
            if (version <= sequenceMaxAssignedVersion) {
                // A large window carries its counter into the timestamp component.
                // A reset (including one caused by a replay or positionless row)
                // must not make a first delivery, including remaining statements of
                // the marked event, tie or fall below it. Older-event replay remains
                // unclamped. Keep the timestamp and formula; retain only needed carry.
                version = Math.addExact(sequenceMaxAssignedVersion, 1L);
                if (gtid == SourcePosition.NO_GTID) {
                    finalVersion = version;
                }
            }
            sequenceNumber = version - timestampComponent;
            if (finalVersion >= sequenceMaxFinalVersion) {
                sequenceMaxFinalGtid = gtid;
            }
            sequenceMaxFinalVersion = Math.max(sequenceMaxFinalVersion, finalVersion);
        }
        sequenceMaxAssignedVersion = Math.max(sequenceMaxAssignedVersion, version);
        return new VersionAssignment(version, versionTs);
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
     * Starts an engine epoch after its predecessor has stopped dispatching.
     * The seed must already cover the previous epoch's handed-off versions.
     * Replay from the durable offset is an ordered suffix, not an isolated in-run
     * duplicate. Keep the counter, anchor and maximum assigned sequence value.
     *
     * @param floorMs the successfully established restart floor
     * @return the floor in force
     */
    static synchronized long beginEngineRun(long floorMs) {
        if (floorMs <= 0) {
            throw new IllegalArgumentException("A positive version floor is required before an engine starts");
        }
        long floor = raiseVersionFloor(floorMs);
        sequenceHighWaterPosition = null;
        sequenceHighWaterEffectiveTs = 0L;
        return floor;
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
     * Raises the seeded version floor to {@code highWaterVersion}: a durable mark in
     * the snowflake domain, left by a run whose rows without a GTID were carried above
     * GTID versions (spec 02.02 section 3.5.1). Every first delivery of the coming run
     * is then versioned above it in either domain, and the run keeps its sequence
     * values above its GTID versions so that the next mark covers them as well. Never
     * lowers it; {@code <= 0} is ignored.
     *
     * @param highWaterVersion the persisted horizon
     * @return the seeded version floor in force
     */
    static synchronized long raiseSeededVersionFloor(long highWaterVersion) {
        if (highWaterVersion > seededVersionFloor) {
            seededVersionFloor = highWaterVersion;
        }
        return seededVersionFloor;
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
