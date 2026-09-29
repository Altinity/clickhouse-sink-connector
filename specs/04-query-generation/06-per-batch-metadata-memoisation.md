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
2. **Keying by identity, never by name.** A memo entry is valid only for the
   exact list / map / configuration objects it was derived from. Keying by
   table name or by column-map contents would let a stale template or a
   pre-ALTER type survive a schema refresh — the silent-divergence class of
   defect specs 04.03 and 08.03 exist to prevent.
3. **Lifetime is one batch.** Both memos live on objects created per
   `processRecordsByTopic` call (the grouper and the executor's mapper). No
   memo survives across batches, across tables or across a DDL barrier.
4. **Failure paths are untouched.** A record that could not be grouped or a
   column that cannot be bound fails exactly as specified in 04.01 §3.3 and
   04.03; a memo never converts a failure into a skip.

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
  different zone for a `DateTime64` column: the rendered instant follows the
  second map's zone. A memo that survived the map change would render the
  first zone.
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
