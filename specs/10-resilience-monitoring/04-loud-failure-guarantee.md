# Spec 10.04: Loud Failure Guarantee & Anti-Swallowing Protocol

## 1. Executive Summary & Purpose
Specifies the non-negotiable policy that unrecoverable replication errors must terminate execution loudly and immediately, strictly prohibiting error swallowing.

---

## 2. Codebase Mapping on 2.11.0
- **System Constitution**: Invariant I9
- **Error Classifier**: `ClickHouseErrorClassifier`
- **DDL failure type**: `DDLReplicationException` (`...embedded/cdc/DDLReplicationException.java`), re-thrown ahead of the catch-all in `DebeziumChangeEventCapture#processEveryChangeRecord`
- **Row failure type**: `RecordReplicationException` (`...embedded/cdc/RecordReplicationException.java`), re-thrown ahead of the same catch-all
- **Engine retry budget and terminal failure**: `DebeziumChangeEventCapture#handleEngineCompletion`, `#markEngineStarted`, `#hasDeadWorker` (rule 6: a dead sink worker is terminal without a retry), `#terminalFailureHook`, `#TERMINAL_FAILURE_EXIT_CODE`; property `exit.on.terminal.failure` (`SinkConnectorLightWeightConfig.EXIT_ON_TERMINAL_FAILURE`); the progress signal that refills the budget: `DebeziumOffsetManagement#acknowledgements` (`sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java`)

---

## 3. Operational Specification

### 3.1 Anti-Swallowing Rule
Under no circumstances may a worker catch a `SQLException` or `DataConversionException` and simply log a warning while allowing the batch to proceed.
```java
// STRICTLY FORBIDDEN ANTI-PATTERN:
try {
    ps.executeBatch();
} catch (SQLException e) {
    logger.warn("Batch failed, skipping records: " + e.getMessage()); // VIOLATION!
}
```

### 3.2 Mandatory Crash Contract
When an unrecoverable failure occurs:
1. Roll back the local JDBC transaction.
2. Log full stack trace, binlog coordinates, and table name at `FATAL` level.
3. Throw an unchecked runtime exception to terminate the JVM process or task thread.
4. Leaves `replica_source_info` intact at the last known good commit.

### 3.3 Record-path anti-swallowing: `DDLReplicationException` and `RecordReplicationException`

`DebeziumChangeEventCapture#processEveryChangeRecord` ends in a catch-all
(`catch (Exception e) { log.error("Exception processing record", e); }`). It
used to be described as keeping "one malformed DML record from killing the
stream". That description was the defect: a record absorbed there returns
`null`, and the batch loop treated every null as a control record whose offset
it committed once the pipeline was quiescent — so the "malformed" row was not
merely skipped, its offset was durably acknowledged and a restart never
redelivered it (spec 01.06 §3.1). There is no record for which continuing past
it is correct:

- **DDL** — a schema change that cannot be applied (a `drainBeforeDDL()` abort,
  or retry exhaustion in `performDDLOperation()`) is raised as
  `DDLReplicationException`. Absorbing it loses the schema change, advances the
  offset past it, and every later row diverges silently from MySQL.
- **Row** — a record whose value carries an `op` field but for which the parser
  returned `null` or threw is raised as `RecordReplicationException`. Absorbing
  it loses that row with the offset advanced past it.

Both types are re-thrown **ahead of** the generic catch, so they leave the
Debezium `handleBatch` consumer and halt the engine. The offset is not committed
past the failed record, so a restart redelivers it — the same
loud-but-recoverable contract as §3.2. This mirrors `ClickHouseBatchRunnable`'s
FATAL rethrow, which stops the scheduled executor rather than retrying a doomed
batch forever. The catch-all that remains is reached only by exceptions raised
BEFORE classification (a record whose schema cannot even be read); for a
non-control record that path still ends in `RecordReplicationException`, thrown
by `handleChangeEventBatch` when the null reaches it (spec 01.06 §3.1 rule 3).

Records that carry no row by contract — heartbeats, transaction markers,
Debezium tombstones — are classified as control records before this rule
applies and are never raised (spec 01.06 §3.1 table).

### 3.4 Worker death must reach the engine (no silent stall)
A `RuntimeException` thrown from a `scheduleAtFixedRate` task only cancels
that task's future; the executor logs nothing and calls nobody. A FATAL
rethrow in `ClickHouseBatchRunnable#run` therefore used to leave the process
alive with one worker dead, its queue filling, and offsets frozen — a stall
with no error after the first one. `DebeziumChangeEventCapture` retains the
workers' `ScheduledFuture`s and checks them at the top of every
`handleChangeEventBatch` (`failIfWorkerDied`); a done future is re-raised as
a `RuntimeException` carrying the worker's cause, which stops the engine
through its completion callback (spec 03.01 §3.3). The DDL drain runs the same
check on every poll (spec 06.01 §3.2): a dead worker is the ONE condition that
makes a pending backlog undrainable, and the only one that aborts the drain.

### 3.5 Terminal failures terminate (retry budget, then exit)
The engine's `CompletionCallback` (`handleEngineCompletion`) recreates a failed
engine up to `errors.max.retries` (`MAX_RETRIES`, default 10) times in a row,
`SLEEP_TIME` apart. Two things used to be wrong once that budget was spent, and
one before it:
1. **Nothing happened.** After the last retry the callback returned; the JVM
   stayed up with replication stopped, the REST API answering and the metrics
   port open, and no process-level signal for a supervisor (systemd,
   Kubernetes) or a liveness probe to act on. Replication was dead and quiet.
2. **The budget never refilled.** `numRetries` was never reset, so a connector
   that had recovered from ten transient failures over its lifetime died on the
   eleventh, however long ago the first ten were.
3. **A transient backlog was terminal.** The DDL drain aborted after a fixed
   60 s; a worker retrying `TOO_MANY_PARTS` for longer than that produced
   `DDLReplicationException` → engine restart → the same drain → … → the
   budget spent → stop, for a condition that would have cleared (spec 06.01).
And two that the first three created:
4. **A deterministic failure was retried forever.** The budget exists for
   transient failures, but every failure drew on it. A FATAL failure (spec
   10.01 §3.1 — an unrepresentable value, an unknown table or column, a type
   mismatch, a denied privilege) is identical on every attempt, so the
   recreated engine redelivered the same batch to the same outcome; and
   because the engine did start (rule 2 refilled the budget on
   `connectorStarted`), `numRetries` never passed 1. The log read
   `Restarting the engine - retry 1 of 10` every `SLEEP_TIME` (measured:
   every 12–25 s for hours), each turn re-reading the whole schema history
   from the target, re-issuing the startup catalog queries, and re-logging
   every skipped row event with its full row image — an unbounded restart
   loop that never reached the terminal exit and never signalled a
   supervisor.
5. **A clean start counted as a recovery — for every failure shape, not only
   the classified one.** Rule 4 stops the FATAL-classified case at once, but
   the refill on `connectorStarted` was itself the wrong signal: a failure the
   classifier cannot recognise (an exception type it does not list, a cause
   chain carrying no ClickHouse error code, an `Error`) that recurs on every
   start — the engine comes up cleanly, streams to the same record, stops —
   still read `retry 1 of 10` on every failure, the budget was never spent
   and the terminal path never ran. Observed on two deployments after
   upgrading onto the loud-clamp default: 28 restarts in six minutes, all
   "retry 1 of 10". A start is not a recovery; committing an offset is.
6. **A dead sink worker was retried against the same pool.** The retry
   recreates the *engine* on the same `DebeziumChangeEventCapture` instance;
   it does not rebuild the worker pool (spec 09.01 §3.8 item 4 relies on that
   pool still being alive). A worker whose scheduled task has terminated
   (spec 03.01 §3.3 — a FATAL rethrow, or the poisoned `OffsetStorageWriter`
   that `isOffsetWriterPoisoned` stops on, spec 10.06) therefore stays dead across every
   retry, `failIfWorkerDied` stops each recreated engine on its first batch,
   and nothing is written or acknowledged in between, so rule 5 cannot refill
   the budget either. Every retry was a full engine start for nothing: the
   schema history re-read from the target, a fresh binlog dump from the
   source (the source logged one `Start binlog_dump` per attempt, each
   aborted seconds later), and every skipped row event re-logged with its
   full row image. Observed: a source connection dropped mid-transaction,
   the engine stopped, one worker died on `OffsetStorageWriter is already
   flushing` 1.5 s later, and the engine was then restarted ten times in
   160 s — ten identical `Sink worker 1 of 10 is dead` failures 13–19 s
   apart, ~0.5 GB of replay log (1.76 M lines in four rotated files plus the
   fifth) — before the terminal exit that should have
   happened at the first one. A dead worker is deterministic for the life of
   the process; only a process restart gives a fresh pool.

And one that the first three created:
4. **A deterministic failure was retried forever.** The budget exists for
   transient failures, but every failure drew on it. A FATAL failure (spec
   10.01 §3.1 — an unrepresentable value, an unknown table or column, a type
   mismatch, a denied privilege) is identical on every attempt, so the
   recreated engine redelivered the same batch to the same outcome; and
   because the engine did start (rule 2 refilled the budget on
   `connectorStarted`), `numRetries` never passed 1. The log read
   `Restarting the engine - retry 1 of 10` every `SLEEP_TIME` (measured:
   every 12–25 s for hours), each turn re-reading the whole schema history
   from the target, re-issuing the startup catalog queries, and re-logging
   every skipped row event with its full row image — an unbounded restart
   loop that never reached the terminal exit and never signalled a
   supervisor.

Contract:
- A failure that is FATAL by `ClickHouseErrorClassifier.classify` (spec 10.01
  §3.1: a `TERMINAL_EXCEPTION_TYPES` instance anywhere in the cause chain, or a
  `FATAL_ERROR_CODES` code) does **not** draw on the budget: it is TERMINAL at
  once (`isDeterministicFailure`), exactly as a spent budget is. Only
  `Exception`s are classified; an `Error` is not a replication verdict and
  keeps the retry path. Retriable and unclassifiable failures draw on the
  budget as before.
- A failure while **any sink worker is dead** does **not** draw on the budget
  either: `handleEngineCompletion` asks `hasDeadWorker()` — any future in
  `workerFutures` with `isDone()`, the same predicate `failIfWorkerDied`
  throws on — after the FATAL check and before touching the budget, and is
  TERMINAL at once when it is true, whatever the engine's own failure was (the
  worker's death, or a source failure that happened to coincide with one). A
  retry keeps the pool, so the recreated engine would stop on its first batch
  with nothing written or acknowledged; the supervisor's restart is what gives
  a fresh pool and resumes from the last committed offset. An empty
  `workerFutures` (single-threaded mode, no pool) never counts as dead.
- The budget refills on **progress**, never on a start.
  `DebeziumOffsetManagement.acknowledgements()` counts every offset
  acknowledged to Debezium (`markBatchFinished()` returned) since the JVM
  started — written units and control records alike; it is monotone and is
  not touched by `reset()`. `handleEngineCompletion` reads it on every
  failure: when the reading differs from the one taken at the previous
  failure, the engine committed an offset in between — it had recovered — and
  `numRetries` restarts from 0 (an INFO line says so, with the number of
  offsets and the count it stood at). When the reading is unchanged, the
  failure is one more in the same row, however cleanly the engine came up.
  Heartbeats are on by default (`DEFAULT_HEARTBEAT_INTERVAL_MS`, spec 01.06),
  so an idle source still proves progress through its control-record commits.
- `markEngineStarted()` (the `connectorStarted` callback) only marks
  replication running for `/status`; it no longer touches `numRetries`.
- A failure while `numRetries < MAX_RETRIES` increments the counter, sleeps
  `SLEEP_TIME`, and recreates the engine (exactly `MAX_RETRIES` retries; the
  previous `<=` test allowed one more than configured).
- When the budget is spent the failure is TERMINAL: replication is marked not
  running (`/status` reports `Replica_Running=false`), a **FATAL** line names
  the count, the last failure and the fact that offsets were not committed past
  the failing point, and — unless `exit.on.terminal.failure=false` — the
  process exits through `terminalFailureHook` (`System::exit` in production,
  replaced in tests) with `TERMINAL_FAILURE_EXIT_CODE` (3), so a supervisor
  restarts or alerts on it. With the exit disabled the process stays up as a
  visible liveness failure; it never idles silently.
- Before anything else — before the sleep, before the retry decision, on a
  clean completion too — the callback retires every unit the completed engine
  handed off (`retireHandoffsOfStoppedEngine`, spec 09.01 §3.8 item 5). The
  engine's offset store closed with it, and the retry keeps the worker pool
  that still holds its batches: left outstanding, the first one written was
  acknowledged through the stopped engine's committer, which threw from the
  closed store and left the `OffsetStorageWriter` "already flushing", and the
  worker died on its next acknowledgement (spec 03.01 §3.3) — the recreated
  engine then failed on the dead worker until the budget was spent.
- The DDL drain waits while the workers are alive (spec 06.01 §3.2); only a
  dead worker or an interrupt aborts it.

Redelivery: a terminal failure commits nothing; the next start (by the
supervisor or an operator) resumes from the last committed offset (spec 09.03).

### 3.6 A source configuration that guarantees divergence is refused at start
Some divergences cannot be made loud per record because every record is
affected and nothing in the record says so. `binlog_row_image` other than
`FULL` is the case the connector checks: with `MINIMAL` or `NOBLOB` every
UPDATE arrives with its untouched (or BLOB/TEXT) columns absent, and the
full-row replace writes them as NULL — silent, count-clean, on every update.
`BinlogRowImagePreflight.check(props)` therefore refuses to start
(`IllegalStateException` out of `setup()`, with an ERROR banner naming the
value and the fix) when the source reports a readable value other than `FULL`;
an unreadable value is a WARN, and `binlog.row.image.check.skip=true` is a WARN
banner on every start (spec 01.01 §3.2).

### 3.7 Loss-by-design options are announced, never defaulted on
Two options make the replica diverge from the source on purpose. They are
honoured — an operator may want them — but neither may be a default, and the
connector says so when they are set:

- **`ignore_delete=true`** (`ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE`,
  read by `PreparedStatementFieldMapper`): the delete marker is never bound, so
  rows deleted at the source stay visible in ClickHouse forever. The config
  constructor (`ClickHouseSinkConnectorConfig#warnIfIgnoreDelete`, predicate
  `isIgnoreDeleteEnabled`) logs one WARN per JVM naming the key and the
  divergence. `doc/configuration.md` documents it as loss by design. The
  systemd deployment role emits the key under its real name (`ignore_delete`;
  it used to emit `ignore.delete`, which nothing reads).
- **`schema.history.internal.skip.unparseable.ddl=true`**: Debezium drops any
  DDL it cannot parse from the schema history, so later rows of that table are
  decoded against a stale schema. Debezium's default is `false`; the systemd
  deployment role (`deploy/ansible-systemd/defaults/main.yml`,
  `sink_connector_skip_unparseable_ddl_default`) used to default it to `true`
  fleet-wide. It now defaults to `false`; enabling it is a per-connector,
  deliberate override.

The same deployment template also emitted keys the connector never reads
(`clickhouse.table.engine`, `clickhouse.table.sign.column`,
`clickhouse.table.version.column`, the deprecated `clickhouse.server.database`)
and Debezium's `max.queue.size` where it meant the sink's
`sink.connector.max.queue.size`; a configuration that looks set but is not
read is a silent divergence of its own, so those keys were removed or renamed.

### 3.8 A configuration contradiction found while building a writer halts
`ColumnTypeOverrideMismatchException` is raised by `ClickHouseAutoCreateTable`
when a configured column type override contradicts the existing table, and is
documented there as "must halt the connector". `DbWriter`'s constructor and
`DbWriter#autoCreateTable` used to catch `Exception` around it and log, so the
writer came up anyway and wrote rows against a type the operator had declared
wrong. Both sites now re-throw `ColumnTypeOverrideMismatchException` ahead of
their generic catch (spec 08.05 §3.3).

---

### 3.5 The grouping stage never drops a record
A record that reaches `GroupInsertQueryWithBatchRecords` is either grouped
into a statement or fails the batch with an exception that names the record.
The grouper used to answer `false` for a record it could not build a
statement for, and the caller kept only the last record's answer — a
per-record skip with the offset still advancing, the same silence as §3.1
with a row-shaped victim (in practice the missing-image case died earlier
with an opaque `NullPointerException`, which told the operator nothing).
Spec 04.01 §3.3 states the exceptions; an empty query map is likewise refused
by the executor instead of being retried forever.

The Kafka Connect entry point follows the same rule one stage earlier.
`ClickHouseSinkTask.put` skips exactly one kind of record: a Kafka tombstone
(`record.value() == null`), which Debezium emits after a DELETE for log
compaction and which carries no change event. Any other record that
`ClickHouseConverter.convert` cannot turn into a `ClickHouseStruct` (a value
with no Debezium envelope `op` / row image, a non-STRUCT value schema) fails
the task with `org.apache.kafka.connect.errors.DataException` naming the
topic, partition and offset. Dropping such a record at DEBUG — the previous
behaviour — lost the change while the committed offset advanced past it.

Kafka-mode liveness (a dead runnable behind `put` / `preCommit`) is §3.4's
counterpart for the sink task: spec 03.01 §3.4.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure / Zero Silence)**: Guarantees that data divergence is never masked by silent error suppression.

---

## 5. Verification Criteria
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — §3.3: a DDL failure escapes the catch-all as `DDLReplicationException`.
- `UnparseableRowRecordIsTerminalTest.insertWhoseParserThrowsIsTerminal()`, `UnparseableRowRecordIsTerminalTest.updateWhoseParserReturnsNullIsTerminal()` — §3.3: a row record whose parse throws / returns null escapes the catch-all as `RecordReplicationException`; nothing is acknowledged.
- `NullParsedRowRecordIsTerminalTest.unconvertibleRowRecordIsTerminal()` — §3.3 at the `processEveryChangeRecord` seam (inverted from the former NullParsedRecordSkipTest, which asserted the skip).
- `Replication.Snapshot.unparsed_row_halts`, `Replication.Snapshot.unparsed_row_never_committed` — the row half of §3.3 in the offset-commit model.
- `ClickHouseErrorClassifierTest.testIsFatal()`, `ClickHouseErrorClassifierTest.testClassifyFatal()` — the FATAL set that triggers the rethrow.
- `ClickHouseBatchWriterMissingTableTest` — a missing target table fails the batch loudly instead of being skipped.
- `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`
- `GroupInsertQueryWithBatchRecordsTest.deleteWithoutBeforeImageFailsLoudly()`, `PreparedStatementExecutorNoSilentDropTest.emptyQueryMapIsRefusedNotRetried()` — §3.5.
- `ClickHouseSinkTaskTest.tombstoneIsDroppedQuietly()`, `ClickHouseSinkTaskTest.unconvertibleRecordIsLoud()` — §3.5 Kafka entry point: a null-value tombstone is skipped, a non-converting non-null value fails the task.
- `ClickHouseSinkTaskTest.deadRunnableFailsPut()`, `ClickHouseSinkTaskTest.deadRunnableFailsPreCommit()` — §3.4 in Kafka Connect mode.
- `TerminalFailureExitTest.exitHookFiresAfterMaxRetries()` — §3.5: `MAX_RETRIES` restarts, then the exit hook fires exactly once with `TERMINAL_FAILURE_EXIT_CODE` and replication is reported stopped (pre-fix: nothing fired, one extra restart).
- `TerminalFailureExitTest.progressResetsTheBudget()` — §3.5: an offset acknowledged through the real `DebeziumOffsetManagement.acknowledgeRecords` path between two failures restores the full budget (pre-fix of point 2: `numRetries` never reset).
- `TerminalFailureExitTest.startWithoutProgressDoesNotResetTheBudget()` — §3.5 point 5: an unclassified failure (`isDeterministicFailure` false); every retry brings the engine up (`markEngineStarted()`) and it fails again with nothing acknowledged; the exit hook fires after exactly `MAX_RETRIES` restarts (pre-fix: every clean start reset the counter and the engine was restarted forever at "retry 1 of N").
- `TerminalFailureExitTest.progressBeforeAnyFailureDoesNotWidenTheBudget()` — §3.5: progress made before the first failure is not a recovery; the budget is exactly `MAX_RETRIES`.
- `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()` — §3.5 rule 6: a worker future that has completed exceptionally (a real scheduled task that threw) makes the next engine failure terminal: no restart, the exit hook fires once, replication is reported stopped (pre-fix: `MAX_RETRIES` restarts of an engine that could only fail again).
- `DeadWorkerRetryIsTerminalTest.engineFailureWhileAWorkerIsDeadIsTerminalToo()` — §3.5 rule 6: the predicate is the pool, not the exception — a source-side engine failure while a worker is dead is terminal as well.
- `DeadWorkerRetryIsTerminalTest.liveWorkersKeepTheRetryPath()` — §3.5 rule 6 scope: with every worker future still running, the same unclassified failure draws on the budget and the engine is recreated.
- `TerminalFailureExitTest.exitDisabledIsALoudLivenessFailure()` — §3.5: `exit.on.terminal.failure=false` keeps the process up, logs FATAL naming replication as STOPPED, reports `Replica_Running=false`.
- `TerminalFailureExitTest.successIsANoOp()`.
- `DdlDrainDeadlockTest.testUndrainableQueueWithLiveWorkersKeepsWaiting()`, `DdlDrainDeadlockTest.testStuckQueueWithDeadWorkerAborts()` — §3.5 point 3: live workers are waited for; only a dead worker aborts.
- `BinlogRowImagePreflightTest.minimalIsRefused()`, `BinlogRowImagePreflightTest.noblobIsRefused()`, `BinlogRowImagePreflightTest.skipIsLoud()` — §3.6.
- `IgnoreDeleteWarningTest.trueIsDetectedCaseAndSpaceInsensitively()`, `IgnoreDeleteWarningTest.unsetFalseOrNullIsNot()` — §3.7: the predicate behind the startup WARN.
- §3.8 has no unit test: constructing a `DbWriter` needs a live ClickHouse (`DbWriterTest` is Testcontainers-based); the change is two `catch (ColumnTypeOverrideMismatchException e) { throw e; }` clauses ahead of the generic catches.
