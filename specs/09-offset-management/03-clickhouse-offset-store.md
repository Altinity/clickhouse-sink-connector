# Spec 09.03: ClickHouse-Backed Durable Offset Storage (`replica_source_info`)

## 1. Executive Summary & Purpose
Specifies the storage of replication offsets in a native ClickHouse `ReplacingMergeTree` table (`replica_source_info`) via `JdbcOffsetBackingStore`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/storage/JdbcOffsetBackingStore.java`
- **Table**: `systemDb.replica_source_info`

---

## 3. Operational Specification

### 3.1 Table DDL
```sql
CREATE TABLE IF NOT EXISTS replica_source_info (
    offset_key String,
    offset_val String,
    record_insert_ts DateTime64(3) DEFAULT now64(3)
) ENGINE = ReplacingMergeTree(record_insert_ts)
ORDER BY offset_key
```

### 3.2 Read on Cold Start
- On bootstrap, `JdbcOffsetBackingStore.load()` queries:
  ```sql
  SELECT offset_key, offset_val FROM replica_source_info FINAL
  ```
- Rebuilds Debezium offset map in memory.

### 3.3 Write on Checkpoint
- `JdbcOffsetBackingStore.save()` issues:
  ```sql
  INSERT INTO replica_source_info (offset_key, offset_val) VALUES (?, ?)
  ```
- ReplacingMergeTree ensures latest checkpoint supersedes previous positions.

---

## 4. Invariants Preserved
- **Crash Recovery Parity**: Restarting the connector after sudden power loss resumes replication exactly from the last durably acknowledged batch.

---

## 5. Verification Criteria
- `JdbcOffsetBackingStoreTest.testLoadAndSave()`
