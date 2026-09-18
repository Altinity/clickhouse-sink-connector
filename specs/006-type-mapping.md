# Specification 006: Comprehensive Data Type Mapping & Conversion

## 1. Executive Summary & Purpose

The type mapping subsystem (`ClickHouseDataTypeMapper` and `PreparedStatementFieldMapper`) defines the bidirectional translation matrix between MySQL data types (represented via Kafka Connect / Debezium schemas) and native ClickHouse columnar types. It enforces value fidelity, prevents truncation, preserves precision and nullability, and handles platform-specific representations such as bit endianness and spatial WKB encodings.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.PreparedStatementFieldMapper` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.converters.DebeziumToClickHouseTypeConverter` (`sink-connector/...`)
- **Key Methods**:
  - `ClickHouseDataTypeMapper.getClickHouseDataType()`
  - `ClickHouseDataTypeMapper.convert()`
  - `PreparedStatementFieldMapper.insertPreparedStatement()`

---

## 3. Comprehensive Data Type Translation Matrix

| MySQL Source Type | Debezium / Kafka Connect Schema | ClickHouse Target Type | Conversion & Binding Semantics |
|---|---|---|---|
| `TINYINT` | `INT8` | `Int8` | Standard byte conversion (`Number.intValue()`) |
| `TINYINT UNSIGNED` | `INT16` | `UInt8` | Zero-extended unsigned 8-bit integer |
| `TINYINT(1)` (Boolean) | `INT8` / `BOOLEAN` | `Bool` / `UInt8` | Mapped to `Bool` or `UInt8` based on configuration |
| `SMALLINT` | `INT16` | `Int16` | 16-bit signed integer |
| `SMALLINT UNSIGNED` | `INT32` | `UInt16` | Zero-extended unsigned 16-bit integer |
| `MEDIUMINT` | `INT32` | `Int32` | 32-bit signed integer |
| `MEDIUMINT UNSIGNED` | `INT32` | `UInt32` | 32-bit unsigned integer |
| `INT` / `INTEGER` | `INT32` | `Int32` | Standard 32-bit integer |
| `INT UNSIGNED` | `INT64` | `UInt32` | Bound via `Long` into `UInt32` |
| `BIGINT` | `INT64` | `Int64` | 64-bit signed integer |
| `BIGINT UNSIGNED` | `BYTES` / `ZonedDecimal` | `UInt64` | Converted to `BigInteger` / `UInt64` |
| `FLOAT` | `FLOAT32` | `Float32` | 32-bit IEEE 754 floating point |
| `DOUBLE` | `FLOAT64` | `Float64` | 64-bit IEEE 754 floating point |
| `DECIMAL(P, S)` | `BYTES` / `Decimal` | `Decimal(P, S)` | Exact `BigDecimal` binding without float conversion |
| `CHAR(N)` | `STRING` | `String` / `FixedString(N)` | UTF-8 encoded string |
| `VARCHAR(N)` | `STRING` | `String` | Dynamic length string |
| `TINYTEXT` / `TEXT` | `STRING` | `String` | Unbounded text string |
| `MEDIUMTEXT` / `LONGTEXT` | `STRING` | `String` | Unbounded text string |
| `JSON` | `STRING` | `String` / `JSON` | Stored as JSON string or native `JSON` type |
| `ENUM(...)` | `STRING` | `String` / `Enum8` / `Enum16` | String literal representation |
| `SET(...)` | `STRING` | `String` / `Array(String)` | Comma-delimited string or array |
| `DATE` | `INT32` (epoch days) | `Date` / `Date32` | Converted from epoch days to `LocalDate` |
| `DATETIME` | `INT64` (epoch millis/micros) | `DateTime64(3)` / `DateTime64(6)` | Converted to timestamp with microsecond resolution |
| `TIMESTAMP` | `STRING` / `INT64` | `DateTime` / `DateTime64(3)` | Timezone-adjusted to server session timezone |
| `TIME` | `INT64` (microseconds) | `Int64` / `String` | Microseconds since midnight or duration string |
| `YEAR` | `INT32` | `Int32` / `UInt16` | 4-digit calendar year |
| `BIT(N)` | `BYTES` / `io.debezium.data.Bits` | `UInt64` / `String` | **Byte-order reversed** to match ClickHouse bit ordering |
| `BINARY` / `VARBINARY` | `BYTES` | `String` / `FixedString` | Raw byte sequence |
| `BLOB` / `LONGBLOB` | `BYTES` | `String` | Binary byte array |
| `GEOMETRY` / `POINT` | `STRUCT` (WKB) | `Point` / `Geometry` | JTS `WKBReader` parses WKB and binds native Geo types |

---

## 4. Nullability & Value Preservation Rules

1. **Nullable Wrapping**:
   Unless a MySQL column is defined as `NOT NULL`, its ClickHouse counterpart is created as `Nullable(type)`.
2. **Explicit NULL Binding**:
   When MySQL sends a field containing `null`:
   - The field **must** be included in the ClickHouse `INSERT` column list.
   - The parameter **must** be explicitly bound with `ps.setNull(index, type)`.
   - **Prohibition**: A `null` value must **never** be dropped from the statement to allow ClickHouse column defaults (e.g. `0`, `''`, `1970-01-01`) to substitute for source `NULL`.
3. **Bit Endianness Handling**:
   Debezium transmits `BIT` values as little-endian byte arrays. ClickHouse expects big-endian bit layouts. `ClickHouseDataTypeMapper` reverses the byte array before binding to prevent bit transposition.

---

## 5. Temporal Timezone Conversion

- MySQL `TIMESTAMP` values are stored in UTC and converted to/from session timezones.
- The connector converts temporal records using configured `source.timezone` and target ClickHouse timezone parameters to ensure that timestamps do not drift across daylight saving boundaries.

---

## 6. Invariants Preserved

1. **Value Fidelity & No Truncation (Invariant I7)**:
   All numeric precisions, decimal scales, temporal units, and string encodings must preserve identical mathematical and textual values across MySQL and ClickHouse.
2. **Explicit Nullability Invariant**:
   `NULL` values in MySQL must be stored as `NULL` in ClickHouse. Source `NULL`s are never mapped to empty strings or default zeros.

---

## 7. Verification Criteria

- **Unit Tests**:
  `ClickHouseDataTypeMapperTest`, `PreparedStatementFieldMapperTest`.
- **Integration Tests**:
  `DataTypesIT`, verifying round-trip fidelity for all numeric, string, decimal, temporal, and spatial types.
- **Formal Verification**:
  Corresponds to `Replication.Basic.Value` and type preservation invariants in `formal_specs/lean/`.
