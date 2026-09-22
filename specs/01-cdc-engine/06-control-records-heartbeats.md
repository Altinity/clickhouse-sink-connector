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
- **Terminal type for an unconvertible row**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/RecordReplicationException.java`
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

### 3.1 A null struct is contract for a CONTROL record and terminal for a ROW record
Every record that produces no `ClickHouseStruct` is classified by
`DebeziumChangeEventCapture.isControlRecord(SourceRecord)` BEFORE anything else
is decided about it. The classification is:

| Record | `isControlRecord` | Handling of a null struct |
|---|---|---|
| topic starts with `__debezium-heartbeat` | control | DEBUG log; offset committed under §3.2/§3.3 |
| value Struct whose schema has **no** `op` field (transaction metadata) | control | same |
| value **null** (Debezium tombstone after a DELETE, `tombstones.on.delete=true`) | control | same |
| value Struct **with** an `op` field (a row: `c`/`r`/`u`/`d`/`t`) | **row** | **terminal** — `RecordReplicationException` |
| value non-null and not a Struct | **row** | **terminal** — `RecordReplicationException` |

**Control records.** `SourceRecordParserService.parse` builds a row only when the
value Struct carries an `op` field. A heartbeat (topic
`__debezium-heartbeat.<server>`, value `{ts_ms}`) and a transaction-metadata
record (topic `<server>.transaction`, value `{status,id,event_count,...}`) have no
`op`, so `parse` returns null by contract — there is no row to write.
`processEveryChangeRecord` must NOT dereference the null struct and must NOT
early-return on it: it returns `null`, and `handleChangeEventBatch` records the
(non-DDL) no-row record as the batch's `lastControlRecord`. The log level is
**DEBUG** (`Control record (heartbeat/transaction metadata) - no row to write; ...`);
WARN is forbidden here because heartbeats arrive every `heartbeat.interval.ms`
for the life of the process and a WARN per heartbeat buries the warnings that
matter.

**Row records — the rule this section used to get wrong.** A record whose value
carries an `op` field IS a row. If `parse` returns null for it, or throws, the row
was not converted. Until this revision the code logged
`Record could not be parsed to a ClickHouseStruct - skipping` at WARN and
returned null — and `handleChangeEventBatch` then treated that null exactly like a
heartbeat's: the record became `lastControlRecord` and, once the pipeline was
quiescent, `commitControlRecordOffset` committed its offset. The durable source
position moved past a row that never reached ClickHouse; a restart did not
redeliver it; row counts disagreed by one with nothing louder than a WARN in the
log. That is the silent loss Invariant I9 forbids, and this spec CODIFIED it.

The rule is now:
1. `processEveryChangeRecord` wraps the parser call: an exception from `parse`
   is raised as `RecordReplicationException` (cause attached); a null result
   for a record that is not a control record is raised as
   `RecordReplicationException` naming the topic.
2. `RecordReplicationException` is re-thrown **ahead of** the method's
   catch-all, exactly like `DDLReplicationException` (spec 10.04 §3.3), so it
   leaves the Debezium `handleBatch` consumer and halts the engine.
3. `handleChangeEventBatch` is the second line: a null struct from a non-DDL
   record may become `lastControlRecord` **only if** `isControlRecord(sr)`;
   otherwise it throws `RecordReplicationException` itself. No future path
   through `processEveryChangeRecord` can turn a lost row into a heartbeat.
4. A value that is neither a Struct nor null is rejected before the version
   sequence is touched (`rejectUnrepresentableValue`), with a message naming
   the value's class and topic instead of a bare `ClassCastException`.

**Redelivery and late commit.** The offset is not committed past the refused
record (nothing in this batch is acknowledged, including an earlier heartbeat
whose offset lies at or after the row), so on restart Debezium redelivers it from
the last committed position. If the record is genuinely unconvertible the
connector stops on it again, every time, until the cause is fixed — a converter
gap, `binlog_row_image != FULL`, a corrupt event. That is intended: a replication
engine that cannot represent a source row must say so, not skip it. The engine's
completion callback retries the start up to `MAX_RETRIES` and then terminates
(spec 10.04).

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
- `UnparseableRowRecordIsTerminalTest.insertWhoseParserThrowsIsTerminal` — `op='c'`, parser throws: `RecordReplicationException` propagates out of `handleChangeEventBatch` with the parser's exception as cause; `committer.processed` empty; `batchesFinished == 0`.
- `UnparseableRowRecordIsTerminalTest.updateWhoseParserReturnsNullIsTerminal` — `op='u'`, parser returns null: same outcome, message names the topic.
- `UnparseableRowRecordIsTerminalTest.nonStructValueIsTerminal`, `UnparseableRowRecordIsTerminalTest.heartbeatBeforeBadRowIsNotCommittedEither` — a non-Struct value is terminal; a heartbeat preceding the refused row is not committed.
- `UnparseableRowRecordIsTerminalTest.tombstoneIsAControlRecord`, `UnparseableRowRecordIsTerminalTest.classifier` — the classifier table above (null value = tombstone = control; `op` present = row).
- `NullParsedRowRecordIsTerminalTest.unconvertibleRowRecordIsTerminal` — INVERTED from the former NullParsedRecordSkipTest, which asserted the WARN-and-skip this section used to codify: a row record parsing to null now raises `RecordReplicationException` from `processEveryChangeRecord`, still without a NullPointerException and with no "skipping" line.
- `ControlRecordOffsetCommitTest` — the quiescence gate.
- `Replication.Snapshot.unparsed_row_halts`, `Replication.Snapshot.unparsed_row_never_committed` — an unparsed row record has no successor state, so no record list containing one reaches a commit; `Replication.Snapshot.old_rule_commits_unparsed_row` — the replaced rule committed its offset on a quiescent pipeline.
- `ControlRecordLogLevelTest.heartbeatIsLoggedAtDebugAndStillCommitsItsOffset` — a heartbeat-only batch through `handleChangeEventBatch` produces no WARN from `DebeziumChangeEventCapture`, one DEBUG control-record line, and its offset is acknowledged (`markProcessed` + `markBatchFinished`). Fails on the pre-fix code (WARN per heartbeat).
- `ControlRecordLogLevelTest.transactionMetadataIsLoggedAtDebug` — a transaction-boundary record (no `op`, non-heartbeat topic) is DEBUG, not WARN.
- `ControlRecordLogLevelTest.unparseableRowRecordIsTerminal` — INVERTED from `unparseableRowRecordStillWarns`: a record WITH `op` for which `parse` returns null is terminal, not a WARN skip.
- `UnparseableRecordProgressIT` — end to end: the control records a real pipeline produces (transaction markers, heartbeats) are skipped at DEBUG and rows keep landing; its former "WARN row-record skip" leg is removed because that outcome is now a defect.
