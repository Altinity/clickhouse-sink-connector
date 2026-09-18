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

---

## 4. Invariants Preserved
- **Encoding Integrity**: Characters, multibyte UTF-8 sequences, and emojis round-trip without corruption.

---

## 5. Verification Criteria
- `ClickHouseDataTypeMapperTest.testStringAndJsonTypes()`
