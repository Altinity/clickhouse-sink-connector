# Spec 01.06: Control Records, Heartbeats & Snapshot Completion (issue #1379)

## 1. Executive Summary & Purpose
Specifies how the connector handles records that produce NO ClickHouse row —
Debezium heartbeats and transaction-boundary events — so that (a) their offset is
never committed ahead of unwritten data (safety), and (b) their offset IS
committed once the pipeline is quiescent (liveness). The liveness half is issue
#1379 ("Initial Snapshot never finishes"): a snapshot's `snapshot_completed=true`
state rides only on a post-snapshot control record, so if that record's offset is
never committed the snapshot re-runs on every restart.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**: `handleChangeEventBatch`, `processEveryChangeRecord`, `commitControlRecordOffset`, `isPipelineQuiescent`, `markTerminalRecord`, `ensureHeartbeatInterval`
- **Offset bookkeeping**: `sink-connector/.../executor/DebeziumOffsetManagement.java` (`hasUnwrittenBatches`, `acknowledgeRecords`)
- **Formal model**: `formal_specs/lean/Replication/Snapshot.lean`

---

## 3. Operational Specification

### 3.0 Root cause of #1379 (what must NOT happen)
Debezium marks a snapshot complete only AFTER the last snapshot row; every
snapshot ROW carries `snapshot_completed=false`. The completed state rides on a
record emitted after the snapshot — on an idle source, only a heartbeat, which
`SourceRecordParserService.parse` converts to a `null` ClickHouseStruct.
Historically that record was mishandled two ways, both of which stalled the
snapshot forever:
1. `processEveryChangeRecord` called `setSequenceNumber` on the null struct →
   NullPointerException every heartbeat cycle, swallowed by the catch-all.
2. Even without the NPE, a null-producing record was dropped without ever being
   acknowledged, so its offset never advanced.

### 3.1 Null control records are contract, not failure
`processEveryChangeRecord` must NOT dereference a null parsed struct and must NOT
early-return on it: it null-guards `setSequenceNumber`/status updates, returns
`null`, and `handleChangeEventBatch` records the (non-DDL) no-row record as the
batch's `lastControlRecord`.

### 3.2 Safety — quiescence gate on the control-record commit
`commitControlRecordOffset` commits the control offset ONLY when both:
1. `handedOffRows == false` — this batch handed no rows to the writers; and
2. `isPipelineQuiescent() == true`, i.e. ALL of:
   - `records` is null/empty (legacy handoff queue),
   - every per-thread queue in `routedQueues` is empty (hash routing), and
   - `!DebeziumOffsetManagement.hasUnwrittenBatches()` — the outstanding
     handoff-sequence set is empty (nothing handed off is unwritten, in
     flight, or parked; spec 09.01 §3.6).

Otherwise it returns `false` without advancing the offset. This prevents
committing a control offset past rows not yet in ClickHouse (issue #1285).

### 3.3 Liveness — offset progress independent of batch shape
1. **Terminal marker on every handed-off unit — precisely.** A *unit* is one
   list passed to `appendToRecords` (the rows of one Debezium batch, or the
   rows ahead of a DDL when the batch is split). `handleChangeEventBatch` calls
   `markTerminalRecord(unit)` immediately before every handoff, so the LAST row
   of every unit, in binlog order, carries `isLastRecordInBatch == true`, and
   no other row of that unit does (the Debezium-index flag lands on the same
   row when the batch is not split, or on the DDL record, which acknowledges
   itself). In routing mode a unit is split into per-table groups for different
   workers; the groups carry no marker of their own. The unit is acknowledged
   as a whole — `markProcessed` for each row in binlog order, then ONE
   `markBatchFinished()` at the terminal row — once every group is written and
   every lower handoff sequence is acknowledged (spec 09.01 §3.3). Hence every
   handed-off unit flushes its offset exactly once, regardless of where control
   records fall in the Debezium batch and regardless of which worker finished
   last.
2. **Guaranteed heartbeats.** `ensureHeartbeatInterval` sets a bounded
   `heartbeat.interval.ms` so the post-snapshot control record actually arrives
   on an idle source and, once quiescent, commits `snapshot_completed=true`.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)** / **I12 (Snapshot Completion)**: a control offset is committed only when quiescent (safety) and IS committed once quiescent (liveness), so the snapshot terminates and never re-runs.

---

## 5. Verification Criteria
- `Replication.Snapshot.control_commit_safe` — a control offset advances only when `outstanding = 0`.
- `Replication.Snapshot.quiescent_control_commits` — a quiescent control record commits its offset.
- `Replication.Snapshot.snapshot_completes` — after the snapshot's rows are written, the end-of-snapshot control record commits its offset (`committed = snapPos`).
- `SnapshotOffsetProgressTest.marksExactlyTheLastRow` — every handed-off unit gets exactly one terminal marker.
- `OffsetHandoffOrderTest.routedGroupsAcknowledgedAsOneUnitInBinlogOrder` — one `markBatchFinished` per unit, after all its groups are written.
- `NullParsedRecordSkipTest`, `ControlRecordOffsetCommitTest` — null-skip and the quiescence gate.
