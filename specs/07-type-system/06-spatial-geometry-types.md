# Spec 07.06: Spatial & Geometric Types (WKB to Geo Types)

## 1. Executive Summary & Purpose
Specifies how spatial source columns are typed on the ClickHouse side and how
their Well-Known Binary (WKB) payloads are bound: a spatial value is either
stored faithfully (as a ClickHouse geo literal or as the exact WKB bytes) or the
batch fails. No spatial value is ever fabricated.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
  — the `Geometry.LOGICAL_NAME` / `Point.LOGICAL_NAME` branches of `convert`, `setGeoValue`
- **Record-schema type mapping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseTableOperationsBase.java`
  — `getColumnNameToCHDataTypeMapping` (auto-create and `schema.evolution` ADD COLUMN)
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

For a `Point` Struct the `x`/`y` fields are bound as the ClickHouse point
literal; a non-Struct carrier fails the batch (it was previously written as the
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

---

## 4. Invariants Preserved
- **Invariant I7 (Value-Level Type Equivalence)**: a spatial value is stored
  either as an exact geo literal or as its exact WKB bytes; it is never
  narrowed to an empty shape or the origin.
- **Invariant I9 (Loud Failure)**: an unrepresentable or unparseable spatial
  value fails the batch with a message naming the geometry type and the column.
- **Geometric Topology Preservation**: Coordinates $(X, Y)$ and polygon rings preserve exact floating-point coordinate geometry.

---

## 5. Verification Criteria
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
- `ClickHouseDataTypeMapperGeometryTest.recordSchemaMapsNonPolygonSpatialTypesToString()`
  — §3.1: `POLYGON` → `Polygon` (also when optional), `LINESTRING` /
  `MULTIPOLYGON` / `GEOMETRY` / `GEOMCOLLECTION` → `String` /
  `Nullable(String)`, no source type → `Polygon`, `POINT` → `Point`.
