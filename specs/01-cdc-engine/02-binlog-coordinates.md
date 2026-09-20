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
- Corresponds to `Replication.Binlog.BinlogPos` and `BinlogPos.lt` in `formal_specs/lean/`.
