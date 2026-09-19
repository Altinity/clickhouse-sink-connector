# Spec 07.03: Temporal Data Types & Timezone Handling

## 1. Executive Summary & Purpose
Specifies the translation and timezone adjustment of MySQL date and time types to ClickHouse temporal representations.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

---

## 3. Translation Matrix & Timezone Semantics

| MySQL Type | ClickHouse Target | Resolution & Conversion |
|---|---|---|
| `DATE` | `Date` / `Date32` | Converted from epoch days to `java.time.LocalDate` |
| `DATETIME` | `DateTime64(3)` / `DateTime64(6)` | Microsecond/millisecond resolution; interpreted in source session timezone |
| `TIMESTAMP` | `DateTime` / `DateTime64(3)` | Stored in UTC; converted to ClickHouse target timezone |
| `TIME` | `Int64` / `String` | Microseconds since midnight ($0$ to $86{,}400{,}000{,}000$) |
| `YEAR` | `UInt16` / `Int32` | 4-digit calendar year |

### 3.1 Timezone Normalization
- Configured via `source.timezone` and `clickhouse.timezone`.
- Prevents 1-hour shifts during daylight saving transitions when converting timestamps across UTC and local timezones.

---

## 4. Invariants Preserved
- **Temporal Fidelity**: Historical and real-time timestamps round-trip without date boundary slippage.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.testTemporalConversions()`
