# Spec 10.02: Exponential Backoff for Retried Batches

## 1. Executive Summary & Purpose
Specifies how a worker (`ClickHouseBatchRunnable`) paces retries of a batch that
failed to reach ClickHouse for a retriable reason. Without pacing, the worker's
scheduled tick (`buffer.flush.time.ms`, default 30 ms) re-ran the same failing
batch roughly 30 times per second — against a server that had just reported
backpressure (`252 TOO_MANY_PARTS`, `241 MEMORY_LIMIT_EXCEEDED`) or a network
fault — with the only pause being a 10 s sleep on the optional error-logging
path. This spec introduces a bounded exponential backoff on consecutive failures
of the SAME batch, with no attempt cap.

An earlier revision of this document described `task.retry.backoff.ms` and
`task.retry.max.attempts`. Those keys never existed in the code; they are
replaced by the keys below, which do.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
  — `run()` (retriable branch of the catch), `processBatch()` (the `result == false` branch).
- **Backoff state**: `sink-connector/.../executor/RetryBackoff.java`
  — `nextDelayMs(Object batch)`, `reset()`, `static delayFor(attempt, initialMs, maxMs)`.
- **Configuration** (`ClickHouseSinkConnectorConfig` / `ClickHouseSinkConnectorConfigVariables`):

| Key | Default | Meaning |
|---|---|---|
| `batch.retry.backoff.initial.ms` | 500 | delay after the first consecutive failure of a batch |
| `batch.retry.backoff.max.ms` | 30000 | cap on the delay |

Both are documented in `doc/configuration.md`.

---

## 3. Operational Specification

### 3.1 Delay sequence
For the $n$-th consecutive failure ($n \ge 1$) of the same batch identity:
$$\text{delay}(n) = \min(\text{initial} \times 2^{\,n-1},\ \text{max})$$
With the defaults: 500, 1000, 2000, 4000, 8000, 16000, 30000, 30000, … ms.
The doubling saturates at `max`; there is no overflow for any $n$.

### 3.2 What counts as a consecutive failure
`RetryBackoff` tracks the identity of the batch that last failed and a counter.
- Every failed attempt of that same batch increments the counter and the worker
  sleeps `delay(counter)` before its next attempt.
- A failure of a DIFFERENT batch (identity `!=`) restarts the counter at 1.
- A successful write calls `reset()`.

Two paths feed it:
1. `run()` catch block, when the classifier returns `RETRIABLE` or `UNKNOWN`
   (spec 10.01): the worker sleeps `delay(n)` inside the tick; the next tick
   re-polls the same `currentBatch`.
2. `processBatch()` when `processRecordsByTopic` returns `false` without
   throwing (table metadata not yet retrievable): the worker sleeps `delay(n)`
   before the run loop re-attempts the same `currentBatch`.
The fixed `buffer.flush.time.ms` sleep after every processed batch and the 10 s
`ERROR_SLEEP_TIME_MS` on the `error.logging.enable` path are unchanged and add
to the delay.

### 3.3 No attempt cap — by design
Retrying the same batch forever is SAFE: offsets never advance past an
unwritten batch (spec 09.01), so no divergence can result, and a batch that
eventually succeeds resumes the stream exactly where it stopped. Escalating a
retriable error to fatal after N attempts would trade a visible stall for a
stopped connector with the same data outcome. The stall is visible: every retry
logs `Retriable ClickHouse error (Code: …)` with the delay, and the pipeline's
quiescence predicate stays false (no control-record commit, no offset progress —
`replica_source_info` lag is the monitoring signal, spec 10.03).

A FATAL classification (spec 10.01) does not enter this backoff: the worker
rethrows, keeps `currentBatch` (so its unit stays outstanding), and the Debezium
thread stops the engine loudly on its next batch (spec 03.01 §3.3).

### 3.4 Head-of-line blocking consequence (per-thread FIFO queues)
In routing mode (spec 03.03) each worker drains ONLY its own FIFO queue and
keeps a failing batch at the head of its work until it succeeds. Therefore:
- **one table in `TOO_MANY_PARTS` blocks every table hashed to the same
  worker** for as long as the condition lasts;
- the worker's queue fills at the producer's rate; once it reaches
  `sink.connector.max.queue.size` the Debezium thread blocks in `put`, and ALL
  tables stop advancing (spec 01.05 §3.4);
- offsets for every unit handed off after the failing one stay outstanding
  (FIFO), even for tables on healthy workers.
This is the deliberate price of per-table ordering (I1): skipping or reordering
the failing batch would either lose it or apply a later state before an earlier
one. Operators relieve it by fixing the ClickHouse-side condition (merge
throughput, `parts_to_throw_insert`, memory limits), not by connector settings.

---

## 4. Invariants Preserved
- **Invariant I1 / I8**: a failing batch is retried in place; nothing behind it
  is written out of order or acknowledged past it.
- **Invariant I9 (Loud Failure)**: every retry is logged with its delay; the
  stall is observable, never silent.
- **ClickHouse headroom**: a recovering server is not hammered 30 times per
  second by every worker.

---

## 5. Verification Criteria
- `RetryBackoffTest.delaySequenceDoublesToCap` — 500, 1000, 2000, 4000, 8000,
  16000, 30000, 30000 for consecutive failures of one batch.
- `RetryBackoffTest.differentBatchRestartsTheSequence` — a new batch identity
  starts again at the initial delay.
- `RetryBackoffTest.resetClearsTheSequence`.
- `RetryBackoffTest.delayForNeverOverflows` — a very large attempt count still
  yields `max`.

---

## 6. Failure Modes & Recovery
The backoff bounds how fast a failing batch is retried and how soon the retry notices a fixed cause (at most `batch.retry.backoff.max.ms`, 30 s); it deliberately does not bound how long the retry lasts. Recovery after the cause is removed is therefore within the RTO, but detection of a retry that never ends is left to the log, and the stall spreads to every table behind the failing batch.

- **FM-10.02-1 A batch is retried forever and nothing outside the log says so**
  - **Trigger**: any failure classified RETRIABLE/UNKNOWN that does not clear on its own (spec 10.01 FM-10.01-2), or `processRecordsByTopic` returning false on every attempt (table metadata never retrievable, spec 08.01 FM-08.01-5).
  - **Behaviour**: `RetryBackoff.nextDelayMs(currentBatch)` grows 500 ms, 1 s, ... to 30 s and stays there; there is no attempt cap. The engine keeps running, so `DebeziumChangeEventCapture.handleEngineCompletion` is never called, the retry budget is never spent and the terminal exit never happens -- unless the handoff cap (spec 01.05) stops the engine after 600 s with 500,000 rows outstanding, after which ten engine retries (10 s apart) precede exit 3.
  - **Detection**: WARN `Retriable ClickHouse error (Code: {}, Category: {}) -- the same batch will be retried in {} ms (consecutive failures: {}). ...` or WARN `Batch not written to ClickHouse; retrying the same batch in {} ms (consecutive failures: {})`, at least every 30 s + `buffer.flush.time.ms` per worker. The consecutive-failure count is in the log only; there is no metric for it or for the age of the oldest unacknowledged unit. `show_replica_status.seconds_behind_source` grows (spec 10.03); the Prometheus lag gauge and `/status` do not (spec 10.03 FM-10.03-2, FM-10.03-4).
  - **Blast radius**: the worker's tables, then (once the worker's queue fills) every table; no loss.
  - **Recovery**: remove the cause (spec 10.01 FM-10.01-2); the next attempt succeeds and the backlog drains in order.
  - **RTO**: <= 30 s + `buffer.flush.time.ms` after the cause is removed, plus the backlog; detection unbounded; unmeasured.
  - **Test**: `RetryBackoffTest.delaySequenceDoublesToCap()`, `RetryBackoffTest.differentBatchRestartsTheSequence()`, `HandoffHardCapBackpressureTest.theWaitIsBoundedAndLoud()`; GAP: a test asserting a metric (consecutive failures, or oldest-outstanding-unit age) rises while a batch is being retried.
  - **DEFECT**: a retry that never ends exposes no metric and no status, so a monitoring system that does not read the log cannot see a stall of any length.

- **FM-10.02-2 One failing table blocks its worker and every later offset**
  - **Trigger**: one table in TOO_MANY_PARTS (or any FM-10.02-1 cause) in routing mode.
  - **Behaviour**: the worker keeps the failing batch at its head (section 3.4); tables hashed to the same worker wait; units handed off after the failing one stay outstanding in the FIFO even when written by healthy workers; when the worker's queue reaches `sink.connector.max.queue.size` the Debezium thread blocks, and the handoff cap pauses the reader.
  - **Detection**: WARN `Routed queue {} is at 90% capacity! ...` / `Routed queue {} is full! ...`, the handoff-cap pause WARN (spec 01.05), and the FM-10.02-1 lines.
  - **Blast radius**: all replication stops advancing its committed offset; healthy workers' written rows are redelivered after a restart (idempotent under `_version`).
  - **Recovery**: fix the ClickHouse-side condition for the one table (merge throughput, `parts_to_throw_insert`, memory limits); connector settings do not relieve it.
  - **RTO**: <= 30 s after the fix, plus draining the backlog (bounded by the handoff cap, 500,000 rows by default); unmeasured.
  - **Test**: `OffsetNoLossParameterIndependenceTest.commitFrontierIsIndependentOfCompletionOrder()` (later units are parked, not acknowledged); GAP: an end-to-end test measuring drain time after a TOO_MANY_PARTS episode on one table.

- **FM-10.02-3 A retried INSERT had already been applied**
  - **Trigger**: the client times out or loses the connection after ClickHouse applied the INSERT (209 SOCKET_TIMEOUT, a cut connection).
  - **Behaviour**: the failure is retriable; the same batch is inserted again with the same `_version` values.
  - **Detection**: the FM-10.02-1 WARN once; nothing else is needed.
  - **Blast radius**: duplicate rows with identical sorting key and version, collapsed by ReplacingMergeTree merges and `FINAL` (a Replicated table's insert deduplication may also drop the identical block); `count()` without `FINAL` is inflated until the merge.
  - **Recovery**: none needed.
  - **RTO**: one retry delay.
  - **Test**: `OffsetNoLossParameterIndependenceTest.retriedBatchIsNeverAcknowledgedUntilWritten()`, `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()`.

- **FM-10.02-4 Extra fixed sleeps lengthen recovery**
  - **Trigger**: `error.logging.enable=true` while a batch is failing.
  - **Behaviour**: each failed attempt also writes the error table and sleeps `ERROR_SLEEP_TIME_MS` (10 s) in `logErrorToClickHouse`, on top of the backoff and `buffer.flush.time.ms`; if the error table itself cannot be written, that failure is logged, the 10 s sleep is skipped and the retry continues.
  - **Detection**: ERROR `******* ERROR **** Failed to log error to ClickHouse *********` when the error table is unreachable.
  - **Blast radius**: up to 10 s more per attempt before a fixed cause is noticed.
  - **Recovery**: none needed.
  - **RTO**: worst case 30 s + 10 s + `buffer.flush.time.ms` after the fix; unmeasured.
  - **Test**: `RetryBackoffTest.delayForNeverOverflows()` (the backoff half); GAP: a test of the total delay with error logging enabled.

Summary: 4 failure modes, 1 DEFECT, 3 GAP.
