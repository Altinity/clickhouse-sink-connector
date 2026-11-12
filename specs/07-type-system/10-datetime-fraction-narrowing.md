# Spec 07.10: A DATETIME Fraction With More Digits Than The Column Is Reported

## 1. Executive Summary & Purpose
A MySQL `DATETIME(n)` value with `n` fractional digits is delivered by Debezium as `io.debezium.time.MicroTimestamp` (`n` of 4 to 6, an `INT64` count of microseconds) or `io.debezium.time.Timestamp` (`n` of 0 to 3, an `INT64` count of milliseconds). The connector binds its zone-less digits as text, and ClickHouse keeps only the leading digits of the fraction that the bound `DateTime64(S)` column declares, dropping the rest. A `DateTime` or `DateTime32` column keeps no fraction at all. Before this change that loss was silent. This spec makes it loud through the one lossy-conversion reporter (kind `FRACTION_TRUNCATE`, spec 07.03 section 3.1.14). The default policy (`clamp.out.of.range=true`) stores the value as before and counts it; the opt-in strict policy refuses it, as section 3.1.14 rule 2 requires for every lossy kind. The stored value is never changed.

---

## 2. Codebase Mapping
- **Source value (library)**: Debezium maps MySQL `DATETIME(4..6)` to `io.debezium.time.MicroTimestamp` and `DATETIME(0..3)` to `io.debezium.time.Timestamp`. Both are `INT64` values with no zone (spec 07.03 section 3.1.1).
- **Bind text (this repo)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java`. `MicroTimestampConverter.convert(...)` renders the wall digits with six fraction digits (`MICROS_FORMAT`); `TimestampConverter.convert(...)` renders three (`MILLIS_FORMAT`). A `DateTime` or `DateTime32` target gets whole seconds (`SECONDS_FORMAT`).
- **Target scale**: `ClickHouseColumn.getScale()` of the parsed declared type. For `DateTime` and `DateTime32` the scale is 0. `PreparedStatementFieldMapper.columnBinding(...)` parses the declared type once per column and keeps it in `ColumnBinding.column`.
- **Report point (new)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`, the bind loop, immediately before `ClickHouseDataTypeMapper.convert(...)`, calls `ClickHouseDataTypeMapper.reportFractionTruncation(value, schemaName, binding.column, rangePolicy)`.
- **Reporter**: `LossyConversionReporter.Kind.FRACTION_TRUNCATE` (label `fraction_truncate`, new). Rate limiting, the first-per-column WARN and the metric are the reporter's (spec 07.03 section 3.1.14).
- **Metric help text**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/MetricsConstants.java`, the help string of `CLICKHOUSE_SINK_LOSSY_CONVERSIONS`, lists the kinds.
- **Not changed**: the `ZonedTimestamp` (`TIMESTAMP`) and `NanoTimestamp` branches. They are not reported (section 6, FM-07.10-3).

---

## 3. Operational Specification

### 3.1 Which values are reported
A value is reported when all of the following hold:
1. The value is a `Long` (a micro or milli count from Debezium; null is never reported).
2. The schema name is `io.debezium.time.MicroTimestamp` (six source fraction digits) or `io.debezium.time.Timestamp` (three source fraction digits).
3. The bound ClickHouse column is a `DateTime`, `DateTime32` or `DateTime64` type (the inner type of a `Nullable(...)` declaration is used), and its scale `S` is smaller than the source precision.
4. The fraction of the second has a digit beyond `S`. The fraction is `Math.floorMod(value, 10^p)` where `p` is the source precision, so a value before the epoch uses its positive fraction (`-1` microsecond is the fraction `999999`). A fraction whose digits beyond `S` are all zero is not reported (`123000` micros into `DateTime64(3)` is `.123`, exact).

The kept digits are the leading `S` digits of the fraction, truncated and not rounded: `123456` micros into `DateTime64(3)` keeps `123`. This is the behaviour of ClickHouse when it parses a string with more fraction digits than the column scale, observed in the datetime-fraction end-to-end scenario on the base jar.

### 3.2 What is reported
- **Kind**: `FRACTION_TRUNCATE`.
- **Column**: the `db.table.column` label of the range policy.
- **Source**: the source fraction digits, `p` digits, zero-padded (`123456` for `.123456`).
- **Stored**: the kept digits, `S` digits (`123`), or the empty string for a `DateTime` or `DateTime32` target, which keeps no fraction.
- The report is made before the bind, as the decimal narrowing report is (spec 07.09 section 3.2).

### 3.3 Outcome by policy
- **Default (`clamp.out.of.range=true`)**: the value is bound unchanged, the conversion is counted (`clickhouse_sink_lossy_conversions_total{kind="fraction_truncate"}`), and the first occurrence per column is logged at WARN with the marker `LOSSY_CONVERSION`.
- **Strict (`clamp.out.of.range=false`)**: `DebeziumConverter.RangePolicy.lossy` throws `DebeziumConverter.ValueOutOfRangeException` before the bind. The batch fails through the terminal path of spec 07.03 section 3.1.14, and nothing is written.

---

## 4. Invariants Preserved
- **I9 loud failure**: a stored fraction that differs from the MySQL value is reported, by default in the counter and the WARN, under strict as a refusal.
- **I11 upgrade safety**: the bound text, the column type and the schema are unchanged. Only a report is added.
- **No change to stored values**: the bind is byte-identical to the base jar. Only the reporting differs.

---

## 5. Verification Criteria
- Unit: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/converters/FractionTruncationReportTest.java`
  - `microFractionBeyondDateTime64ScaleIsCounted`: `.123456` micros into `Nullable(DateTime64(3))` is counted once; the kept digits are `123`.
  - `microFractionWithNoDigitsBeyondScaleIsNotCounted`: `.123000` micros into `Nullable(DateTime64(3))` is not counted.
  - `sameScaleIsNotCounted`: `.123456` micros into `DateTime64(6)` is not counted.
  - `wholeSecondIntoDateTimeIsNotCounted`: a whole second into `DateTime` is not counted.
  - `fractionIntoDateTimeIsCountedAndDropped`: `.500000` micros into `DateTime` is counted once; the kept digits are empty.
  - `millisFractionBeyondDateTime64ScaleIsCounted`: `.123` millis into `DateTime64(2)` is counted once; the kept digits are `12`.
  - `millisFractionWithinScaleIsNotCounted`: `.123` millis into `DateTime64(3)` is not counted.
  - `preEpochFractionUsesTheFractionOfTheSecond`: `-1` microsecond into `DateTime64(3)` is counted once; the kept digits are `999`.
  - `nonLongValuesNonTemporalTargetsAndOtherSchemasAreNotCounted`: a null, a string, an `Int64` target and a `io.debezium.time.Date` schema are not counted.
  - `strictModeRefusesBeforeBind`: under `clamp.out.of.range=false` the value throws `ValueOutOfRangeException`.
- Mutation (must fail): removing the `policy.lossy(...)` call from `reportFractionTruncation` fails `microFractionBeyondDateTime64ScaleIsCounted`, `millisFractionBeyondDateTime64ScaleIsCounted`, `fractionIntoDateTimeIsCountedAndDropped`, `preEpochFractionUsesTheFractionOfTheSecond` and `strictModeRefusesBeforeBind`.
- End to end (cross-engine scenarios: a `DATETIME(6)` column bound into `Nullable(DateTime64(3))`, `EXPECT=DIVERGED` on the base jar; a control table whose `DATETIME` digits fit the column, `EXPECT=PASS`): base jar DIVERGED with 0 lossy lines; fix jar DIVERGED with one or more `kind=fraction_truncate` lines; control PASS with 0 lossy lines.
- Invariants: the kept digits are a prefix of the source fraction and have exactly `S` digits; the kept fraction is the source fraction when it has no digit beyond `S`; the report fires exactly when a digit beyond `S` is present; and the kept fraction never exceeds the source fraction.

---

## 6. Failure Modes & Recovery

Recovery posture: the change is stateless. Rolling back the jar restores the silent truncation for new events; no offset or schema change is needed. Rows already stored truncated are not rewritten by the connector; they need a targeted re-snapshot or re-replication of the affected rows (procedures P-FIX-TYPE and P-RESYNC, spec 07.01 section 6).

- **FM-07.10-1 DATETIME fraction narrowing stored silently**
  - **Trigger**: a MySQL `DATETIME(n)` value with a fraction digit beyond the scale `S` of the bound `DateTime64(S)` column, or any fraction bound into a `DateTime` or `DateTime32` column.
  - **Behaviour (before)**: ClickHouse drops the digits beyond `S`. The connector reports nothing; the row count is intact and the stored value differs from MySQL.
  - **Behaviour (after)**: unchanged stored value; counted under `fraction_truncate` and logged once per column (default), or refused (strict).
  - **Status**: DEFECT, FIXED to loud (the stored value is not corrected). Reproduced on the base jar (the datetime-fraction end-to-end scenario, DIVERGED, 0 lossy lines).
  - **Detection (before)**: none. **Detection (after)**: the `fraction_truncate` counter and the `LOSSY_CONVERSION kind=fraction_truncate` WARN.
  - **Blast radius**: every row of a narrowed temporal column whose fraction has digits beyond the column scale. Rows whose fraction fits are unaffected.
  - **Test**: section 5 unit tests and the end-to-end scenario.
  - **RTO**: new events are loud from the first batch after the jar restart (restart time, unmeasured). Rows already stored need P-RESYNC of the affected tables (unmeasured).

- **FM-07.10-2 Fraction within the column scale, or whole seconds**
  - **Trigger**: `.123000` micros into `DateTime64(3)`, `.123456` micros into `DateTime64(6)`, or a whole second into `DateTime`.
  - **Behaviour**: not reported; the stored value equals the MySQL value.
  - **Status**: by design (no false positive). **Test**: `microFractionWithNoDigitsBeyondScaleIsNotCounted`, `sameScaleIsNotCounted`, `wholeSecondIntoDateTimeIsNotCounted`.
  - **Detection**: not needed. **Blast radius**: none.
  - **RTO**: not applicable.

- **FM-07.10-3 TIMESTAMP (`ZonedTimestamp`) and `NanoTimestamp` fractions**
  - **Trigger**: a `TIMESTAMP(n)` value (`io.debezium.time.ZonedTimestamp`) or a nanosecond value (`io.debezium.time.NanoTimestamp`) bound into a `DateTime64(S)` column with `S` smaller than the source precision.
  - **Behaviour**: not reported. The helper checks only the micro and milli schemas.
  - **Status**: NOT COVERED by this change (open). The end-to-end scenarios do not run a narrowed `TIMESTAMP` or nanosecond column. Not tested end to end.
  - **Detection**: none. **Blast radius**: unknown for this configuration; none observed.
  - **Recovery**: a follow-up change must extend the helper to these schema names through the same reporter, with the same column scale rule.
  - **RTO**: not applicable while open.

- **FM-07.10-4 Strict refusal of a fraction**
  - **Trigger**: `clamp.out.of.range=false` and a value that FM-07.10-1 would count.
  - **Behaviour**: the batch fails with `ValueOutOfRangeException` before the bind (spec 07.03 section 3.1.14 rule 2). Nothing is written, and the failure is terminal.
  - **Status**: by design (strict is an opt-in refusal, not a default change). **Test**: `strictModeRefusesBeforeBind`.
  - **Detection**: the terminal batch failure (existing path). **Blast radius**: the batch containing the row, as with every strict refusal.
  - **RTO**: not applicable (operator opt-in).

Summary: 4 failure modes: 1 DEFECT FIXED to loud (FM-07.10-1), 1 by-design rule (FM-07.10-2), 1 OPEN gap not covered (FM-07.10-3), 1 strict refusal by design (FM-07.10-4).
