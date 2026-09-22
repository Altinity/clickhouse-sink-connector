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
| `DATETIME` | `DateTime64(p, 'UTC')` (auto-create, §3.1.3) | Zone-less digits; decoded as Debezium encoded them (§3.1.1); shifted only when an explicit source zone differs from the session zone (§3.1.2) |
| `TIMESTAMP` | `DateTime64(6, 'UTC')` (auto-create) | An instant (Debezium `ZonedTimestamp`, ISO-8601 in UTC); formatted in the **column's declared zone**, else the session zone (§3.1.3) |
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

#### 3.1.2 An empty `database.connectionTimeZone` means "the session zone", never "UTC"
The two zone settings resolved asymmetrically: an empty
`clickhouse.datetime.timezone` fell back to the ClickHouse server zone
(`SELECT timezone()`), but an empty `database.connectionTimeZone` was taken as
`UTC` (`ClickHouseDataTypeMapper.convert`). On a server whose zone is
`America/Chicago`, the default configuration therefore ran the
"different zones" branch of §3.1.1 with a source zone nobody configured:
`DATETIME '2022-01-01 10:00:00'` was interpreted as a UTC wall time and
written as `04:00:00` — every DATETIME off by the server offset, silently.

Rule (`ClickHouseDataTypeMapper.resolveSourceTimeZone(config, sessionZone)`):
- `database.connectionTimeZone` set → that zone (an unparseable value throws,
  which fails the batch loudly rather than guessing).
- empty → **the ClickHouse session zone**, i.e. the same-zone decode of
  §3.1.1: the digits MySQL holds are the digits stored. This is the only
  default that needs no knowledge of the MySQL server: a DATETIME carries no
  zone, so with no declared source zone there is no basis for a shift. The
  resolution is logged once at INFO naming both zones.
- A wall-clock shift of DATETIME values happens **only** when the operator
  declares a source zone that differs from the session zone; that intent is
  then visible in the configuration.

#### 3.1.3 Values are formatted in the COLUMN's declared zone
ClickHouse parses a `DateTime`/`DateTime64` literal in the **column's** zone
(`DateTime64(3, 'UTC')` parses `'10:00:00'` as 10:00 UTC; measured with
`clickhouse local` 24.8.14: `toString(d, 'America/Chicago')` then reads
`04:00:00`). The auto-create path (Spec 08.05) declares `DateTime64(p, 'UTC')`
columns and the DDL path declares `DateTime64(p, '<clickhouse.datetime.timezone>')`,
while the converters formatted every instant in the session zone. Whenever the
column zone differed from the session zone the stored instant was off by the
difference: a `TIMESTAMP` of 16:00 UTC written to a `DateTime64(6, 'UTC')`
column through an `America/Chicago` session was stored as 10:00 UTC.

Rule: `PreparedStatementFieldMapper` resolves the zone declared in the target
column type (`ClickHouseColumn.of(name, type).getTimeZone()`, null when the type
declares none — `ClickHouseDataTypeMapper.columnTimeZone`) and passes it to
`ClickHouseDataTypeMapper.convert`; the converters format an **instant**
(`ZonedTimestampConverter`, and the different-zones branch of
`TimestampConverter` / `MicroTimestampConverter`) in that zone, falling back to
the session zone when the column declares none. The same-zone digits decode
of §3.1.1 is unaffected — digits are digits in any column zone — which is
also why DATETIME columns are auto-created in `'UTC'`: a DST zone cannot hold
a spring-forward gap wall time (`clickhouse local`: `'2026-03-08 02:30:00'`
into `DateTime64(3, 'America/Chicago')` reads back `01:30:00`; into
`DateTime64(3, 'UTC')` it reads back `02:30:00`).

Residual: the DDL translator (Spec 06.04, not changed here) declares DATETIME
columns in the configured session zone, so under a DST session zone a
DATETIME gap time replicated through a DDL-created column is still stored
shifted by ClickHouse itself; declaring `'UTC'` there as well is the
outstanding fix.

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

The same rule binds the DDL-string mapping
`ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL` (used by the PostgreSQL
schema reconciler for `ADD COLUMN`): `MicroTime` is declared
`Nullable(String)`. It previously declared `Nullable(Int64)` ("microseconds
since midnight") while the value path bound the formatted text, so every
insert into such a reconciled column failed with a parse error — the declared
type and the bound representation must agree.

### 3.3 Out-of-range values are never silently saturated
ClickHouse temporal types are narrower than MySQL's: `DateTime64` holds
`1900-01-01 00:00:00 .. 2299-12-31 23:59:59`, `DateTime` holds
`1970-01-01 00:00:00 .. 2106-02-07 06:28:15`, `Date32` holds
`1900-01-01 .. 2299-12-31`, `Date` holds `1970-01-01 .. 2149-06-06`; MySQL
`DATE`/`DATETIME` reach `9999-12-31`. `DebeziumConverter` saturated every such
value to the ClickHouse bound with **no log line at all**
(`checkIfDateTimeExceedsSupportedRange`, `checkIfDateExceedsSupportedRange`, the
`ZonedTimestampConverter` bound check), and `BigDecimalConverter.truncate`
saturated a PostgreSQL variable-scale decimal with a WARN that named neither
table nor column. A MySQL `DATETIME '9999-12-31 23:59:59'` (the customary
"open-ended" sentinel) therefore landed as `2299-12-31 23:59:59` with the batch
reported successful — a value MySQL never held, and a WARN-less one.

Rule — `DebeziumConverter.RangePolicy`, built per bound column by
`PreparedStatementFieldMapper` (`RangePolicy.of(config, "db.table.column")`)
and threaded through `ClickHouseDataTypeMapper.convert` into every converter
that bounds a value (`TimestampConverter`, `MicroTimestampConverter`,
`DateConverter`, `ZonedTimestampConverter`, `BigDecimalConverter`):
1. `clamp.out.of.range=false` (**default**): the converter throws
   `DebeziumConverter.ValueOutOfRangeException` naming the column, the source
   value, the ClickHouse type and its bounds, and the setting that would
   saturate instead. The batch fails and is retried; nothing is written.
   Remediation is on the ClickHouse side (a wider type — `Date32` for `Date`,
   `DateTime64` for `DateTime` — or a `String` column) or an explicit
   operator decision to saturate.
2. `clamp.out.of.range=true`: the value is saturated to the bound as before,
   and every saturation logs a WARN naming the column, the source value and
   the stored value. Never silent.
3. The pre-existing four-argument converter overloads (no policy) keep the
   saturating behaviour **with** the WARN, for callers that have no
   configuration; every production bind path passes a policy built from the
   configuration.
4. PostgreSQL `timestamptz` `infinity` / `-infinity` (issue #1231) are not
   out-of-range numbers but values with no finite representation; they keep
   their documented saturation to the `DateTime64` bounds under either policy
   (ordering is preserved, and there is no source value to lose).

Upgrade note: a deployment whose source holds sentinel dates beyond the
ClickHouse range will, after upgrading, fail the affected batch instead of
storing the bound. Set `clamp.out.of.range=true` to restore the previous
values (now with a WARN per saturated value) until the column type is fixed.

### 3.4 Years below 100 are not adjusted (`enable.time.adjuster=false`)
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
- `ClickHouseDataTypeMapperDDLTest.testDebeziumLogicalTypes()` (the
  `io.debezium.time.MicroTime` row) and
  `PostgresSchemaReconcilerTest.testDebeziumLogicalTypes()` — §3.2: the
  DDL-string mapping declares `MicroTime` as `Nullable(String)` (both rows
  previously asserted `Nullable(Int64)`, pinning the mismatch).
- `DebeziumConverterTest.testTimestampConverterGapTimePreserved()` — §3.1.1:
  `DATETIME` digits `2026-03-08 02:30:00` (inside the `America/Chicago`
  spring-forward gap) and `2026-11-01 01:30:00` (the repeated fall-back hour)
  come back unchanged when source and session zones are both
  `America/Chicago`; pre-fix `TimestampConverter` returned `01:30:00` for the
  gap time. With source `America/Chicago` and session `UTC` the gap digits use
  the pre-transition offset (`08:30:00` UTC), matching `MicroTimestampConverter`.
- `PreparedStatementFieldMapperColumnZoneTest.testTimestampIntoUtcColumnWhenSessionZoneIsChicago()`
  — §3.1.3 end to end through `insertPreparedStatement`: `ZonedTimestamp`
  `2022-01-01T16:00:00Z` into a `Nullable(DateTime64(6, 'UTC'))` column with
  session zone `America/Chicago` binds `2022-01-01 16:00:00.000000` (pre-fix:
  `10:00:00.000000`).
- `PreparedStatementFieldMapperColumnZoneTest.testEmptySourceZoneKeepsDatetimeDigits()`
  — §3.1.2: with `database.connectionTimeZone` empty and session zone
  `America/Chicago`, DATETIME(3) and DATETIME(6) digits `10:00:00` are bound
  unchanged (pre-fix: `04:00:00`).
- `PreparedStatementFieldMapperColumnZoneTest.testExplicitSourceZoneShiftsIntoTheColumnZone()`
  — §3.1.2/§3.1.3: `database.connectionTimeZone=UTC`, session
  `America/Chicago`: digits `10:00:00` into a `'UTC'` column bind `10:00:00`
  (the instant 10:00Z rendered in the column zone; pre-fix `04:00:00`), and
  into a column without a declared zone bind `04:00:00` (the session zone —
  the operator asked for the shift).
- `DebeziumConverterTest.testTimestampIntoUtcColumnWhenServerZoneIsChicago()`
  — the converter-level contract of §3.1.3 for `TimestampConverter`,
  `MicroTimestampConverter` and `ZonedTimestampConverter`.
- `ClickHouseDataTypeMapperTimeZoneTest` — `resolveSourceTimeZone` (empty →
  session zone, set → parsed, garbage → throws) and `columnTimeZone` (parses
  `Nullable(DateTime64(3, 'UTC'))`, `DateTime('Europe/London')`; null for
  `DateTime64(6)` and `String`).
- `DebeziumConverterRangePolicyTest.defaultPolicyRejectsOutOfRangeDatetimeAtTheMapper()`,
  `DebeziumConverterRangePolicyTest.defaultPolicyRejectsOutOfRangeDateAtTheMapper()`
  — §3.3 rule 1 through `ClickHouseDataTypeMapper.convert` with an empty
  configuration: `DATETIME 9999-12-31 23:59:59` into `DateTime64` and
  `DATE 9999-12-31` into `Date` throw `ValueOutOfRangeException` (pre-fix
  code binds `2299-12-31 23:59:59.000` / `2149-06-06`).
- `DebeziumConverterRangePolicyTest.clampSettingSaturatesAndWarns()` — rule 2:
  `clamp.out.of.range=true` binds the bound and logs a WARN naming the column
  and both values.
- `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()` —
  the exception text of rule 1 for `DateTime`, `DateTime64`, `Date`, `Date32`,
  `ZonedTimestamp` and decimal.
- `DebeziumConverterRangePolicyTest.legacyOverloadsStillSaturateWithAWarn()` —
  rule 3.
- `DebeziumConverterRangePolicyTest.infinityKeepsItsSaturation()` — rule 4.
- `PreparedStatementFieldMapperOutOfRangeTest.outOfRangeValueNamesDatabaseTableAndColumn()`
  — end to end through `insertPreparedStatement`: the exception names
  `db.orders.expires_at`; with `clamp.out.of.range=true` the same row binds
  the bound.
- The pre-existing `testTimestampConverterMinRange` / `MaxRange`,
  `testDateConverterMinRange` / `MaxRange`, `testMicroTimestampConverterMin` /
  `Max`, `testZonedTimestampConverter` tests exercise the four-argument
  overloads and therefore pin the saturating (rule 3) behaviour.
- `TimeAdjusterDefaultTest.absentIsForcedToFalse()`,
  `TimeAdjusterDefaultTest.blankIsForcedToFalse()` — §3.4: absent/blank is
  forced to `false`; `TimeAdjusterDefaultTest.explicitValueWins()` — an
  explicit `true` is left alone; `TimeAdjusterDefaultTest.nullIsTolerated()`.
