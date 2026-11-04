# Spec 07.08: latin1 Text Bytes Decoded As MySQL Decodes Them

## 1. Executive Summary & Purpose
Specifies how the connector turns the bytes of a `latin1` `CHAR`, `VARCHAR`, `TINYTEXT`, `TEXT`, `MEDIUMTEXT` or `LONGTEXT` column into the Unicode text it stores in ClickHouse. MySQL's `latin1` is not ISO-8859-1 and not the JDK's `windows-1252`: five byte values that `windows-1252` leaves undefined are C1 control characters in MySQL. Debezium decodes with the JDK charset and turns those five bytes into U+FFFD, so the stored text differs from what MySQL returns. This spec fixes the streaming (binlog) path. The snapshot path was probed on the base jar and showed no defect for the tested configuration (FM-07.08-2).

---

## 2. Codebase Mapping on 2.11.0
- **Charset resolution (library, not editable)**: `io.debezium.connector.mysql.charset.MySqlCharsetRegistry` delegates to `CharsetMappingMapper.getJavaEncodingForCharSet`, backed by Connector/J `CharsetMapping` (mysql-connector-j 9.1.0). `latin1` maps to the Java name `Cp1252`. Verified by bytecode inspection of the library jars.
- **Streaming decode (library)**: `BinlogValueConverters.convertString`: a `byte[]` value is decoded with `new String(bytes, charsetFor(column))`, so the five undefined bytes become U+FFFD.
- **Snapshot decode (library)**: `MySqlTextProtocolFieldReader` / `AbstractFieldReader.getCharacterSet` use the same registry. A probe on the base jar keeps the five bytes in the snapshot path for the tested configuration; the mechanism is not traced (FM-07.08-2).
- **Fix point (this repo)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/converters/Latin1TextConverter.java` (a Debezium `CustomConverter`), and `Latin1TextConverter.ensureRegistered(Properties)` called from `DebeziumChangeEventCapture` next to `ensureTimeAdjusterDisabled`.
- **Oracle**: `sink-connector-lightweight/src/test/resources/latin1/mysql_latin1_utf8_oracle.tsv` (MySQL 8.0.46 `HEX(CONVERT(b USING utf8mb4))` for every byte 0x01..0xFF in a latin1 `VARCHAR`).
- **Schema-only path (not on the value path)**: `DataTypeConverter` (`BinlogCharsetRegistry` at lines ~107-108) reads the charset for DDL-derived types only. It is not changed.

---

## 3. Operational Specification

### 3.1 Byte-to-character mapping for latin1 text
For a `latin1` text column the decoded character for each byte `b` is:

| Bytes | Decoded as | Source |
|---|---|---|
| `0x00`-`0x7F` | U+0000-U+007F | identity |
| `0x80` | U+20AC (EURO SIGN) | cp1252 and MySQL agree |
| `0x81`, `0x8D`, `0x8F`, `0x90`, `0x9D` | U+0081, U+008D, U+008F, U+0090, U+009D | MySQL truth (oracle); cp1252 leaves these undefined |
| `0x82`-`0x8C`, `0x8E`, `0x91`-`0x9C`, `0x9E` | cp1252 mapping | cp1252 and MySQL agree |
| `0x9F` | U+0178 (LATIN CAPITAL LETTER Y WITH DIAERESIS) | cp1252 and MySQL agree |
| `0xA0`-`0xFF` | U+00A0-U+00FF | identity |

The mapping is total: every byte has exactly one character, and no output is U+FFFD unless the input byte itself is an undefined value outside this table (none exist for latin1).

### 3.2 Which columns and which values the converter touches
- Registered only for columns whose `charsetName()` equals `latin1` (case-insensitive) and whose `typeName()` (case-insensitive, without a length suffix) is one of `CHAR`, `VARCHAR`, `TINYTEXT`, `TEXT`, `MEDIUMTEXT`, `LONGTEXT`. `ENUM` and `SET` are excluded: Debezium delivers their labels from the DDL, not from the value bytes.
- For a `byte[]` value the converter returns the decoded `String` of section 3.1.
- For any other value (a `String` from the snapshot path, a `null`, a default value) the converter returns the value unchanged.
- The registered schema is `SchemaBuilder.string()`. Debezium applies `optional()` for nullable columns and the `PropagateSourceMetadataToSchemaParameter` mapper afterwards (`TableSchemaBuilder.addField`, bytecode-verified), so the schema parameters and nullability match the default string schema.

### 3.3 Configuration wiring
`Latin1TextConverter.ensureRegistered(props)` sets:
- `converters` to `latin1text` when unset or blank; when a user list exists and does not contain `latin1text`, it appends `latin1text`.
- `latin1text.type` to `com.altinity.clickhouse.debezium.embedded.converters.Latin1TextConverter` unless the operator already set `latin1text.type`.

---

## 4. Invariants Preserved
- **I7 value-level type equivalence**: for latin1 text, the stored text equals MySQL's own text conversion of the same bytes, for every byte value.
- **I9 loud failure**: no silent substitution is introduced. Values that are not `byte[]` pass through unchanged, so the converter never hides a value it did not decode.
- **I11 upgrade safety**: the schema shape (`STRING`, same parameters, same optionality) is unchanged, so existing ClickHouse tables and the record-schema auto-create path keep their types.

---

## 5. Verification Criteria
- Unit: `sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/converters/Latin1TextConverterTest.java`
  - `everySingleByteMatchesMySql`: for each row of the oracle, the decoder output encodes to the oracle hex (255 cases).
  - `undefinedBytesAreControlCharacters`: `0x81`, `0x8D`, `0x8F`, `0x90`, `0x9D` decode to U+0081, U+008D, U+008F, U+0090, U+009D.
  - `euroAndYDiaeresisMatchCp1252`: `0x80` is U+20AC and `0x9F` is U+0178.
  - `converterRegisteredOnlyForLatin1Text`: registration happens for latin1 text types only (not `utf8mb4`, not `ENUM`/`SET`, not a null charset).
  - `convertPassesNonBytesThrough`: `String`, `null` and other types are returned unchanged.
  - `ensureRegisteredRespectsOperatorConfig`: blank, existing list and existing `latin1text.type` cases.
- Mutation (must fail): the naive `windows-1252` decoder (plain `Charset.forName("windows-1252")`) fails `everySingleByteMatchesMySql` and `undefinedBytesAreControlCharacters`.
- End to end (scenario with tables `t_bytes` and `t_text`, undefined bytes in ids 129, 141, 143, 144, 157): base jar DIVERGED with 5 byte rows (`EFBFBD`); fix jar PASS; a jar with the naive decoder DIVERGED (negative control).

---

## 6. Failure Modes & Recovery

Recovery posture: the streaming converter is stateless. Rolling back the JAR restores the U+FFFD behaviour for new events; no offset or schema change is needed. Rows already written with U+FFFD are not rewritten by the connector; they need a targeted re-snapshot or re-replication of the affected rows (procedures P-FIX-TYPE / P-RESYNC, spec 07.01 section 6).

- **FM-07.08-1 Streaming: undefined latin1 bytes decoded as U+FFFD**
  - **Trigger**: a `latin1` text value contains `0x81`, `0x8D`, `0x8F`, `0x90` or `0x9D` (for example written through a `latin1` client, or copied from a cp1252 source).
  - **Behaviour (before)**: Debezium's `convertString` replaces each such byte with U+FFFD. ClickHouse stores `EFBFBD`; MySQL returns `C281`, `C28D`, `C28F`, `C290`, `C29D`. Reproduced on the 2.11.1 base (end-to-end scenario: 5 DIFF rows; the cross-engine comparison gives DIVERGED with `t_bytes` value_diffs=5 and `t_text` value_diffs=2).
  - **Status**: DEFECT, FIXED by section 3 (streaming path only). The fix is verified end to end (section 5).
  - **Detection (before)**: none; the substitution is silent, row counts are intact.
  - **Blast radius**: text values that contain those five bytes. Every other byte value already round-trips.
  - **Test**: section 5 unit tests and the end-to-end scenario.
  - **RTO**: new events are correct from the first batch after the JAR restart (restart time, unmeasured); rows already written need P-RESYNC of the affected tables (unmeasured).

- **FM-07.08-2 Snapshot: latin1 text read through the initial snapshot**
  - **Trigger** (as tested): latin1 `VARCHAR`, `CHAR`, `TEXT` and `ENUM`/`SET` rows that exist before the connector starts, with `snapshot.mode=initial`, including the five undefined bytes.
  - **Hypothesis before the probe**: the snapshot reader decodes through the same registry (`Cp1252`), so the five bytes would become U+FFFD before the converter runs.
  - **Result (base jar, no fix)**: no U+FFFD. Snapshot-only scenario: `t_bytes` 260 rows SAME, `t_text` 3 rows SAME, 0 diffs, verdict PASS. The rows had no writes after connector start, so they came through the snapshot (INFERRED from the timing; the connector log has no per-table snapshot line at INFO). The mechanism that keeps the bytes is not traced.
  - **Status**: REFUTED for this configuration (Debezium 3.1.3, MySQL 8.0.46, `snapshot.mode=initial`). Not tested: incremental snapshots (signal table), other `snapshot.mode` values, and a snapshot restarted mid-table.
  - **Detection**: not needed for the tested configuration. **Blast radius**: none observed.
  - **Recovery**: none for the tested configuration. If a later probe reproduces the loss, rows need P-RESYNC of the snapshot tables and the fix belongs in the reader, not in this converter.
  - **RTO**: not applicable while REFUTED.

- **FM-07.08-3 Operator sets a custom converter for the same column**
  - **Trigger**: operator sets `latin1text.type` or lists another converter named `latin1text`.
  - **Behaviour**: `ensureRegistered` does not overwrite an explicit `latin1text.type`; a user list is extended, not replaced.
  - **Status**: FIXED by design (section 3.3). **Test**: `ensureRegisteredRespectsOperatorConfig`.
  - **Detection**: none needed; the converter is not registered for the operator's own type and the list is kept.
  - **Blast radius**: none on data; a wrong list would only disable the fix for the columns it covers.
  - **RTO**: restart with the corrected configuration (restart time, unmeasured); no data is affected.

Summary: 3 failure modes: 1 DEFECT FIXED in the streaming path (FM-07.08-1), 1 hypothesis REFUTED by probe for the snapshot path (FM-07.08-2), 1 operator-config rule FIXED (FM-07.08-3).
