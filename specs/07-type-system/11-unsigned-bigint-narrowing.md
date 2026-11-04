# Spec 07.11: A BIGINT UNSIGNED Value Above 2^63 Stored In A Signed Or Narrower Column Is Reported

## 1. Executive Summary & Purpose
Debezium's default `bigint.unsigned.handling.mode=long` delivers a MySQL `BIGINT UNSIGNED` value in `[2^63, 2^64)` as a negative `INT64` (`18446744073709551615` arrives as `-1`, `9223372036854775808` as `Long.MIN_VALUE`). A ClickHouse column that is not `UInt64` cannot show that magnitude. An operator-created `Int64` column stores the negative number, and a narrower column stores the low bits of it. Before this change that loss was silent. This spec makes it loud through the one lossy-conversion reporter (kinds `INT_WRAP` and `BOOL_COLLAPSE`, spec 07.03 section 3.1.14). The default policy (`clamp.out.of.range=true`) stores the value as before and counts it; the opt-in strict policy refuses it. The stored value is never changed by this spec.

MySQL is the source of truth for the type. A `BIGINT UNSIGNED` column that is created by the connector (`AUTO_CREATE`) is `UInt64` when the source type is propagated (the default), so the automatic path stores the exact value and is not reported. An existing `Int64` or narrower column, created by an operator, cannot hold the value and is reported on every row that carries one.

---

## 2. Codebase Mapping
- **Source value (library)**: Debezium maps MySQL `BIGINT UNSIGNED` to `INT64` under `bigint.unsigned.handling.mode=long`. The source column type is exposed as the schema parameter `__debezium.source.column.type` when `column.propagate.source.type` is set.
- **Auto-created type**: spec 07.01 section 3.1 maps `BIGINT UNSIGNED` to `UInt64` through the propagated source type. The automatic path therefore stores the exact value and is not reported.
- **Propagation (this repo)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` sets `column.propagate.source.type` to `.*` by default. The parameter name is `ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM`.
- **Target type for auto-created columns**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseTableOperationsBase.java`, `getColumnNameToCHDataTypeMapping`. An unsigned source type maps through `ClickHouseDataTypeMapper.getUnsignedClickHouseType`, so `bigint unsigned` gives `UInt64`.
- **Restore of the unsigned magnitude**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`, `convert(...)`, the integer branch. A negative `Long` bound into a `UInt64` target is bound as a `BigInteger` (unchanged by this spec).
- **Report point (this spec)**: `ClickHouseDataTypeMapper.reportUnsignedBigintWrap(...)`, called from the integer branch of `convert(...)` before the bind, so each value is reported once. The bind loop in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` passes the field's schema parameters to the 11-argument `convert(...)` overload.
- **Reporter**: `LossyConversionReporter` (`Kind.INT_WRAP`, `Kind.BOOL_COLLAPSE`). Rate limiting, the first-per-column WARN and the metric are the reporter's (spec 07.03 section 3.1.14). No second reporter is added.

---

## 3. Operational Specification

### 3.1 Which values are reported
A value is reported when all of the following hold:
1. The value is a `Long` and it is negative. A negative `Long` from a `BIGINT UNSIGNED` source is a MySQL value in `[2^63, 2^64)`. Null and non-`Long` values are never reported.
2. The schema parameter `__debezium.source.column.type` is present and names an unsigned `BIGINT` (`bigint unsigned`, with or without a display width or `ZEROFILL`). `getUnsignedClickHouseType` returns `UInt64` for it. Without the propagated source type a signed negative `BIGINT` cannot be told apart from an unsigned one, so the value is not reported (section 6, FM-07.11-3).
3. The bound ClickHouse type is not `UInt64`. A `UInt64` target restores the exact unsigned magnitude (section 3.1 of spec 07.01), so nothing is stored differently.

### 3.2 What is reported
- **Kind**: `INT_WRAP` for an integer target (`Int8`, `Int16`, `Int32`, `Int64`, `UInt8`, `UInt16`, `UInt32`); `BOOL_COLLAPSE` for a `Bool` target. A `Bool` target stores any value other than 0 or 1 as `true`, so the value is collapsed, not wrapped.
- **Column**: the `db.table.column` label of the range policy.
- **Source**: the MySQL value as an unsigned decimal (`Long.toUnsignedString`), for example `18446744073709551615` or `9223372036854775808`.
- **Stored**: the text ClickHouse stores for the bound long in the target. For `Int64` the long itself (`-1` for `18446744073709551615`). For `Int32`, `Int16` and `Int8` the long truncated to the column width and read in its signedness (`-1` for `-1` into `Int32`; `0` for `Long.MIN_VALUE` into `Int32`). For `UInt32`, `UInt16` and `UInt8` the long truncated to the column width and read unsigned (`4294967295` for `-1` into `UInt32`). For `Bool`, `true`.
- **Single report**: the value is reported by this rule and not by the narrowing report of spec 07.03 section 3.1.14. A negative `BIGINT UNSIGNED` value therefore produces exactly one `lossy` call, with the unsigned source text.

### 3.3 Outcome by policy
- **Default (`clamp.out.of.range=true`)**: the value is bound unchanged, the conversion is counted (`clickhouse_sink_lossy_conversions_total{kind="int_wrap"}` or `{kind="bool_collapse"}`), and the first occurrence per column is logged at WARN with the marker `LOSSY_CONVERSION`.
- **Strict (`clamp.out.of.range=false`)**: `DebeziumConverter.RangePolicy.lossy` throws `DebeziumConverter.ValueOutOfRangeException` before the bind. The batch fails through the terminal path of spec 07.03 section 3.1.14, and nothing is written.

### 3.4 Values that are not changed by this spec
- A non-negative `BIGINT UNSIGNED` value (below `2^63`) bound into a narrower signed column keeps the existing narrowing report of spec 07.03 section 3.1.14, exactly once.
- A genuine negative signed `BIGINT` is not reported by this spec.
- The bound value is the same as before for every target. Only the report is added.

---

## 4. Invariants Preserved
- **I9 loud failure**: a stored integer that differs from the MySQL value is reported, by default in the counter and the WARN, under strict as a refusal.
- **I11 upgrade safety**: the bound value, the column type and the schema are unchanged. Only a report is added.
- **Stored values are not changed**: the bind is byte-identical to the base jar for every target. The `UInt64` restore (spec 07.01 section 3.1) is unchanged.

---

## 5. Verification Criteria
- Unit: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/converters/UnsignedBigintWrapReportTest.java`
  - `maximumUnsignedBigintIntoInt64IsCounted`: `-1` from `BIGINT UNSIGNED` into `Nullable(Int64)` is counted once; the stored text is `-1`.
  - `lowestUnsignedBigintAboveTwoTo63IntoInt64IsCounted`: `Long.MIN_VALUE` into `Nullable(Int64)` is counted once.
  - `everyWrappedValueIsCounted`: three wrapped values give a count of three.
  - `zerofillDisplayWidthSpellingIsCounted`: `bigint(20) unsigned zerofill` is counted.
  - `unsignedBigintBelowTwoTo63IsNotCounted`, `negativeSignedBigintIsNotCounted`, `withoutPropagatedSourceTypeIsNotCounted`, `narrowerUnsignedIntegerSourcesAreNotCounted`, `unsignedBigintIntoUInt64IsNotCounted`, `nullAndNonLongValuesAreNotCounted`: the exclusions of section 3.1.
  - `narrowSignedTargetsAreCountedOnce`: `-1` into `Int32`, `Int16` and `Int8` is counted once each (three reports, stored text `-1`).
  - `lowestUnsignedBigintIntoInt32StoresTheLowBits`: `Long.MIN_VALUE` into `Int32` is counted once and stores `0`.
  - `unsignedTargetsAreCountedWithTheLowBits`: `-1` into `UInt32`, `UInt16` and `UInt8` is counted once each, with the stored texts `4294967295`, `65535` and `255`.
  - `boolTargetIsCountedAsBoolCollapse`: `-1` into `Bool` is counted as `BOOL_COLLAPSE` with the stored text `true`.
  - `stringAndDecimalTargetsAreNotCounted`: the targets outside section 3.2 are not counted (open, section 6, FM-07.11-4).
  - `convertReportsOnceAndBindsTheSignedLongForNarrowTargets`: the 11-argument `convert` with `Int32` and `Long.MIN_VALUE` reports exactly one `INT_WRAP` and binds the signed long.
  - `convertRefusesUnderStrictModeNamingTheUnsignedSource`: the same bind under `clamp.out.of.range=false` throws, and the message starts `Value 9223372036854775808 for column` (the unsigned source, not `-9223372036854775808`).
  - `convertDoesNotReportASignedNegativeIntoANarrowTarget`: a genuine negative `BIGINT` bound into `Int32` is not counted.
  - `convertStillReportsAnUnsignedValueBelowTwoTo63Once`: `4294967296` (below `2^63`) bound into `Int32` is counted once, by the narrowing report of spec 07.03 section 3.1.14.
  - `convertBindIsUnchangedForEveryIntegerTarget`: the bound object is the same as the base jar for `Int64`, `Int32`, `UInt32` and `UInt64` (`BigInteger` for `UInt64` only).
  - `strictModeRefusesBeforeBind`: under `clamp.out.of.range=false` the value throws `ValueOutOfRangeException`.
- Mutation (measured, must fail): removing both `policy.lossy(...)` calls (BOOL_COLLAPSE and INT_WRAP) from `reportUnsignedBigintWrap` fails 11 of the 21 tests of the class (`boolTargetIsCountedAsBoolCollapse`, `convertRefusesUnderStrictModeNamingTheUnsignedSource`, `convertReportsOnceAndBindsTheSignedLongForNarrowTargets`, `everyWrappedValueIsCounted`, `lowestUnsignedBigintAboveTwoTo63IntoInt64IsCounted`, `lowestUnsignedBigintIntoInt32StoresTheLowBits`, `maximumUnsignedBigintIntoInt64IsCounted`, `narrowSignedTargetsAreCountedOnce`, `strictModeRefusesBeforeBind`, `unsignedTargetsAreCountedWithTheLowBits`, `zerofillDisplayWidthSpellingIsCounted`). Removing the `unsignedStored == null` guard in the `convert(...)` integer branch fails only `convertReportsOnceAndBindsTheSignedLongForNarrowTargets` (two reports, not one). Calling the narrowing report instead of the unsigned report for a negative `BIGINT UNSIGNED` fails only `convertRefusesUnderStrictModeNamingTheUnsignedSource` (the message then names the signed source text).
- End to end (cross-engine scenarios, MySQL with a Debezium MySQL source and ClickHouse; each scenario compares six rows per table with MySQL): `unsigned-bigint-into-int64` (`BIGINT UNSIGNED` into an operator-created `Int64`, an operator-created `UInt64` and an `AUTO_CREATE` table), `int-unsigned-into-int32` (`INT UNSIGNED` into an operator-created `Int32`, `UInt32` and `AUTO_CREATE` table), `unsigned-bigint-into-narrow` (`BIGINT UNSIGNED` into operator-created `Int32` and `UInt32` tables), `signed-bigint-negative-control` (signed `BIGINT` with negative values into `Int64` and `AUTO_CREATE`, expected exact with 0 lossy lines), and `unsigned-bigint-into-string-decimal` (the probe for FM-07.11-4). Base jar (2.11.1, before this change): every wrapped row differs from MySQL and no lossy line or counter is written. Fix jar: the same rows differ with the same stored values, and each affected column writes one `LOSSY_CONVERSION kind=int_wrap` WARN with the unsigned source text. The counter `clickhouse_sink_lossy_conversions_total{kind="int_wrap"}` counts every wrapped row (3 for the `Int64` table, 2 for the `Int32` table, 7 for the two narrow tables, which also count the below-2^63 narrowing of spec 07.03 section 3.1.14). The `UInt64` and `AUTO_CREATE` tables are exact on both jars and are not reported. The negative control stays exact on both jars with 0 lossy lines. The probe stores the signed text on both jars with 0 lossy lines, as recorded in FM-07.11-4.
- Invariant: a value is reported exactly when it is negative, its source is an unsigned `BIGINT`, and its target is one of `Int8`, `Int16`, `Int32`, `Int64`, `UInt8`, `UInt16`, `UInt32` or `Bool` (not `UInt64`, and not the open text and decimal targets of FM-07.11-4); a reported value has exactly one report, so the narrowing report does not fire for it; the stored text is the value truncated to the target width, read in the target's signedness (`true` for `Bool`); and the bound value never changes.

---

## 6. Failure Modes & Recovery

Recovery posture: the change is stateless. Rolling back the jar restores the silent wrap for new events; no offset or schema change is needed. Rows already stored wrapped are not rewritten by the connector; they need a targeted re-snapshot or re-replication of the affected rows (procedures P-FIX-TYPE and P-RESYNC, spec 07.01 section 6).

- **FM-07.11-1 BIGINT UNSIGNED above 2^63 stored wrapped in a signed or narrower column**
  - **Trigger**: a MySQL `BIGINT UNSIGNED` value in `[2^63, 2^64)` bound into an operator-created `Int64`, `Int32`, `Int16`, `Int8`, `UInt32`, `UInt16`, `UInt8` or `Bool` column.
  - **Behaviour (before)**: the connector stores the negative long, or its low bits, and reports nothing. The row count is intact and the stored value differs from MySQL.
  - **Behaviour (after)**: unchanged stored value; counted under `int_wrap` (or `bool_collapse`) and logged once per column (default), or refused (strict).
  - **Status**: DEFECT, FIXED to loud (the stored value is not corrected). Reproduced on the base jar by the end-to-end scenarios of section 5.
  - **Detection (before)**: none. **Detection (after)**: the `int_wrap` counter and the `LOSSY_CONVERSION kind=int_wrap` WARN.
  - **Blast radius**: every row whose unsigned value is at or above `2^63` in such a column. Rows below `2^63` are unaffected by this spec.
  - **Test**: section 5 unit tests and the end-to-end scenarios.
  - **RTO**: new events are loud from the first batch after the jar restart (restart time, unmeasured). Rows already stored need P-RESYNC of the affected tables (unmeasured).

- **FM-07.11-2 BIGINT UNSIGNED into a UInt64 column**
  - **Trigger**: a value in `[2^63, 2^64)` bound into a `UInt64` column (the automatic path with the propagated source type, or an operator `UInt64`).
  - **Behaviour**: the magnitude is restored by the bind (spec 07.01 section 3.1). The stored value equals the MySQL value. Not reported.
  - **Status**: by design. **Test**: `unsignedBigintIntoUInt64IsNotCounted`, and the end-to-end scenario `unsigned-bigint-into-int64` (its `UInt64` and `AUTO_CREATE` tables are exact on both jars).
  - **Detection**: not needed. **Blast radius**: none.
  - **RTO**: not applicable.

- **FM-07.11-3 BIGINT UNSIGNED without the propagated source type**
  - **Trigger**: `column.propagate.source.type` is disabled, so the schema parameter is absent.
  - **Behaviour**: a wrapped value is not reported by this spec. The table-level `ERROR` log of `ClickHouseTableOperationsBase` (untyped `Int64` columns) is the only signal.
  - **Status**: OPEN for this change. The reporter cannot tell a signed negative from an unsigned value without the source type. Recovery: keep the propagated type enabled, which is the default.
  - **Detection**: the table-level ERROR log. **Blast radius**: every row of such a column with an unsigned value at or above `2^63`.
  - **RTO**: not applicable while open.

- **FM-07.11-4 BIGINT UNSIGNED into a String or Decimal column**
  - **Trigger**: a value in `[2^63, 2^64)` bound into an operator-created `String` or `Decimal(20,0)` column.
  - **Behaviour**: the signed negative long is bound, so the stored text is `-1`-style and not the MySQL digits. This spec does not report it, because the report kinds (spec 07.03 section 3.1.14) describe integer narrowing and no kind names a text or decimal target.
  - **Status**: OPEN. Evidence: the probe scenario `unsigned-bigint-into-string-decimal`. On both the base jar and the fix jar, the `String` and `Decimal(20,0)` tables store `-1` and `-9223372036854775808` for MySQL values 18446744073709551615 and 9223372036854775808, and the connector writes 0 lossy lines. Fixing it needs a bind change (stored text) or a new kind, both outside this spec.
  - **Detection**: none. **Blast radius**: every row of such a column with an unsigned value at or above `2^63`.
  - **RTO**: not applicable while open.

- **FM-07.11-5 Signed negative BIGINT into a UInt64 column**
  - **Trigger**: a genuine negative MySQL `BIGINT` bound into an operator-created `UInt64` column.
  - **Behaviour**: the `UInt64` restore of spec 07.01 section 3.1 applies to every negative `Long`, so the bound value is `2^64 + v`. Not reported by this spec. The restore does not check the source type.
  - **Status**: OPEN. Changing the restore changes the bound value, so it is a separate defect, outside this report-only change.
  - **Detection**: none. **Blast radius**: operator `UInt64` columns that receive negative signed `BIGINT` values, which are invalid in the MySQL schema the column was built from.
  - **RTO**: not applicable while open.

- **FM-07.11-6 Strict refusal of an unsigned value**
  - **Trigger**: `clamp.out.of.range=false` and a value that FM-07.11-1 would count.
  - **Behaviour**: the batch fails with `ValueOutOfRangeException` before the bind (spec 07.03 section 3.1.14 rule 2). Nothing is written, and the failure is terminal.
  - **Status**: by design (strict is an opt-in refusal, not a default change). **Test**: `strictModeRefusesBeforeBind`.
  - **Detection**: the terminal batch failure (existing path). **Blast radius**: the batch containing the row, as with every strict refusal.
  - **RTO**: not applicable (operator opt-in).

Summary: 6 failure modes: 1 DEFECT FIXED to loud (FM-07.11-1), 1 by-design exact restore (FM-07.11-2), 3 OPEN gaps not covered by this report (FM-07.11-3, FM-07.11-4, FM-07.11-5), 1 strict refusal by design (FM-07.11-6).
