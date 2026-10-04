# Spec 07.07: Nullability Semantics & Explicit NULL Binding

## 1. Executive Summary & Purpose
Specifies the parameter binding protocol ensuring that explicit MySQL `NULL` values are durably committed as `NULL` in ClickHouse, preventing fallback to column default expressions.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
  - `insertPreparedStatement()` — the two value reads that feed `ps.setNull` / `ClickHouseDataTypeMapper.convert`.
- **Secondary**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java`
  - `setBeforeStruct()` / `setAfterStruct()` — the "modified fields" lists.
- **Config**: `ClickHouseSinkConnectorConfig` — `non.default.value` (deprecated, no-op).

---

## 3. Operational Specification

### 3.1 The value read MUST bypass the Connect-schema default
Kafka Connect `Struct.get(field)` does **not** return the stored value: when the
stored value is `null` and the field's `Schema` carries a `defaultValue`, it
returns that default. Debezium propagates a MySQL column `DEFAULT` into the
Connect schema, so for every column that has a MySQL default a source `NULL`
read through `Struct.get` arrives as the default (`'new'`, `0`, `1970-01-01`).
Row counts match; only a value-level checksum notices.

Therefore every read of a source value in the bind path uses
`Struct.getWithoutDefault(fieldName)`, unconditionally:
1. `insertPreparedStatement()`: `Object value = struct.getWithoutDefault(colName)`.
2. `insertPreparedStatement()`: the value handed to `ClickHouseDataTypeMapper.convert`
   is `struct.getWithoutDefault(f.name())`.
3. `ClickHouseStruct.setBeforeStruct()` / `setAfterStruct()`: the modified-field
   list is built with `getWithoutDefault`, so a NULL-with-default column is
   classified as NULL, not as "modified with the default value".

### 3.2 Binding NULL
If the value read in §3.1 is `null`:
- Explicitly invoke `ps.setNull(columnIndex, java.sql.Types.OTHER)`.
- **Strict Prohibition**: the parameter may never be skipped, omitted, bound as
  `""`, bound as `0`, or bound as the Connect-schema / ClickHouse DEFAULT.

#### 3.2.1 The server must not substitute either: `input_format_null_as_default=0`
Binding NULL correctly is not sufficient. ClickHouse's own
`input_format_null_as_default` setting defaults to `1`, under which a NULL
inserted into a **non-Nullable** column is silently replaced by the column's
DEFAULT expression (`0`, `''`, `1970-01-01`, ...). Measured with
`clickhouse local` 24.8.14 on `v Int32 DEFAULT 7`: `INSERT ... VALUES (1, NULL)`
stores `7` with the setting at `1`, and is rejected (`Code: 53 TYPE_MISMATCH:
Cannot insert NULL value into a column of type 'Int32'`; other input formats
raise `Code: 349 CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN`) with the setting at
`0`. The connector previously left the setting at the server default, so a
source NULL arriving for a column that was auto-created or hand-created as
non-Nullable was written as the DEFAULT with the batch reported successful —
exactly the failure §3.2 forbids at the bind layer, reintroduced one layer down.

Rule (`BaseDbWriter.createConnection`, helper `customSettings`):
1. The connector's default `custom_settings` are
   `input_format_null_as_default=0,allow_experimental_object_type=1,insert_allow_materialized_columns=1`.
2. When `clickhouse.jdbc.settings` is configured, the user's settings are used
   verbatim **and** `input_format_null_as_default=0` is appended when the
   user's list does not mention `input_format_null_as_default` at all. A user
   who sets the key explicitly (either value) is honoured — the choice is
   then visible in the configuration, not silent.
3. Consequence: a source NULL for a non-Nullable ClickHouse column fails the
   batch loudly (the exception names the column type) and the batch is
   retried; the remediation is on the ClickHouse side — the column must be
   made `Nullable(T)` (the source column admits NULL, so the replica must
   too). The connector does not yet perform that `MODIFY COLUMN` itself; the
   failure is the loud fallback of Invariant I9, never a substituted value.
   Redelivery is safe: nothing was written for the failed batch.

#### 3.2.3 A NULL the ClickHouse column cannot store is a schema mismatch, and is named
A source NULL arriving for a column whose ClickHouse type cannot store NULL
means the two schemas do not match: the source column admits NULL and the
replica column does not. The fix is always to make the ClickHouse schema match
the source; the binder never stores anything in place of the NULL — not `[]`,
not `0`, not `''`, not the column DEFAULT — because ClickHouse would then hold
a value the source never had.

ClickHouse's refusal (`Code: 53 Cannot insert NULL value into a column of type
'Array(Int64)' at: NULL)`) names the type but not the column, so an operator
facing a stopped connector cannot tell which column of which table to fix.
And for some types the remediation of §3.2.1 rule 3 does not exist:
ClickHouse refuses `Nullable(Array(T))` (`Code: 43 Nested type Array(Int64)
cannot be inside Nullable type`), and likewise `Nullable` of `Map`, `Tuple`,
`Nested` and the geo types. A common source shape is a MySQL JSON column —
often a generated column such as `JSON_EXTRACT(doc, '$.items[*].v')`, which
MySQL computes as NULL when the path matches nothing — replicated into a
hand-created `Array(T)` column. Both codes measured with `clickhouse local`
24.8.8 under `input_format_null_as_default=0`.

Rule (`PreparedStatementFieldMapper.insertPreparedStatement`, helpers
`canHoldNull` / `reportNullSchemaMismatch`):
1. The NULL is bound with `ps.setNull` exactly as §3.2 requires, for every
   column type. ClickHouse refuses it for a column that cannot store NULL and
   the connector stops (FM-07.07-1); nothing is written for the batch.
2. Before binding, when the declared ClickHouse type cannot store NULL
   (`canHoldNull` is false: anything other than `Nullable(...)`,
   `LowCardinality(Nullable(...))`, `Variant(...)`, `Dynamic`, `JSON`,
   `Object(...)`), the binder logs one ERROR per `database.table.column`:
   `Schema mismatch: the source sent NULL for column <c> in Database(<db>),
   Table(<t>), but the ClickHouse column is <T>, which cannot store NULL.
   ClickHouse must match the source: <remedy>, then reload the rows written
   while the schemas differed.`
3. `<remedy>` is `change it to Nullable(<T>)` when ClickHouse can declare it
   (`ClickHouseDataTypeMapper.canBeNullable`), and otherwise names the type the
   source column maps to: the connector's own mapping of a MySQL JSON column is
   `Nullable(String)` (`ClickHouseDataTypeMapper`, `Json.LOGICAL_NAME`).
4. A non-NULL value is bound exactly as before and reports nothing.

### 3.2.2 No parameter may be left unbound; no parameter may carry the previous row's value
Two hazards on the V2 JDBC driver (`clickhouse-jdbc` 0.9.x
`PreparedStatementImpl`), verified from its bytecode: `addBatch()` substitutes
the current `values[]` into the SQL template and appends it to the batch
**without clearing `values[]`**; only `clearParameters()` (`Arrays.fill(values,
null)`) does. Therefore a parameter that a row fails to bind silently reuses
the value the previous row bound at that index.

Rules:
1. `ClickHouseDataTypeMapper.convert` returning `false` (no handler for the
   field's Kafka type / logical name — e.g. a `MAP` field) is a **failure**:
   `PreparedStatementFieldMapper.insertPreparedStatement` throws
   `org.apache.kafka.connect.errors.DataException` naming the type, the
   logical name, the column and the table. It previously logged
   `DATA TYPE NOT HANDLED` and continued, leaving the parameter unbound — on
   the first row the driver then failed the batch, but on every later row the
   previous row's value was written in its place, silently.
2. `PreparedStatementExecutor` calls `ps.clearParameters()` immediately after
   every `ps.addBatch()` (both the tombstone and the ordinary row), so no bind
   state survives from one row to the next on either driver.
3. `TableMetaDataWriter.convertRecordToJSON` (the `store.raw.data` JSON copy of
   the row) reads every field with `Struct.getWithoutDefault`, never
   `Struct.get`, for the reason in §3.1: a NULL-with-default column must not
   appear in the raw copy as the Connect-schema default. NULL fields are
   omitted from the JSON object as before.

### 3.3 `non.default.value` is deprecated and has no effect
Before this specification the default-bypassing read was gated behind
`non.default.value=true`, whose hardcoded default was `false` — i.e. the
default configuration substituted MySQL defaults for MySQL NULLs. The prime
directive admits no such mode. The key is retained so existing configurations
still validate, but it is a no-op: the connector always binds the source value.
Setting it explicitly to `false` logs one WARN at startup naming the key as
deprecated and ignored.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)**: a ClickHouse or Connect-schema DEFAULT never stands in for a value the source sent.
- **Invariant I7 (Type Equivalence)**: Distinguishes between "no value provided" (`NULL`) and "zero/empty value".

---

## 5. Verification Criteria
- `NullValueColumnDropTest.testSchemaDefaultIsNotSubstitutedForNull()` — schema
  field `status` optional String with `defaultValue("new")`, stored value `null`,
  empty config: `setNull` is called for the column and `setString` is never
  called with `"new"`. Fails on the pre-fix code (which binds `"new"`).
- `NullValueColumnDropTest.testNullWithSchemaDefaultIsNotAModifiedField()` —
  the modified-field list excludes the NULL-with-default column.
- `JdbcCustomSettingsTest.defaultSettingsDisableNullAsDefault()` — §3.2.1
  rule 1: with no `clickhouse.jdbc.settings`, the effective `custom_settings`
  contain `input_format_null_as_default=0` and keep the two pre-existing
  settings (pre-fix code emits only the latter two).
- `JdbcCustomSettingsTest.userSettingsWithoutTheKeyGetItAppended()` — rule 2:
  `async_insert=1` becomes `async_insert=1,input_format_null_as_default=0`.
- `JdbcCustomSettingsTest.explicitUserSettingIsHonoured()` — rule 2: a user
  list that already sets the key (to `1` or `0`) is passed through unchanged.
- Probe (recorded above, `clickhouse local` 24.8.14): NULL into
  `Int32 DEFAULT 7` stores `7` under the server default and is rejected under
  `input_format_null_as_default=0`.
- `NullSchemaMismatchTest.nullIntoArrayColumnIsBoundAsNullNotSubstituted()` —
  §3.2.3 rule 1: a NULL JSON field for an `Array(Int64)` column is bound with
  `setNull`, never with `[]` or another stand-in.
- `NullSchemaMismatchTest.nullIntoNonNullableColumnsIsReportedAsSchemaMismatch()`
  — rules 1 and 2: in one row, NULL for `Array(Int64)` and for `Int64` is bound
  with `setNull` and both columns are reported as a schema mismatch; NULL for
  `Nullable(String)` is bound with `setNull` and not reported. Fails on code
  that does not report (no column is recorded).
- `NullSchemaMismatchTest.nonNullValuesAreUnchangedAndNotReported()` — rule 4.
- `NullSchemaMismatchTest.canHoldNullRecognisesNullCapableTypes()` — rule 2:
  `Nullable`, `LowCardinality(Nullable)`, `Variant`, `Dynamic`, `JSON` store
  NULL; `Array` (also of `Nullable`), `Map`, `LowCardinality(String)`, `Int64`
  do not.
- Probe (`clickhouse local` 24.8.8, `input_format_null_as_default=0`): NULL into
  `Array(Int64)` is refused with `Code: 53`; `Nullable(Array(Int64))` is refused
  with `Code: 43`; after `MODIFY COLUMN versions REMOVE DEFAULT` and
  `MODIFY COLUMN versions Nullable(String)` on a MergeTree `Array(Int64)` column,
  NULL and `'[1, 2]'` are stored as `\N` and `[1, 2]`, while the rows written
  before the change hold `[5]` and `[]` (converted, not reloaded).
- `PreparedStatementFieldMapperUnhandledTypeTest.unhandledTypeFailsTheBatch()`
  — §3.2.2 rule 1: a `MAP` field bound for a `String` column throws
  `DataException` naming the column; nothing is bound for it (pre-fix code
  logs and continues with the parameter unbound).
- `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()`
  — §3.2.2 rule 2 (also cited by Spec 03.06).
- `TableMetaDataWriterTest.testConvertRecordToJSONDoesNotSubstituteSchemaDefault()`
  — §3.2.2 rule 3: a NULL-with-default field is absent from the raw JSON
  rather than rendered as the default (pre-fix code renders `"new"`).

---

## 6. Failure Modes & Recovery

Recovery posture: a source NULL is always bound as NULL and the server is told not to substitute a default (`input_format_null_as_default=0`), so a NULL the replica cannot hold is refused by ClickHouse with a terminal code; an unbindable field is refused by the connector (retried forever on 2.11.0). The remaining silent path is an operator opting back into default substitution. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.07-1 Source NULL for a non-Nullable ClickHouse column**
  - **Trigger**: a column created non-Nullable while the source admits NULL (hand-created table, a MySQL `ALTER ... NULL` not applied on ClickHouse, a record-path geo column — spec 07.06 §6 FM-07.06-3, a MySQL JSON column hand-created as `Array(T)` — §3.2.3), and a row carries NULL.
  - **Behaviour**: `PreparedStatementFieldMapper.insertPreparedStatement` binds `ps.setNull`; the connection carries `input_format_null_as_default=0` (`BaseDbWriter.customSettings`), so ClickHouse refuses: `Code: 53 ... Cannot insert NULL value into a column of type 'Int32'` (measured). 53 is in `FATAL_ERROR_CODES`: the worker dies, the engine stops on the next source batch and the process exits 3.
  - **Detection**: ERROR `Schema mismatch: the source sent NULL for column <c> in Database(<db>), Table(<t>), but the ClickHouse column is <T>, which cannot store NULL. ...` once per column, naming the column and the type it must become (§3.2.3); ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)`, ERROR `FATAL ClickHouse error (Code: 53) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit code 3, within ≤ 5 s of the failure; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: the whole connector stops; nothing of the batch is written; no loss, no divergence.
  - **Recovery**: make the ClickHouse schema match the source, then reload the rows written while it did not. P-FIX-TYPE with `MODIFY COLUMN c Nullable(T)`; for a type ClickHouse cannot declare `Nullable` (`Array`, `Map`, `Tuple`, `Nested`, geo — Code 43) change the column to the Nullable type the source column maps to (`Nullable(String)` for a MySQL JSON column; drop the column's DEFAULT expression first if its type no longer fits) and P-RESYNC the table, because the rows already written hold values converted from the old type, not the source values (for example `[]` where the source holds NULL); for a sorting-key column ClickHouse refuses (`Code: 524 ALTER of key column ... is not safe`, measured), so rebuild the table with a nullable key (`allow_nullable_key=1`, spec 06.09) or `ch-mysql-resync` into a corrected table (spec 11.04); restart.
  - **RTO**: non-key column: `ALTER` + restart ≈ 1–2 min + re-apply of the in-flight transaction; key column: + table rebuild proportional to table size; unmeasured.
  - **Test**: `JdbcCustomSettingsTest.defaultSettingsDisableNullAsDefault()`, `PoisonValueClassificationTest.nullIntoNonNullableColumnIsFatal()`, `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`, `NullSchemaMismatchTest.nullIntoNonNullableColumnsIsReportedAsSchemaMismatch()`.

- **FM-07.07-2 Operator re-enables default substitution**
  - **Trigger**: `clickhouse.jdbc.settings` contains `input_format_null_as_default=1`.
  - **Behaviour**: `BaseDbWriter.customSettings` passes a user list that mentions the key through unchanged and logs nothing (the INFO line is written only when the key is appended); ClickHouse then stores the column DEFAULT for every source NULL bound into a non-Nullable column (measured: `7` for `Int32 DEFAULT 7`).
  - **Detection**: none. DEFECT.
  - **Blast radius**: silent value divergence (default in place of NULL) on every such row; row counts intact.
  - **Recovery**: remove the key (or set it to 0), restart, then make the columns `Nullable` (FM-07.07-1) and P-RESYNC the affected tables.
  - **RTO**: restart ≈ 1 min + resync; unmeasured.
  - **Test**: `JdbcCustomSettingsTest.explicitUserSettingIsHonoured()` pins the pass-through; GAP: a test that an explicit `input_format_null_as_default=1` is reported at WARN at start.
  - **DEFECT**: a setting that reintroduces the substitution §3.2 forbids is accepted without a warning.

- **FM-07.07-3 Field with no type binding**
  - **Trigger**: a Connect type the mapper has no handler for (a `MAP`, a nested `STRUCT` that is not a known logical type) — a PostgreSQL `hstore`, a Kafka-mode schema.
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` returns `false` and `PreparedStatementFieldMapper.insertPreparedStatement` throws `DataException` (§3.2.2 rule 1); no ClickHouse code, so UNKNOWN: the worker retries forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `No ClickHouse binding for type(MAP), name(<n>) of column <c> in Database(<db>), Table(<t>); the parameter would be left unbound. Failing the batch instead.`, WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits forever; no loss.
  - **Recovery**: none by retry or by ClickHouse DDL; exclude the column on the source connector (`column.exclude.list`) or convert it upstream (an SMT), restart; if the offending transaction is already in the stream, P-SKIP + resync.
  - **RTO**: unbounded; config change + restart ≈ 1–2 min if the exclusion applies to the redelivered event, else P-SKIP + resync; unmeasured.
  - **Test**: `PreparedStatementFieldMapperUnhandledTypeTest.unhandledTypeFailsTheBatch()` (the refusal), `PoisonValueClassificationTest.unhandledTypeRefusalIsFatal()` (disabled, fails on 2.11.0: UNKNOWN instead of FATAL).
  - **DEFECT**: a refusal that can never succeed on retry is retried forever with no exit.

- **FM-07.07-4 A parameter left unbound reaches the V2 driver**
  - **Trigger**: a future converter branch that returns `true` without binding (the float branch of `ClickHouseDataTypeMapper.convert` binds nothing for a value that is neither `Float` nor `Double`; Connect's `Struct` validation prevents that today).
  - **Behaviour**: `PreparedStatementExecutor` calls `clearParameters()` after every `addBatch()` (§3.2.2 rule 2), so the stale value of the previous row cannot leak; the V2 driver's `addBatch` then dereferences the null parameter (`values[i].length()`, jdbc-v2 0.9.8 bytecode) and throws `NullPointerException`: UNKNOWN, retried forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception` with the `NullPointerException` from `PreparedStatementImpl.addBatch`, WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s; no exit.
  - **Blast radius**: the worker's tables stop; no silent reuse of another row's value.
  - **Recovery**: a code fix (the branch must bind or throw); P-SKIP + resync if a release cannot be produced in time.
  - **RTO**: unbounded (needs a fix); unmeasured.
  - **Test**: `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()`; GAP: a test that every `convert` branch either binds its parameter or throws.

Summary: 4 failure modes, 2 DEFECT, 2 GAP.
