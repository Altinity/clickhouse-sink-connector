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
- **Consumer**: `ClickHouseBatchRunnable#run` — `FATAL` rethrows to stop the scheduled task; `RETRIABLE`/`UNKNOWN` leave the batch to be retried on the next scheduled run.

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
  - `241` MEMORY_LIMIT_EXCEEDED, `396` TOO_MANY_PARTITIONS
  - `27` CANNOT_PARSE_TEXT, `33` CANNOT_READ_ALL_DATA,
    `69` ARGUMENT_OUT_OF_BOUND, `349` INVALID_PARTITION_VALUE
- **`RETRIABLE`** (default — any code not in `FATAL_ERROR_CODES`) — re-tried on
  the next scheduled run; no backoff is applied between attempts today (the
  retry interval is simply the executor's fixed schedule — see spec 10.02):
  - `252` TOO_MANY_PARTS — ClickHouse insert backpressure (active parts above
    `parts_to_throw_insert`). It clears on its own as background merges catch
    up, so the SAME batch succeeds on retry. It is deliberately NOT fatal:
    halting the whole connector on a transient, self-healing condition (and
    requiring a manual restart) is a defect. Retrying is safe because offsets
    never advance past an unwritten batch, so there is no divergence risk.
  - Timeouts, socket errors, connection refused, EOF disconnections, and any
    other non-fatal code.
- **`UNKNOWN`** — no ClickHouse code could be extracted from the exception
  chain (`extractErrorCode` returned `-1`). Treated as retriable so an
  unexpected exception format does not stop the task on a possibly transient
  error.

> Note: `241` MEMORY_LIMIT_EXCEEDED is retained as FATAL in this change — a
> batch too large for the memory budget will deterministically fail again.
> Whether it should instead be retriable under transient concurrent memory
> pressure is left for a separate, explicit decision.

---

## 4. Invariants Preserved
- **Safe Containment**: Transient conditions (backpressure, timeouts, network) heal by retry; deterministic structural errors fail fast and stop the task.
- **Invariant I9 (Loud Failure)**: A deterministic error stops the task rather than being swallowed; a transient error is retried, never silently skipped.

---

## 5. Verification Criteria
- `ClickHouseErrorClassifierTest.testClassifyFatal()` — every code in `FATAL_ERROR_CODES` classifies FATAL (252 removed).
- `ClickHouseErrorClassifierTest.testTooManyPartsIsRetriableBackpressure()` — 252 classifies RETRIABLE and `isFatal(252)` is false.
- `ClickHouseErrorClassifierTest.testClassifyRetriable()` / `testClassifyUnknownAndNull()` / `testIsFatal()` / `testExtractErrorCode()`.
