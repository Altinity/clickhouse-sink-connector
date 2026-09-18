# Spec 05.01: Sorting Key Relocation Detection Algorithm

## 1. Executive Summary & Purpose
Specifies the detection algorithm that determines whether an incoming MySQL UPDATE event alters any column belonging to the ClickHouse table's `ORDER BY` sorting key.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementExecutor.java`
- **Method**: `boolean updateRelocatesSortingKey(ClickHouseStruct record, List<String> sortingKeyColumns)`

---

## 3. Operational Specification

### 3.1 Detection Algorithm
1. Check event operation: if `record.getCdcOperation() != 'u'`, return `false`.
2. Retrieve `Struct beforeStruct = record.getBeforeStruct()` and `Struct afterStruct = record.getAfterStruct()`.
3. If `beforeStruct == null` or `afterStruct == null`, return `false`.
4. For each column name $C$ in `sortingKeyColumns`:
   - Obtain value before: $V_{\text{before}} = \text{beforeStruct.get}(C)$.
   - Obtain value after: $V_{\text{after}} = \text{afterStruct.get}(C)$.
   - Compare $V_{\text{before}}$ and $V_{\text{after}}$:
     If `!Objects.equals(V_before, V_after)`:
     Return `true` (Relocation detected!).
5. If all sorting key column values are equal, return `false` (In-place update).

---

## 4. Invariants Preserved
- **Invariant I4 (Sorting Key Mutation Integrity)**: Accurately identifies all cases where in-place row replacement is impossible under ClickHouse `ReplacingMergeTree`.

---

## 5. Verification Criteria
- `PreparedStatementExecutorTest.testUpdateRelocatesSortingKey()`
