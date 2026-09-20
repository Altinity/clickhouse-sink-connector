# Spec 10.05: Redelivered-Record De-duplication (`deduplication.policy`)

## 1. Executive Summary & Purpose
Specifies the optional in-task de-duplication of Kafka Connect `SinkRecord`s
(`deduplication.policy = OLD | NEW`, default `OFF`). Its only legitimate purpose
is to drop a record the framework **redelivers** (rebalance rewind, retried
`put()`), i.e. the *same event* seen twice. It must never drop a *different*
event that happens to touch the same source row.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/deduplicator/DeDuplicator.java`
  - `isNew(String topicName, SinkRecord record)`
  - `prepareDeDuplicationKey(SinkRecord record)` — event identity
  - `updateDedupePool(String topicName, Object key)` — bounded pool
- **Consumer**: `ClickHouseSinkTask.put()` — a record for which `isNew()` is
  false is not converted and never reaches the writers.
- **Config**: `deduplication.policy` (`DeDuplicationPolicy`), `buffer.count`
  (pool bound).

---

## 3. Operational Specification

### 3.1 De-duplication key = event identity, never the row key
The key is the record's position in the stream:
```
(topic, kafkaPartition, kafkaOffset)
```
Kafka guarantees that one `(topic, partition, offset)` names exactly one event,
and a redelivery reproduces it exactly. The row key (`SinkRecord.key()`, the
source primary key) is **not** an event identity: every later UPDATE and DELETE
of a row carries the same key as its INSERT. Keying on it made
`deduplication.policy=OLD|NEW` discard every subsequent change to a row after
the first event seen for it — total, silent loss of updates and deletes, with
the first image frozen in ClickHouse forever.

`policy = NEW` versus `OLD` only decides which `SinkRecord` object the pool
retains for an already-seen identity; in both cases `isNew()` returns false for
the redelivered record.

### 3.2 Bounded pool
The pool is per topic. Each accepted identity is appended to a per-topic FIFO;
when the FIFO exceeds `buffer.count`, the oldest identities are evicted from
**the same map `isNew()` consults**. (The earlier implementation pruned a
separate, always-empty queue, so the map consulted for duplicates grew without
bound for the life of the task.)

### 3.3 Default
`OFF`. De-duplication is opt-in; the writers are idempotent under
`ReplacingMergeTree` versioning for a redelivered event, so `OFF` is safe.

---

## 4. Invariants Preserved
- **Invariant I3 (Convergence)**: no source event is dropped; only a byte-for-byte redelivery of an already-accepted event is.
- **Invariant I9 (Loud Failure)**: a dropped duplicate is logged at WARN with its identity.

---

## 5. Verification Criteria
- `DeDuplicatorTest.testSamePrimaryKeyDifferentOffsetsAreBothNew()` — two
  records with the same row key at offsets 10 and 11, policy `NEW`: both
  `isNew() == true` (pre-fix: the second is false).
- `DeDuplicatorTest.testRedeliveredEventIsDuplicate()` — the same
  `(topic, partition, offset)` seen twice: second `isNew() == false`, for both
  `OLD` and `NEW`.
- `DeDuplicatorTest.testPoolIsBoundedByBufferCount()` — after `buffer.count + k`
  distinct identities the per-topic pool size is `<= buffer.count` (pre-fix: it
  grows without bound).
- `DeDuplicatorTest.testIsNew()` — policy `OFF` accepts everything; different
  topics have independent pools.
