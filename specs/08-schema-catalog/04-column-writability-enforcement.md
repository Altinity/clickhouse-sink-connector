# Spec 08.04: Column Writability Enforcement & MATERIALIZED to DEFAULT Alteration

## 1. Executive Summary & Purpose
Specifies what the writer does when the incoming record carries a source column
that the ClickHouse table cannot currently store: a `MATERIALIZED` column is
converted to `DEFAULT`; a column that is simply **absent** is added (schema
evolution on) or fails the batch loudly (schema evolution off). Only an `ALIAS`
column is ever ignored.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Methods**: `refreshIfRecordHasUnknownColumn(...)`, `boolean enforceSourceColumnIsWritable(...)`
- **Evolution path reused**: `db/operations/ClickHouseAlterTable.alterTable(...)` (`ALTER TABLE ... ADD COLUMN`)
- **Failure type**: `db/batch/MissingTargetColumnException` (unchecked)
- **Config knob**: `schema.evolution` (`ENABLE_SCHEMA_EVOLUTION`, default `false`)

---

## 3. Operational Specification

### 3.1 Classification of a column the record carries but the writable map lacks
After a fresh metadata read still does not produce column $C$, its
`system.columns.default_kind` decides:

| `default_kind` | Meaning | Action |
|---|---|---|
| `ALIAS` | not stored; nothing can diverge | ignore; `markColumnProvenAbsent` so the probe is not repeated per record |
| `MATERIALIZED` | stored, ClickHouse-computed; shadows the source value | §3.2 convert to `DEFAULT`, re-read, bind the source value. If the conversion cannot be performed (declared type or expression unreadable, `ALTER` rejected or without effect) or the re-read still lacks the column, **fail the batch** with `MissingTargetColumnException` naming the failed remediation (§3.3); never continue without the source value |
| `DEFAULT` or `""` (a stored column the connector can write) | the writable map read raced a concurrent change (typically another worker's §3.2 conversion) | §3.4: re-read the writable map once; if it now has $C$, bump the version and bind it; otherwise §3.3 |
| `null` (no `system.columns` row: the column does not exist in ClickHouse) | the replica is incomplete | §3.3 |
| `null` because the kind could not be read at all | unknown | §3.3 as well: with evolution on the `ADD COLUMN` is attempted; otherwise the batch fails and the retry re-probes. Never proven-absent. |

A non-`ALIAS` miss is **never** recorded as proven-absent — not a missing
column, and not a `MATERIALIZED` column whose conversion failed. Doing so made
the writer drop the column's value on every subsequent record after a single
WARN, i.e. it treated the column like an `ALIAS` forever while the offset
advanced past rows written without the source value. `markColumnProvenAbsent`
is reserved strictly for `ALIAS`.

### 3.2 MATERIALIZED: Automated Schema Remediation
`enforceSourceColumnIsWritable()` constructs and executes:
```sql
ALTER TABLE `db`.`table` MODIFY COLUMN `col` type DEFAULT (default_expression)
```
- Modifies column kind in ClickHouse metadata without rewriting existing data parts.
- Invalidates local schema cache: `CacheInvalidationManager.getInstance().invalidateTable(tableKey)`.
- Subsequent `INSERT` statements now successfully bind MySQL values directly into the column, starting with the batch that triggered the conversion: that batch's INSERT is built from the post-DDL re-read and bound with the same map, not with the writer's cached map (spec 04.03 §3.5).

### 3.3 Absent column: add it, or fail the batch
The prime directive: "If a column exists in MySQL but not usefully in
ClickHouse, the ClickHouse side is wrong or incomplete. Fix the ClickHouse
side; never a log line only."

1. **`schema.evolution=true`**: run `ClickHouseAlterTable.alterTable()` for the
   record's schema (it issues `ALTER TABLE ... ADD COLUMN` for every field the
   writable map lacks, typed by `getColumnNameToCHDataTypeMapping`), then
   re-read the column map. If the re-read now contains $C$, bump the table
   version (`invalidateTable`) and build the INSERT from the fresh map so
   **this** batch binds the value. If it still does not, fall through to 2.
2. **Otherwise**: throw `MissingTargetColumnException` naming the database,
   table, column and the `schema.evolution` knob. The batch fails and is
   retried/surfaced; nothing is written with the column silently omitted.
   Rows are never dropped and never written incomplete.
3. **`MATERIALIZED` whose §3.2 conversion failed** (declared type or
   expression unreadable, `ALTER` rejected or without effect, or the post-DDL
   re-read still lacks the column): throw `MissingTargetColumnException`
   naming the database, table, column and the remediation that failed
   (`ALTER TABLE ... MODIFY COLUMN ... DEFAULT <expr>`), so the operator
   applies it by hand or grants the privilege. Reporting is the fallback when
   enforcement fails, but reporting **and continuing** is not: the batch must
   not be written with the source value replaced by ClickHouse's computed one.

### 3.4 Concurrent conversion of the same column
Several workers routinely see the same `MATERIALIZED` column at once (one
table, several threads, one batch each). The column map read, the
`default_kind` read and enforcement's own `default_kind` re-read are separate
catalog queries, and another worker's `ALTER` can land between any two of
them. Two interleavings used to fail a batch that should have been written:

- the map was read while the column was still `MATERIALIZED` (so the writable
  map lacks it), then the kind was read as `DEFAULT`: the column was treated as
  absent and the batch failed with a misleading "does not exist in ClickHouse
  ... set schema.evolution" message;
- the caller read `MATERIALIZED`, then enforcement re-read `DEFAULT`: it
  returned "no longer MATERIALIZED" and the batch failed naming a conversion
  that had in fact succeeded.

Rules:
1. **A writable kind is never "absent".** When the kind reads `DEFAULT` or
   `""` (an ordinary stored column) but the writable map lacked $C$,
   `refreshIfRecordHasUnknownColumn` re-reads the map once; if it now contains
   $C$ it bumps the table version (`invalidateTable`) and builds the INSERT from
   it, exactly as the §3.2 success path does. Only if the re-read still lacks
   $C$ does §3.3 apply.
2. **Already converted is success.** When `enforceSourceColumnIsWritable`
   re-reads a writable kind, another worker converted the column; it issues no
   `ALTER` and reports the column writable, and the caller re-reads the map as
   after its own conversion (still-missing keeps the §3.3 item 3 failure).
3. **One conversion per table at a time.** `enforceSourceColumnIsWritable` runs
   under a per-table lock (`GroupInsertQueryWithBatchRecords.enforcementLock`,
   one object per fully qualified table name, never shared between tables).
   Workers that read `MATERIALIZED` together wait for the first; each re-reads
   the kind under the lock, so one `ALTER` is issued per table instead of one
   per worker. The lock is held only around the kind re-read and the `ALTER`
   of that table.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority & Shadowing Prohibition)**: ClickHouse computed expressions never discard or shadow values supplied by MySQL; a missing replica column is added, not ignored.
- **Invariant I9 (Loud Failure)**: when the replica cannot be corrected automatically the batch fails; it does not succeed with a dropped column.

---

## 5. Verification Criteria
- `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnFailsBatch()` —
  cached map lacks `note`, `default_kind` reads `""`, `schema.evolution` off:
  `MissingTargetColumnException` (pre-fix code omits the column and marks it
  proven-absent).
- `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnIsAddedWhenSchemaEvolutionEnabled()`
  — same setup with `schema.evolution=true`: an `ALTER TABLE ... ADD COLUMN
  \`note\`` is issued and the INSERT built for the batch contains `note`.
- `GroupInsertQueryWithBatchRecordsTest.aliasColumnIsStillIgnoredAndProvenAbsent()`
  — `default_kind == ALIAS` keeps the pre-existing behaviour.
- `GroupInsertQueryWithBatchRecordsTest.materializedColumnWhoseConversionFailsFailsBatch()`
  — `default_kind == MATERIALIZED`, declared type unreadable (enforcement
  returns false): `MissingTargetColumnException`, no proven-absent entry
  (pre-fix code marks it proven-absent and omits the value).
- `GroupInsertQueryWithBatchRecordsTest.materializedColumnStillMissingAfterConversionFailsBatch()`
  — the `MODIFY COLUMN` reads back as `DEFAULT` but the re-read column map
  still lacks the column: same exception, no proven-absent entry.
- `GroupInsertQueryWithBatchRecordsTest.materializedColumnConvertedIsBoundInSameBatch()`
  — the successful conversion path binds the column in the same batch.
- `StaleCacheBindingMapTest.materializedColumnConvertedMidBatchIsBoundInTheSameBatch()`
  — the converted column is bound, through the executor, in the batch that
  triggered the conversion: every placeholder set, the source value written
  (pre-fix code builds the INSERT from the re-read map but binds with the
  cached one, leaving the column's parameter unset).
- `StaleCacheBindingMapTest.conversionLandingBetweenMapReadAndKindReadIsReReadAndBound()`
  — §3.4 rule 1: another worker's conversion lands between this worker's map
  read and kind read; the map is re-read, no `ALTER` is issued and the column
  is bound (pre-fix code throws "does not exist in ClickHouse ... default_kind
  'DEFAULT'").
- `StaleCacheBindingMapTest.enforcementFindingTheColumnAlreadyConvertedReReadsAndBinds()`
  — §3.4 rule 2: the conversion lands between the caller's kind read and
  enforcement's; no `ALTER`, the column is bound (pre-fix code fails the batch
  after "no longer MATERIALIZED").
- `StaleCacheBindingMapTest.concurrentWorkersIssueOneConversionAndAllSucceed()`
  — §3.4 rule 3: five workers see the column at once; exactly one `ALTER` is
  issued and every worker's INSERT binds the column (pre-fix code issues one
  `ALTER` per worker).
- `UnwritableColumnReportingTest` — MATERIALIZED → DEFAULT conversion DDL.

---

## 6. Failure Modes & Recovery
Every non-ALIAS miss fails the batch rather than dropping the value, and the failure is retried with backoff (spec 10.02) until the ClickHouse side is fixed, so the fix is picked up within 30 s without a restart. The price is that a deterministic miss stalls its worker indefinitely, and the stall is announced only by per-attempt log lines: no metric, no status change, no exit.

- **FM-08.04-1 The source adds a column that ClickHouse lacks, schema evolution off**
  - **Trigger**: a source `ADD COLUMN` whose replicated DDL did not reach ClickHouse (unrecognised by the parser, a schema reload under `sql_log_bin=0`, spec 08.01 FM-08.01-6), or a table created by hand without the column; `schema.evolution=false` (the default).
  - **Behaviour**: `refreshIfRecordHasUnknownColumn` re-reads, finds no `system.columns` row (or an empty `default_kind`) and throws `MissingTargetColumnException`; the classifier returns UNKNOWN (no code) and the batch is retried with backoff forever. Nothing is proven absent, so each retry re-probes.
  - **Detection**: on every attempt ERROR `ClickHouseBatchRunnable exception - Task(%s)` carrying `Column '<c>' is carried by the source record but does not exist in ClickHouse table <db>.<t> (default_kind not found). ... Set schema.evolution=true to let the connector add it, or add the column to the ClickHouse table. Failing the batch instead.` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) -- the same batch will be retried in {} ms (consecutive failures: {}). ...`, at most 30 s apart. `clickhouse_sink_topics_error_records_total` does not move (the batch fails before any INSERT); `/status` keeps `Replica_Running=true`.
  - **Blast radius**: every table hashed to the worker waits, and every later offset stays outstanding (spec 10.02 section 3.4). No loss. If the source keeps writing, the handoff cap stops the engine after `sink.connector.handoff.wait.timeout.ms` (600 s) at 500,000 outstanding rows and the process exits 3 after the engine retry budget (spec 10.04 FM-10.04-4); a quieter source stalls without end.
  - **Recovery**: `ALTER TABLE <db>.<t> ADD COLUMN <c> <type>` in ClickHouse with the type the DDL path would declare (`sink-connector-client ddl_translate` on the source DDL), or set `schema.evolution=true` and restart. The next retry binds the column in the same batch.
  - **RTO**: <= 30 s after the column exists; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnFailsBatch()`, `SchemaCacheFailureModesTest.missingColumnFailsTheBatchAfterOneProbe()` (heals when the column is added).
  - **DEFECT**: the stall is visible only in the log: no metric counts the consecutive failures and `/status` reports the replica running (spec 10.02 FM-10.02-1).

- **FM-08.04-2 Schema evolution on, but the ADD COLUMN does not take effect**
  - **Trigger**: `schema.evolution=true` and the connector's `ALTER TABLE ... ADD COLUMN` is denied (497), rejected, or exhausts its retries.
  - **Behaviour**: `ClickHouseAlterTable.alterTable` catches every exception itself; a retry exhaustion returns normally (spec 08.01 FM-08.01-6). The re-read still lacks the column and `MissingTargetColumnException` names the failed automatic ALTER; retried as FM-08.04-1.
  - **Detection**: ERROR `**** ALTER TABLE EXCEPTION` (or ERROR `ALTER TABLE ... ADD COLUMN for '{}' on {} failed`), then the FM-08.04-1 lines with `The automatic ALTER TABLE ... ADD COLUMN did not produce a writable column; add it to the ClickHouse table.`
  - **Blast radius**: as FM-08.04-1.
  - **Recovery**: grant `ALTER` on the table to the connector user, or add the column by hand; heals on the next retry.
  - **RTO**: <= 30 s after the grant or the column; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnIsAddedWhenSchemaEvolutionEnabled()` covers the success path; GAP: a unit test in which the ADD COLUMN is denied, asserting the batch fails naming the automatic ALTER.

- **FM-08.04-3 A MATERIALIZED column cannot be converted**
  - **Trigger**: a ClickHouse column the source also supplies is MATERIALIZED, and `MODIFY COLUMN ... DEFAULT <expr>` fails: no ALTER privilege, a type or expression that cannot be read, a key column ClickHouse refuses to modify, or a conversion that reads back unchanged.
  - **Behaviour**: `enforceSourceColumnIsWritable` returns false, or the re-read still lacks the column; `MissingTargetColumnException` names the remediation; retried as FM-08.04-1. A successful conversion fixes the write path forward only: rows written while the column was MATERIALIZED keep ClickHouse's computed values.
  - **Detection**: WARN `SOURCE VALUE SHADOWED: {} defines column '{}' as MATERIALIZED, ...`, WARN `Could not make {}.{}.{} writable; ...` or `Conversion did not take effect on {}.{}.{}: ...`, then the FM-08.04-1 lines. After a successful conversion, WARN `ENFORCED: '{}' on {} now stores the source value. Rows written BEFORE this point still hold ClickHouse's computed values -- backfill the affected range ...`.
  - **Blast radius**: stall as FM-08.04-1; after conversion, historical rows keep computed values until backfilled.
  - **Recovery**: grant ALTER, or redefine the column as `DEFAULT` over the same expression by hand; backfill the historical range with `ch-mysql-resync` (spec 11.04).
  - **RTO**: <= 30 s after the redefinition for new rows; the backfill is proportional to the table; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.materializedColumnWhoseConversionFailsFailsBatch()`, `GroupInsertQueryWithBatchRecordsTest.materializedColumnStillMissingAfterConversionFailsBatch()`, `GroupInsertQueryWithBatchRecordsTest.materializedColumnConvertedIsBoundInSameBatch()`.

- **FM-08.04-4 Several workers convert the same column at once**
  - **Trigger**: a `MATERIALIZED` column the source supplies, and batches of its table arriving on several workers together (the normal case right after the column first appears in the stream).
  - **Behaviour**: since §3.4 one worker issues the `ALTER` under the table's enforcement lock; the others find the column already writable, re-read the map and write their batches in the same attempt. Before §3.4 every racing worker issued its own `ALTER`, and a worker whose reads straddled another's `ALTER` failed its batch with `MissingTargetColumnException` ("does not exist ... default_kind 'DEFAULT'", or the failed-conversion message); the retry succeeded.
  - **Detection**: INFO `Enforcing source conformance on <db>.<t>: ALTER TABLE ...` once per table, then INFO `Column '<c>' on <db>.<t> is already writable (default_kind 'DEFAULT'); it was converted concurrently, so no ALTER is issued here.` or `... is writable (default_kind '<k>') but was missing from the column map read just before; the table changed concurrently. Using the re-read column map.` from the other workers. If the re-read still lacks the column, the FM-08.04-1 / FM-08.04-3 lines.
  - **Blast radius**: none when the re-read succeeds; otherwise as FM-08.04-1. The racing workers wait on the lock for at most one `ALTER` of that table.
  - **Recovery**: none needed.
  - **RTO**: 0 (same attempt); the lock wait is one metadata-only `ALTER`.
  - **Test**: `StaleCacheBindingMapTest.conversionLandingBetweenMapReadAndKindReadIsReReadAndBound()`, `StaleCacheBindingMapTest.enforcementFindingTheColumnAlreadyConvertedReReadsAndBinds()`, `StaleCacheBindingMapTest.concurrentWorkersIssueOneConversionAndAllSucceed()`.

Summary: 4 failure modes, 1 DEFECT, 1 GAP.
