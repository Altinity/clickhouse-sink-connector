# Spec 06.04: ALTER TABLE Clause Translation Rules

## 1. Executive Summary & Purpose
Specifies the translation mapping from MySQL ALTER TABLE clauses to native ClickHouse ALTER TABLE syntax.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`

---

## 3. Translation Mapping Specification

| MySQL Syntax | ClickHouse Translated Syntax | Notes |
|---|---|---|
| `ALTER TABLE t ADD COLUMN c INT` | `ALTER TABLE t ADD COLUMN IF NOT EXISTS c Int32` | Type mapped via `ClickHouseDataTypeMapper` |
| `ALTER TABLE t ADD COLUMN c INT AFTER a` | `ALTER TABLE t ADD COLUMN IF NOT EXISTS c Int32 AFTER a` | Position preserved |
| `ALTER TABLE t ADD COLUMN c INT FIRST` | `ALTER TABLE t ADD COLUMN IF NOT EXISTS c Int32 FIRST` | Position preserved |
| `ALTER TABLE t DROP COLUMN c` | `ALTER TABLE t DROP COLUMN IF EXISTS c` | Safe idempotent drop |
| `ALTER TABLE t MODIFY COLUMN c VARCHAR(200)` | `ALTER TABLE t MODIFY COLUMN c Nullable(String)` | Nullability adjusted per Spec 06.05 |
| `ALTER TABLE t CHANGE COLUMN old_c new_c INT` | `ALTER TABLE t RENAME COLUMN old_c TO new_c` (if type unchanged) / `ALTER TABLE t ADD ...` | Handled via ClickHouse column rename or type modification |
| `RENAME TABLE old_t TO new_t` | `RENAME TABLE old_t TO new_t` | Standard rename |

---

## 4. Invariants Preserved
- **Idempotency**: Adds `IF NOT EXISTS` / `IF EXISTS` to prevent catastrophic DDL failure on repeated replays.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testAlterTableAddColumn()`
- `MySqlDDLParserListenerImplTest.testAlterTableDropColumn()`
