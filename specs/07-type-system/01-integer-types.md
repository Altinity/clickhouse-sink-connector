# Spec 07.01: Integer Data Type Mapping & Range Rules

## 1. Executive Summary & Purpose
Specifies the conversion of signed and unsigned MySQL integer types to ClickHouse integer primitives without sign inversion or overflow.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

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

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Prevents signed/unsigned overflow or truncation.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.testIntegerConversions()`
- `ClickHouseDataTypeMapperUInt64Test.testWrappedUnsignedBigintIsRestoredForUInt64Target()`
  — `-1L` → `18446744073709551615`, `Long.MIN_VALUE` → `9223372036854775808`
  (pre-fix code binds the negative `long`).
- `ClickHouseDataTypeMapperUInt64Test.testNegativeLongForSignedInt64TargetIsUnchanged()`
  — a real negative `BIGINT` stays negative.
