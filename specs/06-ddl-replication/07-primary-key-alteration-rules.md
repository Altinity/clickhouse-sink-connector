# Spec 06.07: Primary Key Alteration Suppression Rules

## 1. Executive Summary & Purpose
Specifies the suppression of `ALTER TABLE ... ADD PRIMARY KEY` clauses on existing ClickHouse tables, avoiding ClickHouse `Code: 524` (Sorting key cannot be modified after table creation).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`

---

## 3. Operational Specification

In ClickHouse:
- In `ReplacingMergeTree`, the primary key and `ORDER BY` sorting key are established during `CREATE TABLE` and are immutable.
- Executing `ALTER TABLE t ADD PRIMARY KEY (...)` fails with:
  ```
  Code: 524. DB::Exception: Modifying primary key is not supported.
  ```

### 3.1 Suppression Rule
- When the ANTLR visitor encounters an `ALTER TABLE` containing an `ADD PRIMARY KEY` clause on an existing table:
  - If the statement contains multiple clauses (e.g. `ALTER TABLE t ADD PRIMARY KEY (id), ADD COLUMN c INT`), the `ADD PRIMARY KEY` clause is stripped, ensuring no leading or trailing commas break the remaining clauses.
  - If the statement contains only `ADD PRIMARY KEY`, the translator logs an informational warning and emits an empty query, acknowledging the event without failing the stream.

---

## 4. Invariants Preserved
- **Continuous Stream Health**: Avoids fatal syntax exceptions on operations ClickHouse physically cannot execute.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testStripAddPrimaryKeyClause()`
