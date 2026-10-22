# Spec 07.03: Temporal Data Types & Timezone Handling

## 1. Executive Summary & Purpose
Specifies the translation and timezone adjustment of MySQL date and time types to ClickHouse temporal representations.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **Session-zone preflight (lightweight)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ConnectionTimeZonePreflight.java` — `ConnectionTimeZonePreflight.check(Properties)` (§3.1.5): refuses an unset `database.connectionTimeZone` or `clickhouse.datetime.timezone`, and `SERVER` for `database.connectionTimeZone`, before any connection (§3.1.2); a configured zone is not probed.
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
  `clickhouse.datetime.timezone` (ClickHouse session zone). Both are mandatory: an unset value (absent, empty or blank) refuses the start (§3.1.2).
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
`MicroTimestampConverter.convert` take that shortcut: the digits are formatted
as UTC, which is the inverse of the Debezium encoding. Routing the digits
through a real DST zone (`TimeZone.getRawOffset` / `inDaylightTime`, the
previous `TimestampConverter` code) shifted every wall time that falls in the
spring-forward gap: `2026-03-08 02:30:00` in `America/Chicago` came out as
`2026-03-08 01:30:00`, silently, with row counts intact.
`MicroTimestampConverter` already had the shortcut; `TimestampConverter` did not.

The shortcut is exact only while the digits are a unique wall time in the zone
ClickHouse resolves them in, which is the column's declared zone (else the
session zone, §3.1.3). That zone is checked for the digits (§3.1.14 for the kinds):
- **unique** (one valid offset): the digits are bound as digits, unchanged.
- **gap** (no valid offset, e.g. `2026-03-08 02:30:00` in `America/Chicago`):
  the instant is taken with the offset **before** the transition
  (`ZoneRules.getTransition(...).getOffsetBefore()`), which is the
  `java.time` `ZonedDateTime.ofLocal` rule: `02:30 CST` is `03:30 CDT`. It is
  bound as an instant and reported as `DST_GAP`. The stored value is one hour
  later than the wall digits, which no instant in that zone can show; the
  shift is loud, never silent.
- **overlap** (two valid offsets, e.g. `2026-11-01 01:30:00` in `America/Chicago`):
  the instant is the earlier occurrence (`ZoneRules.getValidOffsets(...).get(0)`,
  the offset before the fall-back), which is the occurrence ClickHouse picks
  for the same digits. The later occurrence cannot be told apart from the
  digits alone, so the row is reported as `DST_OVERLAP`.

When the source zone differs from the session zone (an explicit
`database.connectionTimeZone` that is not the session zone), the digits are
interpreted as a wall time in the source zone and converted to an instant, with
the same gap and overlap rules applied in the source zone (reported as above).
`MicroTimestampConverter` and `TimestampConverter` use one helper for this, so
the two converters never disagree on the same digits.

#### 3.1.2 Both zone settings are mandatory: an unset zone setting refuses the start
Before this rule the two settings resolved asymmetrically and both resolved implicitly: an empty
`clickhouse.datetime.timezone` fell back to the ClickHouse server zone (`SELECT timezone()`), an empty
`database.connectionTimeZone` was taken as `UTC` (`ClickHouseDataTypeMapper.convert`), and a later change
substituted `America/Chicago` for both. Each substitution is an implicit zone: the digits of a DATETIME were read
in a zone nobody configured, and a server or host zone that differed from the intent shifted every value silently.

Rule (`TimeZoneResolver.effective`, `TimeZoneResolver.requireConfigured`):
- `database.connectionTimeZone` and `clickhouse.datetime.timezone` are both mandatory. Absent, empty and blank are all unset.
- An unset key refuses the start on every path before any row is read or written: the lightweight engine for MySQL and PostgreSQL
  (`ConnectionTimeZonePreflight.check`, before any connection is made), and the Kafka Connect task
  (`ClickHouseSinkConnectorConfig` construction). The message names the key and a suggested value, for example
  `database.connectionTimeZone is not set. Set it to the IANA zone of the source's time_zone, for example database.connectionTimeZone=UTC`.
- No code path substitutes the JVM zone (`-Duser.timezone`, `TZ`), the host zone, the ClickHouse server zone, the MySQL
  session zone, or `America/Chicago`. The resolver has no default zone.
- The keyword `SERVER` for `database.connectionTimeZone` is refused: it asks the driver to derive the zone from the source
  session, which is an implicit zone (§3.1.5).
- The effective zone of each key is logged once per JVM at start (`Effective time zones: clickhouse.datetime.timezone=<z>;`
  `database.connectionTimeZone=<z>`).
- A wall-clock shift of DATETIME values happens only when the operator declares a source zone that differs from the
  ClickHouse zone; the declaration is mandatory and visible in the configuration.
- Compatibility: a configuration that sets both keys and starts today with valid zones starts unchanged. The refusal of an unset key is the
  one deliberate exception: a configuration that leaves either key unset is refused until the key is set (FM-07.03-9).

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
the session zone when the column declares none. The same-zone digits of §3.1.1
are bound as digits only when they are a unique wall time in that zone. A
DST zone cannot hold a spring-forward gap wall time (`clickhouse local`:
`'2026-03-08 02:30:00'` into `DateTime64(3, 'America/Chicago')` reads back
`01:30:00`; into `DateTime64(3, 'UTC')` it reads back `02:30:00`), so in a DST
column the gap digits are bound as the instant of §3.1.1 and reported
(`DST_GAP`), and the overlap digits are bound as the earlier instant and
reported (`DST_OVERLAP`). A `'UTC'` column never needs either.

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
- Exclusions: a `DateTime`/`DateTime32` column does not take epoch text. Measured
  with `clickhouse local` 24.8.14, `'1793518200'` fails with `Cannot parse DateTime
  from String`. Measured on the connector's bind path (an end-to-end run of a
  build that bound epoch text for `DateTime`, column `Nullable(DateTime('America/Chicago'))`,
  CH 24.8), the batch is acknowledged, no error is logged, and the row is stored
  as NULL, so the connector keeps the digits rendering of §3.1.3 for that type.
  The overlap ambiguity remains for that type and is reported under `dst_overlap`
  (§3.1.14), not corrected (F03); and ClickHouse drops the sign of an epoch between `-1` and `0`
  (`'-0.500000'` reads back as `1970-01-01 00:00:00.500000`, while
  `'-1.500000'` and `'-2208988799.500000'` are exact), so the one second
  before `1970-01-01 00:00:00 UTC` is bound as digits as well
  (`DebeziumConverter.bindsAsEpochText`). MySQL `TIMESTAMP` starts at
  `1970-01-01 00:00:01 UTC` and cannot hold that second.
- The column zone resolved by §3.1.3 is therefore no longer part of the
  stored value for a `DateTime64` instant: the same instant binds the same
  text into a `'UTC'`, an `'America/Chicago'` and an unzoned column.

#### 3.1.5 The source's session zone must be one the JDBC driver can name
`database.connectionTimeZone` is mandatory (§3.1.2), so the connector no longer reads the source's session zone to decide what DATETIME digits mean. An unset or `SERVER` value is refused before any connection, so the driver's own derivation never reaches a conversion. Connector/J derives it from `@@time_zone`, or from `@@system_time_zone` when `time_zone = SYSTEM`; that value is whatever the source host's C library reports, and on a host in a DST zone it is an **abbreviation** such as `CDT`. Connector/J cannot name one zone for `CDT` (`com.mysql.cj.util.TimeUtil.getCanonicalTimeZone` rejects it), and the driver then refuses every connection: `The server time zone value 'CDT' is unrecognized or represents more than one time zone. You must configure either the server or JDBC driver (via the 'connectionTimeZone' configuration property)`. Before this rule the embedded engine failed at start with exactly that error and was restarted `errors.max.retries` times (twenty, each with a full stack trace) before `Replication is STOPPED` — minutes of noise for a configuration that could never work, found in a run with the default profile (source host TZ `US/Central`, both zone keys absent).

`ConnectionTimeZonePreflight` runs at start on every source connector, after the compression preflight (spec 01.08 §3.4):
- `database.connectionTimeZone` set to a zone → nothing is queried; the driver uses the configured value (an unparseable value still fails per §3.1.6). Unset or `SERVER` → refused before any connection (§3.1.2).
- Unset, blank, or `SERVER`: refused before any connection (§3.1.2). No session zone is queried for a conversion.

The rule refuses the start for an unset key before any connection, so a missing key cannot start a retry loop.

#### 3.1.6 One resolver decides what a configured zone is, and a bad value stops the start
Every consumer of `database.connectionTimeZone` and `clickhouse.datetime.timezone` (config validation, `ConnectionTimeZonePreflight`, `DBMetadata`, the batch writer and runnable, the DDL parser, the history handler) calls the same resolver, `com.altinity.clickhouse.sink.connector.common.TimeZoneResolver`. There is no second parser.

Rules, applied to the raw property value:
- **Unset** = absent, empty, or whitespace-only. For **both** keys an unset value refuses the start (§3.1.2), naming the key and a suggested value. An explicit value always wins and is the zone. There is no default zone; the JVM zone, host zone, server zone and session zone are never substituted.
- **Startup decision** (`StartupZonePolicy.plan`): both keys resolve through the resolver, which refuses an unset or `SERVER` `database.connectionTimeZone` and an unset `clickhouse.datetime.timezone`, naming the key and a suggested value. No session zone is compared with the configured zone (that comparison is not implemented). `StartupZonePolicy.logEffective` logs both effective zones once per JVM.
- **Surrounding whitespace** on a non-blank value (`' America/Chicago'`, `'America/Chicago '`) is refused. Kafka's `ConfigDef` trims STRING values before validation, so the check reads the raw property; MySQL would reject the untrimmed value, so silently trimming would hide a divergence from the source.
- **Region ids** must be an exact (case-sensitive) member of `ZoneId.getAvailableZoneIds()`. `US/Central`, `UTC`, `GMT` and `Etc/GMT+5` are members. Refused: `EST`, `CST`, `CDT`, `PST` (JDK 21 does not know them, and the ambiguous ones must not be guessed; each refusal names the region ids it could mean, for example `CST` → `America/Chicago` or `Etc/GMT+6`), `SYSTEM` and `LOCAL` (driver/server keywords, not zones), `Z`, prefix forms such as `UTC+5` (JDK maps them to `UTC+05:00`, ClickHouse 24.8 rejects them), and lower-case variants such as `america/chicago`.
- **Offsets** (`[+-]HH:MM`) are refused for `clickhouse.datetime.timezone` (ClickHouse 24.8.8.17 rejects `+05:30` as a zone, `EST` is accepted there, measured with `clickhouse local`). They are accepted for `database.connectionTimeZone` only, because MySQL/Connector-J accept them as `time_zone`.
- **`SERVER`** is the Connector/J keyword "derive from the source". It is refused for `database.connectionTimeZone`: it names an implicit zone, the source's session zone.
- **Misspelled keys.** The keys are case-sensitive, so `database.connectionTimezone` (the spelling in `doc/timezone.md` before this rule) is silently ignored today. A property whose name equals a zone key ignoring case, but is not that key, is refused at start, naming the correct key.
- **JVM without tzdata**: an empty zone list, or one without `America/Chicago`, refuses at start with a message naming the JVM, not the property.

A refused value throws `TimeZoneResolver.InvalidTimeZoneException` (carries the property name and the raw value). `ClickHouseSinkConnectorConfig` turns it into a Kafka `ConfigException` at construction, so a bad zone never reaches a batch. The lightweight preflight refuses the start with `IllegalStateException("Refusing to start: ...")` before the engine is built.

Consumers that must fail loudly instead of falling back:
- A configured `clickhouse.datetime.timezone` that the resolver refuses throws in every consumer: `ClickHouseBatchRunnable` and `ClickHouseBatchWriter.getServerTimeZone`, the DDL history path (`DebeziumChangeEventCapture`), `MySqlDDLParserListenerImpl.parseTimeZone`, `ClickHouseAutoCreateTable` (`historyDateTimeColumnType` and its server-zone helper). None logs and carries on with the server zone or with no zone.
- An unset `clickhouse.datetime.timezone` cannot reach the batch, history and DDL paths (`ClickHouseBatchRunnable`, `ClickHouseBatchWriter`, `ClickHouseAutoCreateTable`, `DebeziumChangeEventCapture`): the start is refused first (§3.1.2). The server zone (`SELECT timezone()`, `DBMetadata.getServerTimeZone`) is read only by the startup cross-check. That read throws `ServerTimeZoneUnavailableException` when there is no connection, the query fails, the result is empty, or this JVM cannot resolve the reported zone. There is no UTC fallback.
- `BinLogHistory.executeInsertWithStructs` refuses a null or blank server zone (`IllegalArgumentException`) instead of defaulting it to UTC. Its callers resolve the effective ClickHouse zone (always configured, §3.1.2) only when replication history is enabled, so default deployments pay no extra query on the non-history path.
- `ClickHouseDataTypeMapper.resolveSourceTimeZone` resolves a set `database.connectionTimeZone` through the resolver as the Kind `SOURCE`, so it accepts `[+-]HH:MM` offsets and nothing else the config refuses.
- `ReplicationHistoryHandler` takes the configured zone. An unset `database.connectionTimeZone` is refused at start (§3.1.2), so no default reaches the history literals.
- The lightweight PostgreSQL DDL translator (`PostgreSQLDDLParserService.mapPostgresTypeToClickHouse(pgType, schema, table, column, config)`) names a zone-less `TIMESTAMP` or `TIMESTAMP(p)` column with the same resolved zone, `DateTime64(p, 'zone')`, as the MySQL DDL path names `DATETIME`. Before this change it emitted a bare `DateTime64(p)`, which ClickHouse displays in the server zone. A DDL type is only set at creation, so existing tables are not altered. The one-argument mapper keeps its zone-less form because it has no configuration to name a zone from.

This rule refuses values that today fail only per batch or fall back silently. It does not reinterpret any value that is valid today, so a configuration that starts today with a valid zone starts unchanged.

**Startup cross-check.** At start, `ClickHouseZoneCrossCheck` (`sink-connector/.../db/ClickHouseZoneCrossCheck.java`)
runs from two startup paths: the embedded engine (`DebeziumChangeEventCapture.setup`) and the Kafka Connect task
(`ClickHouseSinkTask.start`, task 0 only, so N tasks do not repeat the same lines). It reads `SELECT timezone()` and the explicit zones of existing DateTime/DateTime64 columns (`system.columns`, outside
`system` and `information_schema`). It logs one ERROR per mismatch. The reference zone is the configured `clickhouse.datetime.timezone` (always set, §3.1.2). A column in a view is not reported: a view's type is the zone its SELECT renders (`show_replica_status.utc_time` is `DateTime('UTC')` by design), and the connector never writes a view (`system.tables.engine` identifies views). A server zone that
differs from the reference is one finding; a column whose declared zone differs from the reference is one finding
naming the column. A column with no declared zone follows the server and is not reported. The check never refuses:
when it cannot run (no connection, a query error, an empty server zone) it logs a WARN and startup continues. Zones
are compared as strings, so an alias (`US/Central` against `America/Chicago`) is reported; that is deliberately
conservative. Existing tables are not altered by this check or by any other part of §3.1.6: a DDL type is only set at
creation (see FM-07.03-12 and `doc/timezone.md`).

**Kafka Connect.** `ClickHouseSinkConnectorConfig` declares both zone keys with no default and refuses an unset or `SERVER` `database.connectionTimeZone` and an unset `clickhouse.datetime.timezone` at construction (§3.1.2), so `ClickHouseSinkTask.start` reads only configured zones. The session zone is not read on this path.

#### 3.1.8 `DateTime64(p)` bounds are per scale, and the policy carries the scale
The saturation bound of a `DateTime64` value depends on the column's scale
`p`, because the largest representable value does:

| `p` | maximum (inclusive) | minimum (inclusive) |
|---|---|---|
| 0..8 | `2299-12-31 23:59:59` followed by `p` nines (`.9` for p=1, `.99999999` for p=8) | `1900-01-01 00:00:00` (all p) |
| 9 | `2262-04-11 23:47:16.854775807` (the largest Int64 nanosecond) | `1900-01-01 00:00:00` |

Values above the maximum saturate to the maximum of their own scale, and
values below the minimum saturate to `1900-01-01 00:00:00` (§3.3 rule 1 or 2).
A saturation to `2299-12-31 23:59:59.000` at any scale is wrong: it clamped
`.500` and `.999999` to `.000`, and it was out of range for `DateTime64(9)`,
which raises `DECIMAL_OVERFLOW` (code 407). `RangePolicy` therefore carries the
column's scale, read from the declared type by `ClickHouseColumn.of`; a policy
built without a declared scale uses scale 6. A `DateTime64` value above the
bound of its own scale is not wrapped by ClickHouse (it wraps for p <= 8 and
raises for p = 9), so the connector's clamp is the only guard.

Tests: `DebeziumConverterRangePolicyTest.dateTime64ClampsToTheBoundOfItsScale()`
(p=3, p=6, p=8, p=9) and `DebeziumConverterRangePolicyTest.dateTime64BoundIsNotZeroedFraction()`.

#### 3.1.9 An instant bound into a second-precision column is epoch-exact, not wall digits
`DateTime` (and `DateTime32`) has no sub-second part, and a wall-clock literal
in a DST zone names two instants for the fall-back overlap hour: ClickHouse
resolves `'2026-11-01 01:30:00'` in `America/Chicago` to the first occurrence
only, so the second pass (01:30 CST, epoch `+3600`) is unreachable through digits.
Any **instant** written into a `DateTime`/`DateTime32` column, or compared with
one, is therefore written as its epoch seconds (`toDateTime(<seconds>)` in SQL,
or a numeric `Int64` bind): the value is the same instant in every session
zone and every column zone. Wall digits remain correct for zone-less `DATETIME`
values (§3.1.1), which are not instants. Consequence for the value path: a
`TIMESTAMP` instant bound into a `DateTime` column keeps the digits form of §3.1.3
(the converter returns a `String`); that residual is FM-07.03-5 and is not
changed by this revision. The history statements, whose instants are
generated as SQL, are epoch-exact (12.04 §3.5).

The value-path rule above is NOT in force for `DateTime`/`DateTime32` (§3.1.14
rule 6, F03): epoch text bound into a `DateTime` column stores NULL on the
production path, so the value path keeps the digits of §3.1.3. A numeric `Int64`
bind was not probed on that path and is not implemented; it is an open item,
not a measured fix.

Test: `ReplicationHistoryEpochLiteralTest.historyLiteralsDoNotDependOnZone()`; probe
evidence in the commit message (`toDateTime('2026-11-01 01:30:00','America/Chicago')`
resolves to CDT; `toDateTime(<CST epoch>)` resolves to CST).

#### 3.1.10 Zone is always a parameter; no JVM default zone reaches a converted value
Every conversion of a value into a ClickHouse temporal literal takes its zone as
an explicit parameter (`ZoneId`). `ZoneId.systemDefault()`, `TimeZone.getDefault()`,
`SimpleDateFormat` without a zone, `java.sql.Date.valueOf` and `Timestamp.valueOf`
are not used on a value path. A site where the JVM default is intentional and
verified harmless is listed in the commit that touches it. Each call site of a
`DebeziumConverter` `convert` method and each history `_time`/`_valid_*`
binding carries a comment `// TZ-SEMANTICS: wall` (zone-less digits) or
`// TZ-SEMANTICS: instant` (an epoch) on the same line or within the three lines
above it; `TzSemanticsSourceScanTest` scans the sources for implicit-zone calls and
fails on one that is not allowlisted. That scan does not check the comment;
the comment check is not implemented yet.

#### 3.1.11 Epoch text rounds toward negative infinity
`epochText(Instant)` renders `<seconds>.<6 digits>` by flooring the sub-microsecond
part: `-0.0000005 s` (1969-12-31 23:59:59.9999995 UTC) is `-0.000001`, not the
truncated `-0.000000`. `RoundingMode.DOWN` truncates toward zero, which is one
microsecond too late for every negative instant with a sub-microsecond part.
Test: `DebeziumConverterTest.epochTextFloorsNegativeSubMicrosecond()`.

#### 3.1.12 The open-row sentinel is one instant everywhere (SCD2)
The `_valid_to` open-row sentinel is the single instant `2100-01-01T00:00:00Z`
(epoch `4102444800`, `DataTypeRange.DATETIME32_MAX_TTL`). It is written in three
places, and each must denote that instant:
1. the bound or literal value of an open row (history `INSERT` and the
   `deleted_time` bind of an UPDATE/INSERT row, 12.04 §3.5);
2. the predicate that finds the open row to close (`WHERE _valid_to = ...`);
3. the column `DEFAULT` written at table creation.

Before this revision (1) and (2) were the wall digits of the instant in the
server zone, (3) was the same digits rendered in the column's zone. With
`clickhouse.datetime.timezone` / server zone `America/Chicago` the DDL default
was `2100-01-01 00:00:00` Chicago = `4102466400` (6 h later than the sentinel),
so a row written with the default was never closed. All three now use the epoch
form (`toDateTime(4102444800)`, `DEFAULT 4102444800`). Existing tables keep the
DEFAULT they were created with; the connector always binds `deleted_time` on
its own writes, so the DEFAULT only applies to rows written by other writers
(FM-07.03-13).

Test: `ReplicationHistoryEpochLiteralTest.sentinelIsTheEpochOfTheOpenRowInstant()`
and `ReplicationHistoryEpochLiteralTest.deletedTimeDefaultIsEpochExact()`.
#### 3.1.13 Two zone ids are the same zone when their rules are equal, not when their ids are
`ZoneId.equals` compares identifiers: `America/Chicago` and `US/Central` are
different `ZoneId`s with identical `ZoneRules`. The same-zone test of §3.1.1
(the one that decides whether digits are decoded as-is or shifted) compares
`ZoneRules`, so an alias of the session zone is the same zone. Before this
revision `database.connectionTimeZone=US/Central` with a session zone
`America/Chicago` took the "different zones" branch, which reads zone-less
digits as wall time in the source zone: a `DATETIME` shifted by the source
offset (5 h or 6 h) although both ids are one zone. Equal-rules zones take the
same-zone branch everywhere.

Test: `ClickHouseDataTypeMapperTimeZoneTest.sameRulesDifferentIdIsSameZone()`
(`US/Central` vs `America/Chicago`, 2026-11-01 01:30 wall digits); the guard
for the other call sites is `TzSemanticsSourceScanTest` (§3.1.10).


#### 3.1.14 A lossy conversion is loud by default and refused only on request
A value stored as something other than the value MySQL held is a **lossy
conversion**. There are nine kinds, and each is produced (`decimal_truncate` by spec 07.09,
rule 5; `fraction_truncate` by spec 07.10, rule 7; `int_wrap` and `bool_collapse` for `BIGINT UNSIGNED` by spec 07.11, rule 8;
`fixedstring_nul` by spec 07.12, rule 9; `zero_timestamp` by §3.1.15, rule 10):

| Kind (`kind` label) | Happens when | Stored as |
|---|---|---|
| `range_clamp` | a temporal or decimal value is outside the ClickHouse range (§3.3) | the bound |
| `dst_gap` | a `DATETIME` wall time does not exist in the zone ClickHouse resolves it in (§3.1.1) | the instant with the offset before the transition (one hour later than the digits) |
| `dst_overlap` | a `DATETIME` wall time occurs twice in that zone (§3.1.1, §3.1.4) | the earlier occurrence |
| `bool_collapse` | an integer other than 0 or 1 is bound into a `Bool` column, or a `BIGINT UNSIGNED` value at or above 2^63 (spec 07.11) | `true` |
| `int_wrap` | an integer does not fit a narrower ClickHouse integer column, or a `BIGINT UNSIGNED` value at or above 2^63 is bound into a signed or narrower column (spec 07.11) | the value truncated to the column width |
| `decimal_truncate` | a decimal has more fractional digits than the column scale | the value truncated to the column scale |
| `fraction_truncate` | a `DATETIME` fraction has more digits than the `DateTime`, `DateTime32` or `DateTime64` column keeps (spec 07.10) | the fraction truncated to the column scale (a `DateTime` keeps no fraction) |
| `fixedstring_nul` | a text value whose last byte is NUL is bound into a `FixedString(N)` column, which reads a trailing NUL back as padding (spec 07.12) | the value without its trailing NUL bytes |
| `zero_timestamp` | a MySQL `TIMESTAMP` arrives as the instant 1970-01-01 00:00:00 UTC exactly, which is the zero TIMESTAMP `0000-00-00 00:00:00` (§3.1.15) | the epoch, 1970-01-01 00:00:00 UTC |

Rules, implemented by `LossyConversionReporter` (one reporter for every
converter) and `DebeziumConverter.RangePolicy`:
1. **Default (`clamp.out.of.range=true`): never blocking, never silent.** The
   value is stored as in the table above. Each conversion increments the counter
   `clickhouse_sink_lossy_conversions{kind="<kind>"}` (exposed on the Prometheus
   endpoint as `clickhouse_sink_lossy_conversions_total{kind="<kind>"}`, the
   simpleclient counter suffix), and `LossyConversionReporter.count(kind)`
   reads the same total in process. The first conversion of each `(kind, column)` pair is logged at
   **WARN** with the column label `db.table.column`, the source value and the
   stored value. Later conversions of an already-reported pair are counted only;
   per kind, one summary WARN at most every five minutes names how many were
   counted since the last summary. The log therefore has at most one line per
   column plus twelve per kind per hour, however many rows are lossy.
2. **`clamp.out.of.range=false` (opt-in): refused.** The conversion throws
   `DebeziumConverter.ValueOutOfRangeException` naming the column, the kind, the
   source value and the stored value that the default would have written, and the
   setting that would store it instead. The batch fails and the failure is
   terminal (spec 10.04 §3.5: the batch is not committed and the engine exits
   through the terminal-failure path); nothing is written. This applies to every
   kind the default reports.
3. The `dst_*` conversions are lossy for the reason in §3.1.1: no instant
   of the zone shows the digits of a gap, and the two occurrences of an overlap
   cannot be told apart from the digits. They are reported, not hidden, and
   the `DATETIME` wall time that the source wrote is not the stored value.
4. The counter is part of the connector metrics (`MetricsConstants`
   `CLICKHOUSE_SINK_LOSSY_CONVERSIONS`, help text naming the kinds) and is
   exposed on the same Prometheus endpoint as the other sink counters.
5. **`decimal_truncate` (spec 07.09).** A decimal with more fractional digits than the
   column scale is counted and logged once per column. `ClickHouseDataTypeMapper.reportDecimalScaleNarrowing`
   makes the report before the bind, with the target scale of the parsed column type
   (`ColumnBinding.column` in `PreparedStatementFieldMapper`). The value is stored as before.
   Under rule 2 the strict setting refuses it before the bind.
6. **Measured limit (F03, not changed): a `DateTime`/`DateTime32` value column
   does not take epoch text.** The bind path keeps the digits of §3.1.3
   for those types, so the overlap of §3.1.4 is reported under `dst_overlap` and
   not corrected. The end-to-end probe of that path (epoch text
   bound into `Nullable(DateTime('America/Chicago'))`)
   stored **NULL** for all four rows, with every batch acknowledged and no error
   logged. That is a silent loss worse than the overlap, so epoch text is not used
   for `DateTime` until a probe stores the exact instant through the same path.
7. **`fraction_truncate` (spec 07.10).** A `DATETIME` fraction (a Debezium `MicroTimestamp` or
   `Timestamp` count) with more digits than the `DateTime`, `DateTime32` or `DateTime64` column keeps
   is counted and logged once per column. `ClickHouseDataTypeMapper.reportFractionTruncation` makes the
   report before the bind, with the target scale of the parsed column type (`ColumnBinding.column` in
   `PreparedStatementFieldMapper`). The value is stored as before. Under rule 2 the strict setting
   refuses it before the bind.
8. **`int_wrap` and `bool_collapse` for `BIGINT UNSIGNED` (spec 07.11).** A `BIGINT UNSIGNED` value at or
   above 2^63 arrives as a negative `Long` (Debezium `bigint.unsigned.handling.mode=long`). Bound into an
   `Int64`, `Int32`, `Int16`, `Int8`, `UInt32`, `UInt16` or `UInt8` column it is counted as `int_wrap`, and
   bound into a `Bool` column as `bool_collapse`, once per value, with the unsigned decimal as the source.
   `ClickHouseDataTypeMapper.reportUnsignedBigintWrap` makes the report inside `convert(...)`, before the bind,
   and it replaces the narrowing report for that value. It needs the propagated source type
   (`__debezium.source.column.type`). A `UInt64` target is exact and is not reported. The value is stored as
   before. Under rule 2 the strict setting refuses it before the bind.
9. **`fixedstring_nul` (spec 07.12).** A text value whose last byte is NUL, bound into a `FixedString(N)`
   column, is counted and logged once per value: ClickHouse pads with NUL and reads the value back without
   it. `ClickHouseDataTypeMapper.reportFixedStringNul` makes the report before the bind. The value is bound
   unchanged. Under rule 2 the strict setting refuses it before the bind.
10. **`zero_timestamp` (§3.1.15).** A MySQL `TIMESTAMP` that arrives as the instant 1970-01-01 00:00:00 UTC
    exactly is counted and logged once per value, with the source `0000-00-00 00:00:00`. The value is bound
    as before. Under rule 2 the strict setting refuses it before the bind.

Reporter API (for every converter; package `converters`):
`LossyConversionReporter.report(Kind kind, String column, String source, String stored)`,
`LossyConversionReporter.count(Kind kind)` (in-process total),
`LossyConversionReporter.Kind` (`RANGE_CLAMP`, `DST_GAP`, `DST_OVERLAP`,
`BOOL_COLLAPSE`, `INT_WRAP`, `DECIMAL_TRUNCATE`, `FRACTION_TRUNCATE`, `FIXEDSTRING_NUL`, `ZERO_TIMESTAMP`;
metric label is the lower-case name), and `Metrics.incrementLossyConversion(String kind)`.

#### 3.1.15 A zero TIMESTAMP is reported
A legacy writer can leave the zero TIMESTAMP `0000-00-00 00:00:00` in a MySQL column when the server runs
without `NO_ZERO_DATE` (`sql_mode=''`). ClickHouse has no zero date, so the value has no counterpart there.
The zero `YEAR` is a separate case and is specified in spec 07.01 section 3.4.

1. **What the zero TIMESTAMP becomes.** MySQL stores a `TIMESTAMP` as UTC seconds. The smallest real value is
   `1970-01-01 00:00:01` UTC, so the instant `1970-01-01 00:00:00` UTC exactly can only be the zero
   TIMESTAMP. On the binlog path the connector receives that instant and stores the epoch, a legal
   ClickHouse value that differs from the source. Without a report the stored row cannot be told from a real
   value at the epoch, and the comparison tool reads the two as equal (measured on a real MySQL 8.0 source
   with a stream-read table, nullable and `NOT NULL` columns alike). `DebeziumConverter.ZonedTimestampConverter.convert(value, zone,
   targetType, policy, mysqlTimestamp)` reports it as `zero_timestamp` (source `0000-00-00 00:00:00`, stored
   `1970-01-01 00:00:00 UTC`) and binds what it bound before.
2. **The source type must be known.** A PostgreSQL `timestamptz` is delivered as the same Debezium
   `io.debezium.time.ZonedTimestamp`, and at the epoch it is a real value. `mysqlTimestamp` is true only
   when the propagated source column type (`__debezium.source.column.type`) is the whole word
   `TIMESTAMP` (any case, optional `(n)` suffix): `ClickHouseDataTypeMapper.isMysqlTimestampSource`. The
   connector sets `column.propagate.source.type=.*` when it is unset (`DebeziumChangeEventCapture`), so
   every field it produces carries the type. A record without the parameter is not reported, not guessed.
3. **The instant decides.** The check compares the parsed instant, so any rendering of it
   (`...Z`, `-06:00`, a fraction of zeros) is reported, and one microsecond later is not.
4. **Limits, measured and not changed.**
   - The snapshot read of a zero TIMESTAMP in a nullable column arrives as NULL: the JDBC driver converts it
     (`zeroDateTimeBehavior=CONVERT_TO_NULL` is part of Debezium's JDBC URL) before the connector sees it, so
     nothing is reported there. A `NOT NULL` column in the snapshot arrives as the epoch and is reported.
     Detect the rest on the source with `SELECT COUNT(*) FROM <t> WHERE <col> = 0` (measured on MySQL 8.0.46:
     it counts the zero rows under `sql_mode=''` and under the strict mode with `NO_ZERO_DATE`; comparing with the
     literal `'0000-00-00 00:00:00'` fails with error 1525 under the strict mode).
   - A zero `DATE` or `DATETIME` is not reported either; see FM-07.03-3.
   - Rows stored as the epoch by an earlier version stay as they are until the table is resynchronised.
5. **Verification.** Unit: `ZeroTimestampReportTest` (the converter) and `ZeroTimestampYearCallSiteTest` (the bind
   loop: the report, the strict refusal naming the column, a PostgreSQL `timestamptz` at the epoch not reported,
   no propagated type not reported). Invariant: no real MySQL `TIMESTAMP` arrives as the epoch, so the report fires
   exactly for the zero value from a MySQL source, and a type text names a `TIMESTAMP` only as the whole word.

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

### 3.3 Out-of-range values: saturate loudly by default, or refuse
ClickHouse temporal types are narrower than MySQL's: `DateTime64(p)` holds
`1900-01-01 00:00:00 .. 2299-12-31 23:59:59.<p nines>` for `p` 0..8 and
`1900-01-01 00:00:00 .. 2262-04-11 23:47:16.854775807` for `p` = 9 (the
bound is per scale, §3.1.8; measured on ClickHouse 24.8.8.17 and 25.8.16,
`probe_dt64_bounds`), `DateTime` holds
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
   saturate instead. The batch fails and the failure is terminal (spec 10.04
   §3.5); nothing is written. Remediation is on the ClickHouse side (a wider
   type — `Date32` for `Date`, `DateTime64` for `DateTime` — or a `String`
   column) or returning to the default.
2. `clamp.out.of.range=true` (**default**; a missing configuration means the
   default, `RangePolicy.of(null, column)` saturates): the value is saturated
   to the bound, and the saturation is **not silent** (§3.1.14):
   the first saturation of each column is logged at WARN with the source
   value, the stored value and the bounds; every saturation increments
   `clickhouse_sink_lossy_conversions{kind="range_clamp"}`; and later
   saturations of a column already reported are counted and summarised in one
   WARN per kind at most every five minutes. Replication is not blocked.
   The value MySQL held is not the value stored, so the loss is visible in the
   log and in the metric, not only at DEBUG. History of this rule: the first
   revision logged one WARN per saturated row — a single bitemporal table
   whose every row carries the `9999-12-31 23:59:59` open-ended sentinel
   produced 243,576 of the 248,541 lines (98%) of a connector log in 109 s.
   The second revision moved the saturation to DEBUG, which made a lossy
   conversion invisible, the defect this section now removes. Bounded logging
   (first sample per column, then a summary per kind per five minutes) and the
   counter replace both.
   Rationale for the default: the `9999-12-31 23:59:59` open-ended sentinel is
   customary in bitemporal source schemas, and with the strict policy as the
   default an upgrade stopped replication outright on every deployment holding
   one — the refusal is terminal for the batch (spec 10.04 §3.5) and for the
   engine (spec 10.04 §3.5 rule 4) — until an operator found and set the key.
   A replica that stops by default on a value every earlier release stored is
   a worse outcome than a reported saturation, so saturation is the default
   and strictness is the operator's explicit choice.
3. The converter overloads without a policy use `RangePolicy.CLAMP`: they saturate
   and report exactly as rule 2 does, with the column shown as `(unknown column)`.
   Every production bind path passes a policy built from the configuration.
4. PostgreSQL `timestamptz` `infinity` / `-infinity` (issue #1231) are not
   out-of-range numbers but values with no finite representation; they keep
   their documented saturation to the `DateTime64` bounds under either policy
   (ordering is preserved, and there is no source value to lose).

Upgrade note: the default saturates, so a deployment whose source holds
sentinel dates beyond the ClickHouse range keeps replicating after an upgrade
and stores the bound as before, now with one WARN per affected column and a
`clickhouse_sink_lossy_conversions` counter that shows the loss on the
dashboard (§3.1.14). A deployment that sees those WARNs and wants the source
value stored must widen the ClickHouse column (rule 1 remediation). A deployment that would rather stop on such a value
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
- `DebeziumConverterTest.testTimestampConverterSameZoneGapAndOverlapAreResolvedByTheTransitionRules()` — §3.1.1:
  `DATETIME` digits `2026-03-08 02:30:00` (inside the `America/Chicago`
  spring-forward gap) bind the offset-before-transition instant (`08:30:00` UTC,
  epoch text `1772958600.000000` into a `DateTime64`, `2026-03-08 03:30:00` into a
  `DateTime`), and `2026-11-01 01:30:00` (the repeated fall-back hour) binds the
  earlier occurrence (`06:30:00` UTC), when source and session zones are both
  `America/Chicago`; pre-fix `TimestampConverter` returned `01:30:00` for the
  gap time, silently. With source `America/Chicago` and session `UTC` the gap digits use
  the same pre-transition offset, matching `MicroTimestampConverter`. The shift of
  a gap or overlap value is reported (§3.1.14), never silent.
- `PreparedStatementFieldMapperColumnZoneTest.testTimestampIntoUtcColumnWhenSessionZoneIsChicago()`
  — §3.1.3/§3.1.4 end to end through `insertPreparedStatement`: `ZonedTimestamp`
  `2022-01-01T16:00:00Z` into a `Nullable(DateTime64(6, 'UTC'))` column with
  session zone `America/Chicago` binds the instant as epoch text
  `1641052800.000000` (the §3.1.3 revision bound `2022-01-01 16:00:00.000000`;
  the pre-§3.1.3 code `10:00:00.000000`, an instant six hours early).
- `PreparedStatementFieldMapperColumnZoneTest.testBlankSourceZoneIsRefusedNotKeptAsDigits()`
  — §3.1.2: a blank `database.connectionTimeZone` is refused when the config is
  built (`ConfigException` naming the key), so no DATETIME digits are kept unzoned.
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
- `DebeziumConverterTest.testTimestampConverterSameZoneGapAndOverlapAreResolvedByTheTransitionRules()`
  — the same-zone decode resolves a gap or an overlap by the transition rules of
  §3.1.1: the Chicago gap time `02:30` binds the offset-before-transition instant
  as epoch text for `DateTime64` (`1772958600.000000`) and the zone wall time of
  that instant for `DateTime` (`2026-03-08 03:30:00`); the overlap binds the
  earlier occurrence (`1793514600.000000`); the different-zones assertions
  (`08:30:00 UTC`, `15:00:00 UTC`) are epoch text under §3.1.4
  (`1772958600.000000`, `1782918000.000000`).
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
- `ClickHouseDataTypeMapperTimeZoneTest` — `resolveSourceTimeZone` (unset → refused,
  set → used as given, garbage → throws) and `columnTimeZone` (parses
  `Nullable(DateTime64(3, 'UTC'))`, `DateTime('Europe/London')`; null for
  `DateTime64(6)` and `String`).
- `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper()`,
  `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDateAtTheMapper()`
  — §3.3 rule 2 as the default, through `ClickHouseDataTypeMapper.convert`
  with an empty configuration (and `RangePolicy.of(null, column)` with none):
  `DATETIME 9999-12-31 23:59:59` into `DateTime64` binds
  `2299-12-31 23:59:59.999` and writes one WARN (§3.1.14), an in-range value
  writes none; `DATE 9999-12-31` into `Date` binds `2149-06-06` and `DATE 1000-01-01`
  into `Date32` binds `1900-01-01`. A strict default throws instead, so the tests fail on it.
- `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDatetimeAtTheMapper()`,
  `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDateAtTheMapper()`
  — §3.3 rule 1 through `ClickHouseDataTypeMapper.convert` with
  `clamp.out.of.range=false`: the same values throw `ValueOutOfRangeException`
  naming the setting, and nothing is bound.
- `DebeziumConverterRangePolicyTest.clampSettingSaturatesLoudlyOncePerColumn()` — rule 2:
  `clamp.out.of.range=true` binds the bound for 1,000 saturations of one
  column plus one of another, writes exactly two WARN lines (one per column,
  each naming the column and both values), and counts 1,001 `range_clamp`
  conversions. A revision that is silent, or that logs per row, fails on it.
- `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()` —
  the exception text of rule 1 for `DateTime`, `DateTime64`, `Date`, `Date32`,
  `ZonedTimestamp` and decimal.
- `DebeziumConverterRangePolicyTest.legacyOverloadsSaturateLoudly()` —
  rule 3: the policy-less overloads saturate every bounded type, write one WARN
  for the `(unknown column)` label and count all four saturations.
- `LossyConversionTest` (§3.1.14 and §3.1.1): the DST
  gap and overlap binds (`sameZoneGapBindsOffsetBeforeInstant`,
  `sameZoneMicroGapIntoDateTimeRendersColumnWall`, `sameZoneOverlapBindsEarlierOccurrence`,
  `unambiguousSameZoneIsUnchanged`, `differentZoneGapBindsOffsetBeforeInstant`,
  `secondOccurrenceInColumnZoneIsReported`), the strict refusals (`strictRefusesDstGap`,
  `strictRefusesBoolCollapse`), the counters and WARN rules (`clampWarnsOncePerColumnAndCountsEveryValue`,
  `summaryIsRateLimited`, `boolCollapseIsCounted`, `intWrapIsCountedWithStoredValue`).
  Each was mutation-checked: removing or inverting the hunk it names turns the test red.
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
  module `tests/integration/tests/datatypes.py`, loaded as `tests.datatypes`, which carries the DateTime requirement
  `RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime`) assert the BOUNDED values on purpose (`9999-12-31` ->
  `2299-12-31`, `1000-01-01` -> `1900-01-01`); they run the connector with the
  default, stated explicitly as `clamp.out.of.range=true` in
  `helpers/default_config.py` and the `env/*/config.yml` files. The strict
  setting (rule 1) is pinned by the unit tests above; under the
  previous retriable classification of the refusal (Spec 10.01 before its
  terminal-exception rule) the refused batch parked forever and every later
  suite test failed on a timeout.
- §3.1.5 and §3.1.2: `ConnectionTimeZonePreflightTest.unsetSourceIsRefusedBeforeConnection()`, `ConnectionTimeZonePreflightTest.blankSourceIsRefused()`, `ConnectionTimeZonePreflightTest.unsetClickHouseIsRefused()`, `ConnectionTimeZonePreflightTest.emptyClickHouseIsRefused()`, `ConnectionTimeZonePreflightTest.serverKeywordIsRefused()` (an unset, blank or `SERVER` source zone is refused before any connection); `ConnectionTimeZonePreflightTest.explicitUtcPasses()`, `ConnectionTimeZonePreflightTest.namedZoneResolves()` (configured zones pass); `ConnectionTimeZonePreflightTest.abbreviationIsRefusedWithCandidates()` (`EST` is refused with the region ids it could mean); `ConnectionTimeZonePreflightTest.postgresWithoutZonesIsRefused()`, `ConnectionTimeZonePreflightTest.postgresWithZonesPasses()` (the same rule on a PostgreSQL connector); `ConnectionTimeZonePreflightTest.driverAgreesWithThePreflight()` — pins the driver contract the rule relies on (`CDT` throws, `America/Chicago` resolves).
- §3.1.6: `TimeZoneResolverTest.regionIdsResolveToTheSameIdForBothKinds()`, `TimeZoneResolverTest.refusesAbbreviationsKeywordsAndZUnambiguously()`, `TimeZoneResolverTest.refusesPrefixFormsAndWrongCase()`, `TimeZoneResolverTest.offsetsAreRefusedForClickHouseAndAcceptedForSource()`, `TimeZoneResolverTest.surroundingWhitespaceOnTheRawValueIsRefused()`, `TimeZoneResolverTest.misspelledZoneKeysAreRefused()`, `TimeZoneResolverTest.rawWhitespaceCheckReadsTheUntrimmedProperties()`, `TimeZoneResolverTest.tzdataIsPresentInThisJvm()`; `ClickHouseSinkConnectorConfigTimeZoneTest.badClickHouseZoneIsAConfigException()`, `ClickHouseSinkConnectorConfigTimeZoneTest.misspelledSourceKeyIsAConfigExceptionNamingTheRightKey()`; `ConnectionTimeZonePreflightTest.misspelledSourceKeyIsRefused()`, `ConnectionTimeZonePreflightTest.untrimmedValueIsRefused()` (a misspelled key or an untrimmed zone is refused before any connection).
- §3.1.6, loud failures: `DBMetadataServerTimeZoneTest.resolvedZoneIsReturned()`, `DBMetadataServerTimeZoneTest.nullConnectionFailsLoudly()`, `DBMetadataServerTimeZoneTest.queryFailureFailsLoudly()`, `DBMetadataServerTimeZoneTest.emptyResultFailsLoudly()`, `DBMetadataServerTimeZoneTest.unresolvableZoneFailsLoudly()` (no UTC fallback); `BinLogHistoryTest.timeColumnIgnoresEmptySourceZoneAndRefusesEmptyServerZone()` (a null or blank server zone is refused, an empty source zone is not used).
- §3.1.5: `ConnectionTimeZonePreflightTest.unsetSourceIsRefusedBeforeConnection()` (refused before any connection, naming the key and `UTC`).
- §3.1.6, startup cross-check: `ClickHouseZoneCrossCheckTest.serverMismatchIsReported()`, `ClickHouseZoneCrossCheckTest.columnMismatchIsReportedByName()`, `ClickHouseZoneCrossCheckTest.unsetConfiguredZoneIsRefused()`, `ClickHouseZoneCrossCheckTest.viewColumnsAreNotReported()`, `ClickHouseZoneCrossCheckTest.agreementHasNoFindings()`, `ClickHouseZoneCrossCheckTest.unreadableServerZoneThrows()`, `ClickHouseZoneCrossCheckTest.zoneOfTypeReadsExplicitZones()` (fake metadata source). The JDBC source (`JdbcZoneMetadataSource`) has no unit test and no integration run as part of this change.

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
  - **Test**: `DebeziumConverterRangePolicyTest.defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper()`, `DebeziumConverterRangePolicyTest.clampSettingSaturatesLoudlyOncePerColumn()`, `LossyConversionTest.clampWarnsOncePerColumnAndCountsEveryValue()`.
  - **DEFECT** (baseline, fixed in §3.1.14): saturation was uncounted. The fix counts every saturation on `clickhouse_sink_lossy_conversions_total{kind="range_clamp"}` and logs one WARN per column (the log is not flooded, §3.3), so the detection gap is closed without a per-column metric.

- **FM-07.03-2 Out-of-range value refused under `clamp.out.of.range=false`**
  - **Trigger**: as FM-07.03-1 with the strict setting.
  - **Behaviour**: `RangePolicy.lossy` throws `DebeziumConverter.ValueOutOfRangeException` (for the range kinds, and for the DST, Bool and integer-wrap kinds of §3.1.14 under the same setting); `ClickHouseErrorClassifier` classifies it FATAL (`TERMINAL_EXCEPTION_TYPES`); the worker dies, the engine stops on the next source batch and the process exits 3 (spec 10.04 §3.5). Nothing of the batch is written.
  - **Detection**: ERROR `Value <v> for column <db.t.c> cannot be stored exactly in ClickHouse (<kind>). The default would store <stored> instead, and the source never held that value, so the batch is refused. Either widen or retype the ClickHouse column, or set clamp.out.of.range=true to store the value and count it in clickhouse_sink_lossy_conversions_total{kind="<kind>"} (logged at WARN once per column).`, ERROR `FATAL ClickHouse error (Code: -1) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit code 3 — within ≤ 5 s (heartbeat) of the failing batch; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: the whole connector stops; no data lost, nothing diverges; offsets stay at the last committed transaction.
  - **Recovery**: P-FIX-TYPE (`String`, or `Date32`/`DateTime64` where the value fits), or set `clamp.out.of.range=true` (accepting FM-07.03-1) and restart.
  - **RTO**: config edit or `ALTER` + `RestartSec=30` + engine start + re-apply of the in-flight transaction ≈ 1–2 min; unmeasured.
  - **Test**: `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDatetimeAtTheMapper()`, `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDateAtTheMapper()`, `LossyConversionTest.strictRefusesDstGap()`, `LossyConversionTest.strictRefusesBoolCollapse()`, `ClickHouseErrorClassifierTest.valueOutOfRangeIsFatalRegardlessOfCode()`, `TerminalFailureExitTest.fatalTerminalTypeIsNotRetried()`.

- **FM-07.03-3 Zero or invalid date (`0000-00-00`, `2020-00-15`)**
  - **Trigger**: a permissive `sql_mode` on the source lets MySQL store a zero date (or a zero month/day) in a `DATE`/`DATETIME` column.
  - **Behaviour**: decided by Debezium before connector code runs: `JdbcValueConverters.convertValue` (debezium-core 3.1.3, bytecode) returns `null` for a missing value of an optional column and, for a `NOT NULL` column, the Connect schema default (the MySQL column `DEFAULT`) or the converter's fallback (`convertDateToEpochDays` passes epoch day 0, i.e. `1970-01-01`). How the binlog client presents the zero date to that converter was not verified in bytecode. The connector then binds that value (NULL, the default, or 1970-01-01).
  - **Detection**: none on the binlog path. Debezium's `BinlogValueConverters.containsZeroValuesInDatePart` logs WARN `Invalid value '<v>' stored in column '<c>' of table '<t>' converted to empty value` only on its string-parsing path (snapshot). DEFECT.
  - **Blast radius**: silent substitution on the affected rows; row counts intact. ClickHouse has no zero date, so no column type can hold the source value.
  - **Recovery**: fix the source rows (`UPDATE ... SET d = NULL` or a real date) — the UPDATE replicates and supersedes the substituted value; or map the column to `String` and P-RESYNC.
  - **RTO**: unbounded detection; after detection one source UPDATE (seconds) per affected set; unmeasured.
  - **Test**: not yet covered: an end-to-end test that a `0000-00-00` in a `NOT NULL DATE` column is either refused or reported, instead of arriving as the default or `1970-01-01`.
  - **DEFECT**: a source value is replaced by a different one with no signal from the connector.

- **FM-07.03-4 Zone configuration mistakes**
  - **Trigger**: (a) `database.connectionTimeZone` unset or `SERVER` (for example, when the source's `@@time_zone` resolves to the ambiguous abbreviation `CDT`); (b) an unparseable `database.connectionTimeZone`; (c) an unparseable `clickhouse.datetime.timezone`.
  - **Behaviour**: (a) `ConnectionTimeZonePreflight.check` throws `IllegalStateException("Refusing to start: ...")` before the engine is built (§3.1.5). (b) An unparseable `database.connectionTimeZone` is refused at start by `ClickHouseSinkConnectorConfig` (`ConfigException`) and the preflight (§3.1.6); `resolveSourceTimeZone` is never reached with it. (c) An unparseable `clickhouse.datetime.timezone` is refused at start by the same validator; every consumer that is reached with a bad value throws (§3.1.6) rather than logging and falling back to the server zone or to no zone.
  - **Detection**: (a) ERROR banner `!!  REFUSING TO START: ...` naming `database.connectionTimeZone`, at start. (b) and (c) `ConfigException` at start, naming the property; a batch that somehow reaches a bad zone fails with `InvalidTimeZoneException`, not a silent zone.
  - **Blast radius**: (a) and (b) and (c) at start: nothing starts, nothing lost. If a bad value is reached per batch (only through a path that skips config construction), the affected tables stop and offsets freeze, no loss.
  - **Recovery**: set the key to the IANA zone of the source host / a valid zone id and restart. If a `DateTime`/`String` column was written in a silent fallback zone by an earlier release, P-RESYNC those columns.
  - **RTO**: config edit + restart ≈ 1 min; resync where it applies; unmeasured.
  - **Test**: `ConnectionTimeZonePreflightTest.unsetSourceIsRefusedBeforeConnection()` (a); `ClickHouseSinkConnectorConfigTimeZoneTest.badClickHouseZoneIsAConfigException()` and `ClickHouseDataTypeMapperTimeZoneTest.garbageSourceZoneThrows()` (b, c); `DBMetadataServerTimeZoneTest.queryFailureFailsLoudly()`, `DBMetadataServerTimeZoneTest.nullConnectionFailsLoudly()`, `DBMetadataServerTimeZoneTest.emptyResultFailsLoudly()`, `DBMetadataServerTimeZoneTest.unresolvableZoneFailsLoudly()` (no UTC fallback); `BinLogHistoryTest.timeColumnIgnoresEmptySourceZoneAndRefusesEmptyServerZone()`. Not yet covered: the DDL-path refusals (`MySqlDDLParserListenerImpl.parseTimeZone`, `ClickHouseAutoCreateTable.historyDateTimeColumnType`) are not reached by a unit test, because config construction refuses the same values first.
  - **DEFECT**: none open in this row. §3.1.6 makes an unresolvable zone refuse at start or throw, never fall back.

- **FM-07.03-5 DST ambiguity in `DateTime` (32-bit) and DDL-created DST-zone columns**
  - **Trigger**: a `TIMESTAMP` in the fall-back overlap hour bound into a `DateTime`/`DateTime32` column, or a `DATETIME` spring-forward gap time into a DDL-created `DateTime64(p, '<DST zone>')` column (§3.1.3 residual, §3.1.4 exclusions).
  - **Behaviour**: `DateTime` rejects epoch text, so `DebeziumConverter.bindsAsEpochText` keeps the digits rendering; ClickHouse resolves ambiguous digits to the first occurrence and shifts gap digits (measured, §3.1.3/§3.1.4). The batch succeeds.
  - **Detection**: none. DEFECT (documented residual).
  - **Blast radius**: one-hour shift on rows inside the transition hour; row counts intact.
  - **Recovery**: P-FIX-TYPE to `DateTime64(p, 'UTC')` (or `DateTime64` for the `DateTime` column), then P-RESYNC the rows of the transition hours.
  - **RTO**: unbounded detection; after detection minutes + resync; unmeasured.
  - **Test**: `PreparedStatementFieldMapperColumnZoneTest.testOverlapInstantBindsExactEpochIntoChicagoColumn()` (pins that a `DateTime` target keeps the digits); not yet covered: a test that a DDL-created `DATETIME` column is declared in `'UTC'`.
  - **DEFECT**: the value path cannot store an overlap-hour instant exactly in a `DateTime` column, and the DDL path still declares `DATETIME` columns in the session zone.

- **FM-07.03-13 SCD2 open-row sentinel written or matched in a zone other than the column's**
  - **Trigger**: a history table whose `_valid_to` DEFAULT, sentinel bind or closing predicate was rendered as wall digits in a zone that differs from another of the three (§3.1.12), e.g. `clickhouse.datetime.timezone=America/Chicago` on a UTC-column table.
  - **Behaviour**: rows written with the DEFAULT carry `4102466400` (6 h after the sentinel) and are never closed by the bulk-close predicate, so the history shows two current rows for one key.
  - **Detection**: `SELECT count() FROM <history> WHERE _valid_to > toDateTime(4102444800)` returns rows whose `_valid_to` is not the sentinel and not a close time; no ERROR is logged.
  - **Blast radius**: history correctness only (SCD2 `FINAL` views); data tables and replication are unaffected.
  - **Recovery**: tables created before this revision keep their DEFAULT (ClickHouse `ALTER ... MODIFY COLUMN _valid_to DateTime DEFAULT 4102444800` is an operator step, not performed by the connector); the connector never relies on the DEFAULT for its own writes, which bind `deleted_time` explicitly.
  - **Test**: `ReplicationHistoryEpochLiteralTest.sentinelIsTheEpochOfTheOpenRowInstant()`, `ReplicationHistoryEpochLiteralTest.deletedTimeDefaultIsEpochExact()`.
- **FM-07.03-6 Unparseable `ZonedTimestamp` text**
  - **Trigger**: a PostgreSQL `timestamptz` whose Debezium text matches none of the accepted ISO-8601 forms (e.g. a BC date), other than `infinity`/`-infinity`.
  - **Behaviour**: `ZonedTimestampConverter.boundedInstant` throws `IllegalArgumentException` (§3.3 of spec 07.06); classified UNKNOWN, retried forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception` with `ZonedTimestamp value '<v>' for column <c> matches none of the accepted ISO-8601 forms; refusing to store an empty string in its place` and the WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: the batch thread's tables stop and offsets freeze; nothing lost.
  - **Recovery**: none by retry; P-FIX-TYPE cannot help (the refusal is in the connector); fix the source value and P-SKIP + resync the transaction's tables.
  - **RTO**: unbounded; P-SKIP + resync; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.unparseableZonedTimestampThrows()` pins the refusal; not yet covered: its classification as terminal (same root defect as `PoisonValueClassificationTest.spatialValueRefusalIsFatal()`).
  - **DEFECT**: a deterministic refusal raised by the connector is retried forever instead of stopping the engine.

- **FM-07.03-7 Year-below-100 remap re-enabled by configuration**
  - **Trigger**: a configuration sets `enable.time.adjuster=true` explicitly.
  - **Behaviour**: `DebeziumChangeEventCapture.ensureTimeAdjusterDisabled` leaves it (§3.4); Debezium remaps `0001-01-01` to `2001-01-01` before the connector sees it.
  - **Detection**: WARN at start `enable.time.adjuster=true is set by configuration: Debezium will remap years below 100 into 1970-2069 ...`; nothing per row.
  - **Blast radius**: silent value change on rows with years below 100.
  - **Recovery**: remove the key (or set `false`), restart, P-RESYNC the affected tables.
  - **RTO**: restart ≈ 1 min + resync; unmeasured.
  - **Test**: `TimeAdjusterDefaultTest.explicitValueWins()`, `TimeAdjusterDefaultTest.absentIsForcedToFalse()`.

- **FM-07.03-8 Zone property refused at start (§3.1.6)**
  - **Trigger**: `clickhouse.datetime.timezone` or `database.connectionTimeZone` holds a value that is not a zone for its property (`EST`, `CDT`, `SYSTEM`, `+05:30` for the ClickHouse key, `UTC+5`, lower-case ids, surrounding whitespace), or a misspelled key (`database.connectionTimezone`), or the JVM has no tzdata.
  - **Behaviour**: the connector config refuses to build (Kafka `ConfigException` naming the property); the lightweight preflight refuses before any query (`IllegalStateException("Refusing to start: ...")`). No batch is attempted with the bad zone.
  - **Detection**: the ERROR banner `!!  REFUSING TO START` naming the property and the value (preflight), or the `ConfigException` message at connector start.
  - **Blast radius**: none beyond the start; no data is written with the bad zone.
  - **Recovery**: set the property to an IANA region id (or, for `database.connectionTimeZone`, an offset) and restart.
  - **RTO**: config edit and restart, about 1 minute; measured by the unit tests only.
  - **Test**: `TimeZoneResolverTest.refusesAbbreviationsKeywordsAndZUnambiguously()`, `ClickHouseSinkConnectorConfigTimeZoneTest.badClickHouseZoneIsAConfigException()`, `ConnectionTimeZonePreflightTest.misspelledSourceKeyIsRefused()`, `ConnectionTimeZonePreflightTest.untrimmedValueIsRefused()`.

- **FM-07.03-9 Unset `database.connectionTimeZone` or `clickhouse.datetime.timezone` refused (§3.1.2)**
  - **Trigger**: either key is absent, empty or blank, or `database.connectionTimeZone=SERVER`.
  - **Behaviour**: the start is refused before any connection (lightweight engine, MySQL and PostgreSQL: `IllegalStateException("Refusing to start: ...")`; Kafka Connect: `ConfigException` at config construction). No batch, history or DDL statement runs with an unset zone.
  - **Detection**: the message names the key and a suggested value, for example `database.connectionTimeZone is not set. Set it to the IANA zone of the source's time_zone, for example database.connectionTimeZone=UTC`.


- **FM-07.03-10 The source's session zone is not read (§3.1.5)**
  - **Trigger**: any start with both keys set.
  - **Behaviour**: no session zone query decides a conversion; the configured zone is the zone. A source whose session zone differs from the configured zone is not detected by the connector: the operator's declaration is the contract.
  - **Detection**: the `Effective time zones` INFO line at start.


- **FM-07.03-11 A DateTime column whose declared zone differs from the configured zone (§3.1.6)**
  - **Trigger**: a DateTime or DateTime64 column of a base table declares a zone other than `clickhouse.datetime.timezone`.
  - **Behaviour**: one ERROR per column; startup continues. A view is not reported.
  - **Detection**: ERROR `ClickHouse timezone cross-check: column <db>.<table>.<col> declares zone '<z>' but the sink's reference zone is '<ref>' ...`.


- **FM-07.03-12 Upgrade: what changes for existing deployed tables and history rows (§3.1.6)**
  - **Both keys set**: the start is unchanged. No table is altered, and history rows keep their rendering.
  - **A key unset**: the connector refuses to start and names the key. The fix is one line in the config: `database.connectionTimeZone=<zone>` and/or `clickhouse.datetime.timezone=<zone>`, using the zone the source and the ClickHouse columns really use.
  - **Existing tables** keep their DDL zone. The `DateTime('<zone>')` type applies only to tables created after the upgrade.

Summary: 12 failure modes, 4 DEFECT, 7 test gaps.
