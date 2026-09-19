# Spec 05.03: New Key Live Row Insertion

## 1. Executive Summary & Purpose
Specifies Phase 2 of the sorting key relocation protocol: inserting the live updated row under the new primary/sorting key coordinate.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementFieldMapper.java`
- **Method**: `insertPreparedStatement()`

---

## 3. Operational Specification

### 3.1 Live Row Construction
Immediately following the tombstone insertion:
1. Extract `afterStruct` (capturing the new sorting key and updated values).
2. Bind positional parameters for all columns using `afterStruct`.
3. Set engine columns:
   - `is_deleted = 0` (live row).
   - `_sign = 1` (CollapsingMergeTree).
   - `_version = record.getVersion()`.
4. Call `ps.addBatch()`.
5. Both statements are flushed to ClickHouse in the same atomic `executeBatch()` call.

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: The updated data is immediately queryable under the new sorting key with `FINAL`.

---

## 5. Verification Criteria
- `PrimaryKeyRelocationIT`: Validates complete data integrity across multiple primary key updates.
