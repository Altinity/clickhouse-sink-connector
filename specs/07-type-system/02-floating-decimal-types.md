# Spec 07.02: Floating Point & Fixed-Precision Decimal Types

## 1. Executive Summary & Purpose
Specifies the preservation of exact scales, precisions, and IEEE 754 representations for floating-point and decimal values.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`)

---

## 3. Operational Specification

### 3.1 IEEE 754 Floating Point
Value path (`ClickHouseDataTypeMapper`, Kafka schema → bind):
- `FLOAT32` schema $\to$ `Float32` (`java.lang.Float`)
- `FLOAT64` schema $\to$ `Float64` (`java.lang.Double`)

DDL path (`DataTypeConverter.convertToString`, declared MySQL type → column
type). Debezium 3.1.3 resolves MySQL `FLOAT` / `FLOAT4` to `Types.FLOAT` and
`DOUBLE` / `FLOAT8` / `DOUBLE PRECISION` to `Types.DOUBLE`, and maps **both** to
a `float64` Kafka schema; only `REAL` (`Types.REAL`) is narrowed to `float32`
before any connector code runs. The column types follow what is delivered:
- `FLOAT`, `FLOAT(p)`, `FLOAT(M,D)`, `DOUBLE`, `DOUBLE(M,D)` $\to$ `Float64`
  (a `Float64` column holds every single-precision value exactly; the earlier
  text of this section said `FLOAT` → `Float32`, which the DDL path has never
  emitted).
- `REAL` $\to$ `Float32` (the value arrives already narrowed; declaring
  `Float64` would only make truncated data look full-precision).
- The declared dimensions of a floating type are **never** copied to the
  ClickHouse type: `FLOAT(7,3)` used to become `Float64(7,3)`, which
  ClickHouse rejects (`Code: 36`/`70`), so the CREATE or ALTER failed on every
  retry and stalled the stream. A `(precision[, scale])` suffix is emitted only
  for `Decimal` and for `DateTime64`, the two ClickHouse types that take one.

Value-comparison caveat: mapping `FLOAT` to `Float64` is a **widening**. The
4-byte value is stored exactly (a `float` is exactly representable as a
`double`), but ClickHouse renders it with double precision — MySQL shows `1.1`
for a `FLOAT`, ClickHouse shows `1.100000023841858` for the same bits. A
value-level comparison must therefore compare `toFloat32(col)` on ClickHouse,
or render both sides with the same shortest-repr algorithm, rather than
comparing default string renderings (see `DataTypeConverter` and
`DataTypeConverterTest`).

### 3.2 Fixed-Point Decimals (`DECIMAL(P, S)`)
- Debezium encodes `DECIMAL` values as scaled binary byte arrays or `BigDecimal` objects.
- `ClickHouseDataTypeMapper` binds values directly via `ps.setBigDecimal(index, (BigDecimal) value)`:
  - **No Float Conversion**: The value is never converted to `double` or `float` in memory, completely eliminating binary floating-point rounding errors.
  - Scale $S$ and precision $P$ are mapped directly into ClickHouse `Decimal(P, S)` / `Decimal32` / `Decimal64` / `Decimal128`.

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Financial and mathematical decimal values preserve exact penny and fractional precision.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperFloat64Test.float64MapsToFloat64()`, `ClickHouseDataTypeMapperFloat64Test.float32StillMapsToFloat32()`, `ClickHouseDataTypeMapperFloat64Test.float32ColumnCannotRepresentADoubleValue()` — §3.1.
- `CreateTableDataTypesIT` — auto-created `Decimal(65, 30)` columns for MySQL `DECIMAL` (type mapping, end to end).
- `MySqlDDLParserListenerImplTest.testFloatWithDimensionsIsFloat64()` — DDL path: `FLOAT(7,3)`, `FLOAT`, `DOUBLE(10,2)` → `Float64`, `REAL` → `Float32`, `DECIMAL(7,3)` keeps `Decimal(7,3)` (pre-fix code emits `Float64(7,3)`).
- Verification: a unit test asserting `setBigDecimal` binding without float conversion (§3.2 value path) is not yet covered by an automated test (gap).
- Test-config note: the Postgres fixture `sink-connector-lightweight/src/test/resources/init_postgres.sql` deliberately seeds `tm.amount` with an 89-digit negative `numeric` (about -5.8e88, far outside the Decimal128 range) to exercise the bounded decimal path, so every IT that loads it (`PostgresInitialDockerIT`, `PostgresSchemaAwareNamingIT`, `PostgresDeleteOperationsIT`, `PostgresUpdateOperationsIT`, `PostgresSnapshotCompletionIT`, `PostgresInitialDockerWKeeperMapStorageIT`, `PostgresPgoutputMultipleSchemaIT`, `PostgresInfinityTimestampIT`, `ClickHouseDebeziumEmbeddedPostgresPgoutputDockerIT`, `ClickHouseDebeziumEmbeddedPostgresDecoderBufsDockerIT`) runs with `clamp.out.of.range=true`, stated once in the shared `PostgresProperties.getDefaultProperties`; saturation is the product default (spec 07.03 §3.3), and the explicit setting only keeps the fixture's dependence on it visible. The strict setting (`clamp.out.of.range=false`), pinned by `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDatetimeAtTheMapper()`, `DebeziumConverterRangePolicyTest.strictSettingRejectsOutOfRangeDateAtTheMapper()`, `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()` and `PreparedStatementFieldMapperOutOfRangeTest.outOfRangeValueNamesDatabaseTableAndColumn()`, refuses that row, and the refusal is terminal for the batch under spec 10.01 §3.1.

---

## 6. Failure Modes & Recovery

Recovery posture: floats and decimals are bound without float conversion (`setFloat`, `ClickHouseDoubleValue.asBigDecimal`, `setBigDecimal`), and the driver renders them as unquoted literals; a value the column cannot hold is refused by ClickHouse (`Code: 69`, terminal) or by the connector itself (retried forever), and a scale mismatch is truncated silently by ClickHouse. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.02-1 `NaN` / `±Infinity` in a double-precision value**
  - **Trigger**: a PostgreSQL `float8` holding `NaN`, `Infinity` or `-Infinity` (MySQL rejects these values, so a MySQL source cannot produce them).
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` binds a `Double` through `ClickHouseDoubleValue.of(v).asBigDecimal()`, which throws `NumberFormatException("Infinite or NaN")` (clickhouse-data 0.9.8, verified in bytecode). The exception carries no ClickHouse code, so `ClickHouseErrorClassifier.classify` returns UNKNOWN and the worker retries the batch forever. A `Float` (`FLOAT32`) goes through `setFloat` and is stored (`nan`/`inf` are valid `Float32`/`Float64` values, measured with `clickhouse local`).
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with `java.lang.NumberFormatException: Infinite or NaN` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s; metric `clickhouse.sink.topics.error.records` increments per attempt; no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits forever (spec 06.01). No data is lost or duplicated.
  - **Recovery**: none by retry and none by P-FIX-TYPE (the refusal is in the connector, not in ClickHouse). Either fix the source row (`UPDATE` it to a finite value — the retained batch still holds the old value, so P-SKIP is needed for that transaction) or P-SKIP + resync.
  - **RTO**: unbounded; P-SKIP + resync of the table; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperPoisonValueTest.float64NaNAndInfinityAreBound()` (disabled, fails on 2.11.0 with the `NumberFormatException`); `ClickHouseDataTypeMapperPoisonValueTest.float32NaNAndInfinityAreBound()` pins the `Float32` half.
  - **DEFECT**: a value ClickHouse can store is refused by the bind path, and the refusal is retried forever. Fix: bind a non-finite `Double` directly (`setDouble`), and add the connector's own refusals to the terminal set.

- **FM-07.02-2 Decimal wider than the ClickHouse column precision**
  - **Trigger**: a hand-created or overridden `Decimal(P,S)` narrower than the source `DECIMAL`, or a MySQL `ALTER ... MODIFY` widening not applied on ClickHouse.
  - **Behaviour**: `setBigDecimal` binds the value; the V2 driver renders it unquoted (`Object.toString`, verified in `PreparedStatementImpl.encodeObject` bytecode); ClickHouse refuses it with `Code: 69 ... Too many digits (11 > 10) in decimal value (ARGUMENT_OUT_OF_BOUND)` (measured with `clickhouse local`). 69 is in `FATAL_ERROR_CODES`: the worker dies, the engine stops and the process exits 3. (The same digits as a quoted string are stored silently beyond the declared precision — measured — which is why the unquoted rendering matters.)
  - **Detection**: ERROR `FATAL ClickHouse error (Code: 69) -- this batch will never succeed.`, then FATAL `Replication is STOPPED: ...` and exit code 3 within ≤ 5 s (heartbeat) of the failure; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: whole connector stopped; nothing written for the batch; no loss, no divergence.
  - **Recovery**: P-FIX-TYPE to a `Decimal` at least as wide as the source (`MODIFY COLUMN c Decimal(65,30)` for the widest MySQL type).
  - **RTO**: P-FIX-TYPE ≈ 1–2 min + re-apply of the in-flight transaction; unmeasured.
  - **Test**: `PoisonValueClassificationTest.decimalTooManyDigitsIsFatal()`, `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`.

- **FM-07.02-3 Decimal with more fractional digits than the column scale**
  - **Trigger**: the ClickHouse column scale is smaller than the source scale (hand-created or overridden table; a PostgreSQL variable-scale `numeric` into a fixed-scale column).
  - **Behaviour**: ClickHouse truncates the extra fractional digits without error (measured: `1.239` into `Decimal(10,2)` stores `1.23`, truncation not rounding). The batch succeeds.
  - **Detection**: none. DEFECT.
  - **Blast radius**: silent value divergence in the low digits (penny errors), row counts intact.
  - **Recovery**: P-FIX-TYPE to the source scale, then P-RESYNC (the truncated digits are gone).
  - **RTO**: unbounded detection; after detection P-FIX-TYPE + resync; unmeasured.
  - **Test**: GAP: a unit test that a `BigDecimal` whose scale exceeds the declared `Decimal(P,S)` scale of the target column is refused at bind time rather than handed to ClickHouse.
  - **DEFECT**: the declared scale is known per column (`ColumnBinding`) but never compared with the value's scale.

- **FM-07.02-4 PostgreSQL variable-scale decimal beyond `Decimal128`**
  - **Trigger**: a PostgreSQL `numeric` with more than 38 integer digits (the repository's own fixture seeds -5.8e88).
  - **Behaviour**: `BigDecimalConverter.truncate` via `RangePolicy.boundDecimal`: saturated to the `Decimal128` bound by default (`clamp.out.of.range=true`, DEBUG only), `ValueOutOfRangeException` (terminal) under `clamp.out.of.range=false` — the policy of spec 07.03 §3.3, see spec 07.03 §6 FM-07.03-1 and FM-07.03-2.
  - **Detection**: default: none at the default log level; strict: ERROR `FATAL ClickHouse error (Code: -1)` + exit 3.
  - **Blast radius**: default: the bound is stored in place of the value; strict: connector stopped, nothing lost.
  - **Recovery**: strict: widen the column to `Decimal256`/`String` (P-FIX-TYPE) or set `clamp.out.of.range=true` and restart.
  - **RTO**: strict: P-FIX-TYPE ≈ 1–2 min; unmeasured.
  - **Test**: `DebeziumConverterRangePolicyTest.strictPolicyNamesColumnValueAndBounds()`, `ClickHouseErrorClassifierTest.valueOutOfRangeIsFatalRegardlessOfCode()`.

Summary: 4 failure modes, 2 DEFECT, 1 GAP.
