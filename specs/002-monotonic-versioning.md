# Specification 002: Monotonic Versioning & Ordering

## 1. Executive Summary & Purpose

ClickHouse `ReplacingMergeTree` relies on a dedicated version column (`_version`) to resolve row mutations and collapse duplicate rows into the latest canonical state. The versioning subsystem assigns each MySQL CDC change event a strictly monotonically increasing 64-bit integer version based on transaction commit timestamps and binary log sequence positions.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Class**:
  - `com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture` (`sink-connector-lightweight/src/main/java/...`)
- **Key Methods & Constants**:
  - `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
  - `SEQUENCE_START = 1_000_000_000L`
  - `SEQUENCE_START_INITIAL = 500_000_000L`
  - `sequenceMaxSourceTs`: Tracking floor for commit monotonicity
  - `sequenceHighWaterPosition`: High-water mark for binary log coordinates

---

## 3. Version Generation Specification

### 3.1 64-bit Version Encoding Formula
$$\text{version} = (\text{effectiveTs} \times 1{,}000{,}000) + \text{sequenceNumber}$$
Where:
- $\text{effectiveTs}$ is the epoch millisecond timestamp associated with the commit.
- $\text{sequenceNumber}$ is an intra-second counter providing fine-grained ordering for multiple events within the same millisecond.

### 3.2 Commit Monotonicity Floor Logic
In MySQL, `source.ts_ms` is the statement execution timestamp, **not** the transaction commit timestamp.
- **Problem**: A long-running transaction $T_1$ that started at $t = 100$ may commit *after* a short transaction $T_2$ that started and committed at $t = 150$. If $T_1$ carries $t = 100$ into the version formula, its version will be lower than $T_2$, causing ClickHouse `ReplacingMergeTree` to discard $T_1$'s newer commit.
- **Solution (2.11.0 Floor Rule)**:
  When an event arrives at a new high-water log position (`position > sequenceHighWaterPosition`), its `effectiveTs` is clamped to the highest previously observed timestamp:
  $$\text{effectiveTs} = \max(\text{recordTs}, \text{sequenceMaxSourceTs})$$
  $$\text{sequenceMaxSourceTs} = \text{effectiveTs}$$

### 3.3 Sequence Number Lifecycle & Intra-Second Rollover
- If $\Delta t = (\text{effectiveTs} - \text{sequenceAnchorTs}) / 1000 \ge 1$ (time advanced by at least 1 second):
  - `sequenceAnchorTs = effectiveTs`
  - `sequenceNumber = SEQUENCE_START` (`1_000_000_000L`)
- Otherwise:
  - `sequenceNumber++`

### 3.4 Offset Rewind & Redelivery Stability
When the connector restarts or recovers from an offset rewind, Debezium may redeliver events at or below the high-water mark:
- If `position <= sequenceHighWaterPosition`:
  The event is classified as an offset replay. The assignment logic preserves the assigned sequence counter without advancing `sequenceHighWaterPosition` or `sequenceMaxSourceTs`, ensuring deterministic redelivery behavior.

---

## 4. Invariants Preserved

1. **Strict Monotonicity for New Commits (Invariant I2)**:
   Any event committing later in the MySQL binlog stream receives a strictly greater version than all preceding events on the same primary key.
2. **Non-Zero Validity**:
   `record.getVersion()` must never return `-1` or `0`. An underivable version triggers `rejectUnderivableVersion`, throwing a `RuntimeException` fail-fast.

---

## 5. Known Limitations & Architectural Evolution Note

- **Arithmetic Carry Trade-off**:
  The addition of a 10-digit sequence number (`1_000_000_000`) into a timestamp shifted by $10^6$ carries across the 6-decimal-digit boundary, advancing the apparent timestamp by approximately 1,000 seconds. While internally consistent across a continuous run, the long-term target architecture specifies 64-bit log-coordinate bit-packing:
  $$\text{Version}_{\text{target}} = (\text{BinlogFileSeq} \ll 32) \mid \text{BinlogOffset}$$

---

## 6. Verification Criteria

- **Unit Tests**:
  `DebeziumChangeEventCaptureTest.testNextSequenceNumberMonotonicity()`.
- **Integration Tests**:
  `LateCommitTransactionIT`, verifying that a long-running transaction committing after a quick transaction ranks higher in ClickHouse.
- **Formal Verification**:
  Corresponds to `Replication.Engine.encode_version` and `theorem version_monotonic` in `formal_specs/lean/`.
