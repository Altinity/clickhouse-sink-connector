# Spec 02.05: Version Validation & Underivable Version Rejection

## 1. Executive Summary & Purpose
Specifies the fail-fast validation assertions that reject unversioned or corrupt CDC records before they reach the ClickHouse statement execution layer.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java`
- **Method**: `rejectUnderivableVersion()`

---

## 3. Operational Specification

### 3.1 Strict Version Bound Assertion
Every `ClickHouseStruct` bound for a `ReplacingMergeTree` table must carry a positive, non-zero 64-bit version.
- If `record.getVersion() <= 0`:
  `rejectUnderivableVersion()` immediately throws a `FatalReplicationException`:
  ```
  FatalReplicationException: Underivable version detected on record for table {table}: version={version}
  ```
- **Prohibition**: The connector must **never** substitute default fallbacks (such as `0` or `1`) for missing versions. Missing version information indicates CDC coordinate corruption and must halt replication.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: Every row inserted into ClickHouse carries a mathematically valid, monotonic version.
- **Invariant I9 (Loud Failure)**: Corrupt metadata fails immediately rather than silently writing `_version = 0`.

---

## 5. Verification Criteria
- `ClickHouseStructTest.testRejectUnderivableVersion()`
