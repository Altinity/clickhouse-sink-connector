# Specification 001: CDC Ingestion & Binlog Stream Processing

## 1. Executive Summary & Purpose

The Change Data Capture (CDC) ingestion subsystem captures binary log events emitted by the upstream MySQL database and transforms them into a typed, ordered stream of change records for ClickHouse replication. In standalone mode (`sink-connector-lightweight`), it embeds the Debezium engine directly within the JVM, eliminating the need for intermediary Kafka brokers.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication` (`sink-connector-lightweight/src/main/java/...`)
  - `com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture` (`sink-connector-lightweight/src/main/java/...`)
  - `com.altinity.clickhouse.sink.connector.model.ClickHouseStruct` (`sink-connector/src/main/java/...`)
  - `com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService` (`sink-connector-lightweight/src/main/java/...`)
- **Key Methods**:
  - `ClickHouseDebeziumEmbeddedApplication.main()`
  - `DebeziumChangeEventCapture.setup()`
  - `DebeziumChangeEventCapture.handleChangeEventBatch()`
  - `DebeziumChangeEventCapture.commitControlRecordOffset()`

---

## 3. Operational State Machine & Flow

```
+-----------------------------------------------------------------------------------+
|                            CDC INGESTION PIPELINE                                 |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  [MySQL Binary Log]                                                               |
|        |                                                                          |
|        v (Debezium ChangeConsumer)                                                |
|  DebeziumChangeEventCapture.handleChangeEventBatch(list, committer)               |
|        |                                                                          |
|        +---> Iterate over each ChangeEvent:                                       |
|                 |                                                                 |
|                 +---> Extract source timestamp: `source.ts_ms`                    |
|                 +---> Extract binlog coordinate: (filePrefix, fileSeq, pos, row)   |
|                 +---> Calculate monotonic version: nextSequenceNumber(ts, pos)    |
|                 |                                                                 |
|                 +---> Check if DDL record: isDDLRecord(record)                    |
|                 |        |                                                        |
|                 |        +--- [YES]:                                              |
|                 |        |      1. Flush buffered pre-DDL records                 |
|                 |        |      2. Execute DDL drain barrier (drainBeforeDDL)     |
|                 |        |      3. Parse & execute DDL on ClickHouse              |
|                 |        |      4. Acknowledge DDL offset                         |
|                 |        |      5. Resume worker execution                        |
|                 |        |                                                        |
|                 |        +--- [NO]:                                               |
|                 |               1. Parse CDC struct (before/after images)         |
|                 |               2. If DML: assign version, add to batch buffer    |
|                 |               3. If Control/Heartbeat: track lastControlRecord   |
|                 |                                                                 |
|        +---> Post-batch completion:                                               |
|                 1. Push accumulated batch buffer to `records` handoff queue       |
|                 2. If control record present and pipeline quiescent:              |
|                    commitControlRecordOffset()                                    |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. Operational Invariants

1. **Source Coordinate Monotonicity**:
   Every incoming event must be mapped to its native binlog coordinate `(filePrefix, fileSequence, position, row)`. These coordinates form a strict total order.
2. **Pre-DDL Row Flushing**:
   If an incoming batch contains DML events followed by a DDL event, all preceding DML events must be dispatched and flushed before the DDL event begins execution. No pre-DDL row may be processed under post-DDL table schema.
3. **Control Record Quiescence**:
   Non-data control records (e.g. Debezium heartbeats, snapshot completion tokens) must not commit their offsets unless the internal processing pipeline (`records` queue, worker executors, and in-flight batches) is provably quiescent (`isPipelineQuiescent() == true`).

---

## 5. Edge Cases & Concurrency Boundaries

- **Snapshot vs Streaming Boundary**:
  At the end of an initial snapshot, Debezium emits a heartbeat record marking `snapshot=false`. Committing this offset prematurely while initial snapshot rows are still in-flight will cause offset regression or data loss if the connector restarts.
- **Queue Backpressure**:
  The handoff queue `records` has configurable capacity `max.queue.size`. When full, `appendToRecords` blocks the Debezium engine thread, applying immediate backpressure to the MySQL binlog socket.

---

## 6. Failure Modes & Recovery

- **Connection Loss to MySQL**:
  Debezium engine automatically attempts reconnection using configured backoff parameters. Offsets remain parked at the last durably acknowledged batch.
- **Corrupt Binlog Event**:
  If a change event cannot be parsed by `debeziumRecordParserService`, the connector fails loudly and terminates execution, preventing silent row skipping.

---

## 7. Verification Criteria

- **Unit Tests**:
  `DebeziumChangeEventCaptureTest`, `DebeziumRecordParserServiceTest`.
- **Integration Tests**:
  `BasicMySQLIntegrationTest`, `BinlogPositionTrackingIT`.
- **Formal Verification**:
  Corresponds to `Replication.Binlog` and `Replication.Engine.translate_event` in Lean 4 model.
