# Spec 05.03: New Key Live Row Insertion

## 1. Executive Summary & Purpose
Specifies Phase 2 of the sorting key relocation protocol: inserting the live updated row under the new primary/sorting key coordinate.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, ...)`
- **Call site**: the UPDATE branch of `PreparedStatementExecutor.executePreparedStatement`, immediately after `insertTombstonePreparedStatement` (spec 05.02) when `updateRelocatesSortingKey(record)` is true

---

## 3. Operational Specification

### 3.1 Live Row Construction
Immediately following the tombstone's `ps.addBatch()`:
1. Bind positional parameters for all columns from the `after` struct (the new sorting key and updated values), using `record.getAfterModifiedFields()`.
2. Set engine columns:
   - `is_deleted = 0` (live row).
   - `_sign = 1` (CollapsingMergeTree only, spec 05.04).
   - `_version = record.getVersion()` (the tombstone carries `_version - 1`).
3. `ps.addBatch()`.
4. Both statements are sent to ClickHouse by the same `executeBatch()` call at the end of the partition (spec 03.06).

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: the updated data is queryable under the new sorting key with `FINAL`, and the old key resolves to its tombstone.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnChangeRequiresTombstone()` — the tombstone-then-live-row pair is emitted for a relocating UPDATE.
- `PreparedStatementFieldMapperRecordCarriesTest`, `PreparedStatementFieldMapperUnboundColumnTest` — binding of the after image.
- Lean: `Replication.Proofs.update_pk_relocation_soundness`.
- Verification: an end-to-end integration test across multiple primary-key updates is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery

The live row shares its fate with its tombstone (spec 05.02 §6 FM-05.02-1): both are bound from one record into one statement, so a failure to produce either fails both, and a retry or redelivery reproduces the pair with the same `_version`. (§3.1 step 2's parenthetical "the tombstone carries `_version - 1`" is stale: the tombstone carries the record's own version, spec 05.02 §3.2.) Binding failures and the classification of their exceptions belong to spec 04.03 and spec 10.01; what matters here is that none of them can write half a relocation.

- **FM-05.03-1 The after-image cannot be bound**
  - **Trigger**: the after-image carries a column the cached writer does not know (schema drift made out-of-band, a stale cache), a value that cannot be converted to the column type (`clamp.out.of.range=false`), a column the record lacks.
  - **Behaviour**: `PreparedStatementFieldMapper.insertPreparedStatement` throws while binding the after-image, after the tombstone was staged with `addBatch()` but before `executeBatch()`; `executePreparedStatement` rethrows, the chunk is never sent, so neither the tombstone nor the live row reaches ClickHouse (earlier chunks may have been sent, spec 03.06 §6 FM-03.06-2). The worker then retries (UNKNOWN / stale schema, spec 08.03) or stops (`ValueOutOfRangeException` is FATAL, spec 03.01 §6 FM-03.01-2).
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>) *****************` with the binding exception, then the worker's retry or FATAL lines; immediate.
  - **Blast radius**: no half-applied relocation: the old key keeps its live row until the pair is written; the worker's tables wait.
  - **Recovery**: per the exception — refresh or fix the ClickHouse column (spec 08.03), widen the column or restore `clamp.out.of.range=true` (spec 07.03) — then restart.
  - **RTO**: that of the retry (spec 03.03 §6) or of the fix + restart.
  - **Test**: `PreparedStatementFieldMapperRecordCarriesTest`, `PreparedStatementFieldMapperOutOfRangeTest`.

- **FM-05.03-2 Several relocations of one row in one batch, or in one transaction**
  - **Trigger**: `k` moves a→b→c in quick succession; or `INSERT k='a'` and `UPDATE ... SET k='b'` share one transaction (one GTID, one `_version`).
  - **Behaviour**: every change of the row carries the same Debezium key, so it is routed to one worker (spec 03.07 §3.2) and staged in binlog order: `[tomb a, live b, tomb b, live c]`. Across transactions the versions increase; within one transaction the equal versions are resolved by insertion order, the tombstone being written after the live row it retires (spec 05.02 §3.2).
  - **Detection**: n/a (correct behaviour); a failure would show only in the value-level checksum (spec 11.02).
  - **Blast radius**: none when ordering holds.
  - **Recovery**: none needed; `ch-mysql-resync` (spec 11.04) if a checksum disagrees.
  - **RTO**: n/a.
  - **Test**: `Replication.Proofs.update_pk_relocation_soundness`, `Replication.Proofs.tombstone_wins_version_tie`; GAP: an end-to-end test that relocates one row several times in one transaction and across transactions and compares `FINAL` with MySQL.

Summary: 2 failure modes, 0 DEFECT, 1 GAP.
