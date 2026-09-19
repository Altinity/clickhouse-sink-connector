# Spec 08.05: Automatic Target Table Creation & Engine Selection

## 1. Executive Summary & Purpose
Specifies the automated synthesis and execution of ClickHouse `CREATE TABLE` DDL when `auto.create.tables = true` and a target table does not exist.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java`
- **Method**: `createTable()`

---

## 3. Operational Specification

### 3.1 DDL Synthesis Protocol
When an incoming record targets a table not found in ClickHouse metadata (`Code: 60`):
1. Infers column names, types, and nullabilities from Kafka Connect `Schema`.
2. Appends mandatory replication columns:
   - `_version UInt64`
   - `is_deleted UInt8`
   - `_sign Int8` (if CollapsingMergeTree configured)
3. Selects table engine based on configuration:
   - `ENGINE = ReplacingMergeTree(_version)` (Default)
   - `ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', _version)`
4. Configures `PRIMARY KEY` and `ORDER BY` from source table primary key fields.
5. Executes `CREATE TABLE IF NOT EXISTS` via JDBC connection.

---

## 4. Invariants Preserved
- **Zero Configuration Setup**: Enables out-of-the-box replication without manual ClickHouse DDL pre-provisioning.

---

## 5. Verification Criteria
- `ClickHouseAutoCreateTableTest.testCreateTableDDLGeneration()`
