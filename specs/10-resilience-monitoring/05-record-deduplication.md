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
  false is not converted and never reaches the writers. (A Kafka tombstone —
  `value() == null` — is skipped before de-duplication; any other record that
  does not convert fails the task, spec 10.04 §3.5.)
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

### 3.4 Scope: the Kafka Connect sink only
`DeDuplicator` is constructed only in `ClickHouseSinkTask.start()` and consulted
only in `ClickHouseSinkTask.put()`; no other class references it.
The lightweight engine (`DebeziumChangeEventCapture`) has **no** record-identity
de-duplication of any kind: a redelivered row is always written again, and its
fate is decided by `_version` under `ReplacingMergeTree` — within one run by the
high-water gate of spec 02.04 §3.2 (the redelivered copy keeps its own, lower
version), and across a restart by the seeded floor of spec 02.02 §3.5 (the
replayed copies carry the same data and rank above the stored ones). Lightweight
replay safety therefore rests entirely on version ordering, never on this
filter; do not cite `deduplication.policy` as a lightweight safeguard.

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
- §3.4 (lightweight engine has no record-identity de-duplication) is a code-structure fact (`DebeziumChangeEventCapture` never references `DeDuplicator`); the lightweight replay behaviour it defers to is pinned by `DebeziumChangeEventCaptureTest.replayAfterSeededRestartIsClampedAboveTheOldRun()` and `CommitOrderVersionClampTest.redeliveryKeepsRedeliveryStableVersion()`.

---

## 6. Failure Modes & Recovery
De-duplication is off by default, and with it off a redelivered event is simply written again and collapses under `_version`; the lightweight engine never uses it (section 3.4). With it on, the pool records an identity before the record is handed off, so a `put()` that Kafka Connect retries after a retriable failure loses its records -- the only failure mode here that loses data, and it does so silently.

- **FM-10.05-1 A redelivered event is dropped (the intended case)**
  - **Trigger**: a consumer rebalance rewinds a partition, or Connect redelivers records already handed off.
  - **Behaviour**: `DeDuplicator.isNew` finds `(topic, partition, offset)` in the per-topic pool and returns false; the record is not converted. The pool keeps the last `buffer.count` (default 100) identities per topic, so a redelivery older than that is written again and collapses under ReplacingMergeTree.
  - **Detection**: WARN `Duplicate delivery of event {} on topic {}; dropping the redelivered record` per dropped record.
  - **Blast radius**: none; the original copy is already queued or written.
  - **Recovery**: none needed.
  - **RTO**: 0.
  - **Test**: `DeDuplicatorTest.testRedeliveredEventIsDuplicate()`, `DeDuplicatorTest.testSamePrimaryKeyDifferentOffsetsAreBothNew()`, `DeDuplicatorTest.testPoolIsBoundedByBufferCount()`.

- **FM-10.05-2 A retried `put()` is dropped as a duplicate**
  - **Trigger**: `deduplication.policy=old` or `new`, and the handoff at the end of `ClickHouseSinkTask.put` is interrupted (a task stop, a rebalance, a thread interrupt), so `put` throws `RetriableException`; Kafka Connect's contract for that exception is to call `put` again with the same records (framework behaviour, not re-read in this run).
  - **Behaviour**: the first call already recorded every identity (`isNew` runs per record before `this.records.put(batch)`); the retried call finds them all, drops each one, and hands off an empty batch. The records never reach a writer; the next batch that is written advances the durable watermark behind `preCommit` past them.
  - **Detection**: WARN `Duplicate delivery of event {} on topic {}; dropping the redelivered record` for each lost record -- indistinguishable from FM-10.05-1; no ERROR, no metric.
  - **Blast radius**: silent loss of every record of the retried batch (inserts, updates and deletes), with the Kafka offset committed past them.
  - **Recovery**: set `deduplication.policy=off` (the default); to repair, stop the connector, reset the consumer group of the affected partitions to before the first WARN'd offset (`kafka-consumer-groups --reset-offsets --to-offset <n>`), and restart -- the replay is idempotent under `_version`.
  - **RTO**: unbounded -- silent; unmeasured.
  - **Test**: `DeDuplicatorRetriedPutTest.retriedPutIsNotDroppedAsDuplicate()` (`@Disabled`, confirmed red on 2.11.0); `DeDuplicatorRetriedPutTest.retriedPutHandsOffEveryRecordWhenDeduplicationIsOff()` pins the default.
  - **DEFECT**: identities are pooled before the handoff succeeds, so a retried `put()` loses its records; they must be pooled only after `records.put` returns.

- **FM-10.05-3 The pool holds whole records on the heap**
  - **Trigger**: de-duplication on, wide rows (BLOB/TEXT, JSON) and many topics.
  - **Behaviour**: the pool maps each identity to the full `SinkRecord` (`checkIfRecordIsDuplicate`), up to `buffer.count` per topic; the bound is in records, not bytes.
  - **Detection**: JVM heap metrics (`jvm_memory_used_bytes`, GC time) rising; an `OutOfMemoryError` ends the task.
  - **Blast radius**: GC pressure or a task failure; no silent loss (a failed task is restarted from the committed offset).
  - **Recovery**: lower `buffer.count` or turn de-duplication off; restart the task.
  - **RTO**: task restart; unmeasured.
  - **Test**: `DeDuplicatorTest.testPoolIsBoundedByBufferCount()` (count bound); GAP: a test that the pool retains identities only, or a byte bound.

Summary: 3 failure modes, 1 DEFECT, 1 GAP.
