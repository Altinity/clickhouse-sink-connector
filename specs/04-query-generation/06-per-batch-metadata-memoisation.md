# Spec 04.06: Per-Batch Metadata Memoisation on the Write Path

## 1. Executive Summary & Purpose

A worker turns a batch of records into one JDBC batch in two steps: it groups
the records by INSERT template (spec 04.01) and binds each row's values into
that template (spec 04.03, 07.x). Before this spec both steps re-derived, for
EVERY ROW, metadata that is constant for the whole batch:

- the INSERT template — column list, placeholders and parameter-index map —
  was rebuilt per record from the record's Connect schema and the ClickHouse
  column map, although every record of a table in a batch shares the same
  schema object and the same column map;
- the "does the cached column map know every column this record carries"
  check (spec 08.03) rebuilt a lower-cased set of the column map's keys per
  record;
- the declared ClickHouse type of each column was re-parsed
  (`ClickHouseColumn.of`) per column PER ROW, and the range policy label
  re-concatenated per column per row;
- the temporal converters compiled their fixed `DateTimeFormatter` patterns
  per VALUE.

Measured with the benchmark in section 6 on a 10,000-row, 17-column batch
against a no-op JDBC surface: grouping 116 ms, binding 254 ms — 370 ms per
10,000 rows of the connector's own CPU, i.e. ~27,000 rows/s per worker before
a single byte reaches ClickHouse (the ClickHouse side of the same INSERT takes
11 ms server-side). A JFR profile of that run attributed 49% of samples to
`QueryFormatter.createColumns` (per-record template building), 15% to
`PreparedStatementFieldMapper.parseColumn` (per-row type parsing), 12% to
`refreshIfRecordHasUnknownColumn` (per-record key-set rebuild) and 4% to
`DateTimeFormatter.ofPattern` inside the converters.

This spec makes each of those derivations happen once per batch (or once per
column per statement) instead of once per row, with **byte-identical output**:
the same SQL text, the same parameter-index map, the same bound values, the
same failures. It changes throughput only. Nothing about ordering, versioning,
membership, NULL handling or failure behaviour is touched; every existing test
in domains 04, 05 and 07 continues to define the output.

## 2. Codebase Mapping on 2.11.0

- **Grouping memo**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
  - `TemplateKey` (private static class) — identity of the membership field
    list, identity of the column map, and the source database name.
  - `templateMemo` — `Map<TemplateKey, MutablePair<String, Map<String, Integer>>>`,
    consulted in `updateQueryToRecordsMap` before
    `QueryFormatter.getInsertQueryUsingInputFunction` is called.
  - `verifiedSchemas` — `IdentityHashMap<Schema, Map<String, String>>`,
    consulted in `groupQueryWithRecords` before
    `refreshIfRecordHasUnknownColumn` is called.
- **Binding memo**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
  - `ColumnBinding` (private static class) — the parsed `ClickHouseColumn`,
    its `ClickHouseDataType`, the column's declared zone and its
    `RangePolicy`.
  - `columnBinding(colName, columnNameToDataTypeMap, config, tableName)` —
    the memo, keyed by column name and valid for one (column map, config,
    table) identity triple; used by `insertPreparedStatement` in place of the
    per-row `parseColumn` / `columnTimeZoneOf` / `RangePolicy.of` calls.
- **Formatter constants**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java`
  — `SECONDS_FORMAT`, `MILLIS_FORMAT`, `MICROS_FORMAT`, `EIGHT_DIGIT_FORMAT`,
  `NANOS_FORMAT`, used by `TimestampConverter`, `MicroTimestampConverter`,
  `NanoTimestampConverter`-style overloads and `ZonedTimestampConverter` in
  place of per-call `DateTimeFormatter.ofPattern(...)`.
- **Measurement**: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/batch/WriterHotPathBenchmarkTest.java`
  (section 6).

## 3. Operational Specification

### 3.1 The INSERT template is built once per key per batch

For a record `r` grouped by `updateQueryToRecordsMap`, the template
`QueryFormatter.getInsertQueryUsingInputFunction(...)` returns is a function
of:

1. the membership field list — `r`'s image schema fields when the record
   carries the image its operation binds (`schemaFieldsFor`, spec 04.03),
   else its value-filtered modified-field list;
2. the ClickHouse column map instance in force for `r`;
3. `r.getDatabase()`;
4. this grouper's own resolved engine columns and the connector configuration,
   which are constant for the grouper's life (one `processRecordsByTopic`
   call, spec 03.03 §3.2).

Hence the grouper memoises the template under `TemplateKey(membership list,
column map, database)`, where the list and the map are compared by
**identity**:

- Every record of a table within one batch is built by Debezium from the same
  Connect `Schema` object, whose `fields()` list is the same instance, so all
  of them hit the memo after the first — one template build per schema per
  batch instead of one per row.
- A record built from a different schema (a pre-ALTER record buffered next to
  post-ALTER ones, spec 04.03 §3.1) is a different list instance, therefore a
  different key and its own template — exactly the distinct bucket spec 04.01
  §3.1 requires.
- A refreshed column map (`refreshIfRecordHasUnknownColumn` returned a fresh
  map, spec 08.03) is a different instance, therefore a different key: a
  template built from the stale map is never reused for a record grouped
  after the refresh.
- Only a complete template (non-null pair with non-null SQL and index map) is
  memoised; the failure path of spec 04.01 §3.3 is unchanged.

A memo hit returns the SAME pair object the formatter returned: the same SQL
text and the same `Map<String, Integer>` parameter-index map. The grouper
still wraps it in a fresh `MutablePair` key per record, so the equality-keyed
bucketing of spec 04.01 §3.1 is unchanged — records that previously landed in
one bucket because their templates were `equals` still do, and records whose
templates differ still separate. The index map is read-only downstream
(`PreparedStatementExecutor` only reads `entry.getKey().right`), so sharing
the instance across records is safe.

The staleness check `refreshIfRecordHasUnknownColumn` is likewise a function
of the record's image schema and the column map instance (plus the
proven-absent registry of spec 08.03, which changes only through this very
check or at a DDL barrier — and a DDL barrier drains the writers before any
schema changes, spec 06.01, so it cannot move between two records of one
batch). The grouper therefore runs it once per (schema identity, column map
identity): a **consistent** verdict is recorded in `verifiedSchemas`; a
refresh returns a new map instance, which is not recorded, so the next record
of that schema is checked against the fresh map and recorded only if that
check is clean. The guarantee of spec 08.03 — an INSERT is never built from a
column map provably behind the source — holds unchanged, because the first
record of each schema was verified against the exact map every later record
of that schema is grouped with.

### 3.2 A column's declared type is parsed once per column per statement

`PreparedStatementFieldMapper.insertPreparedStatement` needs, per bound
column: the parsed `ClickHouseColumn` (data type plus declared zone,
`parseColumn`), the zone to render instants in (spec 07.03 §3.1.3), and the
`RangePolicy` carrying the `db.table.column` label (spec 07.03 §3.3). All
three are functions of the column name, the column map, the configuration and
the table name — none of the row. The mapper memoises them per column name in
a `ColumnBinding`, and discards the whole memo whenever the column map
instance, the configuration instance or the table name it last bound with
changes. A refreshed column map (a new instance) therefore re-parses every
column; an `ALTER` that changed a column's declared type is picked up exactly
when the cache it lives in is refreshed, as before.

`ClickHouseColumn.of(name, type)` is a pure function of the declared type
string, and the connector never mutates the parsed column (its setters are
unused), so the memoised object is equivalent to a fresh parse. A declared
type that does not parse memoises as `null`, which binds exactly as before
(`chDataType == null`, no column zone). `RangePolicy` is immutable.

### 3.3 Fixed formatter patterns are compiled once

`DateTimeFormatter` is immutable and thread-safe. The five fixed patterns the
temporal converters render with (`yyyy-MM-dd HH:mm:ss` and its `.SSS`,
`.SSSSSS`, `.SSSSSSSS`, `.SSSSSSSSS` variants) are class constants; the
converters select among them exactly where they used to select among
`ofPattern` calls, and `withZone(...)` is applied to the constant where the
per-call form applied it. The rendered text is unchanged. The one
data-dependent pattern (`formatString` in the zoned-timestamp parser) is left
as is.

### 3.4 What must never change

1. **Output identity.** For any batch, the sequence of `PREPARE`, `set*`,
   `addBatch`, `clearParameters`, `executeBatch` calls and every bound value
   are identical with and without the memos. This is the acceptance criterion
   of the change; the tests in section 5 compare the memoised path against a
   fresh, unmemoised computation of the same inputs.
2. **Keying by identity AND content, never by name.** A memo entry is valid
   only for the exact list / map / configuration objects it was derived from,
   and — for the column map — only while the map's content fingerprint
   (`Map.hashCode()` over every name and declared type) is the one it was
   derived with; a bound column additionally re-reads its declared type on
   every use and re-parses when it differs. Keying by table name alone would
   let a stale template or a pre-ALTER type survive a schema refresh — the
   silent-divergence class of defect specs 04.03 and 08.03 exist to prevent.
   Section 3.5 spells out the DDL cases.
3. **Lifetime is one batch.** Both memos live on objects created per
   `processRecordsByTopic` call (the grouper and the executor's mapper). No
   memo survives across batches, across tables or across a DDL barrier.
4. **Failure paths are untouched.** A record that could not be grouped or a
   column that cannot be bound fails exactly as specified in 04.01 §3.3 and
   04.03; a memo never converts a failure into a skip.

### 3.5 Behaviour around a DDL

A DDL is the one event that changes the inputs the memos are keyed on, so
every way a DDL can reach a batch is enumerated here with the behaviour the
memos must (and do) preserve. Throughout, "the same as before" means the
output of the unmemoised 2.11.0 code path.

1. **The DDL barrier.** A DDL statement replicated through `drainBeforeDDL`
   (spec 06.01) waits for every queue to empty and every handed-off unit to
   be acknowledged before the schema changes, then invalidates the caches
   (spec 08.02). No batch is being grouped or bound while the barrier holds,
   and both memos live on objects created per batch, so no memo entry built
   before the DDL can be consulted after it.
2. **Pre-ALTER and post-ALTER records in one batch** (an `ADD COLUMN` whose
   earlier rows were buffered before it): the two record shapes are two
   Connect `Schema` objects, hence two membership lists, hence two template
   keys. The pre-ALTER template omits the new column (ClickHouse `DEFAULT`
   applies, spec 04.03 §3.1); the post-ALTER template carries it. Each row is
   bound through its own template. The same as before, with each template
   built once instead of once per row.
3. **Stale cache — the record carries a column the cached map lacks** (an
   `ADD COLUMN` this writer has not yet observed): the staleness check runs
   for the first record of that schema, re-reads the metadata ONCE, and
   returns a fresh map instance. The fresh map is a different identity (and
   fingerprint), so the template is built against it and the next record of
   the same schema is verified against it before that verdict is memoised.
   One metadata read per schema per batch, exactly as before.
4. **`DROP COLUMN` / `RENAME COLUMN` — the record carries a column the table
   no longer has** (a pre-ALTER record behind the DDL): the staleness check
   runs for that schema regardless of what was memoised for other schemas,
   the re-read does not produce the column, and the batch fails loudly
   (`MissingTargetColumnException`, spec 08.04) instead of being written with
   the value dropped. A memo never converts this failure into a skip.
5. **`MODIFY COLUMN` type or zone**: the refreshed column map is a new
   instance, so the next batch parses the new declared type. Within a
   statement the binding memo also re-reads the declared type per use, so even
   a map mutated in place mid-statement re-parses the column
   (`bindingIsRebuiltWhenTheDeclaredTypeChangesInPlace`). The rendered value
   follows the column's new type and zone (spec 07.03 §3.1.3).
6. **ALIAS / MATERIALIZED columns proven absent** (spec 08.03/08.04): the
   proven-absent verdict and the resulting fresh map are produced by the first
   record of the schema; later records of that schema re-verify against the
   fresh map once and then hit the memo. One column listing and one
   `default_kind` probe per batch — no query storm.
7. **A column map refreshed in place** (not something the connector does —
   `DbWriter` always replaces the map — but the guard the memos carry): the
   template key and the verified-schema entry both carry the map's content
   fingerprint, so a changed map under the same reference rebuilds the
   template and re-runs the staleness check
   (`templateIsRebuiltWhenTheColumnMapChangesInPlace`).

## 4. Invariants Preserved

- **Invariant I3 (Eventual Convergence)** — unchanged: the rows written, their
  `_version` and delete flags, are byte-identical to the unmemoised path.
- **Invariant I7 (Column Presence Guarantee)** — unchanged: membership is still
  decided per record's schema (spec 04.03); the memo only avoids re-deriving
  the same decision for the same schema object.
- **Invariant I9 (Loud Failure)** — unchanged: every failure path of specs
  04.01, 04.03 and 07.03 is reached with the same inputs.
- **Invariant I11 (Drop-in Upgrade Safety)** — a pure throughput change with
  no data-format, configuration or offset impact; upgrade and downgrade are a
  restart.

## 5. Verification Criteria

- `GroupInsertQueryTemplateMemoTest.templateIsBuiltOncePerSchemaPerBatch` —
  a 300-record batch of two interleaved Connect schemas iterates the column
  map's entries exactly twice (one template build per schema) and its key set
  exactly twice (one staleness check per schema); both buckets hold every
  record of their schema and their templates equal a fresh
  `QueryFormatter.getInsertQueryUsingInputFunction` computation for a record
  of that schema. Mutation: with the memo disabled the counts are 300.
- `GroupInsertQueryTemplateMemoTest.templateIsRebuiltForADifferentColumnMap` —
  the same grouper, the same record schema, a second column map instance
  without one of the record's columns: the template built with the second
  map omits that column. A memo keyed by anything other than map identity
  would return the first template.
- `PreparedStatementFieldMapperColumnBindingMemoTest.memoisedBindingsEqualFreshBindings`
  — three rows bound through one mapper produce exactly the parameter
  sequence that binding each row through a fresh mapper produces.
- `PreparedStatementFieldMapperColumnBindingMemoTest.bindingIsRebuiltForADifferentColumnMap`
  — the same mapper, the same row, a second column map instance declaring a
  different type for the `TIMESTAMP` column (`DateTime64(6, 'UTC')`, then
  `String`): the bound value follows the second map's type (epoch text for
  `DateTime64`, digits for `String` — Spec 07.03 sections 3.1.3/3.1.4). A memo
  that survived the map change would bind the first type's rendering.
- `PreparedStatementFieldMapperColumnBindingMemoTest.bindingIsRebuiltWhenTheDeclaredTypeChangesInPlace`
  — section 3.5 item 5/7: the same map instance with a column's declared
  type changed in place re-parses the column; the next row binds the new
  type's rendering.
- `GroupInsertQueryDdlMemoTest.preAndPostAlterRecordsInOneBatchGetTheirOwnTemplates`
  — section 3.5 item 2: pre-ALTER rows group under a template without the new
  column and post-ALTER rows under one with it, interleaved in one batch, with
  each template built once.
- `GroupInsertQueryDdlMemoTest.staleCacheIsRefreshedOnceAndTheFreshMapKeysTheMemo`
  — section 3.5 item 3: a record carrying a column the cached map lacks
  triggers exactly one metadata re-read; every record of the batch is grouped
  under the template built from the fresh map.
- `GroupInsertQueryDdlMemoTest.aliasColumnIsProvenAbsentOnceForTheWholeBatch`
  — section 3.5 item 6: 100 records carrying an ALIAS column cost one column
  listing and one `default_kind` probe; all group under one template that
  omits the ALIAS column.
- `GroupInsertQueryDdlMemoTest.droppedColumnStillFailsLoudlyAfterACleanSchemaWasMemoised`
  — section 3.5 item 4: after post-ALTER records verified clean, a pre-ALTER
  record carrying a dropped column fails the batch with
  `MissingTargetColumnException`; nothing about it is skipped.
- `GroupInsertQueryDdlMemoTest.templateIsRebuiltWhenTheColumnMapChangesInPlace`
  — section 3.5 item 7: the same map instance with a column removed in place
  yields a template without that column on the next record.
- `PreparedStatementExecutorDdlMemoTest.mixedPreAndPostAlterBatchBindsEachRowThroughItsOwnTemplate`
  — end to end through `addToPreparedStatementBatch` with the recording JDBC
  surface: two statements are prepared, pre-ALTER rows carry no parameter for
  the new column, post-ALTER rows bind it (including an explicit NULL), and
  every row's other values are bound as before.
- Every existing test of domains 04, 05 and 07 (template grouping, field
  membership, NULL binding, tombstone synthesis, sign columns, column zones,
  out-of-range policy, unhandled types, clear-parameters) passes unchanged —
  they define the output this spec promises not to alter.

## 6. Measurement (not a CI gate)

`WriterHotPathBenchmarkTest` runs only with `-Dwriter.bench=1` and prints the
median milliseconds per 10,000 rows for grouping and for binding against a
no-op JDBC surface, for a 17-column ReplacingMergeTree(_version, is_deleted)
row shape with a two-column sorting key and a 20% UPDATE mix:

```
./mvnw -o -q -pl sink-connector test -Dtest=WriterHotPathBenchmarkTest \
    -Dwriter.bench=1 -Dsurefire.failIfNoSpecifiedTests=false
```

Record the before/after figures of the machine the change is measured on in
the pull request; the numbers in section 1 are the reference for 2.11.0 at
`93fde48d`. A JFR profile is obtained by adding
`MAVEN_OPTS="-XX:StartFlightRecording=filename=<file>,settings=profile"` (the
module's surefire runs in-process, `forkCount=0`, so `argLine` is not
applied).

---

## 7. Failure Modes & Recovery

Recovery posture: the memos change throughput only, live for one batch and are keyed by identity and content (§3.4), so a restart, a DDL barrier and a new batch all start from scratch; every failure path of specs 04.01, 04.03 and 07.x is reached unchanged. The failure modes that matter here are those in which a memoised verdict extends one bad decision to every record of the batch. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.06-1 Pre-ALTER record carrying a column the table no longer has**
  - **Trigger**: a record buffered before a `DROP COLUMN` / `RENAME COLUMN` reaches the writer after the ClickHouse column is gone (the DDL barrier of spec 06.01 failed or was bypassed, or the column was dropped out of band on ClickHouse).
  - **Behaviour**: the staleness check runs for that record's schema regardless of what was memoised for other schemas (§3.5 item 4); the re-read does not produce the column and `GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn` throws `MissingTargetColumnException`; UNKNOWN, retried every ≤ 30 s (spec 04.03 §6 FM-04.03-1).
  - **Detection**: WARN `Cached schema for <db>.<t> does not contain column '<c>' ...`, ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `Column '<c>' is carried by the source record but does not exist in ClickHouse table <db>.<t> ...`, WARN `Retriable ... Category: UNKNOWN`, every ≤ 30 s; no exit.
  - **Blast radius**: the worker's tables stop and offsets freeze; nothing is written with the value dropped.
  - **Recovery**: re-add the column on ClickHouse (the retry then writes the pre-ALTER rows; drop it again afterwards if the source dropped it), or P-SKIP + P-RESYNC.
  - **RTO**: `ADD COLUMN` + ≤ 30 s backoff; unmeasured.
  - **Test**: `GroupInsertQueryDdlMemoTest.droppedColumnStillFailsLoudlyAfterACleanSchemaWasMemoised()`.
  - **DEFECT**: as spec 04.03 §6 FM-04.03-1 — waits for an operator indefinitely with no metric.

- **FM-04.06-2 Staleness re-read fails and the failed verdict is memoised**
  - **Trigger**: a record carries a column the cached map lacks (an `ADD COLUMN` this writer has not seen) and the metadata re-read fails — ClickHouse refuses the `system.columns` query (`Code: 202` too many simultaneous queries, a timeout, a dropped connection) while INSERTs still succeed.
  - **Behaviour**: `DBMetadata.getColumnsDataTypesForTable` retries 10 times without delay and returns an empty map; `refreshIfRecordHasUnknownColumn` logs that it keeps the cached map and returns null; `groupQueryWithRecords` records the schema in `verifiedSchemas` as consistent, so every record of that schema in the batch is grouped under a template built from the cached map, which omits the column. The binder walks the same map and never visits the record's extra field: the value is dropped and ClickHouse stores the column DEFAULT; the batch succeeds. (The same outcome without the memo: each record's re-read would fail alike; the memo extends the verdict to the whole batch.)
  - **Detection**: ERROR `Exception retrieving Column Metadata, retrying (<n>/10)` ×10 and WARN `Re-read of <db>.<t> returned no columns; keeping the cached map. The bind-time check will fail the batch if a value would be dropped.` (the last clause is not true for this direction); the dropped values themselves: none. DEFECT.
  - **Blast radius**: silent loss of the new column's value for every row of the batch (row counts intact); the next batch re-runs the check and heals for later rows only.
  - **Recovery**: P-RESYNC the table (or re-apply the affected rows by a source `UPDATE`); find the window from the WARN's timestamp.
  - **RTO**: unbounded detection; after detection resync of the table; unmeasured.
  - **Test**: `GroupInsertQueryStaleCheckFailureTest.failedMetadataReReadDoesNotDropTheUnknownColumn()` (disabled, fails on 2.11.0 — the batch groups without `note` and nothing is thrown).
  - **DEFECT**: a failed staleness re-read is treated as "consistent" (in `refreshIfRecordHasUnknownColumn`, spec 08.03, and memoised here); it must fail the batch so it is retried against readable metadata.

- **FM-04.06-3 A memo returns a template or binding that does not match the record**
  - **Trigger**: a defect in `TemplateKey` / `ColumnBinding` keying (e.g. a future change that keys by name), or a column map mutated in place with an equal content fingerprint (`Map.hashCode()` collision).
  - **Behaviour**: guarded by identity plus content fingerprint and by the per-batch lifetime (§3.4 items 2–3); a placeholder/column mismatch is caught at bind time by `StaleSchemaCacheException` (spec 04.03 §6 FM-04.03-2); a wrong type rendering would not be caught.
  - **Detection**: placeholder mismatch: the `StaleSchemaCacheException` ERROR; wrong rendering: none.
  - **Blast radius**: the affected batch; a rendering mismatch would be silent divergence.
  - **Recovery**: there is no configuration switch that bypasses the memos; roll back to the previous release (a restart, Invariant I11) and P-RESYNC the tables written by the faulty build.
  - **RTO**: rollback restart ≈ 1 min + resync; unmeasured. No known instance on 2.11.0.
  - **Test**: `GroupInsertQueryDdlMemoTest.templateIsRebuiltWhenTheColumnMapChangesInPlace()`, `PreparedStatementFieldMapperColumnBindingMemoTest.bindingIsRebuiltWhenTheDeclaredTypeChangesInPlace()`, `PreparedStatementFieldMapperColumnBindingMemoTest.memoisedBindingsEqualFreshBindings()`; GAP: a differential test that runs randomised batches (mixed schemas, in-batch refreshes) through the memoised and a fresh path and compares every bound value.

- **FM-04.06-4 Write-path throughput regression**
  - **Trigger**: a change reintroduces per-row metadata work (the pre-spec cost was ~27,000 rows/s per worker of connector CPU, §1) under a production burst (180M rows in 20 minutes needs ~150,000 rows/s).
  - **Behaviour**: the connector falls behind; nothing fails.
  - **Detection**: lag only: `show_replica_status.seconds_behind_source` grows (spec 10.03); `WriterHotPathBenchmarkTest` runs only with `-Dwriter.bench=1` and is not a CI gate (§6).
  - **Blast radius**: replication lag; no loss.
  - **Recovery**: raise `thread.pool.size` (key-aware routing spreads tables over more workers, spec 03.07) as a stopgap; roll back the regressing build.
  - **RTO**: restart ≈ 1 min, then the backlog drains at the restored rate; unmeasured.
  - **Test**: `WriterHotPathBenchmarkTest` (manual); GAP: a CI gate that fails when grouping plus binding of the 10,000-row benchmark batch exceeds a stated budget.

Summary: 4 failure modes, 2 DEFECT, 2 GAP.
