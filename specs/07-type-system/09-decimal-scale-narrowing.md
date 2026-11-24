# Spec 07.09: A Decimal Value With More Fractional Digits Than The Column Is Reported

## 1. Executive Summary & Purpose
A MySQL `DECIMAL(p,s)` value can carry more fractional digits than the ClickHouse `Decimal(P,S)` column it is bound to (`S` smaller than `s`). The connector binds the exact `BigDecimal`, and ClickHouse keeps only `S` fractional digits, truncating toward zero (it never rounds). Before this change the loss was silent: no counter and no log line. This spec makes the loss loud through the one lossy-conversion reporter (kind `DECIMAL_TRUNCATE`, spec 07.03 section 3.1.14). The default policy (`clamp.out.of.range=true`) stores the value as before and counts it; the opt-in strict policy refuses it, as section 3.1.14 rule 2 requires for every lossy kind. The stored value is never changed.

---

## 2. Codebase Mapping
- **Source value (library)**: Debezium maps MySQL `DECIMAL` to the Connect `Decimal` logical type (schema name `org.apache.kafka.connect.data.Decimal`, BYTES) with `decimal.handling.mode=precise`. The value is a `java.math.BigDecimal` whose scale is the MySQL column scale.
- **Bind (this repo)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`, `convert(...)`, branch `isFieldTypeDecimal`: `ps.setBigDecimal(index, (BigDecimal) value)`. The bind does not change the scale.
- **Target scale**: `ClickHouseColumn.getScale()` of the parsed declared type. `PreparedStatementFieldMapper.columnBinding(...)` parses the declared type once per column and keeps it in `ColumnBinding.column`.
- **Report point (new)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`, the bind loop, immediately before `ClickHouseDataTypeMapper.convert(...)`, calls `ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(value, binding.column, rangePolicy)`.
- **Reporter**: `LossyConversionReporter.Kind.DECIMAL_TRUNCATE` (label `decimal_truncate`), already defined, now produced. Rate limiting, the first-per-column WARN and the metric are the reporter's (spec 07.03 section 3.1.14).
- **Not changed**: the `VariableScaleDecimal` branch (`ClickHouseDataTypeMapper`, the `STRUCT` branch that calls `BigDecimalConverter.truncate`, range only). It is not reached by a MySQL `DECIMAL` column with the configurations the end-to-end scenarios run (section 6, FM-07.09-3).

---

## 3. Operational Specification

### 3.1 Which values are reported
A value is reported when all of the following hold:
1. The value is a `BigDecimal` (a Connect `Decimal` value; null is never reported).
2. The bound ClickHouse column is a `Decimal`, `Decimal32`, `Decimal64`, `Decimal128` or `Decimal256` type (the inner type of a `Nullable(...)` declaration is used).
3. The value is not equal to its own truncation to the column scale: `value.compareTo(value.setScale(S, RoundingMode.DOWN)) != 0`. Trailing zeros beyond the column scale are not a loss, so `12.3400` into `Decimal(10,2)` is not reported.

The truncation is toward zero: `-1234.5651` into `Decimal(10,2)` is stored as `-1234.56`, and `0.0049` as `0.00`. This is the behaviour of ClickHouse, observed in the decimal-narrowing end-to-end scenario on the base jar.

### 3.2 What is reported
- **Kind**: `DECIMAL_TRUNCATE`.
- **Column**: the `db.table.column` label of the range policy.
- **Source**: `value.toPlainString()`, the MySQL value.
- **Stored**: `value.setScale(S, RoundingMode.DOWN).toPlainString()`, the text ClickHouse keeps.
- The report is made before the bind, as the integer narrowing reports are (spec 07.03 section 3.1.14). A row that then fails for another reason (for example a decimal overflow, section 6, FM-07.09-4) can already be counted; the failure is the signal for that row.

### 3.3 Outcome by policy
- **Default (`clamp.out.of.range=true`)**: the value is bound unchanged, the conversion is counted (`clickhouse_sink_lossy_conversions_total{kind="decimal_truncate"}`), and the first occurrence per column is logged at WARN with the marker `LOSSY_CONVERSION`.
- **Strict (`clamp.out.of.range=false`)**: `DebeziumConverter.RangePolicy.lossy` throws `DebeziumConverter.ValueOutOfRangeException` before the bind. The batch fails through the terminal path of spec 07.03 section 3.1.14, and nothing is written.

---

## 4. Invariants Preserved
- **I9 loud failure**: a stored decimal that differs from the MySQL value is reported, by default in the counter and the WARN, under strict as a refusal.
- **I11 upgrade safety**: the bound value, the column type and the schema are unchanged. Only a report is added.
- **No change to stored values**: the bind is byte-identical to the base jar. Only the reporting differs.

---

## 5. Verification Criteria
- Unit: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/converters/DecimalScaleNarrowingReportTest.java`
  - `fractionBeyondColumnScaleIsCounted`: `-1234.5651` into `Decimal(10, 2)` is counted once; the stored value is `-1234.56`.
  - `sameScaleAndTrailingZerosAreNotCounted`: `12.34` and `12.3400` into `Decimal(10, 2)` are not counted.
  - `zeroTruncatesTowardZero`: `-0.0049` into `Decimal(10, 2)` is counted and the stored value is `0.00`.
  - `nullableDecimalUsesInnerScale`: `Nullable(Decimal(18, 4))` has scale 4, and `1.23456` is counted.
  - `nonDecimalTargetAndNullAreNotCounted`: a `Int64` column and a null value are not counted.
  - `strictModeRefusesBeforeBind`: under `clamp.out.of.range=false` the value throws `ValueOutOfRangeException`.
- Mutation (must fail): removing the `policy.lossy(...)` call from `reportDecimalScaleNarrowing` fails `fractionBeyondColumnScaleIsCounted`, `zeroTruncatesTowardZero`, `nullableDecimalUsesInnerScale` and `strictModeRefusesBeforeBind`.
- End to end (cross-engine scenarios: a narrowing scenario with three narrowed decimal tables and one same-scale control table; a fit scenario whose decimals fit their scale, `EXPECT=PASS`): base jar DIVERGED with 0 lossy lines; fix jar DIVERGED with one or more `kind=decimal_truncate` lines; fit scenario PASS with 0 lossy lines.
- Invariants: the stored value has at most `S` fractional digits; it equals the source when the source has no digits beyond `S`; the report fires exactly when the stored value differs from the source; and truncation never increases the magnitude.

---

## 6. Failure Modes & Recovery

Recovery posture: the change is stateless. Rolling back the jar restores the silent truncation for new events; no offset or schema change is needed. Rows already stored truncated are not rewritten by the connector; they need a targeted re-snapshot or re-replication of the affected rows (procedures P-FIX-TYPE and P-RESYNC, spec 07.01 section 6).

- **FM-07.09-1 Decimal scale narrowing stored silently**
  - **Trigger**: a MySQL `DECIMAL(p,s)` value with more than `S` fractional digits, bound into a `Decimal(P,S)` column (the column is operator-created with a smaller scale, or auto-created from a schema that narrows the scale).
  - **Behaviour (before)**: ClickHouse truncates the value to `S` digits. The connector reports nothing; the row count is intact and the value differs from MySQL.
  - **Behaviour (after)**: unchanged stored value; counted under `decimal_truncate` and logged once per column (default), or refused (strict).
  - **Status**: DEFECT, FIXED to loud (the stored value is not corrected). Reproduced on the base jar (the narrowing end-to-end scenario, 3 narrowed tables, DIVERGED, 0 lossy lines).
  - **Detection (before)**: none. **Detection (after)**: the `decimal_truncate` counter and the `LOSSY_CONVERSION kind=decimal_truncate` WARN.
  - **Blast radius**: every row of a narrowed decimal column whose value has digits beyond the column scale. Rows whose digits fit are unaffected.
  - **Test**: section 5 unit tests and the end-to-end scenario.
  - **RTO**: new events are loud from the first batch after the jar restart (restart time, unmeasured). Rows already stored need P-RESYNC of the affected tables (unmeasured).

- **FM-07.09-2 Trailing zeros beyond the column scale**
  - **Trigger**: `12.3400` into `Decimal(10,2)`.
  - **Behaviour**: not reported; the stored value equals the source value.
  - **Status**: by design (no false positive). **Test**: `sameScaleAndTrailingZerosAreNotCounted`.
  - **Detection**: not needed. **Blast radius**: none.
  - **RTO**: not applicable.

- **FM-07.09-3 Variable-scale decimal (Struct) and decimal inside an array**
  - **Trigger**: a `VariableScaleDecimal` Struct value (the `STRUCT` branch of `ClickHouseDataTypeMapper`), or an `Array(Decimal(P,S))` column.
  - **Behaviour**: not reported. The `VariableScaleDecimal` branch truncates the value to the Decimal128 range only (`BigDecimalConverter.truncate`), so a narrowed scale there stays silent. An array element is not a `BigDecimal` at the report point, so it is not checked.
  - **Status**: NOT COVERED by this change (open). The end-to-end scenarios do not produce either form for a MySQL `DECIMAL` column under the configurations they run. Not tested end to end.
  - **Detection**: none. **Blast radius**: unknown for this configuration; none observed.
  - **Recovery**: a follow-up change must report both forms through the same reporter with the target scale of the column.
  - **RTO**: not applicable while open.

- **FM-07.09-4 Decimal overflow (ERROR, blocking)**
  - **Trigger**: a value with more integer digits than `P - S` allows.
  - **Behaviour**: unchanged. ClickHouse rejects the insert and the batch fails; the failure is loud. The report may count the same row under `decimal_truncate` when the value also has excess fractional digits, before the batch fails.
  - **Status**: unchanged (blocking by design; recorded, not changed). **Test**: none added (no behaviour change).
  - **Detection**: the batch error (existing). **Blast radius**: the batch containing the row, as today.
  - **RTO**: unchanged from the base jar.

Summary: 4 failure modes: 1 DEFECT FIXED to loud (FM-07.09-1), 1 by-design rule (FM-07.09-2), 1 OPEN gap not covered (FM-07.09-3), 1 blocking case unchanged (FM-07.09-4).
