# Spec 01.02: Binary Log Coordinate Extraction & Ordering

## 1. Executive Summary & Purpose
Specifies the extraction, normalization, and comparison of binary log coordinates from MySQL change events. The coordinate establishes the total ordering of events in the source stream and is what separates a first delivery from a redelivery in the version sequence (spec 02.02).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/SourcePosition.java`
- **CDC Interceptor**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `SourcePosition.ofBinlog(String file, Long pos, Integer row)` — MySQL/MariaDB coordinate; a `null` row is row 0; a missing file or pos yields no position (`null`).
  - `SourcePosition.ofLsn(Long lsn)` — PostgreSQL WAL position.
  - `SourcePosition.binlogFileSequence(String file)` (package-private) — numeric suffix extraction.
  - `SourcePosition.compareTo(SourcePosition other)` / `equals` / `hashCode`.
  - `SourcePosition.sameLog(SourcePosition other)` — whether two positions belong to the same binary log (equal file prefix); false across a log basename change (§3.1.1).
  - `ClickHouseStruct.getSourcePositionFromChangeEvent(ChangeEvent<SourceRecord, SourceRecord>)` — reads `source.file` / `source.pos` / `source.row` (or `source.lsn`) from the Debezium envelope; `ClickHouseStruct.getSourcePosition()` does the same from an already-parsed struct.

---

## 3. Coordinate Tuple Specification

A MySQL binary log position is derived from the 4-tuple:
$$(F_{\text{prefix}}, F_{\text{seq}}, P_{\text{byte}}, R_{\text{idx}})$$
Where:
- $F_{\text{prefix}}$: The binlog file name prefix (e.g. `mysql-bin`).
- $F_{\text{seq}}$: The integer sequence extracted from the file name suffix (e.g. `000123` $\to 123$) by `binlogFileSequence`. File names without a numeric suffix fall back to lexical comparison of the whole file name; mixed suffixes keep a transitive order.
- $P_{\text{byte}}$: The 64-bit byte offset within the binlog file (`pos`).
- $R_{\text{idx}}$: The 0-indexed row position within a multi-row event (`row`).

### 3.1 Total Order Lexicographical Comparison
Given two coordinates $C_1 = (F_1, P_1, R_1)$ and $C_2 = (F_2, P_2, R_2)$:
$$C_1 < C_2 \iff (F_1 < F_2) \lor (F_1 = F_2 \land P_1 < P_2) \lor (F_1 = F_2 \land P_1 = P_2 \land R_1 < R_2)$$
PostgreSQL positions compare numerically on the LSN.

### 3.1.1 Log identity change (basename change, `RESET MASTER`)
The order compares $F_{\text{prefix}}$ first, as a string, so it is total across differently named logs — but that order is meaningless across a **log identity change**: when the binary log basename changes (`log_bin` reconfigured, a failover to a server with a different basename, or a `RESET MASTER` followed by the engine being re-created in the same JVM through its completion-callback retry) every position of the new log ranks entirely above or entirely below every position of the old one purely by the spelling of the prefix (`binlog` < `mysql-bin`). A consumer that used that comparison to tell a first delivery from a redelivery would classify the whole new log as a redelivery (and never clamp it, spec 02.02 §3.1) or as new. `sameLog` exposes the prefix equality so the version sequence resets its high-water mark instead of comparing across the change: a positioned record whose prefix differs from the mark's is a **first delivery** and becomes the new mark (spec 02.02 §3.1). PostgreSQL positions have an empty prefix and are always in the same log.

**Gap (tracked)**: a `RESET MASTER` that keeps the basename restarts the numbering at `000001`; within one JVM those positions rank below the mark and are treated as in-run redeliveries (not clamped) until the numbering passes the old mark. Positions alone cannot distinguish that from a legitimate in-run rewind; a process restart clears the mark and is the remedy.

### 3.2 GTID Coordination
When Global Transaction Identifiers (GTID) are enabled:
- The connector reads the GTID from the Debezium `source` struct into `ClickHouseStruct.gtid`.
- The GTID is not part of `SourcePosition`; it is consumed by `ClickHouseStruct.calculateVersion` (spec 02.01, precedence rule), where it takes precedence over the sequence number.

---

## 4. Invariants Preserved
- **Invariant I1 (Log Sequence Monotonicity)**: Debezium delivers events in log order, and log order is commit order; the coordinate makes that order comparable in code.
- **Missing coordinates are not fatal**: an event without file/pos (or lsn) yields no `SourcePosition`; it is versioned as a positionless record (spec 02.04) rather than rejected.

---

## 5. Verification Criteria
- `SourcePositionTest`: ordering by pos then row, rotation ordering by file number, non-numeric file names, transitive mixed suffixes, missing coordinates yielding no position, LSN ordering, `equals`/`compareTo` agreement, and extraction from MySQL / PostgreSQL source structs.
- `SourcePositionTest.sameLogIsByFilePrefix()` — §3.1.1: same prefix across a rotation, different prefix across a basename change, LSNs always the same log.
- `CommitOrderVersionClampTest.binlogBasenameChangeResetsTheHighWaterMark()` — §3.1.1 through the version sequence: the first record of a differently named log is a first delivery, is clamped to the floor and becomes the new mark (pre-fix: treated as a redelivery, never clamped).
- Corresponds to `Replication.Binlog.BinlogPos` and `BinlogPos.lt` in `formal_specs/lean/`.
