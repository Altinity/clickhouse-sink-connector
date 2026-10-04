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
- Explicitly invoke `ps.setNull(columnIndex, java.sql.Types.OTHER)` — except for
  a ClickHouse `Array(...)` column, which §3.2.3 governs.
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

#### 3.2.3 A NULL for an `Array` column is bound as the empty array
§3.2.1 rule 3 rests on a recovery that exists: make the column `Nullable(T)`.
For an `Array(T)` column it does not. ClickHouse refuses `Nullable(Array(T))`
(`Code: 43 Nested type Array(Int64) cannot be inside Nullable type`, measured
with `clickhouse local` 24.8.8), so a source NULL bound for an `Array` column
is refused with `Code: 53 Cannot insert NULL value into a column of type
'Array(Int64)' at: NULL)` (measured, same build, under
`input_format_null_as_default=0`) on every attempt, and the terminal stop of
FM-07.07-1 repeats on every restart with no ClickHouse-side fix short of
changing the column to a different type.

The source shape is ordinary: a MySQL column of type JSON — commonly a
generated column such as `JSON_EXTRACT(doc, '$.items[*].v')`, which MySQL
computes as NULL when the path matches nothing — replicated into an `Array(T)`
column. Debezium delivers it as an optional STRING (`io.debezium.data.Json`);
a non-NULL value is bound as text and parsed by ClickHouse into the array.

Rule (`PreparedStatementFieldMapper.insertPreparedStatement`, helpers
`isArrayType` / `bindEmptyArrayForNull`):
1. When the value read in §3.1 is `null` and the column's declared ClickHouse
   type starts with `Array(`, the parameter is bound as the empty array:
   `ps.setArray(index, ps.getConnection().createArrayOf(<element type>, new Object[0]))`,
   the element type being the declared type with the outer `Array(` ... `)`
   removed (the same driver call the ARRAY branch of
   `ClickHouseDataTypeMapper.convert` uses; the V2 driver renders it `[]`).
2. The empty array is the only value an `Array` column can hold for a NULL
   that does not come from the ClickHouse side (a column DEFAULT expression
   would be a ClickHouse value standing in for the source's, which §3.2
   forbids). It is also what the server stored before §3.2.1 for an `Array`
   column without a DEFAULT expression (measured: NULL into `Array(Int64)`
   under `input_format_null_as_default=1` stores `[]`; into
   `Array(Int64) DEFAULT [7]` it stores `[7]`). NULL and `[]` are indistinguishable in
   such a column, and that is reported, not hidden: the first substitution
   per `database.table.column` is logged at WARN naming the column, its type
   and this section; later rows of the same column at DEBUG.
3. Scope: `Array` columns only. Every other non-Nullable column type keeps the
   §3.2.1 refusal, because its recovery (`MODIFY COLUMN c Nullable(T)`) exists.
   `Map`, `Tuple`, `Nested` and the geo types cannot be `Nullable` either; a
   NULL for them still stops the connector (FM-07.07-5).
4. A non-NULL value for an `Array` column is bound exactly as before.

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
- **Invariant I7 (Type Equivalence)**: Distinguishes between "no value provided" (`NULL`) and "zero/empty value" — wherever the ClickHouse type can represent NULL. An `Array` column cannot (§3.2.3); there the empty array is stored and the loss of the distinction is logged at WARN.

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
- `NullIntoArrayColumnTest.nullIntoArrayColumnIsBoundAsEmptyArray()` — §3.2.3
  rule 1: a NULL JSON field for an `Array(Int64)` column is bound with
  `setArray` holding an empty array created for element type `Int64`, never
  `setNull`; the column is recorded as reported (rule 2). Fails on the pre-fix
  code (which binds `setNull`).
- `NullIntoArrayColumnTest.nullIntoNestedArrayColumnUsesTheDeclaredElementType()`
  — rule 1: `Array(Array(Nullable(String)))` creates the empty array for
  element type `Array(Nullable(String))`.
- `NullIntoArrayColumnTest.nullIntoScalarColumnsIsStillBoundAsNull()` — rule 3:
  in the same row, NULL for `Nullable(String)` and for non-Nullable `Int64` is
  still bound with `setNull`.
- `NullIntoArrayColumnTest.nonNullValueForArrayColumnIsUnchanged()` — rule 4.
- `NullIntoArrayColumnTest.isArrayTypeRecognisesArrayTypesOnly()` — `Map(...)`
  holding an array, `Nullable(String)`, `String` and null are not `Array`.
- Probe (`clickhouse local` 24.8.8, `input_format_null_as_default=0`): into
  `Array(Int64)`, `'[1,2]'`, `[]` and `'[]'` are stored as `[1,2]`, `[]`, `[]`;
  `NULL` is refused with `Code: 53`; `Nullable(Array(Int64))` is refused with
  `Code: 43`.
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
  - **Trigger**: a column created non-Nullable while the source admits NULL (hand-created table, a MySQL `ALTER ... NULL` not applied on ClickHouse, a record-path geo column — spec 07.06 §6 FM-07.06-3), and a row carries NULL. Not an `Array` column: §3.2.3 binds the empty array there.
  - **Behaviour**: `PreparedStatementFieldMapper.insertPreparedStatement` binds `ps.setNull`; the connection carries `input_format_null_as_default=0` (`BaseDbWriter.customSettings`), so ClickHouse refuses: `Code: 53 ... Cannot insert NULL value into a column of type 'Int32'` (measured). 53 is in `FATAL_ERROR_CODES`: the worker dies, the engine stops on the next source batch and the process exits 3.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)`, ERROR `FATAL ClickHouse error (Code: 53) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit code 3, within ≤ 5 s of the failure; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: the whole connector stops; nothing of the batch is written; no loss, no divergence.
  - **Recovery**: P-FIX-TYPE with `MODIFY COLUMN c Nullable(T)`; for a sorting-key column ClickHouse refuses (`Code: 524 ALTER of key column ... is not safe`, measured), so rebuild the table with a nullable key (`allow_nullable_key=1`, spec 06.09) or `ch-mysql-resync` into a corrected table (spec 11.04); restart.
  - **RTO**: non-key column: `ALTER` + restart ≈ 1–2 min + re-apply of the in-flight transaction; key column: + table rebuild proportional to table size; unmeasured.
  - **Test**: `JdbcCustomSettingsTest.defaultSettingsDisableNullAsDefault()`, `PoisonValueClassificationTest.nullIntoNonNullableColumnIsFatal()`, `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`.

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

- **FM-07.07-5 Source NULL for a column type that can never be Nullable**
  - **Trigger**: a row carries NULL for a `Map`, `Tuple`, `Nested` or geo column (an `Array` column is handled by §3.2.3).
  - **Behaviour**: bound with `ps.setNull`; refused with `Code: 53` (terminal) as in FM-07.07-1, but the FM-07.07-1 recovery is impossible — ClickHouse refuses `Nullable(...)` of these types.
  - **Detection**: as FM-07.07-1.
  - **Blast radius**: the whole connector stops; nothing of the batch is written; no loss, no divergence.
  - **Recovery**: change the ClickHouse column to a type that can be `Nullable` (for example `Nullable(String)` holding the source text) or exclude the column on the source connector, then restart.
  - **RTO**: unbounded (needs a schema decision); unmeasured.
  - **Test**: GAP: no test pins the refusal for these types.
  - **DEFECT**: a valid source value stops the connector with no recovery inside the column's current type.

- **FM-07.07-6 NULL and the empty array are indistinguishable in an `Array` column**
  - **Trigger**: §3.2.3 binds `[]` for a source NULL.
  - **Behaviour**: the row is written; the column holds `[]`; a source row whose value is a genuinely empty array holds `[]` too.
  - **Detection**: WARN `Source NULL for column <c> of type Array(<T>) in Database(<db>), Table(<t>) is stored as the empty array []` once per column; DEBUG per later row.
  - **Blast radius**: the NULL/empty distinction for that column only; every other column of the row is exact.
  - **Recovery**: none needed for replication; a consumer that must distinguish NULL from empty reads the source column, or the ClickHouse column is changed to `Nullable(String)` holding the source text.
  - **RTO**: none (no stop).
  - **Test**: `NullIntoArrayColumnTest.nullIntoArrayColumnIsBoundAsEmptyArray()`.

Summary: 6 failure modes, 3 DEFECT, 3 GAP.
