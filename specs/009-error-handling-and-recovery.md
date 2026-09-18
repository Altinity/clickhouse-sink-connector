# Specification 009: Error Classification, Retries & Status Monitoring

## 1. Executive Summary & Purpose

The error handling and resilience subsystem classifies exceptions encountered during CDC parsing, network transport, and ClickHouse JDBC batch execution. It enforces strict triage: transient errors (such as network disconnects or transient lock timeouts) are retried with exponential backoff, while structural errors (such as unrecoverable schema conflicts or poisoned records) fail loudly and immediately.

This prevents silent data divergence while ensuring self-healing capabilities against transient infrastructure disruptions.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.db.error.ClickHouseErrorClassifier` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.error.ClickHouseErrorClassifier.CLICKHOUSE_ERROR_SEVERITY` (`sink-connector/...`)
  - `com.altinity.clickhouse.debezium.embedded.storage.DebeziumJdbcStorageOperations` (`sink-connector-lightweight/...`)
- **Key Methods**:
  - `ClickHouseErrorClassifier.classify()`
  - `DebeziumJdbcStorageOperations.createViewForShowReplicaStatus()`

---

## 3. Error Classification Taxonomy

The connector categorizes errors into three severity tiers:

```
+-----------------------------------------------------------------------------------+
|                        ERROR CLASSIFICATION TAXONOMY                              |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  [Tier 1: FATAL]                                                                  |
|       - Unrecoverable syntax errors, illegal type conversions.                    |
|       - OffsetStorageWriter semaphore leak.                                       |
|       - Missing table when `auto.create.tables=false`.                            |
|       -> Action: Terminate connector task immediately; log stack trace loudly.    |
|                                                                                   |
|  [Tier 2: RETRIABLE]                                                              |
|       - Network socket disconnects, ClickHouse connection refused.               |
|       - ClickHouse Memory Limit Exceeded (Code: 241).                             |
|       - Zookeeper session expiration, transient cluster rebalancing.              |
|       -> Action: Re-queue batch; apply exponential backoff; retry up to max retry.|
|                                                                                   |
|  [Tier 3: UNKNOWN]                                                                |
|       - Unclassified SQL errors or third-party exceptions.                        |
|       -> Action: Default to bounded retry; fail loudly if error persists.         |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. Notable Error Codes & Recovery Semantics

| Error Code / Signature | Root Cause | Connector Operational Policy |
|---|---|---|
| `ClickHouse Code: 241` | Memory limit exceeded on ClickHouse server | **Retriable**: Worker pauses for backoff interval, allowing ClickHouse server memory to recover before resubmitting. |
| `ClickHouse Code: 60` | Table does not exist in target database | If `auto.create.tables=true`, triggers auto-creation; otherwise **Fatal**. |
| `ClickHouse Code: 36` | Cannot alter Nullable column to non-Nullable | Prevented by DDL translator keeping column Nullable (see Spec 005). If thrown, **Fatal**. |
| `ClickHouse Code: 524` | Cannot alter primary / sorting key | Suppressed in DDL translator; if thrown directly, **Fatal**. |
| `OffsetStorageWriter is already flushing` | Debezium internal offset semaphore leak | **Fatal**: Hard abort to force JVM restart, preventing infinite retries with frozen offsets. |

---

## 5. Replica Status Monitoring (`show_replica_status`)

To provide real-time observability into replication health, the connector provisions a monitoring view in ClickHouse:
- Method: `DebeziumJdbcStorageOperations.createViewForShowReplicaStatus()`
- View Name: `system.show_replica_status`
- Exposed Metrics:
  - Last replicated binlog file and position.
  - Ingestion lag in milliseconds (`now() - source_ts`).
  - Total rows replicated and error counters.

---

## 6. Invariants Preserved

1. **Loud Failure & Zero Swallowing (Invariant I9)**:
   Under no circumstances may a worker catch a JDBC execution exception and drop the batch to allow the connector to keep running.
   If a batch fails after all configured retries are exhausted, the connector **must throw a fatal runtime exception and halt execution**.
   A crashed connector alerts operational teams and stops replication safely at a known offset. A connector that drops failed rows creates silent data corruption that requires expensive full-table reconciliations to detect.
2. **Deterministic Crash Recovery**:
   Terminating on unrecoverable errors guarantees that offsets in `replica_source_info` remain pinned at the last successful batch, allowing safe resumption after fixing the underlying issue.

---

## 7. Verification Criteria

- **Unit Tests**:
  `ClickHouseErrorClassifierTest`.
- **Integration Tests**:
  `ErrorHandlingIT`, asserting task termination on fatal errors and recovery on transient disconnections.
- **Formal Verification**:
  Corresponds to Invariant I9 (Loud Failure) in `specs/CONSTITUTION.md`.
