# Spec 03.03: Batch Dequeuing & Topic Partitioning

## 1. Executive Summary & Purpose
Specifies how worker threads dequeue heterogeneous batches from the handoff queue and partition them into per-table record lists for statement execution.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
- **Method**: `processBatch()`

---

## 3. Operational Specification

### 3.1 Batch Dequeue & Registration
1. Worker calls `records.poll(bufferFlushTime, TimeUnit.MILLISECONDS)`.
2. If `batch != null && !batch.isEmpty()`:
   Registers min and max timestamps in `DebeziumOffsetManagement.addToBatchTimestamps(batch)`.

### 3.2 Partitioning by Topic / Table
The raw batch is grouped into a topic map:
```java
Map<String, List<ClickHouseStruct>> topicToRecordsMap = new HashMap<>();
for (ClickHouseStruct record : batch) {
    topicToRecordsMap.computeIfAbsent(record.getTopic(), k -> new ArrayList<>()).add(record);
}
```
Each topic bucket is then processed independently against its corresponding ClickHouse target table.

---

## 4. Invariants Preserved
- **Per-Table Order Preservation**: Intra-table commit ordering is preserved within each topic partition bucket.

---

## 5. Verification Criteria
- `ClickHouseBatchRunnableTest.testTopicPartitioning()`
