# Spec 07.06: Spatial & Geometric Types (WKB)

## 1. Executive Summary & Purpose
Specifies how spatial source columns are typed on the ClickHouse side and how
their Well-Known Binary (WKB) payloads are bound: a spatial value is either
stored faithfully (as a ClickHouse geo literal or as the exact WKB bytes) or the
batch fails. No spatial value is ever fabricated.

The **DDL translation path** (CREATE / ALTER replication, §3.4) types every
spatial column as `String` (the WKB, hex-encoded), because it cannot rely on
the propagated source column type and because the ClickHouse geo types cannot
be `Nullable` — see §3.4. The **record-schema path** (§3.1) preserves `POINT`
and `POLYGON` as native geo types when the propagated source type is present.
The two creation paths therefore agree on the String form of every non-polygon
spatial type, and differ only for `POINT`/`POLYGON` (native geo on the record
path, `String` on the DDL path); both are loss-free and both are exercised by
the tests in §5.

---

## 2. Codebase Mapping on 2.11.0
- **Value path**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
  — the `Geometry.LOGICAL_NAME` / `Point.LOGICAL_NAME` branches of `convert`, `setGeoValue`
- **Record-schema type mapping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseTableOperationsBase.java`
  — `getColumnNameToCHDataTypeMapping` (auto-create and `schema.evolution` ADD COLUMN)
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`, the `isSpatialType` branch)
- **Library**: Java Topology Suite (JTS) `org.locationtech.jts.io.WKBReader`

---

## 3. Operational Specification

Debezium delivers every MySQL spatial column as a Struct: `io.debezium.data.geometry.Point`
(`x`, `y`, plus `wkb`/`srid`) for `POINT`, and `io.debezium.data.geometry.Geometry`
(`wkb` bytes, optional `srid`) for every other spatial type. The Geometry logical
name alone does not say whether the source column is a `POLYGON`, a `LINESTRING`
or a generic `GEOMETRY`; only the propagated source column type
(`__debezium.source.column.type`, always present in the lightweight runtime,
present in Kafka mode when `column.propagate.source.type` is configured) does.

### 3.1 Type mapping (record-schema path)
| Source column type | Debezium logical type | ClickHouse type |
|---|---|---|
| `POINT` | `Point` | `Point` (`Tuple(Float64, Float64)`) |
| `POLYGON` | `Geometry` | `Polygon` (`Array(Ring)`) |
| `LINESTRING`, `MULTIPOINT`, `MULTILINESTRING`, `MULTIPOLYGON`, `GEOMETRYCOLLECTION` / `GEOMCOLLECTION`, `GEOMETRY` | `Geometry` | `String` — the WKB, hex-encoded (§3.2) |
| `Geometry` with no propagated source type | `Geometry` | `Polygon` (the only value the logical name can represent natively; a non-polygon value then fails at bind time, §3.2) |

ClickHouse geo types are composite, so they are **never** wrapped in
`Nullable(...)` even for an optional source column (`Nullable(Polygon)` is
rejected by ClickHouse); the `String` form of a nullable spatial column is
`Nullable(String)`.

### 3.2 Value binding
`ClickHouseDataTypeMapper.convert`, for a `Geometry` Struct:
1. Target column `String` (any spatial type mapped per §3.1, or a
   user-declared `String`): the `wkb` bytes are bound exactly, hex-encoded
   (lower case) like every other binary value (Spec 07.05), or as raw bytes
   when `persist.raw.bytes=true`. The stored text equals
   `LOWER(HEX(ST_AsWKB(col)))` on MySQL. The SRID travels in Debezium's
   separate `srid` field and is not part of the WKB; it is not stored (known
   limitation — a column whose SRID matters needs a companion column).
2. Target column `Polygon`: the WKB is decoded with `WKBReader`; a JTS
   `Polygon` is bound as the ClickHouse polygon literal (exterior ring first,
   then interior rings; `setGeoValue` picks the object or string form the
   active JDBC driver understands).
3. **Loud failure, never a fabricated value** (`IllegalArgumentException`,
   failing the batch, naming the column when known):
   - the decoded geometry is not a `Polygon` (e.g. a `LineString` bound for a
     `Polygon` column);
   - the WKB cannot be parsed (the `ParseException` is the cause);
   - the `wkb` field is missing or not a byte carrier;
   - the value is not a Struct at all.
   Each of these was previously written as an **empty polygon** `[]`, a value
   the source never held, with the batch reported successful.

For a `Point` Struct the target column decides, exactly as for `Geometry`:
- target `Point`: the `x`/`y` fields are bound as the ClickHouse point
  literal;
- target `String` (every DDL-created spatial column, §3.4): the Struct's own
  `wkb` payload is stored as its hex string, byte for byte — `POINT(1 2)` is
  `0101000000000000000000f03f0000000000000040`, `LOWER(HEX(ST_AsWKB(col)))`
  on MySQL. Before this rule the point literal was bound regardless of the
  target, so a `String` column received the text `(1.0,2.0)` while the source
  held the WKB — a value-level divergence on every `POINT` column of a
  DDL-created table, visible in the standard-mode end-to-end suite (`t_geo`)
  as the one hash mismatch it tolerated. A `Point` Struct without a `wkb`
  payload bound for a `String` column fails the batch;
- a non-Struct carrier fails the batch (it was previously written as the
  origin `(0,0)`).

### 3.3 Other silent substitutions removed alongside (PostgreSQL-reachable)
- `VariableScaleDecimal` whose value is not a Struct: fails the batch (was
  bound as `0`).
- `ZonedTimestamp` text that matches none of the accepted ISO-8601 forms:
  fails the batch naming the text (was bound as the empty string after an
  ERROR log line).

Redelivery: nothing is written for a failed batch; the retry re-binds the
same source value and fails the same way until the ClickHouse column type is
corrected (Invariant I9).

### 3.4 DDL translation path: every spatial type is `String` (WKB hex)
The CREATE / ALTER translator (`DataTypeConverter.convertToString`) has no
propagated source-type metadata to distinguish a `POLYGON` from a `LINESTRING`
reliably, and the ClickHouse geo types cannot be `Nullable` (`Nullable(Point)`
/ `Nullable(Polygon)` are rejected with `Code: 43`) while a MySQL spatial
column is nullable unless declared `NOT NULL`. The earlier translator forced
`NOT NULL` on CREATE and emitted `Nullable(Polygon)` on
`ALTER TABLE ... ADD COLUMN`, which ClickHouse rejected and, because DDL is
retried, stalled the stream; and it mapped every non-point kind to `Polygon`,
so a `LINESTRING` or a `GEOMETRYCOLLECTION` had no column that could hold it.

Rule (`isSpatialType` branch of `convertToString`): every spatial type maps to
`String` — `Nullable(String)` when the source column is nullable, `String NOT
NULL` otherwise, exactly like any other column (Spec 06.05), on `CREATE TABLE`,
`ADD COLUMN`, `MODIFY COLUMN` and `CHANGE COLUMN`. `JSON`, which the grammar
parses in the same alternative, keeps its own (`String`) mapping. The value
path stores the WKB payload as its hex string in that column exactly as §3.2
(1) does for a `String` target, so the column holds the source bytes exactly,
is nullable when the source is, and is comparable byte-for-byte; consumers that
want geo types derive them at query time (`readWKBPolygon(unhex(col))` and
friends). This is the DDL-path counterpart of the record-schema mapping in
§3.1: the two agree on every non-polygon type and differ only for
`POINT`/`POLYGON`, where the record path keeps the native geo type when it can
and the DDL path always uses `String`.

---

## 4. Invariants Preserved
- **Invariant I7 (Value-Level Type Equivalence)**: a spatial value is stored
  either as an exact geo literal or as its exact WKB bytes (including NULL,
  without re-encoding); it is never narrowed to an empty shape or the origin.
- **Invariant I9 (Loud Failure)**: an unrepresentable or unparseable spatial
  value fails the batch with a message naming the geometry type and the column.
- **Invariant I5 (DDL barrier / stream health)**: no `ADD COLUMN` of a spatial
  type is emitted in a form ClickHouse rejects (the DDL path uses `String`, §3.4).
- **Geometric Topology Preservation**: Coordinates $(X, Y)$ and polygon rings preserve exact floating-point coordinate geometry.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testAlterAddNullableGeometryIsRepresentable()`
  — §3.4: `ADD COLUMN g GEOMETRY` / `l LINESTRING` / `mp MULTIPOLYGON` emit
  `Nullable(String)`, `ADD COLUMN p POINT NOT NULL` emits `String`, and a
  `CREATE TABLE` with nullable and `NOT NULL` spatial columns emits
  `Nullable(String)` / `String NOT NULL` (pre-fix code emits
  `Nullable(Polygon)` / `Point NOT NULL`).
- `ClickHouseDataTypeMapperGeometryTest.polygonIsBoundAsPolygon()` — §3.2 (2),
  the unchanged happy path: a POLYGON WKB binds `[[(1.0,2.0),(3.0,4.0),(5.0,6.0),(1.0,2.0)]]`.
- `ClickHouseDataTypeMapperGeometryTest.nonPolygonIntoPolygonColumnThrows()`,
  `ClickHouseDataTypeMapperGeometryTest.unparseableWkbThrows()`,
  `ClickHouseDataTypeMapperGeometryTest.nonStructGeometryThrows()` — §3.2 (3);
  the pre-fix code binds `[]` and nothing is thrown.
- `ClickHouseDataTypeMapperGeometryTest.geometryIntoStringColumnIsWkbHex()` —
  §3.2 (1): the bound text is the byte-exact hex of the WKB (pre-fix code binds
  `[]`).
- `ClickHouseDataTypeMapperGeometryTest.nonStructPointThrows()`,
  `ClickHouseDataTypeMapperGeometryTest.nonStructVariableScaleDecimalThrows()`,
  `ClickHouseDataTypeMapperGeometryTest.unparseableZonedTimestampThrows()` —
  §3.2 (Point) and §3.3.
- `ClickHouseDataTypeMapperGeometryTest.pointIntoStringColumnIsWkbHex()` —
  §3.2 (Point into a `String` column): the bound text is the byte-exact hex of
  the Point Struct's WKB (`0101000000000000000000f03f0000000000000040` for
  `POINT(1 2)`), and a Point Struct without a WKB payload is refused; the
  pre-fix code binds the literal `(1.0,2.0)`. The standard-mode end-to-end
  suite compares `t_geo` (`POINT`, `LINESTRING`, `POLYGON`, `GEOMETRY`
  columns created by the DDL path) hash-for-hash against
  `HEX(ST_AsWKB(col))` on the source.
- `CreateTableDataTypesIT.testCreateTable()` (lightweight, MySQL) — §3.2
  (Point) end to end through the DDL path: `employees.point_table` is
  created and one row inserted on the source, then the replicated `c3a` /
  `c3b` `String` columns are compared with `LOWER(HEX(ST_AsWKB(col)))` read
  from the source for the same row (and `c3a` with the `POINT(1 2)` constant
  above). Before this rule the test asserted the point literal `(1.0,2.0)`.
  The same test also reads `system.columns` on the replica and requires both
  DDL-created `POINT` columns to be `String` (§3.4), so the value comparison
  is made against the column type the spec prescribes.
- `ClickHouseDataTypeMapperGeometryTest.recordSchemaMapsNonPolygonSpatialTypesToString()`
  — §3.1: `POLYGON` → `Polygon` (also when optional), `LINESTRING` /
  `MULTIPOLYGON` / `GEOMETRY` / `GEOMCOLLECTION` → `String` /
  `Nullable(String)`, no source type → `Polygon`, `POINT` → `Point`.

---

## 6. Failure Modes & Recovery

Recovery posture: a spatial value is stored faithfully or refused (§3.2); the refusals are raised by the connector as `IllegalArgumentException`, which carries no ClickHouse error code, so on 2.11.0 every spatial poison value is retried forever rather than stopping the engine. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-07.06-1 Non-polygon geometry for a `Polygon` column**
  - **Trigger**: a `LINESTRING`/`MULTIPOLYGON`/`GEOMETRY` value reaches a column typed `Polygon` — Kafka Connect mode without `column.propagate.source.type` auto-creates every `Geometry` column as `Polygon` (§3.1), or a hand-created table.
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` decodes the WKB and throws `IllegalArgumentException` (`Geometry for column <c> is a LineString, not a Polygon, and the ClickHouse column is Polygon; refusing to store an empty polygon in its place. Declare the column as String ...`). `ClickHouseErrorClassifier.classify` returns UNKNOWN; the worker retries the batch forever.
  - **Detection**: that message in ERROR `ClickHouseBatchRunnable exception - Task(<id>)` plus WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)` every ≤ 30 s; metric `clickhouse.sink.topics.error.records`; no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze; the next DDL drain waits forever. Nothing of the batch is written; no loss.
  - **Recovery**: P-FIX-TYPE with `MODIFY COLUMN g String` (accepted by ClickHouse 24.8; existing polygons become their text literal `[[(1,2),...]]`, measured) and restart; the redelivered batch writes WKB hex; then P-RESYNC the table so the older rows carry WKB hex as well. In Kafka mode set `column.propagate.source.type=.*` before any further auto-create.
  - **RTO**: never self-heals; P-FIX-TYPE ≈ 1–2 min + resync of the table; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.nonPolygonIntoPolygonColumnThrows()` (the refusal), `PoisonValueClassificationTest.spatialValueRefusalIsFatal()` (disabled, fails on 2.11.0: UNKNOWN instead of FATAL).
  - **DEFECT**: a deterministic refusal is retried forever with no exit; the connector's own refusal types must join `TERMINAL_EXCEPTION_TYPES` (as `ValueOutOfRangeException` did).

- **FM-07.06-2 Unparseable or missing WKB, non-Struct carrier**
  - **Trigger**: a corrupt WKB payload, a `Geometry`/`Point` Struct without `wkb` bound for a `String` column, or a value that is not a Struct (Kafka converters, a Debezium change).
  - **Behaviour**: `ClickHouseDataTypeMapper.convert` throws `IllegalArgumentException` (`WKB payload for column <c> (<n> bytes) cannot be parsed ...`, `... carries no WKB payload ...`, `... is a <class>, not a Struct ...`); UNKNOWN, retried forever — as FM-07.06-1.
  - **Detection**: as FM-07.06-1, with these messages.
  - **Blast radius**: as FM-07.06-1.
  - **Recovery**: none by retry and none by P-FIX-TYPE (the value itself is bad); repair the source row and P-SKIP + P-RESYNC the transaction's tables.
  - **RTO**: unbounded; P-SKIP + resync; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.unparseableWkbThrows()`, `ClickHouseDataTypeMapperGeometryTest.nonStructGeometryThrows()`, `ClickHouseDataTypeMapperGeometryTest.nonStructPointThrows()`; classification: as FM-07.06-1.
  - **DEFECT**: as FM-07.06-1 — retried forever instead of refused terminally.

- **FM-07.06-3 NULL in a nullable `POLYGON` / `POINT` column on the record-schema path**
  - **Trigger**: the record-schema auto-create path (spec 08.05) types an optional `POLYGON` as `Polygon` and `POINT` as `Point`, never `Nullable` (ClickHouse rejects `Nullable(Polygon)`, §3.1); the source row holds NULL.
  - **Behaviour**: `PreparedStatementFieldMapper.insertPreparedStatement` binds `setNull`; with `input_format_null_as_default=0` (spec 07.07 §3.2.1) ClickHouse refuses with `Code: 53 Cannot insert NULL value into a column of type ...`; 53 is FATAL: the worker dies and the process exits 3.
  - **Detection**: ERROR `FATAL ClickHouse error (Code: 53) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: whole connector stopped; nothing lost.
  - **Recovery**: P-FIX-TYPE to `Nullable(String)` (`MODIFY COLUMN g Nullable(String)`), restart, then P-RESYNC the table (older rows hold the geo literal, new rows WKB hex).
  - **RTO**: ≈ 1–2 min to resume + resync of the table; unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.recordSchemaMapsNonPolygonSpatialTypesToString()` pins the non-Nullable `Polygon` mapping of an optional `POLYGON`; `PoisonValueClassificationTest.nullIntoNonNullableColumnIsFatal()` pins the terminal refusal.
  - **DEFECT**: the record-schema mapping creates a column that is guaranteed to stop replication on the first source NULL; an optional `POLYGON`/`POINT` should map to `Nullable(String)` like the DDL path (§3.4).

- **FM-07.06-4 SRID dropped**
  - **Trigger**: a spatial column whose SRID differs between rows or matters to consumers.
  - **Behaviour**: only the WKB is stored; Debezium's separate `srid` field is ignored by every branch of `ClickHouseDataTypeMapper.convert` (§3.2 (1), known limitation).
  - **Detection**: none. DEFECT (documented limitation).
  - **Blast radius**: the SRID is absent from the replica for every row; coordinates are exact.
  - **Recovery**: add a companion column on the source (`ST_SRID(g)`) so it replicates as an ordinary column; P-RESYNC to back-fill.
  - **RTO**: not applicable (design limitation); unmeasured.
  - **Test**: `ClickHouseDataTypeMapperGeometryTest.geometryIntoStringColumnIsWkbHex()` pins that exactly the WKB is stored; GAP: a test that a non-zero SRID is either stored or reported.
  - **DEFECT**: a source attribute is dropped with no signal.

Summary: 4 failure modes, 4 DEFECT, 1 GAP.
