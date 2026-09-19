# Spec 01.03: CDC Event Batch Dispatch & Triage Loop

## 1. Executive Summary & Purpose
Specifies the central event dispatch loop invoked by Debezium's `ChangeConsumer`. The loop categorizes incoming change events into DDL, DML, or Control records, calculates monotonic versions, enforces schema barriers, and manages thread handoff.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Method**:
  - `void handleChangeEventBatch(List<ChangeEvent<String, String>> list, RecordCommitter<ChangeEvent<String, String>> committer, Properties props, DebeziumRecordParserService parser, ClickHouseSinkConnectorConfig config)`

---

## 3. Dispatch & Triage Algorithm

For every incoming batch emitted by Debezium:
1. Initialize an empty accumulation buffer: `List<ClickHouseStruct> batch = new ArrayList<>()`.
2. Initialize `ChangeEvent lastControlRecord = null` and `boolean handedOffRows = false`.
3. For each `ChangeEvent record` in the batch:
   - Extract source timestamp: `ClickHouseStruct.getSourceTsFromChangeEvent(record)`.
   - Extract source position: `ClickHouseStruct.getSourcePositionFromChangeEvent(record)`.
   - Compute monotonic version: `long version = nextSequenceNumber(recordTs, position)`.
   - Triage record:
     - **If DDL (`isDDLRecord(record)`)**:
       1. Flush accumulated DML records: `appendToRecords(batch, config)` and `batch.clear()`.
       2. Execute DDL barrier protocol: `drainBeforeDDL()`.
       3. Translate and execute DDL on ClickHouse: `performDDLOperation()`.
       4. Invalidate schema cache: `CacheInvalidationManager.invalidateTable()`.
       5. Acknowledge DDL offset: `committer.markProcessed(record)`.
       6. Resume executor: `executor.resume()`.
     - **If DML (`record.value()` contains table data)**:
       1. Parse Kafka Connect `Struct` via `debeziumRecordParserService.parse()`.
       2. Assign monotonic `version` to struct.
       3. Append parsed struct to `batch`.
       4. Set `handedOffRows = true`.
     - **If Control / Heartbeat (`record.value() == null` or heartbeat topic)**:
       1. Record `lastControlRecord = record`.
4. Post-Loop Completion:
   - If `!batch.isEmpty()`: call `appendToRecords(batch, config)`.
   - If `lastControlRecord != null && !handedOffRows`:
     Call `commitControlRecordOffset(lastControlRecord, committer, handedOffRows)`.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: No DML row preceding a DDL record can ever be handed off or executed after the DDL executes.
- **Fail-Fast Parse Guard**: An unparseable record throws an unhandled exception to prevent silent data drop.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testHandleChangeEventBatch()`
- Integration test with interleaved DML and DDL events asserting exact execution ordering.
