# Spec 07.03: Temporal Data Types & Timezone Handling

## 1. Executive Summary & Purpose
Specifies the translation and timezone adjustment of MySQL date and time types to ClickHouse temporal representations.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **Session-zone preflight (lightweight)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ConnectionTimeZonePreflight.java` — `ConnectionTimeZonePreflight.check(Properties)` (§3.1.5): with `database.connectionTimeZone` unset, reads `@@GLOBAL.time_zone` / `@@GLOBAL.system_time_zone` and resolves the effective session zone with the JDBC driver's own `com.mysql.cj.util.TimeUtil.getCanonicalTimeZone`, called by reflection through `MySqlJdbcDriver.canonicalTimeZone` (the GPL driver is supplied at run time and no project source links against it; spec 01.01 §3.2.1); refuses at start when the driver cannot map it to one zone.
- **Converters**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java` — `TimestampConverter`, `MicroTimestampConverter`, `ZonedTimestampConverter`, `epochText(Instant)`, `bindsAsEpochText(ClickHouseDataType, Instant)` (§3.1.4)
- **Debezium property defaults (lightweight)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `ensureTimeAdjusterDisabled(Properties)`, `ENABLE_TIME_ADJUSTER`, called from `setupDebeziumEventCapture` next to the `column.propagate.source.type` default

---

## 3. Translation Matrix & Timezone Semantics

| MySQL Type | ClickHouse Target | Resolution & Conversion |
|---|---|---|
| `DATE` | `Date` / `Date32` | Converted from epoch days to `java.time.LocalDate` |
| `DATETIME` | `DateTime64(p, 'UTC')` (auto-create, §3.1.3) | Zone-less digits; decoded as Debezium encoded them (§3.1.1); shifted only when an explicit source zone differs from the session zone (§3.1.2) |
| `TIMESTAMP` | `DateTime64(6, 'UTC')` (auto-create) | An instant (Debezium `ZonedTimestamp`, ISO-8601 in UTC); bound as **epoch text** into a `DateTime64` column (§3.1.4); formatted in the column's declared zone, else the session zone, only for a `DateTime`/`String` column (§3.1.3) |
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

#### 3.1.4 An instant bound into a `DateTime64` column is epoch text, not local digits
Rendering an instant as wall-clock digits in the column's zone (§3.1.3) is
exact only where that zone maps every wall time to one instant. In the
fall-back overlap hour it does not: two instants share the same digits, and
ClickHouse resolves the digits to the **first** occurrence. Measured with
`clickhouse local` 24.8.14 into `DateTime64(6, 'America/Chicago')`:
`'2026-11-01 01:30:00.000000'` reads back as `06:30:00 UTC` (CDT), so the
instant `07:30:00 UTC` (CST, one hour later, the same digits) cannot be
stored through digits at all. The same measurement shows epoch text is exact:
`'1793518200.000000'` reads back as `07:30:00 UTC` and `'1793514600.000000'`
as `06:30:00 UTC`, for any column zone (`'UTC'`, `'America/Chicago'`, none,
`Nullable`), any precision (`DateTime64(0)`, `(3)`, `(6)`), and for negative
epochs (`'-2208988800.000000'` is `1900-01-01 00:00:00`). MySQL binlogs a
`TIMESTAMP` as the instant, and Debezium delivers that instant exactly
(`ZonedTimestamp`, ISO-8601 in UTC), so the digits rendering lost fidelity
that the source never lost: a session with `time_zone=SYSTEM` (glibc
`mktime`) resolves an overlap wall time to the second occurrence, the
connector rendered it as the ambiguous digits, and ClickHouse stored the first
occurrence — one hour early, row counts intact.

Rule — an instant is bound as epoch text whenever the target column is
`DateTime64`:
- `DebeziumConverter.epochText(Instant)` renders `<seconds>.<6 fraction digits>`
  (`java.math.BigDecimal`, scale 6, truncated; sub-microsecond digits are
  dropped exactly as the `MICROS_FORMAT` digits rendering dropped them).
- `ZonedTimestampConverter.convert(value, zone, ClickHouseDataType, policy)`
  parses and bounds the instant as before (§3.3; the PostgreSQL `infinity`
  literals bound to the `DateTime64` limits) and returns epoch text for a
  `DateTime64` target. `ClickHouseDataTypeMapper.convert` passes the target
  column's type, so a `TIMESTAMP` replicated into a `String` or `DateTime`
  column is still bound as digits (§3.1.3; the policy-less and type-less
  overloads keep the digits contract).
- The different-zones branch of `TimestampConverter` / `MicroTimestampConverter`
  (§3.1.1: `DATETIME` digits converted to an instant because the operator
  declared a source zone) binds that instant as epoch text for a `DateTime64`
  target, including a saturated instant (the bound is an instant too). The
  same-zone branch is unaffected: digits are not an instant, and are bound as
  digits in any column zone.
- Exclusions, each measured on 24.8.14: a `DateTime`/`DateTime32` column
  rejects epoch text (`'1793518200'` fails with `Cannot parse DateTime from
  String`), so a `DateTime` target keeps the digits rendering of §3.1.3 — the
  overlap ambiguity remains for that type and is documented here rather than
  hidden; and ClickHouse drops the sign of an epoch between `-1` and `0`
  (`'-0.500000'` reads back as `1970-01-01 00:00:00.500000`, while
  `'-1.500000'` and `'-2208988799.500000'` are exact), so the one second
  before `1970-01-01 00:00:00 UTC` is bound as digits as well
  (`DebeziumConverter.bindsAsEpochText`). MySQL `TIMESTAMP` starts at
  `1970-01-01 00:00:01 UTC` and cannot hold that second.
- The column zone resolved by §3.1.3 is therefore no longer part of the
  stored value for a `DateTime64` instant: the same instant binds the same
  text into a `'UTC'`, an `'America/Chicago'` and an unzoned column.

#### 3.1.5 The source's session zone must be one the JDBC driver can name
§3.1.2's default — an empty `database.connectionTimeZone` — only works when the driver can resolve the source's session zone. Connector/J derives it from `@@time_zone`, or from `@@system_time_zone` when `time_zone = SYSTEM`; that value is whatever the source host's C library reports, and on a host in a DST zone it is an **abbreviation** such as `CDT`, which names more than one zone. The driver then refuses every connection: `The server time zone value 'CDT' is unrecognized or represents more than one time zone. You must configure either the server or JDBC driver (via the 'connectionTimeZone' configuration property)`. Before this rule the embedded engine failed at start with exactly that error and was restarted `errors.max.retries` times (twenty, each with a full stack trace) before `Replication is STOPPED` — minutes of noise for a configuration that could never work, found by the temporal matrix's `default` profile (source host TZ `US/Central`, both zone keys absent).

`ConnectionTimeZonePreflight` runs at start on a MySQL source, after the compression preflight (spec 01.08 §3.4):
- `database.connectionTimeZone` set to a zone (non-blank, not the driver keyword `SERVER`) → nothing is queried; the driver uses the configured value (an unparseable value still fails per §3.1.2).
- Unset, blank, or `SERVER` (Connector/J's "derive it from the source", which needs the same guarantee) → one read-only `SELECT @@GLOBAL.time_zone, @@GLOBAL.system_time_zone`; the effective zone is `time_zone` unless it is `SYSTEM`, then `system_time_zone`. It is resolved with the driver's own `TimeUtil.getCanonicalTimeZone` (invoked reflectively via `MySqlJdbcDriver.canonicalTimeZone`, which returns the driver's result and rethrows its exception unwrapped; the resolver is looked up first, so a missing driver is an installation error, not a zone refusal), so the preflight and the driver agree by construction (IANA ids, offsets such as `+00:00`, `UTC` and the driver's unambiguous abbreviations pass; `CDT`/`CST`-style ambiguous abbreviations do not). Resolvable → INFO naming the source's two values and the canonical zone. Not resolvable → **refuses to start**, naming the property to set — the IANA zone of the source host, which is also the zone Debezium uses to interpret `TIMESTAMP` columns — and, second, the alternative of a named global `time_zone` on the source.
- A probe that cannot run (unreachable, not permitted) is a WARN and the start continues; the driver will report the failure itself. Non-MySQL connectors are never touched.

The production configurations of this repository's operators all set `database.connectionTimeZone`; the rule protects the default path and turns a retry storm into one line with the fix.

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

### 3.3 Out-of-range values: saturate quietly by default, or refuse
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
1. `clamp.out.of.range=false`: the converter throws
   `DebeziumConverter.ValueOutOfRangeException` naming the column, the source
   value, the ClickHouse type and its bounds, and the setting that would
   saturate instead. The batch fails and the failure is terminal (spec 10.01
   §3.1); nothing is written. Remediation is on the ClickHouse side (a wider
   type — `Date32` for `Date`, `DateTime64` for `DateTime` — or a `String`
   column) or returning to the default.
2. `clamp.out.of.range=true` (**default**; a missing configuration means the
   default, `RangePolicy.of(null, column)` saturates): the value is saturated
   to the bound as before, and the saturation is logged at **DEBUG only** —
   never at WARN, never rate-limited into WARN. Under this setting a
   saturation is the documented mapping of the sentinel, not an event: the
   operator chose it (or accepted the default), the mapping is deterministic,
   and nothing is lost that a log line would recover. The DEBUG line still
   names the column, the source value, the stored value and the bounds for
   anyone who opens that level deliberately. History of this rule: the first
   revision logged one WARN per saturated row — a single bitemporal table
   whose every row carries the `9999-12-31 23:59:59` open-ended sentinel
   produced 243,576 of the 248,541 lines (98%) of a connector log in 109 s,
   ~2,300 lines per second, rotating the 100 MB log every two minutes and the
   WARN-filtered error log every minute. The second revision rate-limited it
   to one WARN per column per minute; on a schema with hundreds of bitemporal
   columns that is still a WARN every few seconds, forever, on a healthy
   connector, and it kept flooding the logs for no reason. Hence DEBUG only.
   Rationale for the default: the `9999-12-31 23:59:59` open-ended sentinel is
   customary in bitemporal source schemas, and with the strict policy as the
   default an upgrade stopped replication outright on every deployment holding
   one — the refusal is terminal for the batch (spec 10.01 §3.1) and for the
   engine (spec 10.04 §3.5 rule 4) — until an operator found and set the key.
   A replica that stops by default on a value every earlier release stored is
   a worse outcome than a reported saturation, so saturation is the default
   and strictness is the operator's explicit choice.
3. The pre-existing four-argument converter overloads (no policy) keep the
   saturating behaviour, logged at DEBUG like rule 2, for callers that have no
   configuration; every production bind path passes a policy built from the
   configuration.
4. PostgreSQL `timestamptz` `infinity` / `-infinity` (issue #1231) are not
   out-of-range numbers but values with no finite representation; they keep
   their documented saturation to the `DateTime64` bounds under either policy
   (ordering is preserved, and there is no source value to lose).

Upgrade note: the default saturates, so a deployment whose source holds
sentinel dates beyond the ClickHouse range keeps replicating after an upgrade
and stores the bound as before, with a DEBUG line per saturation for anyone who
opens that level. A deployment that would rather stop on such a value
than store the bound sets `clamp.out.of.range=false` (rule 1); that refusal is
terminal for the batch and the engine, so set it only once the column types
are known to hold every source value.

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
  — §3.1.3/§3.1.4 end to end through `insertPreparedStatement`: `ZonedTimestamp`
  `2022-01-01T16:00:00Z` into a `Nullable(DateTime64(6, 'UTC'))` column with
  session zone `America/Chicago` binds the instant as epoch text
  `1641052800.000000` (the §3.1.3 revision bound `2022-01-01 16:00:00.000000`;
  the pre-§3.1.3 code `10:00:00.000000`, an instant six hours early).
- `PreparedStatementFieldMapperColumnZoneTest.testEmptySourceZoneKeepsDatetimeDigits()`
  — §3.1.2: with `database.connectionTimeZone` empty and session zone
  `America/Chicago`, DATETIME(3) and DATETIME(6) digits `10:00:00` are bound
  unchanged (pre-fix: `04:00:00`).
- `PreparedStatementFieldMapperColumnZoneTest.testExplicitSourceZoneShiftsIntoTheColumnZone()`
  — §3.1.2/§3.1.4: `database.connectionTimeZone=UTC`, session
  `America/Chicago`: digits `10:00:00` are the instant 10:00Z and bind the
  epoch text `1641031200.000000` into a `'UTC'` `DateTime64` column and into a
  `DateTime64` column without a declared zone alike (the §3.1.3 revision bound
  `10:00:00` and `04:00:00` respectively; the pre-§3.1.3 code `04:00:00` into
  the `'UTC'` column, six hours early), while `TIMESTAMP` 16:00Z binds
  `1641052800.000000`.
- `DebeziumConverterTest.testTimestampIntoUtcColumnWhenServerZoneIsChicago()`
  — the converter-level contract of §3.1.3 for `TimestampConverter`,
  `MicroTimestampConverter` and `ZonedTimestampConverter`: the digits
  overloads render in the column zone; the different-zones `DateTime64`
  instant is epoch text (§3.1.4) whether or not the column declares a zone.
- `DebeziumConverterTest.testInstantsBindAsEpochTextIntoDateTime64()` —
  §3.1.4: the two `America/Chicago` overlap instants `2026-11-01T06:30:00Z`
  and `2026-11-01T07:30:00Z` render the same digits
  (`2026-11-01 01:30:00.000000`) but distinct epoch text
  (`1793514600.000000` / `1793518200.000000`); microseconds are kept and a
  seventh fraction digit is truncated; the PostgreSQL `infinity` literals and a
  clamped out-of-range instant bind the `DateTime64` bounds as epoch text
  (`10413791999.000000` / `-2208988800.000000`); a `DateTime` or `String`
  target and the type-less overload keep the digits; the second before the
  epoch (`1969-12-31T23:59:59.5Z`) keeps the digits; the different-zones
  `TimestampConverter` / `MicroTimestampConverter` instant is epoch text for
  `DateTime64` and digits for `DateTime`, and a same-zone digits decode is
  unchanged. Pre-fix every `DateTime64` case returns the digits, so the test
  fails on it.
- `DebeziumConverterTest.testTimestampConverterGapTimePreserved()` — the
  different-zones assertions (`08:30:00 UTC`, `15:00:00 UTC`) are epoch text
  under §3.1.4 (`1772958600.000000`, `1782918000.000000`).
- `PreparedStatementFieldMapperColumnZoneTest.testOverlapInstantBindsExactEpochIntoChicagoColumn()`
  — §3.1.4 end to end through `insertPreparedStatement`: `ZonedTimestamp`
  `2026-11-01T07:30:00Z` into a `Nullable(DateTime64(6, 'America/Chicago'))`
  column binds `1793518200.000000`; pre-fix it bound
  `2026-11-01 01:30:00.000000`, which ClickHouse stores as `06:30:00 UTC`. The
  same value into a `String` column and into a `DateTime('UTC')` column binds
  the digits.
- `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()`
  — the `DateTime64` overload of `ZonedTimestampConverter.convert` throws the
  same `ValueOutOfRangeException` under the strict policy, so nothing is bound.
- `ClickHouseDataTypeMapperTimeZoneTest` — `resolveSourceTimeZone` (empty →
  session zone, set → parsed, garbage → throws) and `columnTimeZone` (parses
  `Nullable(DateTime64(3, 'UTC'))`, `DateTime('Europe/London')`; null for
  `DateTime64(6)` and `String`).
- `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper()`,
  `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDateAtTheMapper()`
  — §3.3 rule 2 as the default, through `ClickHouseDataTypeMapper.convert`
  with an empty configuration (and `RangePolicy.of(null, column)` with none):
  `DATETIME 9999-12-31 23:59:59` into `DateTime64` binds
  `2299-12-31 23:59:59.000` with no WARN at all, `DATE 9999-12-31` into
  `Date` binds `2149-06-06` and `DATE 1000-01-01` into `Date32` binds
  `1900-01-01`. A strict default throws instead, so the tests fail on it.
- `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDatetimeAtTheMapper()`,
  `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDateAtTheMapper()`
  — §3.3 rule 1 through `ClickHouseDataTypeMapper.convert` with
  `clamp.out.of.range=false`: the same values throw `ValueOutOfRangeException`
  naming the setting, and nothing is bound.
- `DebeziumConverterRangePolicyTest.clampSettingSaturatesSilently()` — rule 2:
  `clamp.out.of.range=true` binds the bound for 1,000 saturations of one
  column plus one of another and writes zero WARN lines; with the logger
  opened to DEBUG each saturation is one DEBUG line naming the column and both
  values. The rate-limited revision writes WARN lines here, so the test fails
  on it.
- `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()` —
  the exception text of rule 1 for `DateTime`, `DateTime64`, `Date`, `Date32`,
  `ZonedTimestamp` and decimal.
- `DebeziumConverterRangePolicyTest.legacyOverloadsSaturateSilently()` —
  rule 3: the policy-less overloads saturate every bounded type and write zero
  WARN lines.
- `DebeziumConverterRangePolicyTest.infinityKeepsItsSaturation()` — rule 4.
- `PreparedStatementFieldMapperOutOfRangeTest.outOfRangeValueNamesDatabaseTableAndColumn()`
  — end to end through `insertPreparedStatement`: by default the row binds
  the bound; with `clamp.out.of.range=false` the exception names
  `db.orders.expires_at`.
- The pre-existing `testTimestampConverterMinRange` / `MaxRange`,
  `testDateConverterMinRange` / `MaxRange`, `testMicroTimestampConverterMin` /
  `Max`, `testZonedTimestampConverter` tests exercise the four-argument
  overloads and therefore pin the saturating (rule 3) behaviour.
- `TimeAdjusterDefaultTest.absentIsForcedToFalse()`,
  `TimeAdjusterDefaultTest.blankIsForcedToFalse()` — §3.4: absent/blank is
  forced to `false`; `TimeAdjusterDefaultTest.explicitValueWins()` — an
  explicit `true` is left alone; `TimeAdjusterDefaultTest.nullIsTolerated()`.
- The testflows regression suites (`sink-connector-lightweight/tests/integration`,
  `datatypes/datetime`) assert the BOUNDED values on purpose (`9999-12-31` ->
  `2299-12-31`, `1000-01-01` -> `1900-01-01`); they run the connector with the
  default, stated explicitly as `clamp.out.of.range=true` in
  `helpers/default_config.py` and the `env/*/config.yml` files. The strict
  setting (rule 1) is pinned by the unit tests above; under the
  previous retriable classification of the refusal (Spec 10.01 before its
  terminal-exception rule) the refused batch parked forever and every later
  suite test failed on a timeout.
- `ConnectionTimeZonePreflightTest.ambiguousAbbreviationRefuses()` — §3.1.5: `time_zone=SYSTEM`, `system_time_zone=CDT`, property unset → refusal naming `database.connectionTimeZone`; `ConnectionTimeZonePreflightTest.namedZoneResolves()`, `ConnectionTimeZonePreflightTest.systemUtcResolves()`, `ConnectionTimeZonePreflightTest.offsetResolves()` — an IANA id, `SYSTEM`+`UTC` and an offset pass; `ConnectionTimeZonePreflightTest.configuredPropertySkipsTheProbe()` — a configured value issues no query; `ConnectionTimeZonePreflightTest.serverKeywordIsProbed()` — `SERVER` is probed like an unset value (refused on `CDT`, passed on `UTC`); `ConnectionTimeZonePreflightTest.probeFailureWarnsAndContinues()`, `ConnectionTimeZonePreflightTest.nonMySqlConnectorIsUntouched()`; `ConnectionTimeZonePreflightTest.driverAgreesWithThePreflight()` — pins the driver contract the rule relies on (`CDT` throws, `America/Chicago` resolves).

---

## 6. Failure Modes & Recovery

Recovery posture: every temporal value is either rendered faithfully, saturated to the ClickHouse bound by policy (the default, logged at DEBUG only), or refused terminally (`clamp.out.of.range=false`); zone configuration mistakes are refused at start where the preflight can see them and otherwise surface per batch. The residual silent modes are the saturation itself, Debezium's zero-date substitution and the DST ambiguity of `DateTime` (32-bit) columns. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.03-1 Out-of-range value saturated under the default policy**
  - **Trigger**: a `DATE`/`DATETIME` outside the ClickHouse type's range — the `9999-12-31 23:59:59` open-ended sentinel, a `1000-01-01` floor, or a typo year such as `0201-05-01` — with the default `clamp.out.of.range=true`.
  - **Behaviour**: `DebeziumConverter.RangePolicy.report` saturates to the bound (`DateTime64`/`Date32`: 1900-01-01 .. 2299-12-31; `Date`: 1970-01-01 .. 2149-06-06; `DateTime`: 1970 .. 2106) and logs at DEBUG only (§3.3 rule 2). The batch succeeds.
  - **Detection**: none at the default log level: DEBUG `Value <v> for column <db.t.c> is outside the ClickHouse <type> range [<min> .. <max>]; stored as <bound> (clamp.out.of.range=true)`; no counter metric. DEFECT.
  - **Blast radius**: the saturated rows hold a value MySQL never held; a typo year is indistinguishable from the sentinel; row counts intact. Deterministic, so redelivery writes the same bound (no duplication beyond ReplacingMergeTree dedup).
  - **Recovery**: if the value must be kept, P-FIX-TYPE to a type that holds it (`String`, or `Date32`/`DateTime64` for a `Date`/`DateTime` column) and P-RESYNC the table; a typo is repaired by fixing the source row (`UPDATE`), which replicates.
  - **RTO**: unbounded detection (only a value-level checksum or a DEBUG log shows it); after detection minutes + resync; unmeasured.
  - **Test**: `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper()`, `DebeziumConverterRangePolicyTest.clampSettingSaturatesSilently()`; GAP: a test that each saturation increments a per-column counter metric.
  - **DEFECT**: saturation is uncounted; a per-column saturation counter (metric, not a log line — the WARN revision flooded the log, §3.3) would make it detectable without the flood.

- **FM-07.03-2 Out-of-range value refused under `clamp.out.of.range=false`**
  - **Trigger**: as FM-07.03-1 with the strict setting.
  - **Behaviour**: `RangePolicy.report` throws `DebeziumConverter.ValueOutOfRangeException`; `ClickHouseErrorClassifier` classifies it FATAL (`TERMINAL_EXCEPTION_TYPES`); the worker dies, the engine stops on the next source batch and the process exits 3 (spec 10.04 §3.5). Nothing of the batch is written.
  - **Detection**: ERROR `Value <v> for column <db.t.c> is outside the ClickHouse <type> range [...]. Refusing to store <bound> in its place ... Widen the ClickHouse column type, or return to the default clamp.out.of.range=true`, ERROR `FATAL ClickHouse error (Code: -1) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit code 3 — within ≤ 5 s (heartbeat) of the failing batch; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: the whole connector stops; no data lost, nothing diverges; offsets stay at the last committed transaction.
  - **Recovery**: P-FIX-TYPE (`String`, or `Date32`/`DateTime64` where the value fits), or set `clamp.out.of.range=true` (accepting FM-07.03-1) and restart.
  - **RTO**: config edit or `ALTER` + `RestartSec=30` + engine start + re-apply of the in-flight transaction ≈ 1–2 min; unmeasured.
  - **Test**: `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDatetimeAtTheMapper()`, `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDateAtTheMapper()`, `ClickHouseErrorClassifierTest.valueOutOfRangeIsFatalRegardlessOfCode()`, `TerminalFailureExitTest.fatalTerminalTypeIsNotRetried()`.

- **FM-07.03-3 Zero or invalid date (`0000-00-00`, `2020-00-15`)**
  - **Trigger**: a permissive `sql_mode` on the source lets MySQL store a zero date (or a zero month/day) in a `DATE`/`DATETIME` column.
  - **Behaviour**: decided by Debezium before connector code runs: `JdbcValueConverters.convertValue` (debezium-core 3.1.3, bytecode) returns `null` for a missing value of an optional column and, for a `NOT NULL` column, the Connect schema default (the MySQL column `DEFAULT`) or the converter's fallback (`convertDateToEpochDays` passes epoch day 0, i.e. `1970-01-01`). How the binlog client presents the zero date to that converter was not verified in bytecode. The connector then binds that value (NULL, the default, or 1970-01-01).
  - **Detection**: none on the binlog path. Debezium's `BinlogValueConverters.containsZeroValuesInDatePart` logs WARN `Invalid value '<v>' stored in column '<c>' of table '<t>' converted to empty value` only on its string-parsing path (snapshot). DEFECT.
  - **Blast radius**: silent substitution on the affected rows; row counts intact. ClickHouse has no zero date, so no column type can hold the source value.
  - **Recovery**: fix the source rows (`UPDATE ... SET d = NULL` or a real date) — the UPDATE replicates and supersedes the substituted value; or map the column to `String` and P-RESYNC.
  - **RTO**: unbounded detection; after detection one source UPDATE (seconds) per affected set; unmeasured.
  - **Test**: GAP: an end-to-end test that a `0000-00-00` in a `NOT NULL DATE` column is either refused or reported, instead of arriving as the default or `1970-01-01`.
  - **DEFECT**: a source value is replaced by a different one with no signal from the connector.

- **FM-07.03-4 Zone configuration mistakes**
  - **Trigger**: (a) `database.connectionTimeZone` unset while the source's `@@time_zone` resolves to an ambiguous abbreviation (`CDT`); (b) an unparseable `database.connectionTimeZone`; (c) an unparseable `clickhouse.datetime.timezone`.
  - **Behaviour**: (a) `ConnectionTimeZonePreflight.check` throws `IllegalStateException("Refusing to start: ...")` before the engine is built (§3.1.5). (b) `ClickHouseDataTypeMapper.resolveSourceTimeZone` throws `DateTimeException` for every `DATETIME` bound — UNKNOWN, so the worker retries forever (whether Connector/J already rejects the value at engine start is unverified). (c) `ClickHouseBatchRunnable.getServerTimeZone` logs ERROR and falls back to the ClickHouse server zone for every batch; `DateTime64` instants are epoch text and unaffected (§3.1.4), but `DateTime`/`String` targets are rendered in the fallback zone.
  - **Detection**: (a) ERROR banner `!!  REFUSING TO START: ...` naming `database.connectionTimeZone`, at start. (b) ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `java.time.DateTimeException` + WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s, no exit. (c) ERROR `**** Error parsing user provided timezone:<value>` on every batch.
  - **Blast radius**: (a) nothing starts, nothing lost; (b) the worker's tables stop, offsets freeze, no loss; (c) replication continues, `DateTime`/`String` temporal values may be rendered in an unintended zone (silent shift).
  - **Recovery**: set the key to the IANA zone of the source host / a valid zone id and restart; for (c) P-RESYNC the `DateTime`/`String` temporal columns written meanwhile.
  - **RTO**: config edit + restart ≈ 1 min (a, b); (c) + resync; unmeasured.
  - **Test**: `ConnectionTimeZonePreflightTest.ambiguousAbbreviationRefuses()` (a), `ClickHouseDataTypeMapperTimeZoneTest.garbageSourceZoneThrows()` (b); GAP: a test that an unparseable `database.connectionTimeZone` or `clickhouse.datetime.timezone` refuses at start instead of failing per batch (b) or falling back silently (c).
  - **DEFECT**: (b) is retried forever instead of refused at start, and (c) continues with a zone nobody configured.

- **FM-07.03-5 DST ambiguity in `DateTime` (32-bit) and DDL-created DST-zone columns**
  - **Trigger**: a `TIMESTAMP` in the fall-back overlap hour bound into a `DateTime`/`DateTime32` column, or a `DATETIME` spring-forward gap time into a DDL-created `DateTime64(p, '<DST zone>')` column (§3.1.3 residual, §3.1.4 exclusions).
  - **Behaviour**: `DateTime` rejects epoch text, so `DebeziumConverter.bindsAsEpochText` keeps the digits rendering; ClickHouse resolves ambiguous digits to the first occurrence and shifts gap digits (measured, §3.1.3/§3.1.4). The batch succeeds.
  - **Detection**: none. DEFECT (documented residual).
  - **Blast radius**: one-hour shift on rows inside the transition hour; row counts intact.
  - **Recovery**: P-FIX-TYPE to `DateTime64(p, 'UTC')` (or `DateTime64` for the `DateTime` column), then P-RESYNC the rows of the transition hours.
  - **RTO**: unbounded detection; after detection minutes + resync; unmeasured.
  - **Test**: `PreparedStatementFieldMapperColumnZoneTest.testOverlapInstantBindsExactEpochIntoChicagoColumn()` (pins that a `DateTime` target keeps the digits); GAP: a test that a DDL-created `DATETIME` column is declared in `'UTC'`.
  - **DEFECT**: the value path cannot store an overlap-hour instant exactly in a `DateTime` column, and the DDL path still declares `DATETIME` columns in the session zone.

- **FM-07.03-6 Unparseable `ZonedTimestamp` text**
  - **Trigger**: a PostgreSQL `timestamptz` whose Debezium text matches none of the accepted ISO-8601 forms (e.g. a BC date), other than `infinity`/`-infinity`.
  - **Behaviour**: `ZonedTimestampConverter.boundedInstant` throws `IllegalArgumentException` (§3.3 of spec 07.06); classified UNKNOWN, retried forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception` with `ZonedTimestamp value '<v>' for column <c> matches none of the accepted ISO-8601 forms; refusing to store an empty string in its place` and the WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: the worker's tables stop and offsets freeze; nothing lost.
  - **Recovery**: none by retry; P-FIX-TYPE cannot help (the refusal is in the connector); fix the source value and P-SKIP + resync the transaction's tables.
  - **RTO**: unbounded; P-SKIP + resync; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.unparseableZonedTimestampThrows()` pins the refusal; GAP: its classification as terminal (same root defect as `PoisonValueClassificationTest.spatialValueRefusalIsFatal()`).
  - **DEFECT**: a deterministic refusal raised by the connector is retried forever instead of stopping the engine.

- **FM-07.03-7 Year-below-100 remap re-enabled by configuration**
  - **Trigger**: a configuration sets `enable.time.adjuster=true` explicitly.
  - **Behaviour**: `DebeziumChangeEventCapture.ensureTimeAdjusterDisabled` leaves it (§3.4); Debezium remaps `0001-01-01` to `2001-01-01` before the connector sees it.
  - **Detection**: WARN at start `enable.time.adjuster=true is set by configuration: Debezium will remap years below 100 into 1970-2069 ...`; nothing per row.
  - **Blast radius**: silent value change on rows with years below 100.
  - **Recovery**: remove the key (or set `false`), restart, P-RESYNC the affected tables.
  - **RTO**: restart ≈ 1 min + resync; unmeasured.
  - **Test**: `TimeAdjusterDefaultTest.explicitValueWins()`, `TimeAdjusterDefaultTest.absentIsForcedToFalse()`.

Summary: 7 failure modes, 5 DEFECT, 5 GAP.
