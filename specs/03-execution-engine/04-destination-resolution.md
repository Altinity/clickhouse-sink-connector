# Spec 03.04: Destination Database & Table Name Resolution

## 1. Executive Summary & Purpose
Specifies the transformation pipeline resolving raw MySQL source database and table names into canonical target ClickHouse database and table identifiers.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Sources**:
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java`
- **Method**: `getDbWriterForTable()`

---

## 3. Transformation Pipeline Specification

Given a source database $D_{\text{src}}$ and table $T_{\text{src}}$:

```
Source Database: D_src
       |
       v
1. Check `clickhouse.database.override.map`:
   If D_src exists in map -> D_mapped = map.get(D_src)
   Else -> D_mapped = D_src
       |
       v
2. Apply `clickhouse.common.database.prefix`:
   If prefix is defined -> D_prefixed = prefix + D_mapped
   Else -> D_prefixed = D_mapped
       |
       v
3. Apply `clickhouse.database.schema.suffix`:
   If suffix is defined -> D_final = format(suffix, D_prefixed)
   Else -> D_final = D_prefixed
       |
       v
4. Target Table:
   T_final = table.name.mapping.getOrDefault(T_src, T_src)
```

### 3.1 Fully Qualified Table Identifier
The canonical key used for cache indexing and metadata tracking is:
$$\text{tableKey} = D_{\text{final}} + "." + T_{\text{final}}$$

### 3.2 Every statement names $D_{\text{final}}$
The `PreparedStatementExecutor` is constructed with the writer's resolved database (`DbWriter.getDatabaseName()` $= D_{\text{final}}$) and every statement it issues names that database. In particular the ``TRUNCATE TABLE `db`.`table` `` issued for a replicated TRUNCATE event (spec 04.05 §3.2) uses the executor's database, never the source database the record carries (`ClickHouseStruct.getDatabase()` $= D_{\text{src}}$): under `clickhouse.database.override.map` the two differ and the source name is not the table's database at all.

---

## 4. Invariants Preserved
- **Deterministic Routing**: Every worker thread resolves identical destination coordinates for the same source record.

---

## 5. Verification Criteria
- `ClickHouseBatchWriterDatabaseResolutionTest`: Validates that overrides, prefixes, and suffixes produce identical outputs in both writers.
- `PreparedStatementExecutorTruncateTest.truncateTargetsTheExecutorDatabaseNotTheSourceDatabase()` — §3.2: the replicated TRUNCATE names the executor's target database.

---

## 6. Failure Modes & Recovery

Destination resolution is a pure function of configuration, so its failures are configuration and out-of-band-schema failures, and the dangerous ones are the silent ones: a row written to the wrong database is acknowledged, and the committed offset moves past it. Recovery of such a mistake is a table resync, not a restart.

- **FM-03.04-1 A malformed `clickhouse.database.override.map` is accepted**
  - **Trigger**: a typo in the map — a missing `:` (`employees`), an invalid destination name (`employees:1x`), a duplicated source.
  - **Behaviour**: `DatabaseOverrideValidator.ensureValid` throws its `ConfigException` inside a `try` whose `catch (Exception)` only calls `printStackTrace`, so the configuration loads. Then `Utils.parseSourceToDestinationDatabaseMap` either returns `null` (missing `:`) — the worker stores it and `resolveDatabaseName` throws `NullPointerException` on every batch — or throws (invalid or duplicated name) — the worker logs once and keeps an EMPTY map, so every row is written to the source-named database. The DDL path resolves the name separately (`DebeziumChangeEventCapture.extractDatabaseNameFromRecord`) and falls back to the name as is.
  - **Detection**: a stack trace on stderr at start (journal only, not the log file). NPE case: the FM-03.03-1 retry lines with `NullPointerException`. Fallback case: ERROR `Error parsing database override map...` once per worker at start and ERROR `Error parsing database override map in extractDatabaseNameFromRecord: ...` per DDL; the misrouted rows themselves are silent.
  - **Blast radius**: NPE case: replication stalls (no loss). Fallback case: rows land in the source-named database (created by `ensureDatabaseExists`, tables auto-created under `auto.create.tables=true`); the intended database stops receiving changes while offsets advance — divergence.
  - **Recovery**: fix the map, restart. Fallback case additionally: `ch-mysql-resync` (spec 11.04) of every intended table for the period since the mistake, then drop the stray database by hand after checking it.
  - **RTO**: NPE case: restart (~1 min). Fallback case: resync time, proportional to table size — unmeasured.
  - **Test**: `DatabaseOverrideValidatorTest.aMalformedOverrideMapIsRejectedWhenTheConfigIsLoaded()` (disabled, fails on 2.11.0).
  - **DEFECT**: the validator swallows its own `ConfigException`; a malformed map must stop the connector at configuration load.

- **FM-03.04-2 A valid but mistaken destination name**
  - **Trigger**: an override, prefix or suffix that yields a database name the operator did not intend (a typo that is still a legal name).
  - **Behaviour**: `ClickHouseBatchRunnable.ensureDatabaseExists` issues `CREATE DATABASE IF NOT EXISTS` once per `host:port/db` and, with `auto.create.tables=true`, the writer creates the tables: replication proceeds into the new database. With `auto.create.tables=false`, `processBatchRecords` logs `*** TABLE METADATA not retrieved ...` and returns false, which the worker retries forever (FM-03.03-1).
  - **Detection**: auto-create case: none (a new database is not reported). Otherwise ERROR `*** TABLE METADATA not retrieved for Database(<db>), table(<t>), retrying on next attempt` and WARN `Batch not written to ClickHouse; retrying the same batch in <d> ms ...` per attempt.
  - **Blast radius**: auto-create case: the intended tables diverge while offsets advance. Otherwise: replication stalls, no loss.
  - **Recovery**: fix the configuration, restart; auto-create case: `ch-mysql-resync` (spec 11.04) of the intended tables.
  - **RTO**: resync time in the auto-create case; restart otherwise — unmeasured.
  - **Test**: `ClickHouseBatchWriterDatabaseResolutionTest`, `ClickHouseBatchRunnableDatabaseBootstrapTest.createDatabaseIsIssuedOncePerDatabaseAcrossWorkersAndCalls()`; GAP: a test that a destination database absent at start is reported at WARN or above.
  - **DEFECT**: a destination database that did not exist is created and filled without any line saying so.

- **FM-03.04-3 Target table dropped or renamed out-of-band in ClickHouse**
  - **Trigger**: someone drops or renames a replicated table directly on ClickHouse while the connector runs.
  - **Behaviour**: the cached `DbWriter` still holds the old metadata; the INSERT fails with Code 60 UNKNOWN_TABLE, which is FATAL: the worker stops and the engine exits terminally (spec 03.01 §6 FM-03.01-2). On restart a fresh writer is built: with `auto.create.tables=true` the table is recreated EMPTY and replication continues from the committed offset; with `false`, FM-03.04-2's retry loop.
  - **Detection**: ERROR `FATAL ClickHouse error (Code: 60) -- this batch will never succeed. ...`, exit code 3. After an auto-create restart: nothing reports that the table lost its history.
  - **Blast radius**: before the restart, all replication stops (no loss); after an auto-create restart, the table holds only rows changed after the committed offset — divergence.
  - **Recovery**: recreate the table, `ch-mysql-resync` (spec 11.04) it, restart.
  - **RTO**: restart + resync of the table — unmeasured.
  - **Test**: `WorkerFailureModesTest.aFatalCodeEndsTheWorkerWithTheBatchStillOutstanding()`.
  - **DEFECT**: after an out-of-band drop, auto-create silently replaces the table with an empty one; nothing tells the operator that a resync is required.

Summary: 3 failure modes, 3 DEFECT, 1 GAP.
