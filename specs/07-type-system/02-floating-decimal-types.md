# Spec 07.02: Floating Point & Fixed-Precision Decimal Types

## 1. Executive Summary & Purpose
Specifies the preservation of exact scales, precisions, and IEEE 754 representations for floating-point and decimal values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`)

---

## 3. Operational Specification

### 3.1 IEEE 754 Floating Point
Value path (`ClickHouseDataTypeMapper`, Kafka schema → bind):
- `FLOAT32` schema $\to$ `Float32` (`java.lang.Float`)
- `FLOAT64` schema $\to$ `Float64` (`java.lang.Double`)

DDL path (`DataTypeConverter.convertToString`, declared MySQL type → column
type). Debezium 3.1.3 resolves MySQL `FLOAT` / `FLOAT4` to `Types.FLOAT` and
`DOUBLE` / `FLOAT8` / `DOUBLE PRECISION` to `Types.DOUBLE`, and maps **both** to
a `float64` Kafka schema; only `REAL` (`Types.REAL`) is narrowed to `float32`
before any connector code runs. The column types follow what is delivered:
- `FLOAT`, `FLOAT(p)`, `FLOAT(M,D)`, `DOUBLE`, `DOUBLE(M,D)` $\to$ `Float64`
  (a `Float64` column holds every single-precision value exactly; the earlier
  text of this section said `FLOAT` → `Float32`, which the DDL path has never
  emitted).
- `REAL` $\to$ `Float32` (the value arrives already narrowed; declaring
  `Float64` would only make truncated data look full-precision).
- The declared dimensions of a floating type are **never** copied to the
  ClickHouse type: `FLOAT(7,3)` used to become `Float64(7,3)`, which
  ClickHouse rejects (`Code: 36`/`70`), so the CREATE or ALTER failed on every
  retry and stalled the stream. A `(precision[, scale])` suffix is emitted only
  for `Decimal` and for `DateTime64`, the two ClickHouse types that take one.

Value-comparison caveat: mapping `FLOAT` to `Float64` is a **widening**. The
4-byte value is stored exactly (a `float` is exactly representable as a
`double`), but ClickHouse renders it with double precision — MySQL shows `1.1`
for a `FLOAT`, ClickHouse shows `1.100000023841858` for the same bits. A
value-level comparison must therefore compare `toFloat32(col)` on ClickHouse,
or render both sides with the same shortest-repr algorithm, rather than
comparing default string renderings (see `DataTypeConverter` and
`DataTypeConverterTest`).

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
- `MySqlDDLParserListenerImplTest.testFloatWithDimensionsIsFloat64()` — DDL path: `FLOAT(7,3)`, `FLOAT`, `DOUBLE(10,2)` → `Float64`, `REAL` → `Float32`, `DECIMAL(7,3)` keeps `Decimal(7,3)` (pre-fix code emits `Float64(7,3)`).
- Verification: a unit test asserting `setBigDecimal` binding without float conversion (§3.2 value path) is not yet covered by an automated test (gap).
