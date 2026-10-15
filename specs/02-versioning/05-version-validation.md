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

---

## 6. Failure Modes & Recovery
The guard itself is correct and loud: no row with a version `<= 0` is ever bound. Its recovery posture is the weak part: the refusal is deterministic but is handled as a transient error.

- **FM-02.05-1 Underivable version refused, then retried forever**
  - **Trigger**: a record reaches the bind with `version <= 0` — no GTID, sequence number, LSN or positive `ts_ms` (possible on the Kafka Connect path; on the lightweight streaming path every row receives a sequence version, so only a defect produces it), or a history-mode encoding refusal (spec 02.01 §7 FM-02.01-3).
  - **Behaviour**: `PreparedStatementFieldMapper.rejectUnderivableVersion` throws `IllegalStateException`; nothing is bound. The exception reaches `ClickHouseBatchRunnable.run`, where `ClickHouseErrorClassifier.classify` finds no `Code: N` and answers `UNKNOWN`, so the worker retries the same batch with backoff (spec 10.02) indefinitely; the redelivered record is the same record.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(...)` carrying `Cannot bind _version -1 for record from topic '...' at kafka offset ...: ... Refusing to write the row.`, then WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) -- the same batch will be retried in ... ms (consecutive failures: n). Every table hashed to this worker waits behind it until it succeeds.`; immediate.
  - **Blast radius**: no bad row is written (the UInt64-max sentinel of issue #1213 cannot reach ClickHouse). Every table hashed to that worker stops; the FIFO head blocks every offset (spec 09.01 FM-09.01-2) until the handoff wait limit stops the engine (`sink.connector.handoff.wait.timeout.ms`, default 600 000 ms).
  - **Recovery**: none bounded: fix the producer of the record (configuration or code), then restart. Skipping it means stopping the process, moving the offset past its transaction and resyncing the tables it touched (spec 11.04).
  - **RTO**: unbounded (code or configuration fix); unmeasured.
  - **Test**: `RejectUnderivableVersionTest.testZeroVersionIsRejected()`, `RejectUnderivableVersionTest.testZeroVersionIsRejectedForTombstone()`, `VersionFallbackWithoutGtidTest.unresolvableVersionThrowsInsteadOfBinding()` (the refusal); `UnderivableVersionClassificationTest.refusalIsCurrentlyClassifiedUnknown()` (pins the retry classification); `UnderivableVersionClassificationTest.underivableVersionIsClassifiedFatal()` (disabled; fails on 2.11.0).
  - **DEFECT**: a deterministic refusal is classified retriable, so the worker loops instead of stopping the engine at once (Invariant I9).

Summary: 1 failure modes, 1 DEFECT, 0 GAP.
