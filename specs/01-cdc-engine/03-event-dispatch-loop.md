# Spec 01.03: CDC Event Batch Dispatch & Triage Loop

## 1. Executive Summary & Purpose
Specifies the central event dispatch loop invoked by Debezium's `ChangeConsumer`. The loop assigns a version to every incoming change event, categorizes events into DDL, DML, or control records, enforces the DDL barrier, hands row batches to the writers and commits control-record offsets when the pipeline is quiescent.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `void handleChangeEventBatch(List<ChangeEvent<SourceRecord, SourceRecord>> list, ...)` (package-private; the per-batch loop)
  - `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)` (spec 02.01–02.03)
  - `ClickHouseStruct processEveryChangeRecord(Properties props, ChangeEvent record, DebeziumRecordParserService parser, ClickHouseSinkConnectorConfig config, RecordCommitter committer, boolean lastRecordInBatch, long recordSequenceNumber)` — returns the parsed row, or `null` for DDL and control records
  - `boolean isDDLRecord(ChangeEvent record)`
  - `private void drainBeforeDDL()` / `private void performDDLOperation(String DDL, Properties props, SourceRecord sr, ClickHouseSinkConnectorConfig config, RecordCommitter committer, ChangeEvent record, boolean lastRecordInBatch, ClickHouseStruct ddlStruct)`
  - `static void markTerminalRecord(List<ClickHouseStruct> batch)`
  - `void commitControlRecordOffset(ChangeEvent lastControlRecord, RecordCommitter committer, boolean handedOffRows)` (spec 01.06 / 09.04)

---

## 3. Dispatch & Triage Algorithm

For every incoming batch emitted by Debezium:
1. Initialize `List<ClickHouseStruct> batch = new ArrayList<>()`, `ChangeEvent lastControlRecord = null`, `boolean handedOffRows = false`.
2. For each `ChangeEvent record` (index `i`; `lastRecordInBatch = (i == list.size() - 1)`):
   - Extract the source timestamp: `long recordTs = ClickHouseStruct.getSourceTsFromChangeEvent(record)` (`source.ts_ms` for streaming rows; the envelope `ts_ms` for snapshot rows and for records without a source struct).
   - **Compute the version for EVERY record, before triage**: `long recordSequenceNumber = nextSequenceNumber(recordTs, ClickHouseStruct.getSourcePositionFromChangeEvent(record))`. This call is made for DDL records, heartbeats and transaction-boundary events as well as for rows, so every record advances the sequence counter and can raise the timestamp floor (spec 02.02 §3.2).
   - `boolean ddlRecord = isDDLRecord(record)`. **If DDL and `batch` is non-empty**: `markTerminalRecord(batch)`; `appendToRecords(new ArrayList<>(batch), config)`; `batch.clear()`; `handedOffRows = true`. Rows read before the DDL in the same Debezium batch are handed to the writers first so the schema change lands at its true stream position.
   - `ClickHouseStruct chStruct = processEveryChangeRecord(props, record, parser, config, committer, lastRecordInBatch, recordSequenceNumber)`:
     - **DDL path** (inside `processEveryChangeRecord`): in a `try` — `drainBeforeDDL()` (spec 06.01: wait for the handoff queue to empty, pause the executor, await quiescence), build a `ddlStruct` carrying the current `sequenceNumber`, then `performDDLOperation(...)`, which translates and executes the DDL (with the configured retry policy), invalidates schema caches (`CacheInvalidationManager.invalidateTable(tableKey)` for every resolved table, or `invalidateAll()` when the affected table cannot be determined) and **acknowledges the DDL's offset itself** via `DebeziumOffsetManagement.acknowledgeRecords(committer, ddlStruct, lastRecordInBatch)`. Any failure is wrapped in `DDLReplicationException` and re-thrown ahead of the method's catch-all (spec 10.04 §3.3). The executor is resumed in a **`finally`** block (`this.executor.resume()` when the executor is not `null`), so a failed DDL never leaves the pool paused. Returns `null`.
     - **DML path**: parses the record, sets the version from `recordSequenceNumber`, returns the `ClickHouseStruct`. If the parser throws, or returns `null` for a record whose value carries an `op` field, the record is a row that was NOT converted: `RecordReplicationException` is raised and re-thrown ahead of the catch-all (spec 01.06 §3.1, spec 10.04 §3.3). A value that is neither a Struct nor `null` is rejected the same way before the version is computed.
     - **Control / heartbeat path** (a Struct value with no `op`, or a `null` value — a tombstone): returns `null`.
   - If `chStruct != null`: `batch.add(chStruct)`. Else if `!ddlRecord`: the record may become `lastControlRecord` **only if** `isControlRecord(record.value())`; otherwise `RecordReplicationException` is thrown here too (second line of defence). DDL acknowledges itself and is never treated as a control record.
3. Post-loop:
   - If `batch` is non-empty: `markTerminalRecord(batch)` (the last handed-off row carries `isLastRecordInBatch = true` even when the Debezium batch ended with a control record), `appendToRecords(batch, config)`, `handedOffRows = true`.
   - `commitControlRecordOffset(lastControlRecord, committer, handedOffRows)` is always called; it commits the newest control record's offset only when no rows from this batch were handed off (and the pipeline is otherwise quiescent — spec 09.04).

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: rows preceding a DDL in the same batch are handed off before the drain; the drain and pause complete before the DDL executes; caches are invalidated before the executor resumes.
- **Invariant I12 (Control-record offset progress)**: every handed-off batch carries a terminal marker, so its offset is flushed once written regardless of where control records fall in the Debezium batch.
- **Loud DDL failure (I9)**: a DDL that cannot be applied stops the engine rather than being skipped.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.shouldRecogniseDDLRecord()`, `DebeziumChangeEventCaptureTest.shouldNotTreatRowChangeAsDDL()`, `DebeziumChangeEventCaptureTest.shouldNotTreatEmptyDDLAsDDL()` — the triage predicate.
- `SnapshotOffsetProgressTest` — `markTerminalRecord` marks exactly the last handed-off row.
- `ControlRecordOffsetCommitTest` — control records are retained as `lastControlRecord` and committed only when nothing was handed off.
- `UnparseableRowRecordIsTerminalTest` — a row record that produced no struct is never retained as `lastControlRecord`: `RecordReplicationException` leaves `handleChangeEventBatch` and nothing is acknowledged.
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — the DDL path escapes the catch-all; `DdlDrainDeadlockTest` — the drain does not deadlock.
- Verification: an end-to-end test of interleaved DML and DDL asserting exact execution ordering is not yet covered by an automated test (gap).
