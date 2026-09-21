# Spec 07.03: Temporal Data Types & Timezone Handling

## 1. Executive Summary & Purpose
Specifies the translation and timezone adjustment of MySQL date and time types to ClickHouse temporal representations.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **Debezium property defaults (lightweight)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `ensureTimeAdjusterDisabled(Properties)`, `ENABLE_TIME_ADJUSTER`, called from `setupDebeziumEventCapture` next to the `column.propagate.source.type` default

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
- Configured via `source.timezone` and `clickhouse.timezone`.
- Prevents 1-hour shifts during daylight saving transitions when converting timestamps across UTC and local timezones.

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

### 3.3 Years below 100 are not adjusted (`enable.time.adjuster=false`)
Debezium's `enable.time.adjuster` defaults to `true`: a two-digit year — and,
on the MySQL connector, any year below 100 — is remapped into 1970–2069, so a
source `DATE`/`DATETIME` of `0001-01-01` arrives as `2001-01-01`. That is a
value-level divergence with row counts intact, decided by the connector on the
source's behalf, which the prime directive forbids. Before this revision only
the Ansible deployment template set the property to `false`; the JAR's bundled
defaults, the Docker configurations and every hand-written configuration ran
with the adjuster on.

Rule: `DebeziumChangeEventCapture.setupDebeziumEventCapture` calls
`ensureTimeAdjusterDisabled(props)` next to the `column.propagate.source.type`
default. When `enable.time.adjuster` is absent or blank it is set to `false`
(INFO). An explicit value is the operator's call and is left alone; an explicit
`true` is logged at WARN because it re-enables the remap.

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
- `TimeAdjusterDefaultTest.absentIsForcedToFalse()`,
  `TimeAdjusterDefaultTest.blankIsForcedToFalse()` — §3.3: absent/blank is
  forced to `false`; `TimeAdjusterDefaultTest.explicitValueWins()` — an
  explicit `true` is left alone; `TimeAdjusterDefaultTest.nullIsTolerated()`.
