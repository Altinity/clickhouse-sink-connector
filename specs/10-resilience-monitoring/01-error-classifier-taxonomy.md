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
- **Consumer**: `ClickHouseBatchRunnable#run` — `FATAL` rethrows (with `currentBatch` retained) to stop the scheduled task, and the Debezium thread turns that into a loud engine stop (spec 03.01 §3.3); `RETRIABLE`/`UNKNOWN` keep the batch and retry it with exponential backoff (spec 10.02). Not every caught exception reaches `classify`: a poisoned `OffsetStorageWriter` (spec 09.02 §6 FM-09.02-1) and an `OffsetAcknowledgementException` from `DebeziumOffsetManagement.reportWritten` (spec 09.01 §6 FM-09.01-4, PR #1437 review Low item 1) are both detected and logged under their own names first, because neither carries a ClickHouse error code and `classify` would otherwise file them under `UNKNOWN` — indistinguishable from an actual ClickHouse outage even though no ClickHouse server was involved in either.

---

## 3. Operational Specification

### 3.1 Severity Rules
Classification is first by exception TYPE — a terminal exception type anywhere
in the cause chain is FATAL whatever the message says — and then by the
extracted ClickHouse error code against a fixed `FATAL_ERROR_CODES` set.
Anything not matched by either rule (including an unextractable code) is
treated as retriable/unknown so a transient condition is never turned into a
hard stop.

- **`FATAL`** (`TERMINAL_EXCEPTION_TYPES`, checked BEFORE code extraction) —
  raised by the connector itself, not by ClickHouse, so no `Code: NNN` is
  present; code extraction alone filed these under `UNKNOWN` and retried the
  same batch with backoff forever (the unit stayed outstanding, every DDL drain
  waited on it, nothing was ever acknowledged again). `classify` walks the
  exception and every `getCause()` down to the root and returns `FATAL` when
  any link is an instance of a listed type:
  - `DebeziumConverter.ValueOutOfRangeException` — the value the source holds
    cannot be stored under the current ClickHouse column type (under
    `clamp.out.of.range=false`, spec 07.03 §3.3). The value and the
    column type are unchanged on every attempt, so retrying can never succeed:
    it is FATAL exactly like an unknown table — the worker rethrows with the
    batch retained and the Debezium thread turns the dead worker into a loud
    engine stop (spec 03.01 §3.3, spec 10.04). Remedy: widen the column, or
    return to the default `clamp.out.of.range=true`.
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
  the next scheduled run; no backoff is applied between attempts today (the
  retry interval is simply the executor's fixed schedule — see spec 10.02):
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
- `ClickHouseErrorClassifierTest.valueOutOfRangeIsFatalRegardlessOfCode()` — `DebeziumConverter.ValueOutOfRangeException` classifies FATAL as the root exception and when wrapped two levels deep, with no error code extractable; a plain `RuntimeException` chain without a code stays UNKNOWN.
- `WorkerDeathIsLoudTest` — a FATAL-killed worker stops the engine loudly (spec 03.01).
- `ClickHouseErrorClassifierTest.testClassifyRetriable()` / `testClassifyUnknownAndNull()` / `testIsFatal()` / `testExtractErrorCode()`.

---

## 6. Failure Modes & Recovery
Classification chooses between two recoveries: FATAL ends in a loud process exit (code 3) within one Debezium batch and a supervisor restart; everything else enters an unbounded retry that self-heals within 30 s of the cause being removed. The second is the right recovery for conditions an operator fixes on the ClickHouse side, but it is the one that can outlast the RTO while the process reports itself healthy.

- **FM-10.01-1 A transient server condition**
  - **Trigger**: 252 TOO_MANY_PARTS, 241 MEMORY_LIMIT_EXCEEDED under concurrent load, 242 TABLE_IS_READ_ONLY (a Replicated table lost its Keeper session), 999 KEEPER_EXCEPTION, 243 NOT_ENOUGH_SPACE, 202 TOO_MANY_SIMULTANEOUS_QUERIES, 159/209/210 timeouts and network errors, or a connection error with no code.
  - **Behaviour**: RETRIABLE (code not in `FATAL_ERROR_CODES`) or UNKNOWN (no code); `ClickHouseBatchRunnable.run` keeps `currentBatch` and sleeps the backoff (spec 10.02); the same batch is retried until it succeeds.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(%s), Table(%s) *****************` (insert-stage failures), ERROR `ClickHouseBatchRunnable exception - Task(%s)`, WARN `Retriable ClickHouse error (Code: {}, Category: {}) -- the same batch will be retried in {} ms (consecutive failures: {}). Every table hashed to this worker waits behind it until it succeeds.` per attempt; `clickhouse_sink_topics_error_records_total` increments for insert-stage failures.
  - **Blast radius**: head-of-line blocking on the worker (spec 10.02 FM-10.02-2); no loss, no duplication beyond an idempotent re-insert.
  - **Recovery**: self-heals when the server condition clears (merges catch up, Keeper returns, disk is freed).
  - **RTO**: <= `batch.retry.backoff.max.ms` (30 s) + `buffer.flush.time.ms` after the condition clears, plus the backlog; unmeasured.
  - **Test**: `ClickHouseErrorClassifierFailureModesTest.selfHealingServerConditionsAreRetriable()`, `ClickHouseErrorClassifierTest.testTooManyPartsIsRetriableBackpressure()`, `ClickHouseErrorClassifierTest.testMemoryLimitExceededIsRetriable()`.

- **FM-10.01-2 A deterministic error outside the FATAL set is retried forever**
  - **Trigger**: 164 READONLY or 291 DATABASE_ACCESS_DENIED (a revoked grant, a `readonly` profile), a single batch larger than the memory budget (241 on every attempt), or one of the connector's own refusals, which carry no code: `MissingTargetColumnException` (spec 08.04), the `IllegalStateException` of a refused ReplacingMergeTree target or delete marker (spec 08.01 section 3.2), `StaleSchemaCacheException`.
  - **Behaviour**: RETRIABLE or UNKNOWN, retried with backoff indefinitely (spec 10.02 section 3.3); the engine never fails, so the retry budget and the terminal exit of spec 10.04 are never reached. The only process-level stop is the handoff cap (spec 01.05): after `sink.connector.handoff.wait.timeout.ms` (600 s) with 500,000 rows outstanding the engine stops and is retried 10 times before exit 3 -- and only if the source produces that many rows.
  - **Detection**: the per-attempt lines of FM-10.01-1 (the connector's refusals log their own message in the ERROR stack). No metric for failures before the INSERT; `/status` reports `Replica_Running=true` with a frozen `Seconds_Behind_Source` and the Prometheus lag gauge is frozen (spec 10.03 FM-10.03-2, FM-10.03-4); only `show_replica_status.seconds_behind_source` grows (MySQL sources).
  - **Blast radius**: the worker's tables stall, then the whole pipeline once queues fill; no loss.
  - **Recovery**: fix the cause on the ClickHouse side (restore the grant, clear `readonly`, add the column, fix the table) -- picked up on the next attempt without a restart; for a batch above the memory budget lower `buffer.max.records` / `buffer.max.bytes` and restart.
  - **RTO**: <= 30 s after the fix; time to detection is unbounded; unmeasured.
  - **Test**: `ClickHouseErrorClassifierFailureModesTest.privilegeErrorsOutsideTheFatalSetAreRetried()`, `ClickHouseErrorClassifierFailureModesTest.connectorRefusalsAreUnknownAndRetried()`.
  - **DEFECT**: a deterministic failure classified RETRIABLE/UNKNOWN has no bounded detection: no metric, no status change and no exit within any stated time.

- **FM-10.01-3 A FATAL error stops the process**
  - **Trigger**: a code in `FATAL_ERROR_CODES` (516, 497, 50, 53, 60, 81, 16, 396, 27, 33, 69, 349) or `DebeziumConverter.ValueOutOfRangeException` anywhere in the cause chain.
  - **Behaviour**: the worker rethrows with `currentBatch` kept (its unit stays outstanding); `DebeziumChangeEventCapture.failIfWorkerDied` raises on the next Debezium batch; `handleEngineCompletion` sees `isDeterministicFailure` (or `hasDeadWorker`) and exits through `terminalFailureHook` with code 3 without spending the retry budget.
  - **Detection**: ERROR `FATAL ClickHouse error (Code: {}) -- this batch will never succeed. Stopping this worker; ...`, ERROR `Sink worker %d of %d is dead: ...`, ERROR `Engine stopped with a FATAL (deterministic) failure; not retrying: ...`, FATAL `Replication is STOPPED: ...`, exit code 3; within one Debezium batch (heartbeats every 5 s by default).
  - **Blast radius**: every table stops; nothing is committed past the failing batch.
  - **Recovery**: fix the cause (grant, table, column type, `clamp.out.of.range`); systemd restarts the service every 30 s and gives up after 5 starts in 300 s (spec 10.04 FM-10.04-6), after which `systemctl reset-failed <unit>` and `systemctl start <unit>`.
  - **RTO**: detection <= 5 s; restart 30 s + engine start + re-apply of the outstanding batch after the fix; unmeasured.
  - **Test**: `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`, `TerminalFailureExitTest.fatalTerminalTypeIsNotRetried()`, `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`, `ClickHouseErrorClassifierTest.testClassifyFatal()`.

- **FM-10.01-4 A transient condition classified FATAL**
  - **Trigger**: a FATAL code raised by a passing condition: 33 CANNOT_READ_ALL_DATA when the insert stream is cut mid-send, 60/81 when a load balancer routes the INSERT to a replica that has not yet applied an `ON CLUSTER` CREATE.
  - **Behaviour**: as FM-10.01-3: exit 3 and a supervisor restart, which heals it (the restart re-reads the metadata and redelivers the batch).
  - **Detection**: as FM-10.01-3.
  - **Blast radius**: a full restart of the connector for a condition a retry would have cleared; no loss.
  - **Recovery**: automatic via the supervisor.
  - **RTO**: 30 s `RestartSec` + engine start + redelivery; unmeasured.
  - **Test**: `ClickHouseErrorClassifierTest.testIsFatal()` pins the set; GAP: a test separating a truncated-stream 33 from a malformed-data 33 (the code alone cannot).

Summary: 4 failure modes, 1 DEFECT, 1 GAP.
