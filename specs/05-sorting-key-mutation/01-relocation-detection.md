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
