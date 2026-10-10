# Spec 07.04: Strings, Text, JSON, ENUM & SET Types

## 1. Executive Summary & Purpose
Specifies the translation and storage of variable-length textual, structured JSON, and set-based MySQL types in ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`

---

## 3. Operational Specification

- `CHAR(N)` $\to$ `String` or `FixedString(N)` (UTF-8 encoded).
- `VARCHAR(N)` $\to$ `String`.
- `TEXT`, `TINYTEXT`, `MEDIUMTEXT`, `LONGTEXT` $\to$ `String` (ClickHouse strings are dynamically sized and unbounded).
- `JSON`:
  - Bound as valid JSON strings into ClickHouse `String` or native experimental `JSON` object types.
- `ENUM('a', 'b', ...)`:
  - Translated as string literal values into ClickHouse `String` or `Enum8` / `Enum16`.
- `SET('a', 'b', ...)`:
  - Stored as comma-separated `String` or mapped to `Array(String)`.

### 3.1 Canonical stored representation (what a value-level comparison must expect)
The writer stores exactly what Debezium delivers, without re-serialisation
(`ClickHouseDataTypeMapper.convert`: `ps.setString(index, (String) value)` for
every `STRING`-typed field, `ps.setObject` for JSON). The resulting text per
source type — the reference for any checksum or diff between MySQL and
ClickHouse:

| Source type | Debezium delivery | Stored ClickHouse text |
|---|---|---|
| `CHAR`/`VARCHAR`/`TEXT` | `STRING` | verbatim UTF-8 |
| `ENUM('a','b')` | `STRING` (`io.debezium.data.Enum`) | the label, e.g. `a` |
| `SET('a','b')` | `STRING` (`io.debezium.data.EnumSet`) | the comma-joined labels in MySQL's declaration order, e.g. `a,b` |
| `JSON` | `STRING` (`io.debezium.data.Json`) | Debezium's canonical serialisation of the document (the binlog stores JSON in MySQL's binary format; Debezium re-renders it, so key order and whitespace follow Debezium, e.g. `{"a":1}`, not the text originally inserted) |
| `TIME(p)` | `INT64` (`io.debezium.time.MicroTime`) | always six fraction digits, `[-]HH:mm:ss.ffffff`, regardless of `p` (Spec 07.03 §3.2) |
| `BIT(1)` | `BOOLEAN` | ClickHouse `Bool` (`true`/`false` when rendered; MySQL renders `b'1'`/`b'0'`) |
| `BOOL` / `TINYINT(1)` | `INT16` (Debezium does not promote MySQL `TINYINT(1)` to `BOOLEAN`) | `0`/`1` in an `Int16`/`Int8` column on the record-schema path; the DDL path declares `BOOL`/`BOOLEAN` keywords as `Bool` (`DataTypeConverter`, `Types.BOOLEAN`), which accepts the bound `0`/`1` and renders `true`/`false` |
| `BINARY`/`VARBINARY`/`BLOB`, `BIT(n>1)` | `BYTES` | lower-case hex text (Spec 07.05 §3.2) |

Consequences: comparisons must render MySQL `TIME` as `TIME(6)`, compare
`Bool` columns as `0`/`1` (`toUInt8(col)`), and treat JSON as semantically —
not textually — equal unless both sides are normalised identically.

---

## 4. Invariants Preserved
- **Encoding Integrity**: Characters, multibyte UTF-8 sequences, and emojis round-trip without corruption.

---

## 5. Verification Criteria
- `MySQLJsonIT` — JSON columns replicated end to end.
- `ClickHouseDataTypeMapperTest.getClickHouseDataType()` — the type-name mapping table.
- Verification: unit coverage of string/ENUM/SET value binding and UTF-8 round-trip is not yet covered by an automated test (gap). The TestFlows suite lists `types/enum` and `types/json` as expected failures (spec 11.03 §6).

---

## 6. Failure Modes & Recovery

Recovery posture: text, ENUM, SET and JSON values are bound verbatim (`setString` / `setObject`) and quoted by the V2 driver (`SQLUtils.escapeSingleQuotes` doubles backslashes and escapes quotes, verified in client-v2 0.9.8 bytecode), so the connector itself never alters a string. Failures come from the source-side decoder, from narrow hand-created ClickHouse types that refuse or pad a value, and from values so large the JVM cannot render them. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.04-1 Bytes invalid in the column's character set**
  - **Trigger**: a `CHAR`/`VARCHAR`/`TEXT` column holds byte sequences that are not valid in its declared charset (written under a lax `sql_mode`, through a mis-declared client charset, or by `sql_log_bin=0` repairs).
  - **Behaviour**: Debezium decodes the column with `new String(bytes, charset)` (`BinlogValueConverters.convertString`, debezium-connector-binlog 3.1.3 bytecode), which replaces every malformed sequence with U+FFFD; the connector binds the replaced text verbatim.
  - **Detection**: none. DEFECT (library-side, not signalled by the connector).
  - **Blast radius**: the affected values differ from the source bytes; row counts intact; the original bytes are unrecoverable from the stream.
  - **Recovery**: repair the source rows (re-encode them) so a new binlog event carries valid text, or declare the column binary on the source; then P-RESYNC the table.
  - **RTO**: unbounded detection; after detection one source repair + resync; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperPoisonValueTest.textEnumSetAndJsonAreBoundVerbatim()` pins that the connector passes a U+FFFD (and multi-byte text) through unchanged; GAP: a test at the Debezium boundary asserting that a malformed source byte sequence is reported rather than replaced.
  - **DEFECT**: the substitution is silent; a strict decoder (`CharsetDecoder` with `CodingErrorAction.REPORT`) in the record parser would turn it into a loud failure naming the column.

- **FM-07.04-2 A narrow ClickHouse type refuses the value**
  - **Trigger**: a hand-created or overridden column narrower than `String`: an `Enum8/16` lacking a label added on MySQL by an `ALTER ... MODIFY ENUM(...)` that was not translated, a `FixedString(N)` shorter than the value, `UUID` for a `CHAR(36)` holding a non-UUID, `Bool` for a text flag, a native `JSON`/`Object` column refusing a document.
  - **Behaviour**: the value is bound as text; ClickHouse refuses it — measured with `clickhouse local` 24.8.14: `Code: 691` (unknown enum element), `Code: 131` (string too long for `FixedString`), `Code: 376` (cannot parse UUID), `Code: 41` (cannot parse DateTime), `Code: 467` (cannot parse boolean). None of these codes is in `FATAL_ERROR_CODES`, so the worker retries the batch forever. (A value shorter than `FixedString(N)` is zero-padded silently instead: `'ab'` into `FixedString(4)` stores `61620000`, measured.) Error codes for the native `JSON` type were not measured.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)` with the ClickHouse message, ERROR `ClickHouseBatchRunnable exception - Task(<id>)`, WARN `Retriable ClickHouse error (Code: 691, Category: RETRIABLE)` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit. Zero-padding: none.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits forever. No loss (nothing of the batch is written).
  - **Recovery**: P-FIX-TYPE to `String` (or add the enum value: `MODIFY COLUMN e Enum8(..., 'c' = 3)`), restart; the batch is redelivered and written.
  - **RTO**: never self-heals; after P-FIX-TYPE ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured.
  - **Test**: `PoisonValueClassificationTest.clickHouseValueRejectionsAreFatal()` (disabled, fails on 2.11.0).
  - **DEFECT**: deterministic value refusals are classified RETRIABLE and retried forever with no exit and no escalation; they should be terminal (or at least counted and escalated), like `Code: 53`/`69`.

- **FM-07.04-3 Very large `TEXT` / `JSON` value (hundreds of MB up to 1 GiB)**
  - **Trigger**: a `LONGTEXT`/`JSON` value near `max_allowed_packet` (up to 1 GiB).
  - **Behaviour (since spec 03.06 §3.4)**: a string of 64 KiB or more in a chunk at or above `insert.spill.threshold.bytes` (which such a value always reaches) is streamed from the `String` itself into the spill file with the driver's escaping: no copy beyond the value. The value and its row images are still held while Debezium decodes them (spec 01.08 FM-01.08-5): measured with `sink-connector-lightweight/tests/e2e/large_row_spill.sh` (one row inserted and updated in one transaction, i.e. three images of the value in one payload, 4 GiB heap): for a binary value; with `binary.handling.mode=bytes` rows of 64, 128, 256 and 512 MiB are value-exact with no `OutOfMemoryError` (heap after GC at most 2.6 GiB at 512 MiB), where 2.11.0 before §3.4 exited on the 256 MiB row in 2 of 3 runs and crash-looped on the restart; with `binary.handling.mode=base64` (the Ansible template's setting) 64 and 256 MiB are value-exact at 4 GiB and 512 MiB needs more than 4 GiB (value-exact at 8 GiB, heap after GC 3.1 GiB); a Java string cannot exceed about 2^31 bytes, so a value above that cannot be held at any heap size. **Before §3.4 (and when the spill path refuses itself, spec 03.06 FM-03.06-7):** the value is a Java `String` (two bytes per char once any character is outside Latin-1); the V2 driver copies it at least four times before sending — `escapeSingleQuotes` (two `String.replace`), the quoted literal, the per-row `StringBuilder` in `addBatch`, the chunk-wide `StringBuilder` in `executeInsertBatch` and its `toString` (bytecode) — and a chunk holding it is its own chunk (`BatchChunker`, a row wider than `buffer.max.bytes` is never refused). With the shipped `-Xmx4G` a value of a few hundred MB exhausts the heap (unmeasured); a Java string cannot exceed about 2^31 bytes (2^30 characters once any character is outside Latin-1), so above that the value cannot be rendered at any heap size. `OutOfMemoryError` is not caught by `ClickHouseBatchRunnable.run` (`catch (Exception e)`), so the worker's scheduled task dies, the engine stops and the process exits 3 (spec 10.04 §3.5 rule 6).
  - **Detection**: `java.lang.OutOfMemoryError` in the log, then `Sink worker <i> of <n> is dead: its scheduled task has terminated. ...` and FATAL `Replication is STOPPED: ...`, exit code 3, within ≤ 5 s; on restart the same row fails the same way; systemd gives up after 5 starts in 300 s. No metric names the row.
  - **Blast radius**: whole connector stopped; nothing written for the batch; other JVM threads may also fail while the heap is exhausted.
  - **Recovery**: raise `-Xmx` (roughly ten times the value; unmeasured) and restart; if no heap suffices, exclude the column on the source connector (`column.exclude.list`) and P-SKIP + resync, or change the application.
  - **RTO**: heap change + restart ≈ 1–2 min when it suffices; otherwise P-SKIP + resync; unmeasured.
  - **Test**: `BatchChunkerTest.oversizedRowIsItsOwnChunk()` pins that a huge row is written alone; `SpillingInsertStatementTest.largeStringMatchesTheDriver()` the streamed escaping; `large_row_spill.sh` with `BINARY_MODE=base64` (the value reaches the writer as a string) the end-to-end bound at a fixed heap.
  - **DEFECT** (residual, reader side): the largest replicable value is bounded by what the decoded row images need in the heap (and by the 2^31-byte Java string limit), not by the source; there is no loud, row-naming refusal above it.

Summary: 3 failure modes, 3 DEFECT, 2 GAP.
