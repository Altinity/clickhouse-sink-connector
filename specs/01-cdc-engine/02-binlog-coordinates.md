# Spec 01.02: Binary Log Coordinate Extraction & Ordering

## 1. Executive Summary & Purpose
Specifies the extraction, normalization, and comparison of binary log coordinates from MySQL change events. The coordinate establishes the absolute total ordering of transactions in the MySQL source stream.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/SourcePosition.java`
- **CDC Interceptor**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `SourcePosition.parseSourcePosition(Map<String, ?> sourceOffset)`
  - `SourcePosition.compareTo(SourcePosition other)`

---

## 3. Coordinate Tuple Specification

A MySQL binary log position is a 4-tuple:
$$(F_{\text{prefix}}, F_{\text{seq}}, P_{\text{byte}}, R_{\text{idx}})$$
Where:
- $F_{\text{prefix}}$: The binlog file name prefix (e.g. `mysql-bin`).
- $F_{\text{seq}}$: The integer sequence extracted from the file name suffix (e.g. `000123` $\to 123$).
- $P_{\text{byte}}$: The 64-bit byte offset within the binlog file (`pos`).
- $R_{\text{idx}}$: The 0-indexed row position within a multi-row event (`row`).

### 3.1 Total Order Lexicographical Comparison
Given two coordinates $C_1 = (F_1, P_1, R_1)$ and $C_2 = (F_2, P_2, R_2)$:
$$C_1 < C_2 \iff (F_1 < F_2) \lor (F_1 = F_2 \land P_1 < P_2) \lor (F_1 = F_2 \land P_1 = P_2 \land R_1 < R_2)$$

### 3.2 GTID Coordination
When Global Transaction Identifiers (GTID) are enabled:
- The connector extracts the GTID set string from `source.gtid`.
- The GTID is recorded in `ClickHouseStruct` metadata and written to `binlog_history` for exact auditability.

---

## 4. Invariants Preserved
- **Invariant I1 (Log Sequence Monotonicity)**: Every event emitted by MySQL possesses a coordinate strictly ordered according to commit order.
- **Strict Coordinate Well-Formedness**: Any event missing file sequence or position triggers a fatal parsing error.

---

## 5. Verification Criteria
- `SourcePositionTest`: Tests coordinate parsing across 6-digit, 7-digit, and non-standard binlog file names.
- Corresponds to `Replication.Binlog.BinlogPos` and `BinlogPos.lt` in `formal_specs/lean/`.
