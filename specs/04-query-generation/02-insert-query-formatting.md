# Spec 04.02: Parameterized Insert Query Formatting

## 1. Executive Summary & Purpose
Specifies the construction of parameterized `INSERT` SQL strings and column-to-index parameter mappings in `QueryFormatter`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/QueryFormatter.java`
- **Method**: `createColumns()`

---

## 3. Operational Specification

### 3.1 Column List Generation
For a given table $T$ and record schema $S$:
1. Identifies the intersection of ClickHouse physical columns and CDC record fields.
2. Appends mandatory engine columns:
   - `_version` (ReplacingMergeTree)
   - `is_deleted` (ReplacingMergeTree with delete flag)
   - `_sign` (CollapsingMergeTree)
3. Generates SQL text:
   ```sql
   INSERT INTO `db`.`table` (`col1`, `col2`, ..., `_version`, `is_deleted`) VALUES (?, ?, ..., ?, ?)
   ```
4. Produces parameter mapping `Map<String, Integer> columnNameToIndexMap` (1-indexed for JDBC `setObject`).

---

## 4. Invariants Preserved
- **SQL Injection Safety**: Column identifiers and database names are backtick-escaped; values are strictly passed via JDBC positional placeholders (`?`).

---

## 5. Verification Criteria
- `QueryFormatterTest.testCreateColumns()`
