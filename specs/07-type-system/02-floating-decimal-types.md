# Spec 07.02: Floating Point & Fixed-Precision Decimal Types

## 1. Executive Summary & Purpose
Specifies the preservation of exact scales, precisions, and IEEE 754 representations for floating-point and decimal values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

---

## 3. Operational Specification

### 3.1 IEEE 754 Floating Point
- `FLOAT` $\to$ `Float32` (`java.lang.Float`)
- `DOUBLE` $\to$ `Float64` (`java.lang.Double`)

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
- `ClickHouseDataTypeMapperTest.testDecimalScaling()`
- `DataTypesIT.testDecimalPrecision()`
