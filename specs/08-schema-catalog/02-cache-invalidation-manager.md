# Spec 08.02: Multi-Epoch Cache Invalidation & Version Counters

## 1. Executive Summary & Purpose
Specifies the global coordination singleton (`CacheInvalidationManager`) that invalidates stale metadata across concurrent worker threads using monotonic per-table version counters and a global epoch.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/CacheInvalidationManager.java`
- **Fields**:
  - `private final Map<String, Long> tableVersions` (a `ConcurrentHashMap`)
  - `private final AtomicLong globalEpoch`
  - `private final Map<String, Map<String, Long>> columnsProvenAbsent` (spec 08.03)
- **Which tables a DDL invalidates**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DdlTableNames.java` (`affected`, `renamed`, `fromRecord`, `bareName`; section 3.4)
- **Methods**: `getInstance()`, `invalidateTable(String tableName)`, `getVersion(String tableName)`, `invalidateAll()`, `clearAll()` (tests), `pendingInvalidations()` (tests)

---

## 3. Operational Specification

### 3.1 Table-Scoped Invalidation (`invalidateTable`)
When a DDL has been applied to table `tableName` (`"database.table"`):
1. `tableVersions.merge(tableName, 1L, Long::sum)` increments the table's version (a table never invalidated has no entry and reads as 0).
2. Nothing else is cleared: proven-absent column proofs (spec 08.03) are stamped with the version they were taken at and are discarded lazily by `isColumnProvenAbsent` when the current version no longer matches.
3. A worker whose cached version differs from `getVersion(tableName)` discards its `DbWriter` and re-reads ClickHouse metadata before its next write.

### 3.2 Version read (`getVersion`)
`getVersion(tableName) = globalEpoch.get() + tableVersions.getOrDefault(tableName, 0L)` (0 for a `null`/empty name). The epoch is folded in so that a global invalidation reaches tables that have no entry of their own.

### 3.3 Global Epoch Invalidation (`invalidateAll`)
When the table affected by a DDL cannot be determined (unparsed statement, unresolvable table name, or an error while working it out):
1. `globalEpoch.incrementAndGet()`.
2. Because every `getVersion` result includes the epoch, every cached writer — including those for tables absent from `tableVersions` — observes a version change and rebuilds, at the cost of one metadata re-read per active table on its next batch.

### 3.4 Which tables a DDL invalidates (`DdlTableNames`)
`DebeziumChangeEventCapture` invalidates (and drops the cached writer of) every
table `DdlTableNames.affected(record, ddl)` returns: the bare names of the
record's `tableChanges[].id` (else `source.table`), then BOTH participants of
every rename in the statement text (`RENAME TABLE a TO b, c TO d`,
`ALTER TABLE a RENAME [TO|AS] b`). A rename's `tableChanges` entry names only the
NEW table; without the text scan the OLD name's cached writer would keep
inserting into a table that no longer exists.

---

---

## 4. Invariants Preserved
- **Deterministic Cache Convergence**: after any schema modification every writer re-reads metadata before its next write; a DDL whose target is unknown invalidates everything rather than nothing.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest` rename cases (`DdlTableNames.renamed`) — section 3.4: both sides of `RENAME TABLE a TO b`, of `ALTER TABLE a RENAME TO b`, of multi-pair renames; null and empty statements yield nothing.
- `CacheInvalidationProvenAbsentTest.testDdlInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testInvalidateAllInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testProofRetakenAfterDdlSticks()` — `invalidateTable` / `invalidateAll` move `getVersion`.
- `StaleSchemaCacheIT`, `AlterTableDropColumnCacheIT`, `AlterTableDropColumnDatabaseOverrideCacheIT` — writers rebuild after DDL.
- Verification: a dedicated unit test of the version counter arithmetic (`merge`/epoch fold) independent of proven-absent tracking is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery
Invalidation is an in-memory, per-process signal: it fires for DDL the connector applies and for a probe that found a column (spec 08.03), is lost harmlessly with the process (a new process reads fresh metadata), and never fires for a change made to ClickHouse out of band (spec 08.01 FM-08.01-1 to FM-08.01-3). Its failure modes are a signal sent to the wrong key, a signal sent too broadly, and a signal sent too often.

- **FM-08.02-1 Invalidation key and writer key disagree**
  - **Trigger**: a DDL is applied while the database name is resolved differently on the two sides: `DebeziumChangeEventCapture` builds the invalidation key (prefix, schema suffix, override map, history database) in its own code, and `ClickHouseBatchRunnable.resolveDatabaseName` builds the writer key; they are kept in step by hand.
  - **Behaviour**: `invalidateTable(k1)` moves a version no writer reads; writers cached under `k2` keep the pre-DDL map. The record-witness probe (spec 08.03) still catches a column the record carries and the map lacks; a removed, renamed or retyped column falls to spec 08.01 FM-08.01-1 / FM-08.01-2.
  - **Detection**: none for the mismatch itself. INFO `Marked table {} for cache invalidation after DDL (version {})` shows the key used; the absence of INFO `Invalidating cached DbWriter for {} after DDL (version {} -> {})` for the table is the only hint.
  - **Blast radius**: stale binding for the table until a downstream failure; silent for a type change.
  - **Recovery**: restart the service; fix the resolution difference (both paths must apply the same prefix, suffix and override map).
  - **RTO**: restart 30 s + engine start once detected; detection itself is unbounded; unmeasured.
  - **Test**: `AlterTableDropColumnDatabaseOverrideCacheIT` (Docker) covers the override-map case; GAP: a unit test asserting both resolvers yield the same key for every prefix, suffix, override and history-mode combination.
  - **DEFECT**: the two key resolvers are duplicated code with no shared implementation or equality check, and a divergence is silent until an INSERT fails.

- **FM-08.02-2 DDL target unresolvable: every writer rebuilds**
  - **Trigger**: a DDL whose table cannot be named from the event, the statement or the topic, or an exception while resolving it.
  - **Behaviour**: `invalidateAll()` bumps the epoch; every worker rebuilds each writer it uses on its next batch -- per writer the column listing, the ALIAS/MATERIALIZED list, the database probe, the engine and the sorting key (about six catalog queries). The cost is bounded per DDL (tables in use x workers) and DDLs are serialised behind the pre-DDL drain (spec 06.01), so a storm of unresolvable DDL costs one sweep per statement, never a loop.
  - **Detection**: WARN `Could not resolve any table name for DDL [{}]; invalidating every cached schema rather than risk writing against a stale one.`, WARN `Invalidating ALL cached schemas after a DDL whose affected table could not be determined (epoch {}). ...`, INFO `Rebuilt DbWriter schema for {} at cache version {} ({} columns): {}` per rebuild.
  - **Blast radius**: a latency spike on the next batch of every table; no loss.
  - **Recovery**: self-heals after one rebuild per table per worker.
  - **RTO**: one batch per table; unmeasured.
  - **Test**: `CacheInvalidationVersionArithmeticTest.invalidateAllReachesATableThatWasNeverInvalidated()`; GAP: a count of catalog queries per unresolvable DDL for N tables and M workers.

- **FM-08.02-3 A stale writer passes the version check (ABA)**
  - **Trigger**: a sequence of table and global invalidations that brings `getVersion(table)` back to a value a stale writer cached.
  - **Behaviour**: impossible on 2.11.0: `tableVersions` and `globalEpoch` only increase (`clearAll()` is test-only), so the sum strictly increases on every invalidation that reaches the table.
  - **Detection**: not applicable; pinned by test.
  - **Blast radius**: none.
  - **Recovery**: none needed.
  - **RTO**: 0.
  - **Test**: `CacheInvalidationVersionArithmeticTest.versionIsStrictlyMonotoneUnderAnyInterleaving()`, `CacheInvalidationVersionArithmeticTest.nullAndEmptyNamesReadZeroAndAreInert()`.

- **FM-08.02-4 The version is bumped at record rate (the cache-invalidation livelock)**
  - **Trigger**: a path that calls `invalidateTable` on a condition a re-read does not resolve. It happened (fixed in #1436): a MATERIALIZED column was taken as proof of staleness on every record, the table's version reached 66,731,063 in three hours for a table without DDL in a month, every worker rebuilt its writer on every batch, 9,664,873 `system.columns` queries ran in one hour (15.5% of the server's queries), and the connector's binlog offset stopped advancing for hours while sibling connectors on the same source stayed current.
  - **Behaviour**: on 2.11.0 `invalidateTable` is called from the probe only after a re-read produced the column, after a successful MATERIALIZED conversion, or after a successful `ADD COLUMN` (`GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn`); an ALIAS is recorded proven-absent without a bump, and every other miss fails the batch without one (spec 08.03). There is still no rate limit and no counter on bumps or rebuilds.
  - **Detection**: INFO `Marked table {} for cache invalidation after DDL (version {})` and INFO `Invalidating cached DbWriter for {} after DDL (version {} -> {})` at record rate, and the `system.columns` query rate in ClickHouse's `system.query_log`; no connector metric and no ERROR.
  - **Blast radius**: if reintroduced, CPU and ClickHouse metadata load proportional to record rate x workers, and a stalled offset.
  - **Recovery**: the cause is code or a ClickHouse column definition; a restart does not help while the cause persists. Correct the column (spec 08.04) or roll back the release.
  - **RTO**: 0 for the fixed shapes; unbounded for a new one until an operator notices the INFO flood.
  - **Test**: `SchemaCacheFailureModesTest.aliasColumnIsProbedOncePerDdlGenerationNotPerRecord()` (10,000 records across 50 batches: three catalog queries and no version bump), `CacheInvalidationProvenAbsentTest.testProofRetakenAfterDdlSticks()`.
  - **DEFECT**: a version-bump or writer-rebuild storm has no metric and no ERROR line, so the incident class that stalled production for hours would again be visible only as an INFO flood.

Summary: 4 failure modes, 2 DEFECT, 2 GAP.
