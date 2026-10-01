# Spec 04.03: Field Membership Rules: Explicit NULLs vs. Omitted Columns

## 1. Executive Summary & Purpose
Specifies the critical distinction between columns that are omitted from a CDC event schema versus columns that are present in the schema but carry explicit `null` values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java`
- **Method**: `getInsertQueryUsingInputFunction(...)` (builds the INSERT column list from the record's unfiltered schema)
- **Bind-time counterpart**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` (Spec 07.07 §3.1)

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
- `NullValueColumnDropTest.testSchemaDefaultIsNotSubstitutedForNull()` — §3.3:
  the Connect-schema default is never bound in place of a stored `null`.
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
  - **Trigger**: the grouping staleness check (FM-04.03-1) returns a fresh column map mid-batch, while the executor binds against the writer's cached map (`ClickHouseBatchRunnable.flushRecordsToClickHouse` passes `writer.getColumnNameToDataTypeMap()`); or any other divergence between the map the INSERT was built from and the map the binder walks.
  - **Behaviour**: `PreparedStatementFieldMapper.insertPreparedStatement` refuses a column that has no placeholder or no source field with `StaleSchemaCacheException` instead of binding the DEFAULT or NULL over a real value; a placeholder the stale map never visits stays unbound and the V2 driver's `addBatch` fails on it (FM-07.07-4). Either way UNKNOWN, retried. The fresh read bumped `CacheInvalidationManager` (`invalidateTable`), so the retry's `ClickHouseBatchRunnable.getDbWriterForTable` rebuilds the writer and the batch goes through on the next attempt. A divergence that does not bump the version would be retried forever; none is known on 2.11.0 (the `StaleSchemaCacheException` Javadoc's "rebuilds the writer" holds only through that version bump).
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)` and `ClickHouseBatchRunnable exception - Task(<id>)` with `Column <c> is present in the ClickHouse table and carried by the record, but has no placeholder in the generated INSERT. ... Failing the batch instead.` (or the NPE), WARN `Retriable ... Category: UNKNOWN`; metric `clickhouse.sink.topics.error.records`.
  - **Blast radius**: one failed attempt of the worker's batch; nothing is written with a dropped value; the rows written by earlier statements of the batch are re-inserted (FM-04.01-3).
  - **Recovery**: self-heals on the next attempt (first backoff step, 500 ms); if it repeats, restart the connector (rebuilds every writer from fresh metadata).
  - **RTO**: ≤ 1 backoff step (500 ms) + re-apply of the batch; unmeasured.
  - **Test**: `PreparedStatementFieldMapperColumnCaseTest.columnCaseMismatchIsResolvedToTheSourceField()` (a case mismatch is not mistaken for staleness), `GroupInsertQueryDdlMemoTest.staleCacheIsRefreshedOnceAndTheFreshMapKeysTheMemo()` (one re-read, fresh map keys the templates); GAP: a test that a batch whose grouping refreshed the map is written on the retry through a rebuilt writer.

- **FM-04.03-3 Explicit NULL for a column ClickHouse cannot hold NULL in**
  - **Trigger**: Case A (§3.1) for a non-Nullable ClickHouse column.
  - **Behaviour**: bound as NULL; refused by ClickHouse with `Code: 53` (terminal) — see spec 07.07 §6 FM-07.07-1; with `input_format_null_as_default=1` set by the operator it is silently replaced by the column DEFAULT — spec 07.07 §6 FM-07.07-2.
  - **Detection**: ERROR `FATAL ClickHouse error (Code: 53)`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s.
  - **Blast radius**: connector stopped; nothing lost.
  - **Recovery**: P-FIX-TYPE to `Nullable(T)` and restart.
  - **RTO**: ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured.
  - **Test**: `NullValueColumnDropTest.testNullColumnIsBoundOnInsert()`, `PoisonValueClassificationTest.nullIntoNonNullableColumnIsFatal()`.

Summary: 3 failure modes, 1 DEFECT, 2 GAP.
