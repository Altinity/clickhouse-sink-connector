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

**Why `parse` returns null for them.** `SourceRecordParserService.parse` builds a
row only when the value Struct carries an `op` field (`c`/`r`/`u`/`d`/`t`). A
heartbeat (topic `__debezium-heartbeat.<server>`, value `{ts_ms}`) and a
transaction-metadata record (topic `<server>.transaction`, value
`{status,id,event_count,data_collections}`) have no `op`, so `parse` returns
null by contract — there is no row to write.

**Log level.** A null parse result is classified BEFORE it is logged
(`DebeziumChangeEventCapture.isControlRecord(SourceRecord)`):
- **Control record** — the topic starts with `__debezium-heartbeat`, OR the value
  Struct's schema has no `op` field: logged at **DEBUG** as
  `Control record (heartbeat/transaction metadata) - no row to write; ...`.
  Its offset is still committed under §3.2/§3.3. WARN is forbidden here:
  heartbeats arrive every `heartbeat.interval.ms` (seconds apart) for the life
  of the process, and a WARN per heartbeat buries the warnings that matter.
- **Row record** — the value Struct HAS an `op` field and `parse` still returned
  null: a real row was dropped, logged at **WARN** as
  `Record could not be parsed to a ClickHouseStruct - skipping ...` (issue #1379
  visibility requirement).

### 3.2 Safety — quiescence gate on the control-record commit
`commitControlRecordOffset` commits the control offset ONLY when both:
1. `handedOffRows == false` — this batch handed no rows to the writers; and
2. `isPipelineQuiescent() == true`, i.e. ALL of:
   - `records` is null/empty (legacy handoff queue),
   - every per-thread queue in `routedQueues` is empty (hash routing), and
   - `!DebeziumOffsetManagement.hasUnwrittenBatches()` (nothing in flight).

Otherwise it returns `false` without advancing the offset. This prevents
committing a control offset past rows not yet in ClickHouse (issue #1285).

### 3.3 Liveness — offset progress independent of batch shape
1. **Terminal marker on every handoff.** `handleChangeEventBatch` marks the last
   row of each handed-off batch via `markTerminalRecord` before handoff. Without
   it, a Debezium batch that ends with a control record (or is split ahead of a
   DDL) leaves no row flagged `isLastRecordInBatch`, so `acknowledgeRecords`
   never calls `markBatchFinished()` and that batch's offset is only committed by
   a later heartbeat — delaying, and on an idle source stranding, progress.
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
- `SnapshotOffsetProgressTest.marksExactlyTheLastRow` — every handed-off batch gets exactly one terminal marker.
- `NullParsedRecordSkipTest` (row record carrying `op`), `ControlRecordOffsetCommitTest` — null-skip at WARN and the quiescence gate.
- `ControlRecordLogLevelTest.heartbeatIsLoggedAtDebugAndStillCommitsItsOffset` — a heartbeat-only batch through `handleChangeEventBatch` produces no WARN from `DebeziumChangeEventCapture`, one DEBUG control-record line, and its offset is acknowledged (`markProcessed` + `markBatchFinished`). Fails on the pre-fix code (WARN per heartbeat).
- `ControlRecordLogLevelTest.transactionMetadataIsLoggedAtDebug` — a transaction-boundary record (no `op`, non-heartbeat topic) is DEBUG, not WARN.
- `ControlRecordLogLevelTest.unparseableRowRecordStillWarns` — a record WITH `op` for which `parse` returns null is still WARN.
- `ControlRecordLogLevelTest.isControlRecordClassification` — the classifier: heartbeat topic → control; no `op` → control; `op` present → row; null/non-Struct value → row.
