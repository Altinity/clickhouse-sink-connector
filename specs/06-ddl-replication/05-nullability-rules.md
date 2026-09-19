# Spec 06.05: Nullability Translation & NOT NULL Modification Rules

## 1. Executive Summary & Purpose
Specifies the handling of `MODIFY COLUMN ... NOT NULL` statements to avoid ClickHouse `Code: 36` (Cannot convert column from Nullable to non-Nullable without default or part rewrites), which historically caused infinite retry loops and stream stalls.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java`
- **Related PRs**: #1455, #1457, #1459

---

## 3. Operational Specification

### 3.1 The ClickHouse Code 36 Conflict
In ClickHouse:
- Executing `ALTER TABLE db.table MODIFY COLUMN col Type NOT NULL` on an existing column that was created as `Nullable(Type)` fails with:
  ```
  Code: 36. DB::Exception: Cannot convert column from Nullable to non-Nullable because it may contain NULL values.
  ```
- If retried indefinitely, this halts the entire replication pipeline.

### 3.2 Translation Policy (PR #1455 & #1459)
1. **For `ADD COLUMN ... NOT NULL`**:
   The `NOT NULL` constraint is safely honored because no existing rows have values for this column. ClickHouse creates the column as non-Nullable.
2. **For `MODIFY COLUMN ... NOT NULL` on Existing Columns**:
   The translator keeps the ClickHouse column as `Nullable(Type)`.
   - **Rationale**: MySQL already enforces that all future inserts for this column are non-null. Keeping the ClickHouse column `Nullable` allows all future non-null values to be inserted without triggering `Code: 36` or stalling the pipeline.

---

## 4. Invariants Preserved
- **Continuous Replication**: Eliminates DDL stall deadlocks while preserving the ability to ingest all valid upstream data.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testModifyColumnNotNullKeepsNullable()`
