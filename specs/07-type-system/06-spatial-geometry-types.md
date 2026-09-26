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
- `ClickHouseDataTypeMapperGeometryTest.recordSchemaMapsNonPolygonSpatialTypesToString()`
  — §3.1: `POLYGON` → `Polygon` (also when optional), `LINESTRING` /
  `MULTIPOLYGON` / `GEOMETRY` / `GEOMCOLLECTION` → `String` /
  `Nullable(String)`, no source type → `Polygon`, `POINT` → `Point`.
