# Spec 06.06: Generated Columns Mapping: DEFAULT vs. MATERIALIZED

## 1. Executive Summary & Purpose
Specifies the translation of MySQL generated columns (`GENERATED ALWAYS AS (expr) STORED/VIRTUAL`) to ClickHouse column definitions, establishing why they must be mapped to `DEFAULT (expr)` and never `MATERIALIZED`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`

---

## 3. Operational Specification

### 3.1 The MATERIALIZED Trap
In ClickHouse:
- A `MATERIALIZED` column is computed at insertion time by ClickHouse and **strictly rejects** explicit values in `INSERT` statements (`Code: 44. Cannot insert value into a column with type MATERIALIZED`).
- Debezium, depending on configuration, may transmit the generated value from MySQL in CDC payloads.
- If mapped to `MATERIALIZED`, ClickHouse rejects the insert, stalling replication.

### 3.2 Mapping to DEFAULT (expr)
- When parsing:
  ```sql
  ALTER TABLE t ADD COLUMN full_name VARCHAR(100) GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) STORED
  ```
- The translator emits:
  ```sql
  ALTER TABLE t ADD COLUMN IF NOT EXISTS full_name Nullable(String) DEFAULT (CONCAT(first_name, ' ', last_name))
  ```
- **Benefits**:
  1. If MySQL sends the generated value, ClickHouse accepts and stores it directly.
  2. If MySQL omits the column, ClickHouse evaluates the default expression automatically.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority)**: ClickHouse computed expressions never block source data ingestion.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testGeneratedColumnMapping()`
