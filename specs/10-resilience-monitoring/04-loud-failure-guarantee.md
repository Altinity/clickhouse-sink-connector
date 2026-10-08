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
- **PostgreSQL DDL parse path (section 3.10)**: `PostgreSQLDDLParserService#parseSql`, `#runAntlrPipeline` (`PostgreSQLDDLParserService.java`), `PostgreSQLDDLParserListenerImpl.java`; reuses MySQL's own `ErrorListenerImpl` (`ErrorListenerImpl.java`), all in `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/`

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

### 3.9 A PostgreSQL schema-drift failure halts, not merely warns
`ClickHouseConverter.extractDebeziumSchema` returns `null` only for the
legitimate no-row cases: a control record carrying no value schema at all
(a heartbeat, a transaction-metadata record, a tombstone), or a value
struct with no populated `after`/`before` row image or an empty field
list. Any other exception raised while reading the record's own schema
(e.g. a corrupt envelope) now propagates instead of being caught and
logged at WARN: a source column the reader never saw is a column that
would be written to ClickHouse without a value, which is divergence
(section 1, "Prime Directive"), not a condition to merely log and move
past.

`PostgresSchemaChangeDetector.checkAndReconcile` no longer wraps its body
in a catch-all either. It still returns without acting for the two
legitimate non-failure outcomes -- the extraction result above, and a
table that does not yet exist in ClickHouse (`fetchClickHouseSchema`
returns `null` only after a `system.tables` count query confirms the
table is genuinely absent, and the connector's auto-create mechanism is
expected to create it) -- but every other failure now propagates: a
`null` writer or `null` JDBC connection (the detector was wired before
the writer was ready), a `system.columns` query that throws, or
`PostgresSchemaReconciler.addMissingColumns` failing to add one or more
columns all throw out of `checkAndReconcile` instead of being caught and
logged.

**Parity scope.** Schema-drift detection only ever reconciles a column
that exists in the source (Debezium) schema but is missing from
ClickHouse -- `findMissingColumns` inspects only the Debezium-side field
list. A column that a user has added to a ClickHouse table and that does
not exist in the source is never flagged, never reconciled and never
halts the pipeline: parity is required only for columns that match the
source and for tables the connector manages, and a ClickHouse-only
column is tolerated by design.

**Cooldown does not mask a failure.** Before this fix `addMissingColumns`
never threw, so recording `lastReconcileAttempt` immediately before
calling it was safe: the call always "succeeded" from the caller's point
of view. Now that `addMissingColumns` can throw, the same unconditional
timestamp write would otherwise let every record arriving inside the
following `RECONCILE_COOLDOWN_MS` (10 s) window pass through unchecked --
each one written with the column still missing. The detector now also
tracks the last reconciliation failure per table
(`lastReconcileFailure`); while the cooldown is active and the last
attempt failed, `checkAndReconcile` re-throws the stored failure instead
of returning silently, so no record is written during the cooldown that
follows a failed reconciliation. A successful reconciliation, and a check
that finds nothing missing, clears the stored failure.

The caller in the PostgreSQL event path,
`DebeziumChangeEventCapture#processEveryChangeRecord`, wraps whatever
`checkAndReconcile` throws in `RecordReplicationException` -- the same
exception type, and the same wrap-and-rethrow shape, already used a few
lines below for a record whose `debeziumRecordParserService.parse`
throws -- so it escapes ahead of the method's generic catch-all (section
3.3) and reaches the same halt as any other row failure: the engine
stops, the offset is not committed past the failing record, and
`DebeziumChangeEventCapture.handleEngineCompletion` applies the same
retry/terminal-exit policy (section 3.5).

### 3.10 A PostgreSQL DDL translation failure halts, not merely warns
Before this fix, `PostgreSQLDDLParserService.parseSql` (both overloads)
wrapped the whole ANTLR lexer/parser/listener pipeline in a
`catch (Exception e) { log.error(...); }` and returned `null` either
way, and `PostgreSQLDDLParserListenerImpl` additionally wrapped the
bodies of five `enter*()` callbacks (`enterCreatestmt`,
`enterAltertablestmt`, `enterRenamestmt`, `enterDropstmt`,
`enterTruncatestmt`) in the same catch-log-continue shape. A statement
the grammar could not parse, or a translation that threw while walking
a parsed tree (a `NullPointerException` from an unexpected shape, an
`IndexOutOfBoundsException`, anything), produced an empty or partial
translation and a log line, and the caller
(`DebeziumChangeEventCapture`'s DDL branch) had no way to tell that
apart from a statement that was legitimately empty-translated by
design (section 3.9's "Parity scope", and the allowed-skip list below).
The pipeline advanced the offset past a managed-table DDL statement
that was never actually applied to ClickHouse -- exactly the divergence
section 1's "Prime Directive" forbids, and unlike the MySQL DDL path
(spec 06.03 FM-06.03-1), which has always halted on the same class of
failure.

**The fix reuses MySQL's own mechanism; it does not invent a second
one.** `runAntlrPipeline` now installs `ErrorListenerImpl` -- the same
`ANTLRErrorListener` class `MySQLDDLParserService` already installs on
its own lexer/parser, generically typed against `Recognizer<?, ?>` so
nothing PostgreSQL-specific was needed -- in place of the removed
`LenientErrorListener` (an inner class that only logged at WARN and
never threw); its `syntaxError()` throws
`RuntimeException("Error parsing DDL")` exactly as it does for MySQL.
`PostgreSQLDDLParserService.parseSql` no longer catches anything: a
syntax error, or any other exception thrown while walking the parse
tree, now propagates out of `parseSql` exactly as a MySQL parse failure
propagates out of `MySQLDDLParserService.parseSql`, reaches the same
`performDDLOperation()` retry loop and `DDLReplicationException` wrap
point, and halts the pipeline without acknowledging the offset (section
3.3, spec 06.03 FM-06.03-1). The five `enter*()` callbacks no longer
catch anything either: a translation bug inside one of them (a `null`
dereference while building the column list, for instance) now also
escapes as a loud, unclassified `RuntimeException` instead of leaving
`parsedQuery` empty and the caller none the wiser.

**Allowed-skip list (named, per the Parity scope ruling in section
3.9 and the "ClickHouse-only tables/columns are tolerated" ruling).**
These are not errors and must keep producing an intentionally empty
translation without throwing -- each is a statement kind or clause the
spec already says the connector does not manage, not a translation
that failed:

1. **A statement kind with no listener callback at all** (`CREATE
   INDEX`, `CREATE SEQUENCE`, `CREATE VIEW`, `COMMENT ON`, and any other
   grammar rule `PostgreSQLDDLParserListenerImpl` does not override).
   The ANTLR walk completes normally and `parsedQuery` stays empty;
   `runAntlrPipeline` logs a WARN (`"PostgreSQL DDL produced no output
   (unsupported or ignored)"`) naming the statement so the empty
   translation is never silent, but nothing halts. (Mirrors MySQL's
   `FM-06.03-3`.)
2. **`DROP` of a non-`TABLE` object** (`DROP INDEX`, `DROP VIEW`, `DROP
   SEQUENCE`, ...). `enterDropstmt` returns early when
   `object_type_any_name()` is not `TABLE`; only `DROP TABLE` has a
   ClickHouse equivalent.
3. **An `alter_table_cmd` keyword `translateAlterTableCmd` does not
   recognise** (anything other than `ADD`/`ADD_P`, `DROP`, `ALTER`). The
   `default:` branch logs at DEBUG and skips just that one clause of the
   `ALTER TABLE` statement; the clauses it does recognise are still
   translated.
4. **A table-level constraint clause with no ClickHouse equivalent**
   (`ADD CONSTRAINT ... PRIMARY KEY/UNIQUE/CHECK/FOREIGN KEY`, `DROP
   CONSTRAINT`). `reportUntranslatedConstraint` (spec 06.09 section 3.7)
   already reports these explicitly -- WARN with the manual remedy when
   the clause could change the table's identity, INFO otherwise -- so
   they are named, not silently dropped; this was already correct
   before this fix and is unchanged by it.
5. **A ClickHouse-only table or column the connector does not manage**
   (section 3.9's "Parity scope"). Schema drift reconciliation, and by
   the same ruling this DDL path, never inspects or acts on anything
   that exists only on the ClickHouse side; it is tolerated by design,
   not detected and skipped.

None of the five is reached by catching an exception: each is a
dedicated early-return or a dedicated reporting call keyed on the
parsed statement's shape, decided before any translation is attempted,
so a genuine translation failure on a managed table can never be
mistaken for one of them.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure / Zero Silence)**: Guarantees that data divergence is never masked by silent error suppression.

---

## 5. Verification Criteria
- `ClickHouseConverterSchemaExtractionTest.unreadableSchemaPropagates()` — §3.9: an unreadable schema now throws out of `extractDebeziumSchema` instead of being caught and logged (pre-fix: returned `null` after a WARN).
- `PostgresSchemaChangeDetectorTest.genuineRowWithUnreachableWriterThrows()`, `PostgresSchemaChangeDetectorTest.cooldownAfterAFailureStillThrows()`, `PostgresSchemaChangeDetectorTest.extraClickHouseOnlyColumnNeverHalts()` — §3.9: a genuine schema-drift failure on a real row halts, a failure during the cooldown window halts again rather than passing silently, and a ClickHouse-only column (not present in the source) never triggers reconciliation or a halt.
- `PostgresSchemaReconcilerAddColumnFailureTest.failedAlterThrowsAfterAttemptingAllColumns()` — §3.9: `addMissingColumns` attempts every column, then throws naming the ones that failed, instead of logging and returning normally.
- `SchemaDriftFailureIsTerminalTest.schemaDriftFailurePropagatesThroughProcessEveryChangeRecord()` — §3.9 at the `processEveryChangeRecord` seam: a schema-drift failure on a real row reaches `RecordReplicationException`, the same halt as an unconvertible row.
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
- `PostgreSQLDDLParserServiceTest.unparseablePostgresDdlPropagatesInsteadOfBeingSwallowed()` — §3.10 / FM-10.04-9: a statement the PostgreSQL grammar cannot parse now throws out of `parseSql` instead of logging and returning an empty translation (pre-fix: caught, logged at ERROR, `parsedQuery` left empty, offset still acknowledged by the caller).
- `PostgreSQLDDLParserServiceTest.testUnsupportedCreateIndexIsSkipped()`, `testUnsupportedCreateSequenceIsSkipped()`, `testUnsupportedAlterTableSetDefaultIsSkipped()` — §3.10 allowed-skip #1 and #3: a statement kind with no listener callback, and an unrecognised `alter_table_cmd` keyword, both still produce an empty translation without throwing.
- `PostgreSQLConstraintClauseReportTest` (all three tests) — §3.10 allowed-skip #4, unchanged by this fix: an untranslatable constraint clause is still reported at WARN/INFO, never silently dropped and never thrown as an error.

---

## 6. Failure Modes & Recovery
A FATAL classification, a dead worker and an exhausted engine budget all end in a FATAL log line and exit code 3, within one Debezium batch for the first two and within about ten engine restarts for the third; the supervisor then restarts from the last committed offset. What this contract does not cover is the process that is up, retrying forever and reporting itself healthy, and two documented "halts" that do not halt.

- **FM-10.04-1 A deterministic failure is terminal at once**
  - **Trigger**: an engine failure that `ClickHouseErrorClassifier.classify` calls FATAL (spec 10.01 FM-10.01-3).
  - **Behaviour**: `DebeziumChangeEventCapture.handleEngineCompletion` retires the stopped engine's handoffs, sees `isDeterministicFailure`, marks replication stopped and calls `terminalFailureHook` with `TERMINAL_FAILURE_EXIT_CODE` (3) unless `exit.on.terminal.failure=false`; the budget is untouched.
  - **Detection**: ERROR `Engine stopped with an error: ...`, ERROR `Engine stopped with a FATAL (deterministic) failure; not retrying: ...`, FATAL `Replication is STOPPED: the engine failed {} time(s) in a row (errors.max.retries={}) and will not be restarted. Last failure: {}. Offsets were not committed past the failing point; fix the cause and restart. Exiting with code 3 ...`; exit code 3.
  - **Blast radius**: all replication stops; nothing is committed past the failing point.
  - **Recovery**: fix the cause, then let systemd restart the service (FM-10.04-6).
  - **RTO**: detection immediate; restart 30 s + engine start + redelivery after the fix; unmeasured.
  - **Test**: `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`, `TerminalFailureExitTest.fatalTerminalTypeIsNotRetried()`.

- **FM-10.04-2 A dead worker stops the engine and is terminal**
  - **Trigger**: a worker's scheduled task ends (a FATAL rethrow, a poisoned `OffsetStorageWriter`).
  - **Behaviour**: `failIfWorkerDied` runs at the top of every `handleChangeEventBatch`, between the 50 ms slices of the handoff-cap wait, and in every DDL-drain poll; it raises the worker's cause and the engine stops; `handleEngineCompletion` sees `hasDeadWorker()` and exits 3 without a retry. On an idle source the next batch is a heartbeat, so detection is bounded by `heartbeat.interval.ms` (5 s by default); with heartbeats disabled (`heartbeat.interval.ms=0`) an idle source defers detection to the next source event.
  - **Detection**: ERROR `Sink worker %d of %d is dead: its scheduled task has terminated. ...`, ERROR `Engine stopped while a sink worker is dead; not retrying: ...`, FATAL `Replication is STOPPED: ...`, exit code 3.
  - **Blast radius**: all replication stops; the dead worker's batch stays outstanding, so nothing is committed past it.
  - **Recovery**: as FM-10.04-1; a process restart is what gives a fresh worker pool.
  - **RTO**: detection <= 5 s with heartbeats on; restart 30 s + start; unmeasured.
  - **Test**: `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()`, `DeadWorkerRetryIsTerminalTest.engineFailureWhileAWorkerIsDeadIsTerminalToo()`, `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`, `DdlDrainDeadlockTest.testStuckQueueWithDeadWorkerAborts()`.

- **FM-10.04-3 An unclassified failure recurs on every start**
  - **Trigger**: an engine failure the classifier cannot recognise (no code, an unlisted type, an `Error`) that happens again after every restart -- a source-side error, a bookkeeping write that keeps failing (spec 10.06 FM-10.06a-2).
  - **Behaviour**: each failure without an acknowledged offset since the previous one consumes one of `errors.max.retries` (10) retries, `SLEEP_TIME` (10 s) apart, each a full engine start; progress (an acknowledgement) refills the budget, a clean start does not. When spent: FATAL and exit 3.
  - **Detection**: ERROR `Restarting the engine - retry {} of {}` per retry, then the FATAL line and exit 3.
  - **Blast radius**: all replication stops for the duration; nothing is committed past the failing point.
  - **Recovery**: fix the cause; the supervisor restarts the service.
  - **RTO**: detection 10 x (10 s + engine start), about 3-5 minutes; unmeasured (the unit test counts restarts, not time).
  - **Test**: `TerminalFailureExitTest.exitHookFiresAfterMaxRetries()`, `TerminalFailureExitTest.startWithoutProgressDoesNotResetTheBudget()`, `TerminalFailureExitTest.progressResetsTheBudget()`.

- **FM-10.04-4 The JVM is up, replicating nothing, and reports healthy**
  - **Trigger**: a worker retries a batch forever (spec 10.02 FM-10.02-1): TOO_MANY_PARTS that never clears, a missing column with evolution off, a revoked grant outside the FATAL set, a table metadata that never loads.
  - **Behaviour**: no engine failure occurs, so none of FM-10.04-1 to FM-10.04-3 applies. The only exit path is the handoff cap: after `sink.connector.handoff.wait.timeout.ms` (600 s) with `sink.connector.handoff.max.outstanding.records` (500,000) rows unacknowledged the engine stops with `Handoff hard cap: ... they are stalled, not slow. Stopping the engine ...`, and FM-10.04-3's ten restarts follow (each redelivering into the same stall) -- over 100 minutes, and never if the source writes fewer rows than the cap.
  - **Detection**: WARN/ERROR retry lines at most 30 s apart; `/status` reports `Replica_Running=true` with a frozen `Seconds_Behind_Source`; the Prometheus lag gauge is frozen (spec 10.03 FM-10.03-2, FM-10.03-4); only `show_replica_status.seconds_behind_source` grows.
  - **Blast radius**: all replication behind the stalled worker; no loss.
  - **Recovery**: remove the cause; the retry succeeds within 30 s without a restart.
  - **RTO**: <= 30 s after the fix; detection by the process unbounded; unmeasured.
  - **Test**: `HandoffHardCapBackpressureTest.theWaitIsBoundedAndLoud()` (the only bounded stop); GAP: a liveness test asserting that a batch failing for longer than a stated bound changes `/status` or a metric, or stops the process.
  - **DEFECT**: there is no bounded liveness signal: a stall with live workers is not reflected in `/status`, in any metric, or in the exit code within any stated time (Invariant I15 property 1).

- **FM-10.04-5 Terminal exit disabled**
  - **Trigger**: `exit.on.terminal.failure=false`.
  - **Behaviour**: on a terminal failure the process stays up, `/status` reports `Replica_Running=false`, and a FATAL line says replication is stopped; nothing restarts it.
  - **Detection**: FATAL `Replication is STOPPED: ... The process stays up with replication stopped (exit.on.terminal.failure=false); /status reports Replica_Running=false.`; a supervisor sees nothing.
  - **Blast radius**: replication stays stopped until an operator acts.
  - **Recovery**: fix the cause and restart the service, or `sink-connector-client restart`.
  - **RTO**: operator response time; unmeasured.
  - **Test**: `TerminalFailureExitTest.exitDisabledIsALoudLivenessFailure()`.

- **FM-10.04-6 The supervisor gives up after repeated terminal exits**
  - **Trigger**: a deterministic failure that persists across restarts.
  - **Behaviour**: the shipped unit (`deploy/ansible-systemd/templates/systemd_sink_connector.service.j2`) has `Restart=always`, `RestartSec=30`, `StartLimitInterval=300`, `StartLimitBurst=5`: each exit 3 is followed by a restart 30 s later, each start redelivers from the committed offset and fails again, and after 5 starts in 300 s systemd marks the unit failed and stops restarting.
  - **Detection**: the unit enters `failed` (`systemctl status`), with the FATAL line of the last run in the journal.
  - **Blast radius**: replication stays down until an operator acts; nothing is committed past the failing point.
  - **Recovery**: fix the cause, `systemctl reset-failed <unit>`, `systemctl start <unit>`.
  - **RTO**: operator response time + 30 s + engine start; unmeasured.
  - **Test**: GAP: a deployment test asserting the unit's restart limits and that exit code 3 is restarted.

- **FM-10.04-7 A documented halt that retries instead**
  - **Trigger**: `ColumnTypeOverrideMismatchException` (section 3.8), or any other connector exception documented as halting that carries no ClickHouse code and is not in `TERMINAL_EXCEPTION_TYPES`.
  - **Behaviour**: `DbWriter` rethrows it past its generic catch as section 3.8 says, but `ClickHouseBatchRunnable.run` classifies it UNKNOWN and retries the batch forever; in practice the check is not even reached for an existing table (spec 08.05 FM-08.05-4).
  - **Detection**: as FM-10.04-4.
  - **Blast radius**: as spec 08.05 FM-08.05-4.
  - **Recovery**: as spec 08.05 FM-08.05-4.
  - **RTO**: unbounded; unmeasured.
  - **Test**: `ClickHouseErrorClassifierFailureModesTest.columnTypeOverrideMismatchIsFatal()` (`@Disabled`, confirmed red on 2.11.0).
  - **DEFECT**: section 3.8's halt is not implemented: the exception is retried, not terminal.

- **FM-10.04-8 A PostgreSQL schema-drift failure halts instead of being skipped**
  - **Trigger**: a row record whose own schema cannot be read, a ClickHouse
    `system.columns` fetch that fails or finds the writer/connection not
    ready, or a reconciliation `ALTER TABLE ... ADD COLUMN` that fails --
    for a source column that is genuinely missing from ClickHouse (section
    3.9). A ClickHouse-only column never triggers this mode (parity
    scope, section 3.9).
  - **Behaviour**: `PostgresSchemaChangeDetector.checkAndReconcile` no
    longer catches these; `DebeziumChangeEventCapture` wraps whatever it
    throws in `RecordReplicationException` at the same call site that
    wraps a parser failure, which escapes the generic catch-all (section
    3.3) and stops the engine before the row is written. A failure during
    the 10 s reconciliation cooldown re-throws the stored cause instead
    of letting the record through silently.
  - **Detection**: the engine stops with `RecordReplicationException`
    naming the topic; the same ERROR/FATAL sequence as FM-10.04-1 follows
    through `handleEngineCompletion`.
  - **Blast radius**: all replication stops; the record whose column is
    missing from ClickHouse is never written, so no row is stored with a
    source column silently absent.
  - **Recovery**: fix why the column could not be added (a permissions or
    connectivity problem, an unmappable type), or add it manually; restart
    per FM-10.04-6.
  - **RTO**: detection immediate; restart as FM-10.04-1; unmeasured.
  - **Test**: `ClickHouseConverterSchemaExtractionTest.unreadableSchemaPropagates()`, `PostgresSchemaChangeDetectorTest.genuineRowWithUnreachableWriterThrows()`, `PostgresSchemaChangeDetectorTest.cooldownAfterAFailureStillThrows()`, `PostgresSchemaReconcilerAddColumnFailureTest.failedAlterThrowsAfterAttemptingAllColumns()`, `SchemaDriftFailureIsTerminalTest.schemaDriftFailurePropagatesThroughProcessEveryChangeRecord()`.

- **FM-10.04-9 A PostgreSQL DDL translation failure halts instead of being skipped**
  - **Trigger**: a statement on a managed table that the PostgreSQL
    grammar cannot parse (a syntax error, or a construct the grammar
    does not cover), or any other exception thrown while
    `PostgreSQLDDLParserListenerImpl` walks a successfully parsed tree
    for `CREATE TABLE`, `ALTER TABLE` (rename/add/drop column), `DROP
    TABLE` or `TRUNCATE TABLE`. Any of the five named allowed skips
    (section 3.10) is excluded by definition, not by catching an
    exception.
  - **Behaviour**: `runAntlrPipeline` installs `ErrorListenerImpl` (the
    same class `MySQLDDLParserService` uses) on both the lexer and the
    parser in place of the removed, non-throwing
    `LenientErrorListener`; its `syntaxError()` throws
    `RuntimeException("Error parsing DDL")`. Neither overload of
    `PostgreSQLDDLParserService.parseSql`, nor any of the five
    `enter*()` listener callbacks, catches anything any more, so the
    failure propagates out of `parseSql` exactly as a MySQL parse
    failure does (spec 06.03 FM-06.03-1), into
    `DebeziumChangeEventCapture`'s DDL branch and its
    `DDLReplicationException` wrap point, ahead of the generic catch-all
    (section 3.3).
  - **Detection**: the engine stops with `DDLReplicationException`
    naming the failing statement; the same ERROR/FATAL sequence and
    retry/terminal-exit policy as FM-10.04-1 follows through
    `handleEngineCompletion` (section 3.5).
  - **Blast radius**: all replication stops; the statement is never
    partially applied and the offset is not committed past it, so
    ClickHouse never diverges from a managed table's schema because of
    an untranslated DDL statement.
  - **Recovery**: fix why the statement could not be translated (an
    unsupported construct that needs a listener change, or a grammar
    gap), or hand-apply the equivalent DDL to ClickHouse out of band and
    skip past the statement at the source; restart per FM-10.04-6.
  - **RTO**: detection immediate; restart as FM-10.04-1; unmeasured.
  - **Test**: `PostgreSQLDDLParserServiceTest.unparseablePostgresDdlPropagatesInsteadOfBeingSwallowed()`.

Summary: 9 failure modes, 2 DEFECT, 2 GAP.
