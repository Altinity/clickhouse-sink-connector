# Spec 08.07: A Positively Missing Target Table Fails the Batch

## 1. Executive Summary & Purpose

A target table that does not exist in ClickHouse cannot appear by retrying. Before this
spec the connector had no way to say so: the column listing returned an empty map, the
writer therefore reported its metadata as not retrieved, and the batch returned `false`,
which both execution engines treat as a retry. The retry is unbounded, so the condition
that can never settle was retried for as long as the table stayed missing.

On 2026-11-09 that shape stopped a staging connector repeatedly for hours: a dropped table
in `trade_staging` produced 5,700 consecutive empty `system.columns` reads, filled the
handoff FIFO, and tripped the hard cap *"Handoff hard cap: 500193 row(s) ... after 600042
ms"* at 09:19:38, 09:33:32, 09:47:00, 10:00:28, 10:13:56, 10:27:25 and 10:40:55 UTC -- an
operator-visible message about a row count, naming neither the table nor the cause.

This spec adds a three-valued catalog probe and makes the positively-absent case a
terminal failure. `Boolean.TRUE` means the catalog answered that the table is not there and
the batch fails, naming the table and the remedy, with its offset uncommitted so the
records replay once the table exists. `Boolean.FALSE` means the table is there, and `null`
means the probe could not answer; both keep the retry, because an unreachable server says
nothing about whether a table exists and reporting it as a dropped table would stop a
healthy connector and name the wrong cause.

---

## 2. Codebase Mapping on 2.11.1

- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
  - `isTableAbsent(Connection conn, String databaseName, String tableName)` -- the tri-state probe.
  - `tableExists(Connection conn, String database, String tableName)` -- the `system.tables` count query the probe uses.
- **Writer seam**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java`
  - `isTargetTableAbsent()` -- the probe bound to this writer's connection, database and table.
  - `wasTableMetaDataRetrieved()` -- unchanged; answers "is the writer resolved", which the probe exists to disambiguate.
- **Exception**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/MissingTargetTableException.java`
- **Throw sites (both engines)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` (`processBatchRecords`), `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` (`processRecordsByTopic`).
- **Classification**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java` -- `TERMINAL_EXCEPTION_TYPES`, `hasTerminalCause`, `classify`.
- **Callers that must keep working**: `ClickHouseBatchRunnable.run()` (the FATAL branch that rethrows `"Fatal ClickHouse error, stopping task"`), `ClickHouseBatchWriter.persistRecords` (the wrapper that carries any non-`BatchPersistenceException` as the cause of a `BatchPersistenceException`), and `ClickHouseBatchWriter.addRecordsToHistoryTable` (gated on `replication.history.enable`, so the default configuration issues no history query).

---

## 3. Operational Specification

### 3.1 The positively-absent case fails the batch, in both engines
- When `wasTableMetaDataRetrieved()` is false and `isTargetTableAbsent()` is true, the batch raises `MissingTargetTableException` instead of returning `false`.
- The message names the fully qualified table, states the remedy (`create the table, or enable auto.create.tables`), and states that the batch failed so its offset is not committed and the records replay from the last committed offset.
- The throw happens inside the batch path, before any query is grouped or executed, so no partial write and no acknowledgement can occur.
- Both engines raise the same type, so the same classifier branch handles them. The runnable throws it bare; the writer's `persistRecords` wraps it in `BatchPersistenceException` with the missing table as the cause, which is the writer's existing contract for every persistence failure.

### 3.2 The probe answers three values, never two
- `Boolean.TRUE` -- the catalog answered that `system.tables` does not list the table. Terminal.
- `Boolean.FALSE` -- the catalog answered that it does list the table. Not terminal; the writer's empty column map is then a read problem, which the existing metadata path handles.
- `null` -- the probe could not answer: no connection, or the query threw after the retries. Not terminal. A caller must treat `null` as unresolved and retry.
- A boolean probe would collapse `null` into one of the other two answers, and both collapse directions are wrong: `true` stops a connector on a network blip, `false` restores the unbounded retry this spec removes.

### 3.3 The probe is not cached
- Every call reads the catalog. A table created between two attempts must be seen by the second attempt without a restart, and the batch that failed on the previous tick replays against the new table.
- The probe therefore costs one `system.tables` count query per batch that reaches the unresolved state -- a state that ends as soon as the table exists, so it cannot become a steady-state query load.

### 3.4 The probe reads only on the unresolved path
- It runs only inside the `wasTableMetaDataRetrieved() == false` branch, after the ordinary metadata refresh has already failed.
- A resolved writer -- the normal case, every batch of a healthy connector -- never probes, so a working connector issues no extra query.

### 3.5 No connection is never reported as absence
- `isTableAbsent` returns `null` when the connection is null and logs at ERROR, naming the database and table it could not check.
- A pooled connection that throws is caught and also answered `null`, so a rejected query is a retry, never a dropped-table verdict.

### 3.6 The failure is classified terminal
- `MissingTargetTableException` is registered in `TERMINAL_EXCEPTION_TYPES`, so `classify` returns `FATAL` for it bare and for it anywhere in a cause chain.
- It carries no ClickHouse error code (the throw has no server response behind it), so `extractErrorCode` cannot decide it and the type registration is the only thing that does. Without the registration it classified `UNKNOWN`, which is retried, which is the incident.
- The registration is per type, not per message: a `BatchPersistenceException` that carries no terminal cause still classifies `UNKNOWN` and is still retried, so a transient write failure keeps its existing behaviour.

### 3.7 Ordering invariants preserved
- The offset always lags the writes. The failure path acknowledges nothing, so a restart replays from the last committed offset, which is at or before these records. Nothing in this change moves a commit earlier.
- Re-delivery is harmless: the replayed rows are written to a table that now exists, and the ReplacingMergeTree collapses any row already applied.

---

## 4. Invariants Preserved

- **I3 (no silent loss)**: a batch that cannot be written raises; it never returns as if it had been. The new throw strengthens this for the one case that previously reported a retry forever.
- **I5 (fail loudly)**: the operator sees the table name and the remedy, not a row count.
- **I14 (bounded bookkeeping)**: the probe reads `system.tables`, catalog metadata, with a `count()` aggregate, so it is inside the sanctioned shape.
- **Fail-loud over fail-quiet for anything that can never heal**: a missing table cannot heal without an external action, so retrying it converts a precise error into an unattributed stall.

---

## 5. Verification Criteria

- `DBMetadataTableAbsentProbeTest.absentTableIsTrue()` -- section 3.2: a `count()` of 0 from `system.tables` answers `TRUE`.
- `DBMetadataTableAbsentProbeTest.presentTableIsFalse()` -- section 3.2: a listed table answers `FALSE`, never `TRUE`.
- `DBMetadataTableAbsentProbeTest.noConnectionIsUnknown()` -- section 3.5: a null connection answers `null`, never `TRUE`.
- `DBMetadataTableAbsentProbeTest.unreadableCatalogIsUnknown()` -- section 3.5: a query that throws answers `null`, never `TRUE`.
- `DBMetadataTableAbsentProbeTest.probeNamesTheTableItAsksAbout()` -- section 3.2: the query names `system.tables` and both the database and the table.
- `DBMetadataTableAbsentProbeTest.probeIsNotCached()` -- section 3.3: a table created between two probes is seen by the second.
- `MissingTargetTableEngineTest.runnableFailsTheBatchWhenTheCatalogReportsTheTableMissing()` -- sections 3.1 and 3.6: the multi-threaded engine raises the bare exception, names the table and `auto.create.tables`, classifies FATAL, and acknowledges nothing.
- `MissingTargetTableEngineTest.writerCarriesTheMissingTableAsTheCauseOfTheBatchFailure()` -- sections 3.1 and 3.6: the single-threaded engine raises `BatchPersistenceException` with the missing table as the cause, classifies FATAL through the wrapper, and acknowledges nothing.
- `MissingTargetTableEngineTest.inconclusiveProbeIsStillARetryNotAStop()` -- sections 3.2 and 3.5: a catalog that refuses the count query returns `false` from the batch, never the throw, and the test asserts the probe actually asked (a zero denominator would make it vacuous).
- `ClickHouseErrorClassifierFailureModesTest.missingTargetTableIsFatal()` -- section 3.6: the bare exception classifies FATAL.
- `ClickHouseErrorClassifierFailureModesTest.missingTargetTableWrappedInBatchFailureIsFatal()` -- section 3.6: the terminal type is found through `BatchPersistenceException`.
- `ClickHouseErrorClassifierFailureModesTest.plainBatchFailureIsNotFatal()` -- section 3.6: a wrapper with no terminal cause stays `UNKNOWN`, so the registration cannot widen into "every batch failure stops the connector".
- `ClickHouseBatchWriterMissingTableTest.missingTableFailsTheBatchInsteadOfDroppingIt()` -- section 3.1: the writer fails loudly and acknowledges nothing when the table cannot be reached at all.
- `ClickHouseBatchWriterMissingTableTest.emptyBatchIsNotAFailure()` -- section 3.1: an empty batch stays silent, so the stricter path cannot turn a no-op into a restart.

---

## 6. Failure Modes & Recovery

### A target table the catalog reports missing
- **Trigger**: the connector has records for `db.t` and `system.tables` does not list `t` in `db`.
- **Behaviour**: ERROR `*** TABLE METADATA not retrieved for Database(db), table(t) retrying`, then `MissingTargetTableException` (or `BatchPersistenceException` with it as the cause on the single-threaded engine), and the task stops. No record is acknowledged.
- **Detection**: the exception message names `db.t` and the remedy; the offset for the failed batch is not committed.
- **Blast radius**: the affected connector task only. Nothing is written and nothing is acknowledged, so no data is lost; the records replay from the last committed offset once the table exists.
- **Recovery**: create the table, or set `auto.create.tables=true`, then restart the connector. A recreated table is picked up on the restart without any other change.
- **RTO**: the time for a human to create the table plus one restart.
- **Test**: `MissingTargetTableEngineTest.runnableFailsTheBatchWhenTheCatalogReportsTheTableMissing()`.

### A catalog that cannot answer the probe
- **Trigger**: the connection is null, or the `system.tables` count query throws on every attempt (for example `Code: 202. Too many simultaneous queries`).
- **Behaviour**: `isTableAbsent` logs the database and table it could not check and returns `null`; the batch returns `false` and is retried with the existing backoff, exactly as before this spec.
- **Detection**: ERROR `Could not determine whether db.t exists` carrying the driver failure, followed by the existing per-batch metadata-retry ERROR.
- **Blast radius**: the affected table's batches only; they are held and retried, never dropped.
- **Recovery**: none required once the catalog answers.
- **RTO**: the duration of the catalog outage plus one backoff interval.
- **Test**: `MissingTargetTableEngineTest.inconclusiveProbeIsStillARetryNotAStop()`.

### A table recreated while the batch is failing
- **Trigger**: a human creates the table after the connector has already stopped on the missing-table failure.
- **Behaviour**: nothing happens until the connector restarts, which is the same requirement as any stopped Debezium task. On restart the writer resolves the table and the retained records replay.
- **Detection**: the connector is down; the target table now exists.
- **Blast radius**: none beyond the stalled task.
- **Recovery**: restart the connector.
- **RTO**: the restart.
- **Test**: `DBMetadataTableAbsentProbeTest.probeIsNotCached()`.
