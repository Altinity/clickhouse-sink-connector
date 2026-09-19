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
| `BIGINT UNSIGNED` | `BYTES` / `Decimal` | `UInt64` | Bound via `BigInteger` to prevent sign inversion |

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Prevents signed/unsigned overflow or truncation.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.testIntegerConversions()`
