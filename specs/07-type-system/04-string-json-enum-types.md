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
