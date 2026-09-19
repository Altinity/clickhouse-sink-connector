# Spec 10.03: Replica Status View & Monitoring Metrics

## 1. Executive Summary & Purpose
Specifies the structure and operational query interface of the ClickHouse monitoring view (`system.show_replica_status`).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/storage/DebeziumJdbcStorageOperations.java`
- **Method**: `createViewForShowReplicaStatus()`

---

## 3. Operational Specification

### 3.1 View Schema
```sql
CREATE OR REPLACE VIEW system.show_replica_status AS
SELECT
    hostName() AS host,
    now() AS current_time,
    argMax(offset_val, record_insert_ts) AS last_offset,
    max(record_insert_ts) AS last_replicated_time
FROM replica_source_info
```

### 3.2 Lag Metric Extraction
- Ingestion lag is computed as:
  $$\text{Lag}_{\text{ms}} = \text{now64}(3) - \text{last\_replicated\_time}$$
- Exposed to Prometheus metrics endpoint when `metrics.enable = true`.

---

## 4. Invariants Preserved
- **Observability**: Exposes real-time replication status directly within ClickHouse system tables.

---

## 5. Verification Criteria
- `DebeziumJdbcStorageOperationsTest.testCreateViewForShowReplicaStatus()`
