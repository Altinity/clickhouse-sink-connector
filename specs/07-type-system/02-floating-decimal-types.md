# Spec 07.02: Floating Point & Fixed-Precision Decimal Types

## 1. Executive Summary & Purpose
Specifies the preservation of exact scales, precisions, and IEEE 754 representations for floating-point and decimal values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

---

## 3. Operational Specification

### 3.1 IEEE 754 Floating Point
- `DOUBLE` / `DOUBLE PRECISION` / `FLOAT8` $\to$ `Float64` (`java.lang.Double`).
- `FLOAT` / `FLOAT4` $\to$ `Float64` as well. Debezium's MySQL connector
  delivers MySQL `FLOAT` as a `FLOAT64` Kafka schema (`java.lang.Double`;
  `io.debezium.jdbc.JdbcValueConverters` maps `Types.FLOAT` to `float64`), so
  both the record-schema auto-create path (`FLOAT64` → `Float64`) and the DDL
  path (`DataTypeConverter`) declare `Float64`. This is a **widening**: the
  4-byte value is stored exactly (a `float` is exactly representable as a
  `double`), but ClickHouse renders it with double precision — MySQL shows
  `1.1` for a `FLOAT`, ClickHouse shows `1.100000023841858` for the same
  bits. A value-level comparison must therefore compare `toFloat32(col)` on
  ClickHouse, or render both sides with the same shortest-repr algorithm,
  rather than comparing default string renderings.
- `REAL` $\to$ `Float32` on the DDL path only: Debezium resolves `REAL` to
  `Types.REAL` → `float32`, so the value is already narrowed when it arrives
  (see `DataTypeConverter` and `DataTypeConverterTest`).

### 3.2 Fixed-Point Decimals (`DECIMAL(P, S)`)
- Debezium encodes `DECIMAL` values as scaled binary byte arrays or `BigDecimal` objects.
- `ClickHouseDataTypeMapper` binds values directly via `ps.setBigDecimal(index, (BigDecimal) value)`:
  - **No Float Conversion**: The value is never converted to `double` or `float` in memory, completely eliminating binary floating-point rounding errors.
  - Scale $S$ and precision $P$ are mapped directly into ClickHouse `Decimal(P, S)` / `Decimal32` / `Decimal64` / `Decimal128`.

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Financial and mathematical decimal values preserve exact penny and fractional precision.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperFloat64Test.float64MapsToFloat64()`, `ClickHouseDataTypeMapperFloat64Test.float32StillMapsToFloat32()`, `ClickHouseDataTypeMapperFloat64Test.float32ColumnCannotRepresentADoubleValue()` — §3.1.
- `CreateTableDataTypesIT` — auto-created `Decimal(65, 30)` columns for MySQL `DECIMAL` (type mapping, end to end).
- Verification: a unit test asserting `setBigDecimal` binding without float conversion (§3.2 value path) is not yet covered by an automated test (gap).
