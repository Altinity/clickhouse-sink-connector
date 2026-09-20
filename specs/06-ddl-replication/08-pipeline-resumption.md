# Spec 06.08: DDL Execution, Cache Invalidation & Pipeline Resumption

## 1. Executive Summary & Purpose
Specifies the final phase of DDL replication: executing the translated DDL on ClickHouse, invalidating metadata caches, committing the DDL offset, and unparking worker threads.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `performDDLOperation()`, `processEveryChangeRecord()` (DDL branch), `drainBeforeDDL()`
- **Failure type**: `DDLReplicationException` (same package)

---

## 3. Operational Specification

When DDL translation yields one or more ClickHouse SQL statements:
1. **Execution**:
   The statement is executed via `systemDbConnection.createStatement().execute(clickHouseQuery)`.
2. **Table Key Resolution**:
   Resolves affected table names from the AST or source event topic.
3. **Cache Invalidation**:
   - `CacheInvalidationManager.getInstance().invalidateTable(tableKey)` increments the table version counter.
   - Clears proven-absent column sets for the affected table.
   - If table resolution is ambiguous, calls `invalidateAll()` to bump `globalEpoch`.
4. **Offset Acknowledgment**:
   `committer.markProcessed(record)` acknowledges the DDL event offset.
5. **Worker Resumption**:
   Calls `executor.resume()`, setting `isPaused = false` and waking parked workers.
   The resume runs in a `finally` and is **guarded** by `executor != null`: in
   single-threaded mode there is no pool, so the guard prevents an NPE.
6. Post-DDL records now proceed against the newly altered table schema.

### 3.1 DDL Failure Is Terminal and Loud (not swallowed)

A DDL that cannot be applied must STOP the pipeline, never be skipped while the
row stream continues — continuing past an unapplied schema change writes every
later row against a ClickHouse schema that no longer matches MySQL, a silent,
count-clean divergence (violates the Prime Directive and Invariant I9).

1. **Wrapping**: The DDL branch of `processEveryChangeRecord()` wraps
   `drainBeforeDDL()` + `performDDLOperation()`. Any failure — a drain abort
   (`IllegalStateException`), or a failed DDL execution in
   `performDDLOperation()` (first attempt when `ddl.retry` is off, retry
   exhaustion when it is on; see §3.2) — is raised as `DDLReplicationException`.
2. **Non-swallowing**: `processEveryChangeRecord()` catches
   `DDLReplicationException` **ahead of** its generic `catch (Exception e)`
   catch-all (which exists only to keep one malformed DML record from killing
   the stream) and re-throws it. The exception therefore leaves
   `handleChangeEventBatch()` / the Debezium `handleBatch` consumer and halts
   the engine.
3. **Recoverable retry**: Because the engine halts before the DDL offset is
   committed, a restart re-delivers the DDL from the last committed position.
   This is the genuine retry the drain abort was always intended to enable; the
   prior behaviour logged the abort and let the row stream advance, which was
   neither a retry nor safe.
4. **Single-threaded mode**: `drainBeforeDDL()` returns immediately when
   `executor == null` (no worker pool; rows are persisted inline before the DDL
   record is handled), so the first DDL is applied rather than dropped by an NPE.

### 3.2 `ddl.retry` decides whether to RETRY, never whether a failure is loud

`performDDLOperation()` executes the translated statement(s) inside a bounded
retry loop (`MAX_RETRIES` attempts, 10 s apart). The `ddl.retry` property
(`SinkConnectorLightWeightConfig.DDL_RETRY`, default unset = `false`) controls
only whether further attempts are made after a failure. It never permits the
loop to be left normally after a failure.

1. **Error record first.** Every failed attempt is logged and written to the
   error table (`ErrorLogger.createErrorTable` + `ErrorLogger.logError`) BEFORE
   the retry decision, so the record exists whether or not the pipeline halts.
2. **`ddl.retry` unset / `false` (the default): terminal on the first attempt.**
   `DDLReplicationException` is thrown immediately, carrying the underlying
   exception as its cause. Leaving the retry loop normally is forbidden: it
   acknowledges the DDL offset and lets the row stream continue against a
   ClickHouse schema that no longer matches MySQL. This was observed in a
   production deployment — an `ALTER TABLE` rejected by ClickHouse was logged
   and skipped, the table stayed without the new column, and later rows were
   written count-clean against the stale schema.
3. **`ddl.retry=true`: bounded retries, then terminal.** After `MAX_RETRIES`
   failed attempts `DDLReplicationException` is thrown, carrying the LAST
   failure as its cause.
4. In both cases the exception leaves through §3.1 (re-thrown ahead of the
   catch-all, engine halts, offset not advanced, restart re-delivers the DDL).

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: Cache invalidation takes effect before any post-DDL rows begin query formulation.
- **Invariant I9 (Loud Failure)**: A DDL that cannot be applied halts the pipeline instead of being swallowed while offsets advance past it.

---

## 5. Verification Criteria
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — a DDL whose drain aborts throws `DDLReplicationException` out of `processEveryChangeRecord` rather than returning null.
- `DdlFailureLoudTest.drainIsNoOpWhenExecutorIsNull()` — single-threaded mode drains as a no-op instead of an NPE.
- `DdlFailureLoudTest.ddlExecutionFailureWithoutRetryIsLoud()` — `ddl.retry`
  unset, single-threaded (`executor == null`), a writer whose connection rejects
  the statement with a non-retryable ClickHouse error: `processEveryChangeRecord`
  throws `DDLReplicationException` whose cause is that `SQLException`. Fails on
  the pre-fix code, which left the retry loop normally and returned null.
- `DdlFailureLoudTest.ddlExecutionFailureAfterRetriesExhaustedIsLoud()` —
  `ddl.retry=true` with the retry budget exhausted: `DDLReplicationException`
  carrying the last failure as its cause.
- `DdlDrainDeadlockTest` — the drain still aborts a genuinely undrainable queue and stays quiescent+paused when it returns (spec 06.01).
- Mutation check: restoring `if (retryDDLProperty == false) { break; }` turns
  `ddlExecutionFailureWithoutRetryIsLoud` red.
