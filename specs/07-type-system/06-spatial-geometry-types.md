# Spec 07.06: Spatial & Geometric Types (WKB to Geo Types)

## 1. Executive Summary & Purpose
Specifies the extraction, Well-Known Binary (WKB) decoding, and ClickHouse native Geo type binding for spatial geometry columns.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java`
- **Library**: Java Topology Suite (JTS) `org.locationtech.jts.io.WKBReader`

---

## 3. Operational Specification

- MySQL spatial types (`GEOMETRY`, `POINT`, `POLYGON`, `LINESTRING`, `MULTIPOINT`) are transmitted by Debezium as WKB binary structs.
- `ClickHouseDataTypeMapper` decodes the WKB payload using `WKBReader`:
  - `POINT` $\to$ ClickHouse `Point` (`Tuple(Float64, Float64)`).
  - `POLYGON` $\to$ ClickHouse `Polygon` (`Array(Ring)`).
  - Generic `GEOMETRY` $\to$ Stored as WKB byte string or native `Geometry` type.

---

## 4. Invariants Preserved
- **Geometric Topology Preservation**: Coordinates $(X, Y)$ and polygon rings preserve exact floating-point coordinate geometry.

---

## 5. Verification Criteria
- Verification: WKB decoding and Geo-type binding are not yet covered by an automated test (gap).
