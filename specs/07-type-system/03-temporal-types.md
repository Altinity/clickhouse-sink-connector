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
| `TIME` | `String` | Signed duration, `-838:59:59.000000` .. `838:59:59.000000` (see §3.2) |
| `YEAR` | `UInt16` / `Int32` | 4-digit calendar year |

### 3.1 Timezone Normalization
- Configured via `database.connectionTimeZone` (source zone) and
  `clickhouse.datetime.timezone` (ClickHouse session zone; when empty, the
  server's `SELECT timezone()`).
- Prevents 1-hour shifts during daylight saving transitions when converting timestamps across UTC and local timezones.

#### 3.1.1 `DATETIME` digits are decoded exactly the way Debezium encoded them
MySQL `DATETIME` is zone-less wall-clock digits. Debezium delivers
`DATETIME(0..3)` as `io.debezium.time.Timestamp` and `DATETIME(4..6)` as
`io.debezium.time.MicroTimestamp`: the digits interpreted **as if UTC** and
encoded as an epoch (`LocalDateTime.toInstant(ZoneOffset.UTC)`). The digits are
the value MySQL holds, so the faithful decode is the inverse operation —
format the epoch in UTC — and nothing else.

When the source zone equals the ClickHouse session zone (the "same zone"
configuration, and the default — §3.1.2), both `TimestampConverter.convert` and
`MicroTimestampConverter.convert` therefore take that shortcut. It is not an
optimisation: it is the only decode that survives DST. Routing the digits
through a real DST zone (`TimeZone.getRawOffset` / `inDaylightTime`, the
previous `TimestampConverter` code) shifted every wall time that falls in the
spring-forward gap: `2026-03-08 02:30:00` in `America/Chicago` came out as
`2026-03-08 01:30:00`, silently, with row counts intact.
`MicroTimestampConverter` already had the shortcut; `TimestampConverter` did not.

When the source zone differs from the session zone (an explicit
`database.connectionTimeZone` that is not the session zone), the digits are
interpreted as a wall time in the source zone and converted to an instant;
a wall time inside a spring-forward gap uses the offset **before** the
transition (`ZoneRules.getTransition(...).getOffsetBefore()`), the same rule
`MicroTimestampConverter` applies, so the two converters never disagree on the
same digits.

### 3.2 `TIME` is a signed duration, not a time of day
MySQL `TIME` ranges from `-838:59:59` to `838:59:59` (it stores elapsed time and
differences, not only clock time). Debezium delivers it as
`io.debezium.time.MicroTime` — an `INT64` holding the **signed** total in
microseconds, so `-01:00:00` arrives as `-3 600 000 000` and `25:30:00` as
`91 800 000 000`.

`DebeziumConverter.MicroTimeConverter.convert(Object)` therefore formats the
signed total directly:
```
sign · hours (unbounded, at least 2 digits) : mm : ss . ffffff
```
e.g. `-3_600_000_000L -> "-01:00:00.000000"`, `91_800_000_000L -> "25:30:00.000000"`,
`0L -> "00:00:00.000000"`. It must **not** be reduced modulo 24 h through a
`java.time.LocalTime`: that mapped `25:30:00` to `01:30:00` and every negative
value to a wrapped positive one, silently, with row counts intact.

The ClickHouse target column type for `TIME` is `String` (that is what
`ClickHouseDataTypeMapper.dataTypesMap` assigns to `MicroTime`, and what the DDL
translator emits). A `String` column stores the formatted text verbatim
(verified with `clickhouse local`: `'-01:00:00.000000'` and `'25:30:00.000000'`
round-trip unchanged), so the full MySQL range is representable; no ClickHouse
`Time`-like type is involved.

---

---

## 4. Invariants Preserved
- **Temporal Fidelity**: Historical and real-time timestamps round-trip without date boundary slippage.

---

## 5. Verification Criteria
- `DebeziumConverterTest.testTimestampConverter()`,
  `DebeziumConverterTest.testTimestampConverterMinRange()`,
  `DebeziumConverterTest.testTimestampConverterMaxRange()` — `DATETIME`
  round-trips at and beyond the ClickHouse `DateTime64` bounds are clamped,
  never wrapped.
- `DebeziumConverterTest.testDateConverterMinRange()`,
  `DebeziumConverterTest.testDateConverterMaxRange()`,
  `DebeziumConverterTest.testDateConverterWithinRange()` — `DATE` boundaries.
- `DebeziumConverterTest.testMicroTimestampConverterMin()`,
  `DebeziumConverterTest.testMicroTimestampConverterMax()` — microsecond
  `DATETIME(6)` boundaries.
- `DebeziumConverterTest.testZonedTimestampConverter()` — `TIMESTAMP` is
  normalised through the configured zone without date slippage.
- `DebeziumConverterTest.testMicroTimeConverterSignedAndBeyond24Hours()` —
  `convert(-3_600_000_000L) == "-01:00:00.000000"` and
  `convert(91_800_000_000L) == "25:30:00.000000"` (pre-fix code returns
  `"23:00:00.000000"` and `"01:30:00.000000"`).
- `DebeziumConverterTest.testMicroTimeConverter()` — an ordinary time of day
  (`09:01:01`) is unchanged by the fix.
- `DebeziumConverterTest.testTimestampConverterGapTimePreserved()` — §3.1.1:
  `DATETIME` digits `2026-03-08 02:30:00` (inside the `America/Chicago`
  spring-forward gap) and `2026-11-01 01:30:00` (the repeated fall-back hour)
  come back unchanged when source and session zones are both
  `America/Chicago`; pre-fix `TimestampConverter` returned `01:30:00` for the
  gap time. With source `America/Chicago` and session `UTC` the gap digits use
  the pre-transition offset (`08:30:00` UTC), matching `MicroTimestampConverter`.
