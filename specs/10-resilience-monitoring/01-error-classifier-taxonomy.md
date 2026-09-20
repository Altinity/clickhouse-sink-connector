# Spec 10.01: Error Classification Taxonomy & Code Mapping

## 1. Executive Summary & Purpose
Specifies the error triage taxonomy in `ClickHouseErrorClassifier` that maps
ClickHouse error codes (extracted from an exception message and its cause
chain) into actionable severity tiers, so the batch runnable can decide whether
to stop the task or retry the batch.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java`
- **Enum**: `ErrorCategory { RETRIABLE, FATAL, UNKNOWN }`
- **Code extraction**: `extractErrorCode(Exception)` matches `Code:\s*(\d+)` against the exception and every `getCause()` in the chain; `-1` when none is found.
- **Consumer**: `ClickHouseBatchRunnable#run` — `FATAL` rethrows (with `currentBatch` retained) to stop the scheduled task, and the Debezium thread turns that into a loud engine stop (spec 03.01 §3.3); `RETRIABLE`/`UNKNOWN` keep the batch and retry it with exponential backoff (spec 10.02).

---

## 3. Operational Specification

### 3.1 Severity Rules
Classification is by the extracted ClickHouse error code against a fixed
`FATAL_ERROR_CODES` set. Anything not in that set (including an unextractable
code) is treated as retriable/unknown so a transient condition is never turned
into a hard stop.

- **`FATAL`** (`FATAL_ERROR_CODES`) — deterministic; the same batch can never
  succeed without external intervention (config, schema, or privilege change),
  so the task is stopped:
  - `516` AUTHENTICATION_FAILED, `497` ACCESS_DENIED
  - `50` NUMBER_OF_COLUMNS_DOESNT_MATCH, `53` TYPE_MISMATCH,
    `60` UNKNOWN_TABLE, `81` UNKNOWN_DATABASE, `16` NO_SUCH_COLUMN_IN_TABLE
  - `396` TOO_MANY_PARTITIONS
  - `27` CANNOT_PARSE_TEXT, `33` CANNOT_READ_ALL_DATA,
    `69` ARGUMENT_OUT_OF_BOUND, `349` INVALID_PARTITION_VALUE
- **`RETRIABLE`** (default — any code not in `FATAL_ERROR_CODES`) — re-tried on
  the next scheduled run:
  - `252` TOO_MANY_PARTS — ClickHouse insert backpressure (active parts above
    `parts_to_throw_insert`). It clears on its own as background merges catch
    up, so the SAME batch succeeds on retry. It is deliberately NOT fatal:
    halting the whole connector on a transient, self-healing condition (and
    requiring a manual restart) is a defect. Retrying is safe because offsets
    never advance past an unwritten batch, so there is no divergence risk.
  - `241` MEMORY_LIMIT_EXCEEDED — usually transient: the limit that trips is
    the per-query or per-user/server memory budget under CONCURRENT load
    (merges, other inserts, other queries), and the same batch succeeds once
    that pressure passes. Treating it as fatal stopped the whole connector on a
    self-healing condition. The genuinely deterministic case (a single batch
    larger than the memory budget) shows up as an unbounded retry of one batch
    with backoff — visible in the logs and in `replica_source_info` lag — and
    is remedied by lowering `buffer.max.records`; it is never silent.
  - Timeouts, socket errors, connection refused, EOF disconnections, and any
    other non-fatal code.
- **`UNKNOWN`** — no ClickHouse code could be extracted from the exception
  chain (`extractErrorCode` returned `-1`). Treated as retriable so an
  unexpected exception format does not stop the task on a possibly transient
  error.

### 3.2 What a FATAL classification does to the pipeline
A FATAL rethrow ends the worker's periodic task. The worker keeps
`currentBatch`, so the batch's handoff unit stays outstanding: no control-record
offset can pass it and every younger unit stays parked in the FIFO. That is the
correct SAFE state — nothing is committed past unwritten rows — but it must not
be a silent one: `DebeziumChangeEventCapture.failIfWorkerDied()` surfaces the
dead worker's cause on the next Debezium batch and stops the engine
(spec 03.01 §3.3).

### 3.3 Written-once
Classification only ever applies to a batch that FAILED to write. A batch that
was written and is merely waiting for its offset turn is never re-executed and
never re-enters this path (spec 09.01 §3.2).

---

## 4. Invariants Preserved
- **Safe Containment**: Transient conditions (backpressure, timeouts, network) heal by retry; deterministic structural errors fail fast and stop the task.
- **Invariant I9 (Loud Failure)**: A deterministic error stops the task rather than being swallowed; a transient error is retried, never silently skipped.

---

## 5. Verification Criteria
- `ClickHouseErrorClassifierTest.testClassifyFatal()` — every code in `FATAL_ERROR_CODES` classifies FATAL (252 and 241 removed).
- `ClickHouseErrorClassifierTest.testTooManyPartsIsRetriableBackpressure()` — 252 classifies RETRIABLE and `isFatal(252)` is false.
- `ClickHouseErrorClassifierTest.testMemoryLimitExceededIsRetriable()` — 241 classifies RETRIABLE and `isFatal(241)` is false.
- `WorkerDeathIsLoudTest` — a FATAL-killed worker stops the engine loudly (spec 03.01).
- `ClickHouseErrorClassifierTest.testClassifyRetriable()` / `testClassifyUnknownAndNull()` / `testIsFatal()` / `testExtractErrorCode()`.
