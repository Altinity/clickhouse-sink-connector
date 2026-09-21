# Spec 04.04: UPDATE Event Handling & Before/After Image Processing

## 1. Executive Summary & Purpose
Specifies the handling of MySQL UPDATE operations (`op == 'u'`) across standard replication mode and replication history mode.

---

## 2. Codebase Mapping on 2.11.0
- **Grouping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Binding**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` (`executePreparedStatement`) and `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **History mode**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/ReplicationHistoryHandler.java`, enabled by `replication.history.enable` (`ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE`)

---

## 3. Operational Specification

### 3.1 Standard Mode Update Handling
- In standard replication mode, an UPDATE event carries both a `before` struct and an `after` struct.
- If the UPDATE does not relocate the sorting key, the `after` image is processed as a standard insert with `is_deleted = 0` and the record's `_version`.
- If the UPDATE alters any sorting key column (`updateRelocatesSortingKey`, spec 05.01), the two-phase tombstone protocol (specs 05.02 and 05.03) binds an old-key tombstone before the new-key insert in the same batch.

### 3.2 Replication History Mode (`binlog_history`)
When `replication.history.enable = true`:
- The connector maintains SCD Type 2 bitemporal history in the configured history tables.
- Both `before` and `after` images are preserved with audit columns:
  - `_operation = 'u'`
  - `_valid_from`, `_valid_to`
  - `is_deleted`

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: Latest update images strictly supersede earlier versions for the same primary key.

---

## 5. Verification Criteria
- `GroupInsertQueryHistoryMultiRowTest.standardModeStillSplitsUpdateIntoBeforeAndAfter()`, `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()`, `GroupInsertQueryHistoryMultiRowTest.recordsAfterTheFirstUpdateSurviveInHistoryMode()`.
- `ReplicationHistoryHandlerTest` — history-column population; `ReplicationHistoryHandlerTest.compositePrimaryKeyClosesOnlyTheMatchingRow()` — the previous history row is closed by every primary-key column, not only the first (spec 02.01 §3.5 a).
- `BinLogHistoryIT` — end to end history mode.
