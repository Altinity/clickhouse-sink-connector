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
