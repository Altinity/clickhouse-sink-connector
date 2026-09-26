# Spec 07.01: Integer Data Type Mapping & Range Rules

## 1. Executive Summary & Purpose
Specifies the conversion of signed and unsigned MySQL integer types to ClickHouse integer primitives without sign inversion or overflow.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`, `normalizeIntegerTypeName`), `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`SERIAL` nullability and key candidacy)

---

## 3. Type Conversion Matrix

| MySQL Type | Connect Schema | ClickHouse Target | Conversion Logic |
|---|---|---|---|
| `TINYINT` | `INT8` | `Int8` | Standard 8-bit signed byte |
| `TINYINT UNSIGNED` | `INT16` | `UInt8` | Zero-extended to `short`, stored as unsigned 8-bit |
| `TINYINT(1)` | `INT8` / `BOOLEAN` | `Bool` / `UInt8` | Configurable: maps to `Bool` or `UInt8` |
| `SMALLINT` | `INT16` | `Int16` | Standard 16-bit signed short |
| `SMALLINT UNSIGNED` | `INT32` | `UInt16` | Zero-extended to `int`, stored as unsigned 16-bit |
| `MEDIUMINT` | `INT32` | `Int32` | Standard 32-bit signed int |
| `MEDIUMINT UNSIGNED` | `INT32` | `UInt32` | Stored as unsigned 32-bit int |
| `INT` / `INTEGER` | `INT32` | `Int32` | Standard 32-bit signed int |
| `INT UNSIGNED` | `INT64` | `UInt32` | Handled via `Long.longValue()`, stored as `UInt32` |
| `BIGINT` | `INT64` | `Int64` | Standard 64-bit signed long |
| `BIGINT UNSIGNED` | `INT64` (default `bigint.unsigned.handling.mode=long`) | `UInt64` | Values $\ge 2^{63}$ arrive as a **negative** `long` (two's-complement wrap); the mapper restores the unsigned magnitude before binding (§3.1) |
| `BIGINT UNSIGNED` | `BYTES` / `Decimal` (only if `bigint.unsigned.handling.mode=precise` is configured on the source) | `UInt64` | Bound as `BigDecimal`; no wrap occurs |

### 3.1 `BIGINT UNSIGNED` under Debezium's default `long` mode
No connector configuration sets `bigint.unsigned.handling.mode=precise`, so the
default `long` applies: Debezium emits `INT64` and a MySQL value in
$[2^{63}, 2^{64})$ wraps to a negative Java `long` (e.g. `18446744073709551615`
arrives as `-1L`). `ClickHouseDataTypeMapper.convert` previously bound that
`long` with `ps.setObject`, so ClickHouse either rejected the row (negative into
`UInt64`) or, through driver coercion, stored a different number.

Rule: when the Kafka type is `INT64` (no logical name), the ClickHouse target
column is `UInt64`, and the value is a negative `Long`, bind
`new BigInteger(Long.toUnsignedString(value))` — the exact MySQL value. Values
$< 2^{63}$ are unaffected. A negative `long` bound for a *signed* `Int64`
target is left untouched (it is a genuine negative `BIGINT`).

### 3.2 DDL path: every spelling of an unsigned integer maps to `UInt`
The DDL translator resolves the declared type through Debezium's
`DataTypeResolver`, whose type *name* carries the attribute tokens verbatim
(`BIGINT UNSIGNED ZEROFILL`, `INT8 UNSIGNED`, `SERIAL`). The unsigned lookup
used to be an exact match against six spellings (`tinyint unsigned` …
`bigint unsigned`), so every other spelling fell through to the signed Kafka
schema mapping and was created **signed**: `BIGINT UNSIGNED ZEROFILL` →
`Int64`, `INT8 UNSIGNED` → `Int64`, `SERIAL` → `Nullable(Int64)`, `INT(10)
UNSIGNED ZEROFILL` → `Int64`. A value in $[2^{63}, 2^{64})$ (or, for the
narrower types, above the signed maximum) then cannot be stored — a rejected
insert or a wrapped value, both divergence.

Rule (`DataTypeConverter.normalizeIntegerTypeName`, applied before any lookup):
1. Lower-case, collapse whitespace.
2. Fold the integer synonyms MySQL defines: `INT1` → `TINYINT`, `INT2` →
   `SMALLINT`, `INT3` / `MIDDLEINT` → `MEDIUMINT`, `INT4` → `INT`, `INT8` →
   `BIGINT`, `SERIAL` → `BIGINT UNSIGNED`.
3. `ZEROFILL` implies `UNSIGNED` (MySQL adds the attribute itself); the token
   is then dropped, as is a redundant `SIGNED`.
4. A normalised name containing `unsigned` resolves through
   `ClickHouseDataTypeMapper.getUnsignedClickHouseType` (the same function the
   record path uses, which also tolerates a display width), so `TINYINT` /
   `SMALLINT` / `MEDIUMINT` / `INT` / `BIGINT UNSIGNED` in any spelling map to
   `UInt8` / `UInt16` / `UInt32` / `UInt32` / `UInt64`. A signed name keeps the
   signed mapping (`TINYINT` and `INT1` → `Int8`).
5. `SERIAL` is MySQL shorthand for `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
   UNIQUE`: the DDL translator maps it to `UInt64`, treats the column as
   `NOT NULL` (CREATE and `ADD COLUMN`), and on CREATE registers it as the
   candidate `UNIQUE` key so a table with only a `SERIAL` column is keyed by
   it (Spec 06.05 §3.6).

### 3.3 Auto-created column type for `BIGINT UNSIGNED` needs the propagated source type
The record-schema auto-create path (`ClickHouseTableOperationsBase.getColumnNameToCHDataTypeMapping`,
Spec 08.05) can only declare `UInt64` when the field carries Debezium's
`__debezium.source.column.type` parameter (`column.propagate.source.type`).
The lightweight runtime always sets `column.propagate.source.type=.*`
(`DebeziumChangeEventCapture.setupDebeziumEventCapture`). In Kafka Connect
mode nothing forces it: an `INT64` field with no logical name and no
parameters is indistinguishable from a signed `BIGINT`, is declared `Int64`,
and a `BIGINT UNSIGNED` value in $[2^{63}, 2^{64})$ is then stored as the
wrapped **negative** number — §3.1 cannot help because the target column is
not `UInt64`.

Rule: whenever the mapping declares `Int64` for an `INT64` field that carries
no `__debezium.source.column.type` parameter, it logs **one ERROR per table**
(static, per JVM, keyed by the table label) naming the column(s) and the
remedy: set `column.propagate.source.type=.*` on the source connector, or
declare the ClickHouse table by hand. The connector cannot resolve the
ambiguity itself (there is no source metadata to read in Kafka mode), so the
loud report is the fallback of Invariant I9, not a substitute for the fix.
The `ADD COLUMN` path (`ClickHouseAlterTable.alterTable`) passes the table
name through so the report is attributable there too.

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Prevents signed/unsigned overflow or truncation.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.getClickHouseDataType()` — signed MySQL
  integer types map to `Int8`/`Int16`/`Int32`/`Int64`.
- `ClickHouseTableOperationsBaseUntypedInt64Test.untypedInt64LogsOneErrorPerTable()`
  — §3.3: two mappings of the same table with an `INT64` field lacking the
  source type produce exactly one ERROR naming the column and
  `column.propagate.source.type`; a field carrying the parameter, and a
  different table, are reported separately / not at all (pre-fix code logs
  nothing).
- `ClickHouseDataTypeMapperTest.getUnsignedClickHouseType()` — `TINYINT` /
  `SMALLINT` / `MEDIUMINT` / `INT` / `BIGINT UNSIGNED` map to `UInt8` /
  `UInt16` / `UInt32` / `UInt32` / `UInt64`; display width and `ZEROFILL`
  are tolerated; signed types are not remapped.
- `ClickHouseDataTypeMapperInt8Test.int8ByteIsBoundToInt8Column()`,
  `ClickHouseDataTypeMapperInt8Test.int8ByteIsBoundToUInt8Column()`,
  `ClickHouseDataTypeMapperInt8Test.negativeInt8KeepsItsSign()` — a
  `TINYINT` arrives as `INT8` and is bound without sign loss or truncation.
- `ClickHouseDataTypeMapperUInt64Test.testWrappedUnsignedBigintIsRestoredForUInt64Target()`
  — `-1L` → `18446744073709551615`, `Long.MIN_VALUE` → `9223372036854775808`
  (pre-fix code binds the negative `long`).
- `ClickHouseDataTypeMapperUInt64Test.testNegativeLongForSignedInt64TargetIsUnchanged()`
  — a real negative `BIGINT` stays negative.
- §3.2: `MySqlDDLParserListenerImplTest.testUnsignedSynonymsAndZerofillMapToUInt()`
  — `BIGINT UNSIGNED ZEROFILL`, `INT8 UNSIGNED`, `INT(10) UNSIGNED ZEROFILL`,
  `INT ZEROFILL`, `INT1 UNSIGNED`, `MIDDLEINT UNSIGNED`, `SMALLINT(5) ZEROFILL`
  map to the `UInt` types; `INT1` maps to `Int8` (pre-fix code emits the signed
  types); `MySqlDDLParserListenerImplTest.testSerialIsUnsignedNotNullKey()` —
  `SERIAL` is `UInt64 NOT NULL` and the sorting key on CREATE, `UInt64` (not
  `Nullable`) on `ADD COLUMN`.
