# Spec 07.01: Integer Data Type Mapping & Range Rules

## 1. Executive Summary & Purpose
Specifies the conversion of signed and unsigned MySQL integer types to ClickHouse integer primitives without sign inversion or overflow.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`, `normalizeIntegerTypeName`), `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`SERIAL` nullability and key candidacy)

---

## 3. Type Conversion Matrix

| MySQL Type | Connect Schema | ClickHouse Target | Conversion Logic |
|---|---|---|---|
| `TINYINT` | `INT8` | `Int8` | Standard 8-bit signed byte |
| `TINYINT UNSIGNED` | `INT16` | `UInt8` | Zero-extended to `short`, stored as unsigned 8-bit |
| `TINYINT(1)` | `INT8` / `BOOLEAN` | `Bool` / `UInt8` | Configurable: maps to `Bool` or `UInt8` |
| `SMALLINT` | `INT16` | `Int16` | Standard 16-bit signed short |
| `SMALLINT UNSIGNED` | `INT32` | `UInt16` | Zero-extended to `int`, stored as unsigned 16-bit |
| `MEDIUMINT` | `INT32` | `Int32` | Standard 32-bit signed int |
| `MEDIUMINT UNSIGNED` | `INT32` | `UInt32` | Stored as unsigned 32-bit int |
| `INT` / `INTEGER` | `INT32` | `Int32` | Standard 32-bit signed int |
| `INT UNSIGNED` | `INT64` | `UInt32` | Handled via `Long.longValue()`, stored as `UInt32` |
| `BIGINT` | `INT64` | `Int64` | Standard 64-bit signed long |
| `BIGINT UNSIGNED` | `INT64` (default `bigint.unsigned.handling.mode=long`) | `UInt64` | Values $\ge 2^{63}$ arrive as a **negative** `long` (two's-complement wrap); the mapper restores the unsigned magnitude before binding (§3.1) |
| `BIGINT UNSIGNED` | `BYTES` / `Decimal` (only if `bigint.unsigned.handling.mode=precise` is configured on the source) | `UInt64` | Bound as `BigDecimal`; no wrap occurs |

### 3.1 `BIGINT UNSIGNED` under Debezium's default `long` mode
No connector configuration sets `bigint.unsigned.handling.mode=precise`, so the
default `long` applies: Debezium emits `INT64` and a MySQL value in
$[2^{63}, 2^{64})$ wraps to a negative Java `long` (e.g. `18446744073709551615`
arrives as `-1L`). `ClickHouseDataTypeMapper.convert` previously bound that
`long` with `ps.setObject`, so ClickHouse either rejected the row (negative into
`UInt64`) or, through driver coercion, stored a different number.

Rule: when the Kafka type is `INT64` (no logical name), the ClickHouse target
column is `UInt64`, and the value is a negative `Long`, bind
`new BigInteger(Long.toUnsignedString(value))` — the exact MySQL value. Values
$< 2^{63}$ are unaffected. A negative `long` bound for a *signed* `Int64`
target is left untouched (it is a genuine negative `BIGINT`).

### 3.2 DDL path: every spelling of an unsigned integer maps to `UInt`
The DDL translator resolves the declared type through Debezium's
`DataTypeResolver`, whose type *name* carries the attribute tokens verbatim
(`BIGINT UNSIGNED ZEROFILL`, `INT8 UNSIGNED`, `SERIAL`). The unsigned lookup
used to be an exact match against six spellings (`tinyint unsigned` …
`bigint unsigned`), so every other spelling fell through to the signed Kafka
schema mapping and was created **signed**: `BIGINT UNSIGNED ZEROFILL` →
`Int64`, `INT8 UNSIGNED` → `Int64`, `SERIAL` → `Nullable(Int64)`, `INT(10)
UNSIGNED ZEROFILL` → `Int64`. A value in $[2^{63}, 2^{64})$ (or, for the
narrower types, above the signed maximum) then cannot be stored — a rejected
insert or a wrapped value, both divergence.

Rule (`DataTypeConverter.normalizeIntegerTypeName`, applied before any lookup):
1. Lower-case, collapse whitespace.
2. Fold the integer synonyms MySQL defines: `INT1` → `TINYINT`, `INT2` →
   `SMALLINT`, `INT3` / `MIDDLEINT` → `MEDIUMINT`, `INT4` → `INT`, `INT8` →
   `BIGINT`, `SERIAL` → `BIGINT UNSIGNED`.
3. `ZEROFILL` implies `UNSIGNED` (MySQL adds the attribute itself); the token
   is then dropped, as is a redundant `SIGNED`.
4. A normalised name containing `unsigned` resolves through
   `ClickHouseDataTypeMapper.getUnsignedClickHouseType` (the same function the
   record path uses, which also tolerates a display width), so `TINYINT` /
   `SMALLINT` / `MEDIUMINT` / `INT` / `BIGINT UNSIGNED` in any spelling map to
   `UInt8` / `UInt16` / `UInt32` / `UInt32` / `UInt64`. A signed name keeps the
   signed mapping (`TINYINT` and `INT1` → `Int8`).
5. `SERIAL` is MySQL shorthand for `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
   UNIQUE`: the DDL translator maps it to `UInt64`, treats the column as
   `NOT NULL` (CREATE and `ADD COLUMN`), and on CREATE registers it as the
   candidate `UNIQUE` key so a table with only a `SERIAL` column is keyed by
   it (Spec 06.05 §3.6).

### 3.3 Auto-created column type for `BIGINT UNSIGNED` needs the propagated source type
The record-schema auto-create path (`ClickHouseTableOperationsBase.getColumnNameToCHDataTypeMapping`,
Spec 08.05) can only declare `UInt64` when the field carries Debezium's
`__debezium.source.column.type` parameter (`column.propagate.source.type`).
The lightweight runtime always sets `column.propagate.source.type=.*`
(`DebeziumChangeEventCapture.setupDebeziumEventCapture`). In Kafka Connect
mode nothing forces it: an `INT64` field with no logical name and no
parameters is indistinguishable from a signed `BIGINT`, is declared `Int64`,
and a `BIGINT UNSIGNED` value in $[2^{63}, 2^{64})$ is then stored as the
wrapped **negative** number — §3.1 cannot help because the target column is
not `UInt64`.

Rule: whenever the mapping declares `Int64` for an `INT64` field that carries
no `__debezium.source.column.type` parameter, it logs **one ERROR per table**
(static, per JVM, keyed by the table label) naming the column(s) and the
remedy: set `column.propagate.source.type=.*` on the source connector, or
declare the ClickHouse table by hand. The connector cannot resolve the
ambiguity itself (there is no source metadata to read in Kafka mode), so the
loud report is the fallback of Invariant I9, not a substitute for the fix.
The `ADD COLUMN` path (`ClickHouseAlterTable.alterTable`) passes the table
name through so the report is attributable there too.

### 3.4 `YEAR`: the stream value 1900 is the zero year `0000`
MySQL stores `YEAR` in one byte `b`: `b = 0` is the year `0000` (its number is 0), and `b` from 1 is the year
`1900 + b`, so the real years run from 1901 to 2155. Debezium delivers `YEAR` as `INT32` with the logical name
`io.debezium.time.Year`, mapped to `Int32` (`ClickHouseDataTypeMapper`, the `(INT32, Year.SCHEMA_NAME)` entry).
The binlog row decoder adds 1900 to every byte, so the stream carries the zero year as **1900**, a year MySQL
cannot hold. Before this section the connector stored 1900, a year that is not in the source. A reader that
trusts the column then sees a real-looking year, and a value comparison against MySQL (`y + 0` is 0) differs.

Rule (`ClickHouseDataTypeMapper.zeroYearValue`, called from `convert(...)` for a field whose schema name is
`io.debezium.time.Year`, before the integer bind): the value 1900 is bound as 0, the number MySQL returns for
`YEAR 0000`; every other value is bound unchanged. A field of any other schema is never touched, because 1900
is a real value of every other integer column. The mapped 0 is the number the source holds, so it is not a
lossy conversion: it is not reported, and `clamp.out.of.range=false` does not refuse it. It follows the same
bind as before: `setObject` for an `Int32`, `Int64`, `UInt32` or `UInt64` target, `setInt` for a narrower one.

Limits, measured and not changed here:
- A zero `YEAR` read by the **snapshot** arrives as 2000. The JDBC driver converts it before the connector
  sees the value, and 2000 is a real year, so the sink cannot tell it from a source 2000. Setting the driver
  property `yearIsDateType=false` through `driver.` and through `database.` left the stored value at 2000
  (measured with an initial snapshot of a table holding `YEAR 0000`, 1901, 2024 under each setting). To find
  the rows, query the source: `SELECT COUNT(*) FROM <t> WHERE <year column> = 0`; their ClickHouse rows hold 2000.
- Rows already stored as 1900 stay 1900 until the table is resynchronised (spec 11.04). On the source,
  the same query counts them.
- A history-mode `before`/`after` text carries Debezium's rendering of the value, so a zero year is 1900 there.

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Prevents signed/unsigned overflow or truncation.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.getClickHouseDataType()` — signed MySQL
  integer types map to `Int8`/`Int16`/`Int32`/`Int64`.
- `ClickHouseTableOperationsBaseUntypedInt64Test.untypedInt64LogsOneErrorPerTable()`
  — §3.3: two mappings of the same table with an `INT64` field lacking the
  source type produce exactly one ERROR naming the column and
  `column.propagate.source.type`; a field carrying the parameter, and a
  different table, are reported separately / not at all (pre-fix code logs
  nothing).
- `ClickHouseDataTypeMapperTest.getUnsignedClickHouseType()` — `TINYINT` /
  `SMALLINT` / `MEDIUMINT` / `INT` / `BIGINT UNSIGNED` map to `UInt8` /
  `UInt16` / `UInt32` / `UInt32` / `UInt64`; display width and `ZEROFILL`
  are tolerated; signed types are not remapped.
- `ClickHouseDataTypeMapperInt8Test.int8ByteIsBoundToInt8Column()`,
  `ClickHouseDataTypeMapperInt8Test.int8ByteIsBoundToUInt8Column()`,
  `ClickHouseDataTypeMapperInt8Test.negativeInt8KeepsItsSign()` — a
  `TINYINT` arrives as `INT8` and is bound without sign loss or truncation.
- `ClickHouseDataTypeMapperUInt64Test.testWrappedUnsignedBigintIsRestoredForUInt64Target()`
  — `-1L` → `18446744073709551615`, `Long.MIN_VALUE` → `9223372036854775808`
  (pre-fix code binds the negative `long`).
- `ClickHouseDataTypeMapperUInt64Test.testNegativeLongForSignedInt64TargetIsUnchanged()`
  — a real negative `BIGINT` stays negative.
- §3.2: `MySqlDDLParserListenerImplTest.testUnsignedSynonymsAndZerofillMapToUInt()`
  — `BIGINT UNSIGNED ZEROFILL`, `INT8 UNSIGNED`, `INT(10) UNSIGNED ZEROFILL`,
  `INT ZEROFILL`, `INT1 UNSIGNED`, `MIDDLEINT UNSIGNED`, `SMALLINT(5) ZEROFILL`
  map to the `UInt` types; `INT1` maps to `Int8` (pre-fix code emits the signed
  types); `MySqlDDLParserListenerImplTest.testSerialIsUnsignedNotNullKey()` —
  `SERIAL` is `UInt64 NOT NULL` and the sorting key on CREATE, `UInt64` (not
  `Nullable`) on `ADD COLUMN`.
- §3.4: `ZeroTimestampYearCallSiteTest.theStreamValueOfTheZeroYearIsBoundAsZero()` — 1900 on a `Year` field is
  bound as 0; `ZeroTimestampYearCallSiteTest.everyOtherYearIsBoundUnchanged()` — 0, 1, 1899, 1901, 1999, 2000,
  2024 and 2155 are not touched; `ZeroTimestampYearCallSiteTest.aWiderIntegerTargetReceivesTheMappedYearToo()`
  and `ZeroTimestampYearCallSiteTest.aNarrowIntegerTargetReceivesTheMappedYearToo()` — the mapping reaches the
  `setObject` and the `setInt` bind;
  `ZeroTimestampYearCallSiteTest.aPlainIntegerColumnHoldingNineteenHundredIsNotRemapped()` and
  `ZeroTimestampYearCallSiteTest.aNamedSchemaThatIsNotYearIsNotRemapped()` — 1900 in any other integer or
  `DATE` field is a real value and is not remapped; `ZeroTimestampYearCallSiteTest.theMappedYearIsNotAReportedConversion()`
  — nothing is counted and strict mode does not refuse it.
- §3.4 invariant: for every stored `YEAR` byte the mapped stream value is the number MySQL returns, only the stream
  value 1900 changes, and 1900 comes only from the byte 0.

---

## 6. Failure Modes & Recovery

Recovery posture: an integer is bound as the number Debezium delivered; the connector performs no range check against the ClickHouse column type, so a value the column cannot hold is either refused by ClickHouse (terminal only if its error code is in `FATAL_ERROR_CODES`, spec 10.01) or — the common case, measured — wrapped silently by ClickHouse. The failure modes below are therefore mostly silent-divergence modes whose only repair is on the ClickHouse side plus a re-synchronisation.

Procedures referenced by every Failure Modes section of domains 07 and 04:
- **P-FIX-TYPE** (no data lost): (1) change the ClickHouse column so it can hold the source value, `ALTER TABLE <db>.<t> MODIFY COLUMN <c> <type>` (a wider integer / `Decimal`, `Date32`, `DateTime64`, `String`, `Nullable(T)`); a sorting-key column cannot be made `Nullable` (ClickHouse `Code: 524`, measured) and needs a table rebuild (spec 06.09) or `ch-mysql-resync` (spec 11.04); (2) restart the connector (`systemctl restart <unit>`; after five failed starts in 300 s the shipped unit `systemd_sink_connector.service.j2` gives up, so run `systemctl reset-failed <unit>` first). The restart is required: the worker's cached column map (`ClickHouseBatchRunnable.getDbWriterForTable`) is re-read only after a replicated DDL bumps `CacheInvalidationManager`, or when a record carries a column the cache lacks (spec 08.03), so an out-of-band `ALTER` is not seen by the retry loop; (3) the retained batch is redelivered from the last committed offset and written. RTO: the `ALTER` (metadata-only for widenings, unmeasured) + `RestartSec=30` + engine start (unmeasured) + re-apply of the in-flight transaction.
- **P-SKIP** (only when no column type can hold the value): stop the connector; find the failing transaction's end position with `mysqlbinlog` from the committed offset (`sink-connector-client show_replica_status`); move past it with `sink-connector-client change_replication_source --binlog_file <f> --binlog_position <p>` (or `--gtid`); start; then `ch-mysql-resync` (spec 11.04) every table that transaction touched. Moving the offset alone is never a recovery (Invariant I15 property 2). RTO: minutes + a resync proportional to the tables' size.
- **P-RESYNC**: for a silent divergence already written, fix the cause, then `ch-mysql-resync` (spec 11.04) the affected tables; a source `UPDATE` of the affected rows also repairs them row by row.
- Retry vs stop (multi-threaded default, `single.threaded=false`): a batch whose exception classifies FATAL kills its worker (`ClickHouseBatchRunnable.run`), the engine stops on the next source batch (≤ 5 s with the default heartbeat) and the process exits 3 (spec 10.04 §3.5); any other exception is retried by the same worker forever, 500 ms doubling to 30 s (`batch.retry.backoff.initial.ms` / `batch.retry.backoff.max.ms`), logging ERROR `ClickHouseBatchRunnable exception - Task(<id>)` and WARN `Retriable ClickHouse error (Code: <n>, Category: <c>) -- the same batch will be retried in <ms> ms (consecutive failures: <k>)` per attempt; offsets stop behind it and a pending DDL drain waits on it (spec 06.01). With `single.threaded=true` a failure goes through the engine retry budget (10 × 10 s without progress) and then exits 3.

- **FM-07.01-1 `BIGINT UNSIGNED` ≥ 2^63 into a signed `Int64` column**
  - **Trigger**: Kafka Connect mode without `column.propagate.source.type`, or a hand-created table, declares `Int64` for a `BIGINT UNSIGNED` column; a value in [2^63, 2^64) arrives as a negative `long`.
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` restores the magnitude only when the target is `UInt64` (§3.1); for an `Int64` target the negative `long` is bound unchanged and stored as the wrapped negative number.
  - **Detection**: auto-create path only — one ERROR per table from `ClickHouseTableOperationsBase.getColumnNameToCHDataTypeMapping` naming the column and `column.propagate.source.type` (§3.3), at table creation. Hand-created `Int64`: `Detection: none`.
  - **Blast radius**: the affected rows hold a different number; row counts match; nothing stops. No self-heal.
  - **Recovery**: set `column.propagate.source.type=.*` (Kafka mode) or P-FIX-TYPE to `UInt64` (measured with `clickhouse local`: `MODIFY COLUMN v UInt64` turns the stored -1 into 18446744073709551615, i.e. the wrapped rows become correct without a resync).
  - **RTO**: P-FIX-TYPE ≈ 1–2 min (the conversion itself repairs the wrapped rows; no resync); unmeasured end to end — no harness.
  - **Test**: `ClickHouseTableOperationsBaseUntypedInt64Test.untypedInt64LogsOneErrorPerTable()` (the ERROR), `ClickHouseDataTypeMapperUInt64Test.testNegativeLongForSignedInt64TargetIsUnchanged()` (the bind).
  - **DEFECT**: for a hand-created `Int64` column the wrap is silent; the connector cannot tell the signed and unsigned cases apart at bind time and does not compare against the source column type when it is propagated.

- **FM-07.01-2 Integer wider than its ClickHouse column (overflow wrap)**
  - **Trigger**: the ClickHouse column is narrower than the source value — a hand-created table, a MySQL `ALTER ... MODIFY` widening that was not applied on ClickHouse (DDL translation failure, `sql_log_bin=0` schema change), or a signed/unsigned mismatch (`INT UNSIGNED` 4000000000 into `Int32`, `-1` into `UInt32`).
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` binds `setInt` / `setObject` without a range check; ClickHouse 24.8 wraps the value in the VALUES path (measured with `clickhouse local`: 4000000000 → `Int32` stores -294967296, 300 → `UInt8` stores 44, -1 → `UInt32` stores 4294967295, 18446744073709551615 → `Int64` stores -1). The batch succeeds.
  - **Detection**: none. DEFECT.
  - **Blast radius**: silent value divergence on every overflowing row, row counts intact; only a value-level checksum (spec 11.02) finds it.
  - **Recovery**: P-FIX-TYPE to the correct width, then P-RESYNC for the table (the wrapped rows cannot be reversed reliably).
  - **RTO**: unbounded detection; after detection P-FIX-TYPE + resync; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperPoisonValueTest.integerOutsideTheTargetColumnRangeIsRefused()` (disabled, fails on 2.11.0).
  - **DEFECT**: no bind-time range check against the declared integer type (`ClickHouseDataType` is known per column), so overflow is stored as a different number with the batch reported successful. Fix: refuse with `ValueOutOfRangeException` (terminal) exactly like the temporal policy of spec 07.03 §3.3.

- **FM-07.01-3 `BOOL` / `TINYINT(1)` value other than 0/1 into a `Bool` column**
  - **Trigger**: MySQL `BOOL`/`BOOLEAN` is `TINYINT(1)` and stores -128..127; the DDL translator declares it `Bool`; an application writes `2` or `-1`.
  - **Behaviour**: the value arrives as `INT16` and is bound with `setObject` (`ClickHouseDataTypeMapper.convert`); ClickHouse stores any non-zero integer into `Bool` as `true` (measured: `INSERT ... VALUES (5)` reads back `true`, `toUInt8` 1).
  - **Detection**: none. DEFECT.
  - **Blast radius**: silent divergence (MySQL 2, ClickHouse 1) on the affected rows; nothing stops.
  - **Recovery**: P-FIX-TYPE to `Int8` (`MODIFY COLUMN c Nullable(Int8)`; the already-collapsed rows stay 1), then P-RESYNC.
  - **RTO**: unbounded detection; after detection minutes + resync; unmeasured.
  - **Test**: `MySqlDDLBoolRangeTest.boolIsDeclaredWithTheTinyintRange()` (disabled, fails on 2.11.0), `MySqlDDLBoolRangeTest.tinyintOneIsInt8()` (the correct half), `ClickHouseDataTypeMapperPoisonValueTest.boolColumnRefusesATinyintOtherThanZeroOrOne()` (disabled).
  - **DEFECT**: the DDL path maps `BOOL` to `Bool`, a type narrower than the source; declare `Int8` (as for `TINYINT(1)`), or refuse values outside {0,1} at bind time.

- **FM-07.01-4 Unsigned spelling the DDL normaliser does not know**
  - **Trigger**: a future or vendor spelling of an integer type that `DataTypeConverter.normalizeIntegerTypeName` does not fold (e.g. a new synonym) reaches the DDL path.
  - **Behaviour**: it falls through to the signed Kafka-schema mapping (§3.2), creating a signed column; values above the signed maximum then follow FM-07.01-2.
  - **Detection**: none at DDL time; as FM-07.01-2 afterwards.
  - **Blast radius**: as FM-07.01-2 for that column.
  - **Recovery**: P-FIX-TYPE to the `UInt` type, P-RESYNC.
  - **RTO**: as FM-07.01-2; unmeasured.
  - **Test**: `MySqlDDLParserListenerImplTest.testUnsignedSynonymsAndZerofillMapToUInt()` pins every known spelling; GAP: a test that an integer type name the normaliser does not recognise fails the DDL loudly instead of defaulting to signed.

- **FM-07.01-5 `YEAR 0000` read by the snapshot**
  - **Trigger**: a `YEAR` column holds `0000` (a legacy writer under `sql_mode=''`) and the connector takes an initial snapshot or a resync reads the table through the snapshot.
  - **Behaviour**: the JDBC driver delivers the zero year as 2000 before the connector sees it, so ClickHouse stores 2000, a real year, where MySQL holds 0. The stream path stores 0 (§3.4).
  - **Detection**: none at the sink. On the source, `SELECT COUNT(*) FROM <t> WHERE <year column> = 0` counts the rows whose ClickHouse copy reads 2000 after a snapshot.
  - **Blast radius**: only the rows holding `0000`; row counts intact.
  - **Recovery**: no setting recovers the value (§3.4 limits); change the source value to NULL or a real year, or accept the stored 2000 for those rows and exclude the column from the value comparison.
  - **RTO**: unmeasured.
  - **Test**: none; the stream half is `ZeroTimestampYearCallSiteTest.theStreamValueOfTheZeroYearIsBoundAsZero()`.
  - **DEFECT**: the snapshot value is converted by the driver before the sink; a connector-side fix needs a snapshot read that does not convert the year.

Summary: 5 failure modes, 4 DEFECT, 1 GAP.
