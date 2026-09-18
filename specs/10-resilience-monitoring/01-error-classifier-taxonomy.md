# Spec 10.01: Error Classification Taxonomy & Code Mapping

## 1. Executive Summary & Purpose
Specifies the error triage taxonomy in `ClickHouseErrorClassifier` that maps JDBC exceptions and ClickHouse error codes into actionable severity tiers.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/error/ClickHouseErrorClassifier.java`
- **Enum**: `CLICKHOUSE_ERROR_SEVERITY { FATAL, RETRIABLE, UNKNOWN }`

---

## 3. Operational Specification

### 3.1 Severity Rules
- **`FATAL`**:
  - ClickHouse `Code: 44` (`Cannot insert into MATERIALIZED column` without remediation).
  - ClickHouse `Code: 36` (Illegal nullability alteration).
  - ClickHouse `Code: 60` (Table does not exist, with `auto.create.tables=false`).
  - `ConnectException: OffsetStorageWriter is already flushing`.
  - $\implies$ Halts connector execution immediately.
- **`RETRIABLE`**:
  - ClickHouse `Code: 241` (Memory limit exceeded).
  - ClickHouse `Code: 159` (Timeout).
  - Socket exceptions, connection refused, EOF disconnections.
  - $\implies$ Re-queues batch and applies backoff retry.
- **`UNKNOWN`**:
  - Unclassified vendor errors; subjected to bounded retries before failing loudly.

---

## 4. Invariants Preserved
- **Safe Containment**: Transient blips heal automatically; structural bugs fail fast.

---

## 5. Verification Criteria
- `ClickHouseErrorClassifierTest.testErrorClassification()`
