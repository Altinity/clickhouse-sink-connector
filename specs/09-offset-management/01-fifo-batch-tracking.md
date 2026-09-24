# Spec 09.01: FIFO Batch Tracking by Handoff Sequence & Written-Once Acknowledgement

## 1. Executive Summary & Purpose
Specifies the offset tracking engine (`DebeziumOffsetManagement`) that decides
WHEN a batch written to ClickHouse may have its Debezium offset acknowledged.
Worker threads finish batches in arbitrary wall-clock order; the offset store
keeps the LAST offset staged per partition; so the acknowledgement order alone
decides whether the durable binlog position can run ahead of rows that are still
queued or in flight. This spec fixes that order to the **handoff sequence** —
the order in which the Debezium thread handed batches to the writers, which is
binlog order — and separates **written** (rows durably in ClickHouse, the worker
is done with the batch) from **acknowledged** (offset staged with Debezium).

It replaces the earlier rule, which compared envelope-timestamp ranges among the
batches a worker had already PICKED UP. That rule was unsound under per-table
hash routing (spec 03.03) and is deleted, not amended (see §3.5).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java`
- **Producer side** (assigns sequences): `sink-connector-lightweight/.../cdc/DebeziumChangeEventCapture.java`
  — `appendToRecords`, `appendToRecordsWithHashRouting` (spec 01.05).
- **Consumer side** (reports a written batch): `sink-connector/.../executor/ClickHouseBatchRunnable.java`
  — `processBatch` (spec 03.03).
- **Carrier**: `sink-connector/.../model/RoutedBatch.java` — `getHandoffSequence()`.
- **State** (all static — process-wide, shared by every engine started in the
  JVM, see §3.8 — all guarded by the class monitor of `DebeziumOffsetManagement`
  for mutation; reads of the outstanding set are lock-free):
  - `AtomicLong handoffCounter` — monotone; the next sequence to assign.
  - `ConcurrentSkipListSet<Long> outstandingSequences` — sequences handed off
    and not yet acknowledged (ordered; `first()` is the head).
  - `ConcurrentHashMap<BatchKey, HandoffUnit> unwrittenGroups` — identity of
    every routed group (or legacy batch) not yet written → its unit.
  - `ConcurrentSkipListMap<Long, HandoffUnit> completedUnits` — units whose
    groups are ALL written, parked until every lower sequence is acknowledged.
  - `HandoffUnit { long sequence; List<ClickHouseStruct> records; int unwrittenGroups }`
    — one per `appendToRecords` call; `records` is the handed-off list in binlog order.
  - `BatchKey` — identity wrapper over a batch `List` (`==`/`identityHashCode`),
    see §3.0.
- **Key Methods**:
  - `long registerHandoff(List<ClickHouseStruct> unit, List<List<ClickHouseStruct>> groups)`
    — producer; assigns and returns the sequence.
  - `boolean checkIfBatchCanBeCommitted(List<ClickHouseStruct> group)` — consumer,
    called once per group after its rows are durably written. Returns `true`
    iff the group's unit was acknowledged during this call.
  - `boolean hasUnwrittenBatches()` — `!outstandingSequences.isEmpty()`.
  - `int outstandingCount()` — size of the outstanding set.
  - `int reset()` — abandons every outstanding unit (§3.8); called from
    `DebeziumChangeEventCapture.stop()` after the pool has terminated, and from
    `DebeziumChangeEventCapture.setup()` when the units it finds belong to a
    previous engine that terminated without `stop()` (`activeEngine` /
    `isAlive()`).
  - `acknowledgeRecords(List<ClickHouseStruct>)` — `markProcessed` for every
    record in list order, `markBatchFinished()` at the terminal record, all
    inside `OFFSET_COMMIT_LOCK` (spec 09.02).
- **Formal model**: `formal_specs/lean/Replication/OffsetFifo.lean`.

---

## 3. Operational Specification

### 3.0 Keying: identity, not timestamp range (MANDATORY)
`unwrittenGroups` MUST be keyed by the group's **object identity** (`BatchKey`),
never by a `(minTs, maxTs)` range. Two distinct batches routinely share a range
(one multi-row statement split across batches; rows in the same millisecond),
and range keys made them collide so an unwritten older batch stopped blocking
the commit. Identity keying makes every group a distinct entry regardless of
its timestamps.

### 3.1 Handoff: the producer assigns the sequence (binlog order)
On the Debezium thread, for every list handed to the asynchronous writers
(one per `appendToRecords` call; the "unit"):
1. The unit's rows are in binlog order and its LAST row carries the terminal
   marker (`markTerminalRecord`, spec 01.06 §3.3).
2. `registerHandoff(unit, groups)` is called BEFORE any group is enqueued:
   - `sequence = handoffCounter.getAndIncrement()`;
   - `outstandingSequences.add(sequence)`;
   - for every group: `unwrittenGroups.put(BatchKey(group), unit)`;
   - `unit.unwrittenGroups = groups.size()`.
   In legacy mode (`thread.pool.size == 1`) there is exactly one group: the
   unit itself. In routing mode the groups are the per-table lists that go to
   different workers' queues (spec 01.05 §3.3).
3. Only then are the groups enqueued. A consumer can therefore never finish a
   group before its unit is registered, and a batch reads as unwritten from the
   instant it is handed off — including the window between a worker's `poll()`
   and its write.
4. **Backlog advisory — edge-triggered, never per handoff, never ERROR.** If
   the registration has just taken the outstanding count above
   `BACKLOG_ADVISORY_THRESHOLD` (1000), ONE line is logged at WARN naming the
   count. The next line about the backlog is ONE INFO, logged by the
   acknowledgement (§3.3) that brings the count back to or under the
   threshold, after which the advisory is re-armed for the next crossing. A
   backlog is the reader ahead of the writers — capacity, not failure: nothing
   was lost or skipped and every guarantee below still holds, so ERROR is the
   wrong level and one line per handoff is the wrong rate. (Before this rule
   every handoff above the threshold logged an ERROR: 4,003 lines in seven
   minutes on one deployment, in an error log that otherwise held six genuine
   warnings for the hour.) `reset()` (§3.8) clears the advisory silently along
   with the set it abandons; its own abandonment WARN is the line for that
   event.

Sequences are assigned by one thread in handoff order, so
`seq(A) < seq(B)` iff A was read from the binlog before B. No wall-clock value
takes part in any commit decision.

### 3.2 Written: the consumer reports once, then moves on (WRITTEN-ONCE)
When a worker has durably written a group (`processRecordsByTopic` returned
`true` for every topic of the group) it calls `checkIfBatchCanBeCommitted(group)`
EXACTLY ONCE and then drops the group (`currentBatch = null`) **regardless of
the return value**, proceeding to the next queued batch.

Inside, under the class monitor:
1. `unit = unwrittenGroups.remove(BatchKey(group))`.
   - If no unit is registered and some record carries a Debezium committer,
     throw `IllegalStateException` — a committer-bearing batch that bypassed
     `registerHandoff` cannot be ordered and must not be acknowledged silently
     (Invariant I9).
   - If no unit is registered and no record carries a committer, return `true`:
     this is the Kafka Connect sink path, which commits offsets through its own
     durable watermark and has nothing to acknowledge here.
2. `unit.unwrittenGroups--`. If it is still `> 0`, other groups of the same
   unit are unwritten: return `false` (nothing acknowledged yet).
3. Otherwise the unit is fully written: `completedUnits.put(unit.sequence, unit)`.
4. **Drain**: while `outstandingSequences.first()` is a key of `completedUnits`:
   acknowledge that unit (`acknowledgeRecords(unit.records)`), remove it from
   both collections. Stop at the first outstanding sequence that is not yet
   completed.
5. Return `true` iff this group's unit was acknowledged by the drain.

The written→acknowledged separation is what makes the WRITTEN-ONCE invariant
hold: a batch that is written but not yet commit-eligible is parked in
`completedUnits`, never handed back to the worker, and therefore never
re-executed. Before this spec the worker kept a parked batch as `currentBatch`
and its run loop re-inserted it into ClickHouse on every scheduler tick until
it became committable (measured: every row present exactly 3 times in a
non-`FINAL` read; ~0.07% duplicate raw rows on hot tables; write amplification
and extra parts). Only a FAILED write may keep `currentBatch` for retry, and
retries back off (spec 10.02).

### 3.3 Acknowledgement: unit-level, in binlog order, one `markBatchFinished`
A unit is acknowledged as a whole, by iterating `unit.records` — the handed-off
list in binlog order — not group by group. Reason: routed groups of one unit
interleave in the binlog, and the offset writer keeps the last offset staged per
partition; acknowledging group A then group B would stage B's last offset, which
may lie BEFORE rows of A already staged, so the flushed position would regress
within the batch. Acknowledging the unit in list order stages offsets in strictly
increasing binlog order (Invariant I8) and calls `markBatchFinished()` exactly
once, at the unit's terminal record.

`markBatchFinished()` in Debezium's `AsyncEmbeddedEngine.SourceRecordCommitter`
(3.1.3) carries no per-batch state: it asks `OffsetCommitPolicy.performCommit`
and flushes if the policy says so. It is therefore safe to call once per unit
(and would be safe to call more often); the API does not require it to follow
every `markProcessed` of a Debezium batch, but I8 monotonicity does require the
`markProcessed` calls of one unit to run in binlog order, which is why the
acknowledgement is per unit.

### 3.4 Commit point
Define `commitPoint` as the number of leading sequences `0, 1, 2, …` that are
all acknowledged. By construction of the drain:
- every sequence `< commitPoint` is acknowledged;
- no outstanding sequence is `< commitPoint`;
- acknowledged sequences form a prefix of the handoff order (a sequence is
  acknowledged only after every lower one).
Hence the durable offset never passes a batch that is still queued or in
flight, on any worker. Machine-checked as `commitPoint_acked`,
`outstanding_ge_commitPoint`, `commit_never_passes_outstanding` and
`acked_downward_closed` in `OffsetFifo.lean`.

### 3.5 Why the timestamp-overlap rule was deleted (not repaired)
The previous predicate acknowledged a batch unless some OTHER batch already in
`inFlightBatches` had `otherMinTs < currentMaxTs` (strict). Two independent
defects, each sufficient for data loss under hash routing:
1. **Queued batches were invisible.** A batch entered `inFlightBatches` only
   when its worker reached `processBatch`. With one Debezium batch split into
   one group per table on different workers' queues, a fast worker (idle
   thread) finished its group while a slow worker (busy) had not yet dequeued
   its group; the slow group was in neither map, so the fast group was
   acknowledged — and it carried the Debezium batch's terminal marker, so the
   offset was committed past the queued rows. A crash before the slow worker
   dequeued lost them.
2. **Equal timestamps did not block.** With `>` strict, two batches whose rows
   share one millisecond never blocked each other even when both were in flight.
   `OffsetFifo.old_overlap_rule_unsafe` exhibits the counterexample.
A wall-clock predicate cannot be made correct here: the required order is
binlog order, and only the handoff sequence carries it.

### 3.6 Quiescence
`hasUnwrittenBatches()` is `!outstandingSequences.isEmpty()`. A sequence is
added at handoff (before the batch is visible to any consumer) and removed only
when its unit is acknowledged, so the predicate is conservative in exactly one
direction: it can withhold a control-record commit, never permit an unsafe one
(spec 09.04). There is no separate handoff counter and no separate pick-up
registry.

### 3.8 In-process engine restart: abandon, never poison
The state above is static, but the embedded engine is restarted INSIDE the
process — REST `/restart`, `/start` after `/stop`, and the restart monitor each
construct a new `DebeziumChangeEventCapture` on the SAME FIFO. When the old
engine's worker pool terminates, any unit still outstanding can never be written
or acknowledged by anyone; left in place it is the FIFO head forever: every unit
of the new engine parks behind it (§3.2 step 4 stops at the first outstanding
sequence), no offset is ever acknowledged again, `hasUnwrittenBatches()` stays
true (no control-record commit, spec 09.04; every DDL drain times out, spec
06.01), rows keep being inserted while the durable offset freezes, and the
parked units' record lists leak.

Contract:
1. `DebeziumChangeEventCapture.stop()` closes the engine (no more handoffs),
   drains through the still-running pool (bounded), shuts the pool down, and
   THEN calls `reset()` (spec 01.01 §3.3). `reset()` clears
   `outstandingSequences`, `unwrittenGroups` and `completedUnits`, logs the
   number of abandoned units at WARN, and returns it. `handoffCounter` is NOT
   reset: sequences stay unique for the life of the JVM.
2. Everything abandoned was never acknowledged (a unit leaves the outstanding
   set only through acknowledgement), so no committed offset is rolled back and
   the next engine redelivers the abandoned rows from the last committed
   offset — at-least-once, never loss (`Replication.OffsetFifo.acked_never_rolled_back`).
   A unit that was written but parked is abandoned too: its rows are in
   ClickHouse and will be redelivered; the redelivery-stable versioning (spec
   02.04) makes the second write idempotent.
3. `setup()` never starts a new engine on top of a LIVE engine's units.
   `DebeziumChangeEventCapture` keeps `private static volatile
   DebeziumChangeEventCapture activeEngine` — set at the end of `setup()`,
   cleared by that instance's `stop()` — and `isAlive()`: its worker pool
   exists and is not terminated AND its Debezium event executor is not shut
   down (the two things `stop()` tears down; single-threaded mode has no pool
   and hands nothing off, so it is never alive here) AND at least one of its
   scheduled workers has not terminated — a pool whose every worker died on a
   FATAL rethrow (spec 03.01 §3.3) can never write or acknowledge a unit
   again and counts as dead even though the pool object was never shut
   down. When
   `hasUnwrittenBatches()` at the top of `setup()`:
   - `activeEngine != null && activeEngine != this && activeEngine.isAlive()`:
     refuse (`IllegalStateException` naming the count). The live engine's pool
     can still write and acknowledge those units; a second engine would park
     behind them forever. `stop()` on the live engine is what clears the way.
   - otherwise (no recorded engine, or the recorded engine is dead): the units
     belong to an engine that terminated WITHOUT `stop()` — its pool taken
     down by a FATAL worker, a test that never stopped its engine — and nobody
     can ever acknowledge them. `setup()` logs at WARN ("previous engine in
     this process terminated without stop(); abandoning N handed-off
     batch(es) …") and calls `reset()` exactly as `stop()` step 5 does, so
     this engine starts from a quiescent FIFO. Everything abandoned was never
     acknowledged (item 2), so this is redelivery, never loss. Before this
     rule a dead engine's leftovers refused every later engine in the process:
     in CI one unrepresentable value (spec 10.01 §3.1) failed one Postgres IT
     and then, through this refusal, 29 unrelated MySQL ITs in the same JVM.
4. The engine's own completion-callback retry (`setupDebeziumEventCapture` on
   the same instance, spec 10.04 §3.5) does NOT reset: its pool is still alive
   and will finish the outstanding units. (It does not pass through `setup()`
   at all; `activeEngine != this` keeps the same-instance case out of the
   refusal in any event.)

Machine-checked as the `restart` event of `OffsetFifo.lean`:
`restart_quiescent`, `acked_never_rolled_back`, `abandoned_not_acked`, the
counterexample `old_restart_poisons_fifo` (old `stop()`: `handoff, restart,
handoff, write 1` leaves `acked = []` with `0` outstanding) and
`restart_unblocks_next_engine` (with the reset the same run acknowledges `1`).
`acked_downward_closed` is stated modulo abandoned sequences: below an
acknowledged sequence everything is acknowledged or abandoned, never
outstanding.

### 3.7 Handoff failure is loud
If enqueueing a group fails (`InterruptedException` from `put`), the exception
propagates out of `handleChangeEventBatch`: the unit stays outstanding, nothing
is acknowledged, the engine stops, and a restart redelivers from the last
committed offset. The registration is deliberately NOT released — releasing it
would let a later batch commit an offset past rows that never reached a queue.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: offsets are acknowledged in
  handoff (binlog) order; a batch's offset is never staged while a lower-sequence
  batch is unacknowledged, whether that batch is queued, in flight, or parked.
  Within a unit, offsets are staged in binlog order, so the durable position is
  monotone.
- **Invariant I1 (Log Sequence Monotonicity)**: commit order follows the handoff
  order, which is the binlog order, never wall-clock timestamps.
- **WRITTEN-ONCE**: a batch whose rows are durably written is never executed
  again by any worker. Machine-checked as `write_at_most_once` and
  `written_batch_not_reexecuted` in `OffsetFifo.lean`.
- **Invariant I9 (Loud Failure)**: an unregistered committer-bearing batch and a
  failed handoff both throw.

---

## 5. Verification Criteria
- `OffsetHandoffOrderTest.queuedOlderBatchBlocksYoungerCommit` — A(seq 1) still
  queued, B(seq 2) written: B is parked, `markProcessed` never called; then A
  written: A acknowledged before B.
- `OffsetHandoffOrderTest.equalTimestampsInFlightStillOrdered` — both in flight,
  equal `ts_ms`: same result.
- `OffsetHandoffOrderTest.outOfOrderCompletionAcknowledgesInSequence` — written
  3, 1, 2 → acknowledged 1, 2, 3, exactly one `markBatchFinished` per unit.
- `OffsetHandoffOrderTest.routedGroupsAcknowledgedAsOneUnitInBinlogOrder` — two
  groups of one unit finishing in reverse: nothing acknowledged until both are
  written, then `markProcessed` in binlog order, one `markBatchFinished`.
- `OffsetHandoffOrderTest.committerBearingBatchWithoutHandoffIsRejected`.
- `ParkedBatchWrittenOnceTest.parkedBatchIsNotReinserted` — mock writer counts
  insert executions per batch when commit eligibility is initially false:
  exactly 1 (was > 1).
- `OffsetBatchIdentityTest` — identity keying: equal-timestamp batches are
  distinct sequences; a sibling with the same range still blocks; a batch does
  not block itself; acknowledging one leaves the sibling tracked.
- `HandedOffBatchVisibilityTest` — visible from handoff; per-group counting;
  quiescent only after the whole unit is acknowledged.
- `HandoffBacklogAdvisoryTest` — §3.1 step 4: crossing the threshold logs
  exactly one WARN naming the count and nothing at ERROR
  (`raisedOnceAtWarnWhenCrossingTheThreshold`); draining back to the threshold
  logs exactly one INFO, further drain and under-threshold handoffs log
  nothing, and the next crossing is advised again
  (`clearedOnceWhenTheBacklogDrainsUnderTheThreshold`); `reset()` clears the
  advisory without a clearing line and re-arms it (`resetClearsTheAdvisory`).
- `EngineRestartFifoResetTest` — §3.8: `stop()` abandons a never-written unit and
  the next engine's heartbeat commits / first unit is acknowledged with nothing
  parked (`stopThenStartNewInstanceIsNotPoisoned`); nothing outstanding after
  `stop()`; engine closed before the pool; in-flight work drained before the
  pool stops; `deadEngineWithoutStopIsAbandonedNotPoisoning` — the recorded
  engine's pool terminated without `stop()`, FIFO dirty: a second engine's
  `setup()` succeeds, the FIFO is empty afterwards and its first unit is
  acknowledged at once; `liveEngineStillRefusesASecondEngine` — the recorded
  engine alive with a dirty FIFO: `setup()` of a second instance throws the
  `IllegalStateException`, the live engine's unit is untouched, and after its
  `stop()` (which clears `activeEngine`) a new engine starts.
- `Replication.OffsetFifo` — `commit_never_passes_outstanding`,
  `acked_downward_closed`, `commitPoint_acked`, `outstanding_ge_commitPoint`,
  `write_at_most_once`, `written_batch_not_reexecuted`,
  `old_overlap_rule_unsafe`, `fifo_acknowledges_in_handoff_order`,
  `restart_quiescent`, `acked_never_rolled_back`, `abandoned_not_acked`,
  `old_restart_poisons_fifo`, `restart_unblocks_next_engine`.
