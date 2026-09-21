# Spec 07.06: Spatial & Geometric Types (WKB as String)

## 1. Executive Summary & Purpose
Specifies how MySQL spatial columns are represented in ClickHouse: as a
`String` holding the source's Well-Known Binary (WKB) payload as hex, on every
path that creates or alters a column.

---

## 2. Codebase Mapping on 2.11.0
- **Value path**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **DDL path (CREATE / ALTER translation)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/parser/DataTypeConverter.java` (`convertToString`, the `SpatialDataTypeContext` branch)

---

## 3. Operational Specification

### 3.1 Why not the ClickHouse Geo types
Debezium transmits every MySQL spatial type (`GEOMETRY`, `POINT`, `LINESTRING`,
`POLYGON`, `MULTIPOINT`, `MULTILINESTRING`, `MULTIPOLYGON`,
`GEOMETRYCOLLECTION`) as a struct carrying the WKB bytes and an SRID. The
ClickHouse Geo types cannot hold that faithfully:
- `Point` and `Polygon` cannot be `Nullable` (ClickHouse rejects
  `Nullable(Point)` with `Code: 43`), yet a MySQL spatial column is nullable
  unless declared `NOT NULL`. The DDL translator therefore forced `NOT NULL`
  on CREATE and emitted `Nullable(Polygon)` on `ALTER TABLE ... ADD COLUMN`,
  which ClickHouse rejected; because DDL is retried, that stalled the stream.
- Every non-point kind was mapped to `Polygon`, so a `LINESTRING` or a
  `GEOMETRYCOLLECTION` had no column that could hold it.
- The SRID is dropped, and the coordinates are re-encoded through a decoder
  and the Geo tuple types, so `db_compare` cannot compare the two sides
  byte-for-byte.

### 3.2 Rule: the spatial family is `String` (WKB hex)
On the DDL path every spatial type maps to `String` — `Nullable(String)` when
the source column is nullable, `String NOT NULL` otherwise, exactly like any
other column (Spec 06.05). This holds for `CREATE TABLE`, `ADD COLUMN`,
`MODIFY COLUMN` and `CHANGE COLUMN`. `JSON`, which the grammar parses in the
same alternative, is unaffected (it was already `String`).

The value path stores the WKB payload as its hex string in that column
(companion change on the writer side). The column then holds the source bytes
exactly, is nullable when the source is, and is comparable byte-for-byte.
Consumers that want ClickHouse Geo types derive them at query time
(`readWKBPoint(unhex(col))` and friends); the replica never re-encodes what the
source sent.

---

## 4. Invariants Preserved
- **Invariant I7 (Value-Level Type Equivalence)**: the ClickHouse column can
  hold every source value, including NULL, without re-encoding.
- **Invariant I5 (DDL barrier / stream health)**: no `ADD COLUMN` of a spatial
  type is emitted in a form ClickHouse rejects.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testAlterAddNullableGeometryIsRepresentable()`
  — `ADD COLUMN g GEOMETRY` / `l LINESTRING` / `mp MULTIPOLYGON` emit
  `Nullable(String)`, `ADD COLUMN p POINT NOT NULL` emits `String`, and a
  `CREATE TABLE` with nullable and `NOT NULL` spatial columns emits
  `Nullable(String)` / `String NOT NULL` (pre-fix code emits
  `Nullable(Polygon)` / `Point NOT NULL`).
- Value path: WKB-hex binding is verified on the writer side (companion
  change); until then the DDL rule above is the only automated coverage.
