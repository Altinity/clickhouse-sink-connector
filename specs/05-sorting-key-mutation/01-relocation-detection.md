# Spec 05.01: Sorting Key Relocation Detection Algorithm

## 1. Executive Summary & Purpose
Specifies the detection algorithm that determines whether an incoming MySQL UPDATE event alters any column belonging to the ClickHouse table's `ORDER BY` sorting key.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java`
- **Method**: `boolean updateRelocatesSortingKey(ClickHouseStruct record)` (package-private; line 400 on 2.11.0). The sorting key is not a parameter: it is read on each call from the `Supplier<List<String>> sortingKeyColumnsSupplier` passed to the constructor overload `PreparedStatementExecutor(String, boolean, String, String, String, ZoneId, Supplier<List<String>>)`, so it always reflects current metadata (`DbWriter.sortingKeyColumns`, spec 08.01).
- **Call site**: the UPDATE branch of `executePreparedStatement`, guarded by the engine being a ReplacingMergeTree variant.

---

## 3. Operational Specification

### 3.1 Detection Algorithm
1. `List<String> sortingKeyColumns = sortingKeyColumnsSupplier.get()`. If `null` or empty, return `false` — an unknown or unreadable sorting key degrades to the previous behaviour rather than emitting a speculative tombstone.
2. Read `before = record.getBeforeStruct()` and `after = record.getAfterStruct()`. If either is `null`, return `false`.
3. For each column name $C$ in `sortingKeyColumns`:
   - If `before.schema().field(C) == null` or `after.schema().field(C) == null`, skip $C$: the sorting key may be an expression over columns the record does not carry (for example `toDate(deleted_time)`), and absence is not guessed either way.
   - If `!Objects.equals(before.getWithoutDefault(C), after.getWithoutDefault(C))`, return `true` (relocation detected; a `NULL`-to-value or value-to-`NULL` transition counts as a change).
4. If every carried sorting-key column is equal, return `false` (in-place update).

The method does not inspect the CDC operation; the caller invokes it only for UPDATE records.

### 3.2 The comparison reads STORED values, never the Connect-schema default
Both images are read with `Struct.getWithoutDefault`, never `Struct.get`.
`Struct.get` substitutes `schema.defaultValue()` for a stored `null`, and
Debezium fills that default from the MySQL column `DEFAULT` (Spec 07.07 §3.1).
For a sorting-key column declared `DEFAULT 'x'`, an UPDATE that changes the
stored value from `NULL` to `'x'` therefore compared `'x'` against `'x'` and
was judged an in-place update: no tombstone was written, the row was inserted
under its new sorting key, and the pre-update row at the `NULL` key survived
forever — MySQL one row, ClickHouse two. The bind path already reads stored
values (Spec 04.03 §3.3); the relocation decision must observe the same values
the rows are written with, or the two halves of Invariant I4 disagree about
which key a row lives under.

---

## 4. Invariants Preserved
- **Invariant I4 (Sorting Key Mutation Integrity)**: every UPDATE whose carried sorting-key columns change is routed to the two-phase tombstone protocol; every other UPDATE stays an in-place replace.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnChangeRequiresTombstone()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testNonSortingKeyColumnChangeNeedsNoTombstone()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testKeylessTableAllColumnsSortingKeyDetectsAnyChange()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testNoOpUpdateNeedsNoTombstone()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testNullTransitionInSortingKeyIsAChange()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testUnknownSortingKeyEmitsNoTombstone()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnAbsentFromRecordIsSkipped()`, `PreparedStatementExecutorSortingKeyTombstoneTest.testMissingAfterImageEmitsNoTombstone()`.
- `PreparedStatementExecutorSortingKeyTombstoneTest.testNullToSchemaDefaultInSortingKeyIsAChange()` — §3.2: a `NULL` → `DEFAULT`-valued transition of a sorting-key column (Connect-schema `defaultValue` equal to the new value) is detected as a relocation in both directions, and `NULL` → `NULL` under a default is not.

---

## 6. Failure Modes & Recovery

Relocation detection fails safe in one direction only: when it cannot decide, it emits no tombstone. That keeps a live row from being deleted, but every missed relocation leaves a ghost row at the old sorting key that ReplacingMergeTree never removes (rows of different sorting keys never merge) — a silent, permanent divergence repaired only by a table resync. The failure modes are the ways the detector's view of the sorting key can be wrong.

- **FM-05.01-1 The sorting-key read fails when the writer is built, and "unknown" is cached as "empty"**
  - **Trigger**: the `system.columns` query of `DBMetadata.getSortingKeyColumns` fails once — a timeout under load, a connection reset, ClickHouse restarting — at the moment a `DbWriter` is built (first batch of a table, after a DDL, after a restart).
  - **Behaviour**: `getSortingKeyColumns` logs and returns an empty list; `DbWriter` stores it for the writer's life (re-read only by `updateColumnNameToDataTypeMap` or a rebuild after a replicated DDL); `PreparedStatementExecutor.updateRelocatesSortingKey` returns false for an empty key; every UPDATE of that table that moves a row is written as an in-place update.
  - **Detection**: one ERROR `Error retrieving sorting key columns for <db>.<t>` (or `Error with DB connection, cannot read sorting key for <db>.<t>`) when the writer is built; silence afterwards. The ghost rows are found only by the value-level checksum (spec 11.02).
  - **Blast radius**: that table, until the writer is rebuilt: one extra live row per relocating UPDATE (MySQL one row, ClickHouse two) — permanent.
  - **Recovery**: restart the connector (rebuilds the writer), then `ch-mysql-resync` (spec 11.04) of the table.
  - **RTO**: resync time, proportional to the table — unmeasured.
  - **Test**: `SortingKeyReadFailureTest.aFailedSortingKeyReadIsNotAnEmptySortingKey()` (disabled, fails on 2.11.0); `PreparedStatementExecutorSortingKeyTombstoneTest.testUnknownSortingKeyEmitsNoTombstone()` pins the consequence of an empty key.
  - **DEFECT**: a failed read must fail the writer build (and be retried), not be cached as a table without a sorting key.

- **FM-05.01-2 The sorting key is changed out-of-band**
  - **Trigger**: `ALTER TABLE ... MODIFY ORDER BY` (adding a newly added column to the key) or a table recreated with another `ORDER BY`, run directly on ClickHouse.
  - **Behaviour**: the writer's cached key is refreshed only when the writer is rebuilt; `CacheInvalidationManager` versions move only for DDL replicated through the connector. A relocation on the new key column is not detected until a restart.
  - **Detection**: none.
  - **Blast radius**: ghost rows for every UPDATE of the new key column between the change and the next restart.
  - **Recovery**: restart the connector after any out-of-band sorting-key change; `ch-mysql-resync` (spec 11.04) of the table for the interval.
  - **RTO**: resync time — unmeasured.
  - **Test**: GAP: a writer whose sorting key changes on the server between two batches, asserting the second batch sees the new key or fails.
  - **DEFECT**: an out-of-band sorting-key change is invisible to a running connector and silently disables tombstones for the new column.

- **FM-05.01-3 An expression in the sorting key makes an in-place update look like a relocation**
  - **Trigger**: `ORDER BY (toDate(updated_at), id)` or any key expression over a column; an UPDATE changes `updated_at` within the same day.
  - **Behaviour**: `system.columns.is_in_sorting_key` flags the underlying column (ClickHouse documentation; not verified against a server here), so `updateRelocatesSortingKey` compares the raw value and reports a relocation although the key expression is unchanged. The tombstone and the after-image then share the sorting key and `_version`, tombstone staged first — the tie the executor's own comment says "can permanently drop a live row" when ClickHouse resolves it by insertion order inside one block (not verified here).
  - **Detection**: none.
  - **Blast radius**: possibly a live row deleted in ClickHouse (MySQL one row, ClickHouse none) on tables with expression sorting keys.
  - **Recovery**: `ch-mysql-resync` (spec 11.04) of the table; avoid expression sorting keys on replicated tables.
  - **RTO**: resync time — unmeasured.
  - **Test**: GAP: an IT on a `toDate()` sorting key updating a row within one day, asserting it stays live under `FINAL`.
  - **DEFECT**: the detector compares columns, not the key expression, and the resulting same-key tombstone relies on an unverified tie-break.

- **FM-05.01-4 A sorting-key column the record does not carry**
  - **Trigger**: a key column that is `MATERIALIZED` in ClickHouse (computed from source columns) or otherwise absent from the Debezium record.
  - **Behaviour**: `updateRelocatesSortingKey` skips a key column missing from either image (§3.1 step 3). An UPDATE that changes the source columns a `MATERIALIZED` key column is computed from changes the key but is judged in-place.
  - **Detection**: none.
  - **Blast radius**: ghost rows on such tables, permanent.
  - **Recovery**: `ch-mysql-resync` (spec 11.04); do not put `MATERIALIZED` columns in the sorting key of a replicated table (`AGENTS.md`: a `MATERIALIZED` column named like a source column is already a divergence).
  - **RTO**: resync time — unmeasured.
  - **Test**: `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnAbsentFromRecordIsSkipped()` pins the skip; GAP: a `MATERIALIZED` key column case asserting a tombstone or a refusal.
  - **DEFECT**: a key column the detector cannot see is skipped silently instead of making the relocation decision "unknown" and failing loudly.

Summary: 4 failure modes, 4 DEFECT, 3 GAP.
