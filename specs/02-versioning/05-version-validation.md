# Spec 02.05: Version Validation & Underivable Version Rejection

## 1. Executive Summary & Purpose
Specifies the fail-fast validation assertions that reject unversioned or corrupt CDC records before they reach the ClickHouse statement execution layer.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `private static void rejectUnderivableVersion(ClickHouseStruct record)`
- **Call sites**: `handleVersionColumn()` (live rows) and
  `insertTombstonePreparedStatement()` (relocation tombstones), both after
  `ClickHouseStruct.calculateVersion()` has had its chance to derive a version.
- **Exception**: `java.lang.IllegalStateException` (unchecked, so it propagates
  through the batch lambda in `PreparedStatementExecutor` and fails the batch).

---

## 3. Operational Specification

### 3.1 Strict Version Bound Assertion
Every `ClickHouseStruct` bound for a `ReplacingMergeTree` table must carry a
positive, non-zero 64-bit version.
- If `record.getVersion() <= 0`, `rejectUnderivableVersion()` throws
  `IllegalStateException` naming the topic and Kafka offset, and the batch fails.
- **Prohibition**: The connector must **never** substitute default fallbacks (such as `0` or `1`) for missing versions. Missing version information indicates CDC coordinate corruption and must halt replication.

### 3.2 Why `-1` and `0` are both rejected
- `-1` is `ClickHouseStruct.UNINITIALIZED_VALUE`: nothing could be derived
  (no GTID, sequence number, LSN, **and** no source timestamp). Bound with
  `setLong` into `UInt64` it stores `18446744073709551615`, which wins every
  future merge for the key permanently (issue #1213).
- `0` is not producible by any branch of `calculateVersion()`: GTID transaction
  numbers start at 1, the SnowFlakeId forms embed a positive `ts_ms`, PostgreSQL
  LSN `0` is invalid, and the lightweight sequence counter starts at
  `500 000 000` / `1 000 000 000`. A `0` therefore means a corrupt or
  hand-built record. It is not a data-destroying value the way `-1` is (it
  loses every merge rather than winning them), but writing it would put a row
  in ClickHouse whose ordering relative to its own history is undefined, so it
  is refused for the same reason: fail loudly rather than write an unordered
  row. The check is `<= 0`, matching this specification's original wording.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: Every row inserted into ClickHouse carries a mathematically valid, monotonic version.
- **Invariant I9 (Loud Failure)**: Corrupt metadata fails immediately rather than silently writing `_version = 0`.

---

## 5. Verification Criteria
- `VersionFallbackWithoutGtidTest` — the `-1` sentinel is never bound; the
  ts_ms + Kafka-offset fallback is used when no ordering key exists.
- `RejectUnderivableVersionTest.testZeroVersionIsRejected()` — a record whose
  version is exactly `0` fails the batch with `IllegalStateException` on both
  the live-row and the tombstone bind paths (pre-fix code binds `0`).
- `RejectUnderivableVersionTest.testPositiveVersionIsBound()` — `1` is accepted.
