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
- `PreparedStatementFieldMapperUnhandledTypeTest.unhandledTypeFailsTheBatch()`
  — §3.2.2 rule 1: a `MAP` field bound for a `String` column throws
  `DataException` naming the column; nothing is bound for it (pre-fix code
  logs and continues with the parameter unbound).
- `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()`
  — §3.2.2 rule 2 (also cited by Spec 03.06).
- `TableMetaDataWriterTest.testConvertRecordToJSONDoesNotSubstituteSchemaDefault()`
  — §3.2.2 rule 3: a NULL-with-default field is absent from the raw JSON
  rather than rendered as the default (pre-fix code renders `"new"`).
