# Spec 07.12: A Text Value Whose Trailing NUL Is Bound Into A FixedString Is Reported

## 1. Executive Summary & Purpose
A MySQL text value can end in a NUL byte (`CONCAT('ab', CHAR(0))`), and a `CHAR(N)` or `VARCHAR` column can hold it. A ClickHouse `FixedString(N)` column pads every value with NUL bytes to N and reads it back without them. A trailing NUL of the source is therefore indistinguishable from the padding: the stored value reads back as the source without that NUL. Before this spec the connector bound such a value silently, and no counter or log line showed it. This spec makes the loss loud by the rule of spec 07.03 section 3.1.14: counted and logged by default, refused under `clamp.out.of.range=false`.

Padding alone is the FixedString contract and is not a loss. A value `abc` bound into `FixedString(4)` reads back as `abc` on both sides, so it is not reported.

---

## 2. Codebase Mapping
- **Source value (library)**: Debezium passes a MySQL text column as a Connect `STRING`, so the bound value is a `java.lang.String`. A NUL byte is the character `U+0000`.
- **Declared width**: the parsed ClickHouse column `com.clickhouse.data.ClickHouseColumn` for `FixedString(N)` (or `Nullable(FixedString(N))`). Its data type is `ClickHouseDataType.FixedString`, and `getPrecision()` is `N` for both the plain and the Nullable form. (`getEstimatedLength()` is not the width: it is `N` for the plain form and 1 for the Nullable form, measured on clickhouse-data 0.9.8.)
- **Rule (this spec)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`, `reportFixedStringNul(...)`.
- **Call site (this spec)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`, the bind loop, after `reportFractionTruncation`. It runs before `convert(...)`, so each value is reported once and before the bind.
- **Reporter**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/LossyConversionReporter.java`, `Kind.FIXEDSTRING_NUL`. Rate limiting, the first-per-column WARN and the metric are the reporter's (spec 07.03 section 3.1.14).
- **Range policy**: `DebeziumConverter.RangePolicy.lossy`, which counts and logs under the default and throws `DebeziumConverter.ValueOutOfRangeException` under strict mode.

---

## 3. Operational Specification

### 3.1 Which values are reported
A value is reported when all of the following hold:
1. The value is a `String`. Byte arrays and null are never reported.
2. The bound ClickHouse column is `FixedString(N)`, or `Nullable(FixedString(N))`.
3. The value is not empty and its last character is `U+0000`.
4. Its UTF-8 length is at most `N` bytes. A longer value is refused by ClickHouse with Code 131 (spec 07.04 FM-07.04-2), and this rule leaves it to that refusal.

A value is not reported when it does not end in NUL (padding only), when the NUL is inside the value (`a` NUL `b`), or when the column is not a FixedString.

### 3.2 What is reported
- **Kind**: `FIXEDSTRING_NUL` (label `fixedstring_nul`).
- **Column**: the `db.table.column` label of the range policy.
- **Source**: the value as the source held it, with each NUL shown as `\0`, for example `ab\0`.
- **Stored**: the value without its trailing NUL bytes, which is the text ClickHouse reads back, for example `ab`.
- **Count**: one per value. A value that ends in several NULs is one report.

### 3.3 Outcome by policy
- **Default (`clamp.out.of.range=true`)**: the value is bound unchanged, the conversion is counted (`clickhouse_sink_lossy_conversions_total{kind="fixedstring_nul"}`), and the first occurrence per column is logged at WARN with the marker `LOSSY_CONVERSION`.
- **Strict (`clamp.out.of.range=false`)**: `RangePolicy.lossy` throws `ValueOutOfRangeException` before the bind. The batch fails through the terminal path of spec 07.03 section 3.1.14, and nothing is written.

### 3.4 A value of exactly N bytes that ends in NUL
A value of exactly `N` bytes that ends in NUL is stored byte for byte, so the storage is not truncated. The read path is: ClickHouse converts the FixedString to a String without the NUL, and the comparison tooling reads through that conversion (spec 07.12 section 6, FM-07.12-1). The rule reports this case too, because the NUL is not readable as data. This is a stated judgment, not a storage loss.

### 3.5 Values that are not changed by this spec
- The bound value is the same as before for every target. Only the report is added.
- Values that do not fit N bytes keep the refusal of ClickHouse (Code 131).
- A `String` column is never reported.

---

## 4. Invariants Preserved
- **I9 loud failure**: a value whose trailing NUL does not read back as data is reported, by default in the counter and the WARN, under strict as a refusal.
- **I11 upgrade safety**: the bound value, the column type and the schema are unchanged. Only a report is added.

---

## 5. Verification Criteria
- Unit: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/converters/FixedStringNulReportTest.java`
  - `trailingNulIntoFixedStringIsCountedAndReadBackWithoutIt`: `ab` NUL into `FixedString(4)` is counted once; the stored text is `ab`.
  - `everyNulEndingValueIsCountedOnce`: two NUL-ending values give a count of two.
  - `nullableFixedStringIsCounted`: `Nullable(FixedString(4))` is checked the same way, and `Nullable(FixedString(2))` rejects a three-byte value (width by `getPrecision()`).
  - `paddingOnlyIsNotCounted`, `nulInTheMiddleIsNotCounted`: padding and an inner NUL are not reported.
  - `valueLongerThanTheWidthIsLeftToClickHouse`: a value of more than N bytes is not reported here.
  - `widthIsCountedInUtf8Bytes`: a two-byte character plus NUL is three bytes, so it fits `FixedString(3)` and not `FixedString(2)`.
  - `otherColumnTypesAndValuesAreNotCounted`: `String` columns, null and byte arrays are not checked.
  - `strictModeRefusesTheValue`: under strict mode the value is refused and not counted.
- Call site: `sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/converters/FixedStringNulCallSiteTest.java` drives `PreparedStatementFieldMapper.insertPreparedStatement`, so the report is tied to the bind loop and not only to the helper.
  - `aTrailingNulIntoFixedStringIsReportedAndTheValueIsBoundUnchanged`: the count is one and the bound value is the source value with its NUL.
  - `nullableFixedStringIsReportedToo`, `paddingOnlyAndOtherColumnTypesAreNotReported`: the same rules through the loop.
  - `strictModeRefusesTheRowNamingTheColumn`: the refusal names `db.codes.code4`.
- Mutation check: removing the call from the loop, removing the width test, testing the first character instead of the last, reading the width from `getEstimatedLength()`, accepting any column type, returning the value without stripping, and stripping only one NUL each turn at least one of these tests red.
- Invariant: a value that fits `N` is reported exactly when ClickHouse reads back something other than the value, and the stored value has no trailing NUL.

---

## 6. Failure Modes & Recovery
- **FM-07.12-1 A text value ends in NUL and is bound into a FixedString(N)**
  - **Trigger**: a MySQL `CHAR`, `VARCHAR` or `TEXT` value whose last byte is `0x00`, written to a ClickHouse `FixedString(N)` column (created by the operator or by an override of the auto-created `String` type).
  - **Behaviour**: the NUL is bound as part of the value. ClickHouse pads and reads back without it, so the stored value differs from the source by the NUL byte. Before this spec this was silent.
  - **Detection**: WARN `LOSSY_CONVERSION kind=fixedstring_nul column=<db.table.column> source=<...\0> stored=<...>` on the first occurrence per column; counter `clickhouse_sink_lossy_conversions_total{kind="fixedstring_nul"}`; summary WARN at most every 5 minutes.
  - **Blast radius**: the affected values differ from the source by a trailing NUL; row counts intact. Under strict mode the batch is refused and nothing is written.
  - **Recovery**: remove the trailing NUL from the source rows or change the column to `String`, then resynchronise the table (spec 11.04). Locating the affected rows needs a byte-level comparison; see GAP below.
  - **RTO**: unmeasured.
  - **Test**: `FixedStringNulReportTest` and `FixedStringNulCallSiteTest` (section 5).
  - **GAP**: the value comparison tooling reads the ClickHouse side through `toString`, which drops the padding, so a NUL-ending value compares equal to its stored form and the tool cannot locate these rows on its own. Changing the tool is not part of this spec.

Summary: 1 failure mode, 0 DEFECT, 1 GAP (comparison tooling blind spot).
