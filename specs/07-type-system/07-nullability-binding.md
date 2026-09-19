# Spec 07.07: Nullability Semantics & Explicit NULL Binding

## 1. Executive Summary & Purpose
Specifies the parameter binding protocol ensuring that explicit MySQL `NULL` values are durably committed as `NULL` in ClickHouse, preventing fallback to column default expressions.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementFieldMapper.java`

---

## 3. Operational Specification

When iterating through record fields in `insertPreparedStatement()`:
1. Check if the value is null: `Object value = struct.get(fieldName) == null`.
2. If `value == null`:
   - Determine ClickHouse column type from `columnNameToDataTypeMap`.
   - Explicitly invoke:
     ```java
     ps.setNull(columnIndex, java.sql.Types.OTHER);
     ```
   - **Strict Prohibition**: Under no circumstance may the parameter be skipped, omitted, or bound as an empty string `""` or numeric zero `0`.

---

## 4. Invariants Preserved
- **Invariant I7 (Type Equivalence)**: Distinguishes between "no value provided" (`NULL`) and "zero/empty value".

---

## 5. Verification Criteria
- `PreparedStatementFieldMapperTest.testSetNullOnNullableColumns()`
