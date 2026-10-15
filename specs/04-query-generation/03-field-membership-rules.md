# Spec 04.03: Field Membership Rules: Explicit NULLs vs. Omitted Columns

## 1. Executive Summary & Purpose
Specifies the critical distinction between columns that are omitted from a CDC event schema versus columns that are present in the schema but carry explicit `null` values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`
- **Method**: `getInsertQueryUsingInputFunction(...)` (builds the INSERT column list from the record's unfiltered schema)
- **Bind-time counterpart**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` (Spec 07.07 §3.1)
- **Binding map (§3.5)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` (`requireEveryPlaceholderBindable`), `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java` (`InsertTemplate`, `bindingColumnMap`)

---

## 3. Operational Specification

### 3.1 The Two Column Membership Cases

```
Incoming Record Field Evaluation
       |
       +---> Case A: Column is PRESENT in record schema, but value is NULL:
       |        -> MUST BE INCLUDED in INSERT column list.
       |        -> JDBC binds: `ps.setNull(index, type)`.
       |        -> ClickHouse stores native NULL.
       |        -> Rationale: Prevents ClickHouse from substituting column DEFAULTs!
       |
       +---> Case B: Column is ABSENT from record schema (e.g. pre-ALTER record):
                -> MUST BE EXCLUDED from INSERT column list.
                -> ClickHouse evaluates native DEFAULT expression.
                -> Rationale: Pre-ALTER records should receive the table's default.
```

Case B covers two triggers that share one mechanism. A pre-ALTER record's
column is *transiently* absent — a later record, once the ALTER lands, will
carry it. A column that exists on the ClickHouse replica alone, with no column
of that name on the MySQL source, is *permanently* absent — no record ever
carries it, by construction. Both are decided by the same schema-membership
check and excluded from the INSERT the same way; the replica-only case is the
parity-scope carve-out of Invariant I6 (`specs/CONSTITUTION.md`) and is pinned
separately as FM-04.03-4.

### 3.2 Production Hazard Prevented
In earlier versions, `null`-valued fields were omitted from the query column list. Consequently, ClickHouse applied column defaults (e.g. converting `NULL` to `0`, `""`, or `1970-01-01`), causing silent data divergence. The 2.11.0 rule strictly preserves explicit `NULL`s.

### 3.2.1 Engine columns are members by name, not by record
`_version`, the delete column and the sign column are never in the record's schema, so Case B would wrongly exclude them. They are retained by name — the defaults and the names resolved from the table's engine clause (spec 04.02 §3.1) — and a table engine column with no placeholder in the INSERT is refused at bind time rather than stored as its type default.

### 3.3 The Connect-schema default is the same hazard at bind time
Membership (this spec) is decided from the record's unfiltered schema. The
*value* bound for a member column is read with `Struct.getWithoutDefault`, never
`Struct.get`: `Struct.get` substitutes the Connect-schema `defaultValue` (which
Debezium fills from the MySQL column `DEFAULT`) for a stored `null`, so the
column would be present in the INSERT but bound to the default. See Spec 07.07
§3.1. Both halves are required for Case A to actually store `NULL`.

### 3.4 Column names are matched to source fields case-insensitively, everywhere
Membership (`QueryFormatter.createColumns`) compares the ClickHouse column name
to the record's field names case-insensitively, so a table created by hand as
`` `ID` Int32, `Amount` Float64 `` for source columns `id, amount` is a member
match. The bind-time value read, however, used the ClickHouse column name
verbatim (`struct.getWithoutDefault(colName)`), which Kafka Connect resolves
case-sensitively: it threw `DataException`, `PreparedStatementFieldMapper`
classified that as a stale cache (`StaleSchemaCacheException`) and the batch
was retried forever against a cache that was never stale — a permanent stall
with nothing written.

Rule (`PreparedStatementFieldMapper.resolveSourceField(struct, colName)`): the
source field a column is bound from is resolved against the **record's
schema** — an exact name match first, then a case-insensitive match — and the
value is read with `getWithoutDefault(field.name())`. The record's schema, not
the modified-field list, is consulted because that list omits NULL-valued
fields; a NULL source value in a case-mismatched column must still be bound as
NULL (Case A). Only a column that matches no field under either comparison
reaches the stale-cache branch, which is exactly the condition it was written
for.

### 3.5 A segment is bound with the column map its template was built from
Membership is decided against a column map, and the grouping may replace that
map mid-batch: the stale-cache re-read (`refreshIfRecordHasUnknownColumn`,
FM-04.03-1), a `MATERIALIZED` column converted to `DEFAULT` (spec 08.04 §3.2)
or schema evolution. The caller still holds the writer's cached map
(`ClickHouseBatchRunnable.flushRecordsToClickHouse` and
`ClickHouseBatchWriter.flushRecordsToClickHouse` pass
`writer.getColumnNameToDataTypeMap()`). Binding a template built from the fresh
map with the cached one walked a column set that lacks the new column, so its
placeholder was never set and the V2 driver's `addBatch()` threw a
`NullPointerException`: the first batch after every re-read or enforcement
failed, and the retry through a rebuilt writer succeeded.

Rule: **the map used to bind a segment's records equals the map its INSERT
template was built from.** Every key the grouper produces
(`GroupInsertQueryWithBatchRecords.InsertTemplate`) carries that map and
`PreparedStatementExecutor` binds the key's records with it
(`GroupInsertQueryWithBatchRecords.bindingColumnMap`); the caller's map binds
only a key that carries none. Equal keys (same SQL text, same parameter-index
map) name the same column list, so the map of the key that opened the bucket
binds every record in it.

Backstop: before any row of a template is bound,
`PreparedStatementExecutor.requireEveryPlaceholderBindable` checks that every
placeholder's column is in the binding map and otherwise throws
`StaleSchemaCacheException` naming the column. A template's placeholders are
always a subset of the map it was built from, so this fires only when the two
maps disagree; an unbound parameter never reaches `addBatch()`.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)** & **Invariant I7 (Type Equivalence)**: Explicit source `NULL` values are preserved on the replica without default substitution.

---

## 5. Verification Criteria
- `NullValueColumnDropTest.testNullColumnIsBoundOnInsert()` — Case A: a column
  present in the schema with a `null` value is a member of the INSERT and is
  bound as SQL NULL.
- `NullValueColumnDropTest.testUpdateClearingColumnBindsIt()` — an UPDATE that
  sets a column to `null` binds it (Case A on the after-image).
- `PreparedStatementFieldMapperColumnCaseTest.columnCaseMismatchIsResolvedToTheSourceField()`
  — §3.4: ClickHouse columns `ID`, `Amount`, `NOTE` for source fields `id`,
  `amount`, `note` (the last NULL) are bound (`7`, `12.5`, `setNull`); the
  pre-fix code throws `StaleSchemaCacheException` for `ID`.
- `NullValueColumnDropTest.testPreAlterRecordStillOmitsUnknownColumn()` — Case B:
  a column absent from a pre-ALTER record's schema is not a member.
- `ClickHouseOnlyColumnTest.testClickHouseOnlyColumnIsOmittedFromGeneratedInsert()`,
  `ClickHouseOnlyColumnTest.testClickHouseOnlyColumnRecordWritesWithoutError()` —
  FM-04.03-4: Case B's permanent variant. A column with no source counterpart
  is never a member of any record's schema, is excluded from every generated
  INSERT, and the bind-time mapper writes the rest of the row without error.
- `NullValueColumnDropTest.testSchemaDefaultIsNotSubstitutedForNull()` — §3.3:
  the Connect-schema default is never bound in place of a stored `null`.
- `StaleCacheBindingMapTest.materializedColumnConvertedMidBatchIsBoundInTheSameBatch()`,
  `StaleCacheBindingMapTest.columnFoundByStaleCacheReReadIsBoundInTheSameBatch()`,
  `StaleCacheBindingMapTest.templatesBuiltBeforeAndAfterAReReadAreEachBoundWithTheirOwnMap()`
  — §3.5: grouped and executed with the writer's cached map, as the runnable
  does; every placeholder of every staged row is bound and the source value of
  the re-read column is written in the same batch (pre-fix code leaves that
  placeholder unbound).
- `StaleCacheBindingMapTest.placeholderMissingFromTheBindingMapFailsNamingTheColumn()`
  — §3.5 backstop: a placeholder whose column the binding map lacks throws
  `StaleSchemaCacheException` naming it and nothing is staged (pre-fix code
  stages the row with the parameter unset).
- `NullColumnValueRoundTripIT.testNullOnInsertStaysNull()`,
  `NullColumnValueRoundTripIT.testUpdateToNullClearsTheStoredValue()`,
  `NullColumnValueRoundTripIT.testPreAlterRowStillReceivesTheColumnDefault()` —
  end to end against MySQL and ClickHouse: explicit `NULL` lands as NULL,
  a pre-ALTER row receives the ClickHouse column DEFAULT.

---

## 6. Failure Modes & Recovery

Recovery posture: membership is decided from the record's schema and a disagreement between the record, the cached column map and the ClickHouse table is refused rather than written with a value dropped or NULL-filled; a column the replica lacks is retried until it exists (then self-heals without a restart), and a map refreshed mid-batch heals on the next attempt. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.03-1 Source column missing on ClickHouse**
  - **Trigger**: the record carries a column the ClickHouse table does not have — a MySQL `ADD COLUMN` whose translation failed or was made with `sql_log_bin=0`, an out-of-band `DROP COLUMN` on ClickHouse, `schema.evolution=false`.
  - **Behaviour**: the staleness check `GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn` re-reads the metadata once; if the column is still absent (and not `ALIAS`) it adds it when `schema.evolution=true`, otherwise throws `MissingTargetColumnException` (spec 08.04). No ClickHouse code: UNKNOWN, retried every ≤ 30 s — each retry re-runs the check, so the batch goes through as soon as the column exists.
  - **Detection**: WARN `Cached schema for <db>.<t> does not contain column '<c>' carried by the incoming record; the cache is stale relative to the source. Re-reading table metadata before building the INSERT.`, then ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `Column '<c>' is carried by the source record but does not exist in ClickHouse table <db>.<t> (default_kind not found). Writing the row without it would silently drop the source value with row counts intact. Set schema.evolution=true to let the connector add it, or add the column to the ClickHouse table. Failing the batch instead.` and WARN `Retriable ... Category: UNKNOWN`, every ≤ 30 s; no metric (grouping precedes the executor); no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits until the column exists. Nothing lost.
  - **Recovery**: `ALTER TABLE <db>.<t> ADD COLUMN <c> <type>` on ClickHouse (translate the source definition with `sink-connector-client ddl_translate`), or set `schema.evolution=true` and restart; no restart is needed after a manual `ADD COLUMN` — the next retry re-reads and writes the batch.
  - **RTO**: `ADD COLUMN` + ≤ 30 s backoff + re-apply of the in-flight batch; unmeasured.
  - **Test**: `GroupInsertQueryDdlMemoTest.droppedColumnStillFailsLoudlyAfterACleanSchemaWasMemoised()` (the refusal); GAP: a test that the retry after a manual `ADD COLUMN` writes the retained batch without a restart.
  - **DEFECT**: the condition waits for an operator indefinitely with no metric and no escalation (log lines only), while every later DDL of the connector waits behind it.

- **FM-04.03-2 Index map and bound column map disagree within one batch**
  - **Trigger**: the map an INSERT template was built from and the map the binder walks disagree. The grouping staleness check (FM-04.03-1) returning a fresh column map mid-batch no longer causes this: since §3.5 each template is bound with the map it was built from, not with the writer's cached map that `flushRecordsToClickHouse` passes. Before that rule the first batch after every re-read or `MATERIALIZED` enforcement failed with a `NullPointerException` from the driver's `addBatch()` and was written on the retry. Remaining trigger: a segment key built outside the grouper (it carries no map) bound with a map that lacks one of its placeholders' columns; none is known on 2.11.0.
  - **Behaviour**: `PreparedStatementExecutor.requireEveryPlaceholderBindable` refuses a placeholder whose column the binding map lacks with `StaleSchemaCacheException` before any row is staged, so no parameter reaches `addBatch()` unbound; `PreparedStatementFieldMapper.insertPreparedStatement` refuses a column that has no placeholder or no source field with `StaleSchemaCacheException` instead of binding the DEFAULT or NULL over a real value. Either way UNKNOWN, retried. When a fresh read bumped `CacheInvalidationManager` (`invalidateTable`), the retry's `ClickHouseBatchRunnable.getDbWriterForTable` rebuilds the writer; a divergence that does not bump the version would be retried forever; none is known on 2.11.0 (the `StaleSchemaCacheException` Javadoc's "rebuilds the writer" holds only through that version bump).
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `Column <c> has a placeholder in the generated INSERT but is not in the column map used to bind it, so its parameter would be left unbound. ... Failing the batch instead.`, or ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)` with `Column <c> is present in the ClickHouse table and carried by the record, but has no placeholder in the generated INSERT. ... Failing the batch instead.`; WARN `Retriable ... Category: UNKNOWN`; metric `clickhouse.sink.topics.error.records` for the second message only (the first is raised before the batch is executed).
  - **Blast radius**: one failed attempt of the worker's batch; nothing is written with a dropped value; the rows written by earlier statements of the batch are re-inserted (FM-04.01-3).
  - **Recovery**: self-heals on the next attempt (first backoff step, 500 ms); if it repeats, restart the connector (rebuilds every writer from fresh metadata).
  - **RTO**: ≤ 1 backoff step (500 ms) + re-apply of the batch; unmeasured.
  - **Test**: `PreparedStatementFieldMapperColumnCaseTest.columnCaseMismatchIsResolvedToTheSourceField()` (a case mismatch is not mistaken for staleness), `GroupInsertQueryDdlMemoTest.staleCacheIsRefreshedOnceAndTheFreshMapKeysTheMemo()` (one re-read, fresh map keys the templates), `StaleCacheBindingMapTest.materializedColumnConvertedMidBatchIsBoundInTheSameBatch()` and `StaleCacheBindingMapTest.columnFoundByStaleCacheReReadIsBoundInTheSameBatch()` (a batch whose grouping refreshed the map is written on the first attempt), `StaleCacheBindingMapTest.placeholderMissingFromTheBindingMapFailsNamingTheColumn()` (the backstop).

- **FM-04.03-3 Explicit NULL for a column ClickHouse cannot hold NULL in**
  - **Trigger**: Case A (§3.1) for a non-Nullable ClickHouse column.
  - **Behaviour**: bound as NULL; refused by ClickHouse with `Code: 53` (terminal) — see spec 07.07 §6 FM-07.07-1; with `input_format_null_as_default=1` set by the operator it is silently replaced by the column DEFAULT — spec 07.07 §6 FM-07.07-2.
  - **Detection**: ERROR `Schema mismatch: the source sent NULL for column <c> ...` once per column, naming the column and the type it must become (spec 07.07 §3.2.3); ERROR `FATAL ClickHouse error (Code: 53)`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s.
  - **Blast radius**: connector stopped; nothing lost.
  - **Recovery**: make the ClickHouse schema match the source: P-FIX-TYPE to `Nullable(T)`, or, for a type ClickHouse cannot declare `Nullable` (`Array`, `Map`, `Tuple`, ...), to the Nullable type the source column maps to, then P-RESYNC the table — spec 07.07 §6 FM-07.07-1; restart.
  - **RTO**: ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured.
  - **Test**: `NullValueColumnDropTest.testNullColumnIsBoundOnInsert()`, `PoisonValueClassificationTest.nullIntoNonNullableColumnIsFatal()`.

- **FM-04.03-4 A column only ClickHouse has (parity scope, Constitution I6)**
  - **Trigger**: a column added on the ClickHouse replica alone — by hand, by another process, or by a prior schema mistake — with no column of that name on the MySQL source and not one of the connector's own bookkeeping columns (`isConnectorManagedColumn`: `_version`, `is_deleted`/the configured delete column, the sign column, the replication-history columns). `DBMetadata.getColumnsDataTypesForTable` still reports it (it excludes only `ALIAS`/`MATERIALIZED`), so it reaches the same writable-column map as every other column.
  - **Behaviour**: this is Case B (§3.1) forever, not transiently: the column can never appear in any record's schema, so `QueryFormatter.createColumns()` never gives it a placeholder, in every batch, not just the first one after an `ADD COLUMN`. At bind time `PreparedStatementFieldMapper.insertPreparedStatement` finds no entry for it in `columnNameToIndexMap`, finds it is not `isUnboundByDesign`, finds `recordCarries(fields, colName)` false (the field cannot exist in any schema derived from the source table), and takes the by-design branch: DEBUG log, `continue`. No value is bound, no exception is thrown, no `ALTER` is issued, and ClickHouse keeps whatever value the column already holds (its DEFAULT, or a value a human or another process wrote) — never NULL, never shadowed, never overwritten. Nothing here recognises "this column will never appear" as a distinct case; it falls out of the ordinary Case B / `recordCarries` check having nothing to find, every time.
  - **Detection**: none, by design (Constitution I6, Parity scope: a column with no source counterpart has no source value to conform to or diverge from, so there is nothing to report at the connector layer). `db_compare`'s value-level checksum reports the same finding, once per table, at INFO (spec 11.02 §3.3, §3.9, FM-11.02-10).
  - **Blast radius**: none. The column is never written, altered, or warned about by the connector; its value is whatever it was before the connector saw the table, for as long as the column exists.
  - **Recovery**: none required — this is the tolerated case. A column the owner wants removed is dropped directly on the replica (no connector action); a column that should instead track a source column is renamed or the source `ADD COLUMN` is applied, which moves it out of this failure mode and into Case A or FM-04.03-1.
  - **RTO**: not applicable.
  - **Test**: `ClickHouseOnlyColumnTest.testClickHouseOnlyColumnIsOmittedFromGeneratedInsert()` (query-formatting half, through `GroupInsertQueryWithBatchRecords.updateQueryToRecordsMap`), `ClickHouseOnlyColumnTest.testClickHouseOnlyColumnRecordWritesWithoutError()` (bind-time half, through `PreparedStatementFieldMapper.insertPreparedStatement`, asserting no index outside the real columns is ever touched and the call completes without error).

Summary: 4 failure modes, 1 DEFECT, 1 GAP.
