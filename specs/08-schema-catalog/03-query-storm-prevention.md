# Spec 08.03: Metadata Query Storm Prevention & Proven-Absent Tracking

## 1. Executive Summary & Purpose
Specifies the proven-absent column tracking mechanism that prevents unbounded `system.columns` queries when incoming records carry fields not present in ClickHouse's writable column map.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/CacheInvalidationManager.java`
- **Methods**:
  - `markColumnProvenAbsent(String tableName, String columnName)`
  - `isColumnProvenAbsent(String tableName, String columnName)`
  - (there is no `clearProvenAbsentColumns`; proofs expire through the version stamp — §3.2 step 5 — and `clearAll()` exists for tests)
- **Caller**: `GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn(...)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Database probe (§3.3)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java` — `checkIfDatabaseExists(Connection, String)`, the `SHOW DATABASES` probe (`ClickHouseDbConstants.CHECK_DB_EXISTS_SQL`) run for a batch's database before it is written

---

## 3. Operational Specification

### 3.1 The Query Storm Defect
In high-throughput replication, if an incoming event carries a column that does not exist in the writable column map:
- Without tracking, every record triggers an expensive `system.columns` re-read.
- At thousands of events per second this generates millions of metadata queries per hour, exhausting ClickHouse connections and stalling replication. The only column that legitimately stays absent after a re-read is one ClickHouse owns (`MATERIALIZED` or `ALIAS`), which `getColumnsDataTypesForTable` filters out by design.

### 3.2 Proven-Absent Protocol
1. `refreshIfRecordHasUnknownColumn` encounters a record field missing from the cached column map.
2. It checks `isColumnProvenAbsent(tableKey, column)` (case-insensitive). If true, it returns immediately without issuing SQL.
3. Otherwise it re-reads the metadata once.
4. If the column is still absent from the writable map **and is an `ALIAS`**
   (`default_kind = 'ALIAS'`, i.e. ClickHouse owns it and no re-read will ever
   produce it), it calls `markColumnProvenAbsent(tableKey, column)`, which
   stores the column with the table's **current** `getVersion(tableKey)`. A
   `MATERIALIZED` column is converted instead (and fails the batch if the
   conversion fails), and a column that does not exist at all is added or
   fails the batch (Spec 08.04 §3.1/§3.3) — neither is ever marked
   proven-absent, in any outcome, because that would silently drop its value
   on every later record. `markColumnProvenAbsent` is reserved for `ALIAS`.
5. The proof is honoured only while `getVersion(tableKey)` still equals the stored version: a later `invalidateTable(tableKey)` or `invalidateAll()` changes the version, `isColumnProvenAbsent` then discards the stale proof and returns false, and the column is probed once against the new schema.
6. `null`/empty table or column names are inert for both methods.

### 3.3 A catalog probe reports a retry only when one happened
`DBMetadata.checkIfDatabaseExists` runs the `SHOW DATABASES` probe
(`ClickHouseDbConstants.CHECK_DB_EXISTS_SQL`) up to `MAX_RETRIES` times until
the database is seen, re-opening the pooled connection after a failure. The
probe is on the write path — it runs for a batch's database before the batch
is written — so on a ten-worker deployment it executes several times a second.

Defect: the loop logged `Retrying checkIfDatabaseExists, attempt {n}` at INFO
*before every attempt, including the first*. A healthy connector therefore
wrote a "Retrying" line for probes that never failed — measured at 218 lines in
20 minutes on one deployment, with `attempt 2` never appearing once — and an
operator grepping the log for retries found only noise.

Rule: the "Retrying" line is written only when `retryCount > 1`, i.e. after a
previous attempt failed, and it names the database and the budget
(`attempt {n} of {MAX_RETRIES}`). A failed attempt keeps its ERROR line
(`Retry attempt ({n}/{MAX_RETRIES}) failed`, with the exception). A first
attempt that succeeds logs nothing at INFO.

---

## 4. Invariants Preserved
- **System Stability**: Protects ClickHouse connection pools from metadata query storms.
- **No stale absence**: a column that becomes writable after a DDL is re-probed exactly once, so the proof can never hide a real column.
- **A log line means what it says**: a "Retrying" line is written only when a retry happened, so the retained log history is spent on events, not on the steady state (the same discipline as Spec 03.06 §3.3 for batch progress lines).

---

## 5. Verification Criteria
- `CacheInvalidationProvenAbsentTest.testUnprobedColumnIsNotProvenAbsent()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentColumnIsRemembered()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentIsCaseInsensitive()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentIsScopedToItsTable()`, `CacheInvalidationProvenAbsentTest.testDdlInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testInvalidateAllInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testProofRetakenAfterDdlSticks()`, `CacheInvalidationProvenAbsentTest.testNullAndEmptyInputsAreInert()`.
- Verification: a benchmark asserting zero additional `system.columns` queries under a sustained stream of records with extra fields is not yet covered by an automated test (gap).
- `DBMetadataDatabaseExistsLogTest.firstAttemptThatSucceedsLogsNoRetry()` — §3.3: a probe answered on the first attempt returns true and writes no "Retrying" line at any level (pre-fix code writes one INFO line per call).
- `DBMetadataDatabaseExistsLogTest.retryAfterFailureIsLoggedOnce()` — §3.3: a probe whose first attempt throws and whose second succeeds returns true and writes exactly one "Retrying" INFO line, naming the database and `attempt 2 of` the budget, after the ERROR line for the failure.

---

## 6. Failure Modes & Recovery
The probe costs one catalog read per unknown column per DDL generation, independent of record and batch count, and touches only `system.columns`; the known storm (spec 08.02) is closed and pinned. The open failure is the probe's own failure: a re-read that returns nothing is treated as "cache consistent", and the batch is written without the column.

- **The source carries a column ClickHouse computes (ALIAS)**
  - **Trigger**: a table whose ClickHouse definition has an ALIAS column that the source also sends.
  - **Behaviour**: the first record probes once (the ALIAS/MATERIALIZED list, the column listing, one `default_kind` lookup), `markColumnProvenAbsent` stores the proof at the table's current version, and every later record and batch skips it without SQL and without a version bump. A DDL on the table retires the proof and costs exactly one more probe.
  - **Detection**: the stale-cache probe logs DEBUG `Cached schema for {}.{} does not contain column '{}' carried by the incoming record. Re-reading table metadata before building the INSERT.` (not a WARN: the probe only asks whether the cache is stale, and an ALIAS is not stale). An INFO is logged when the re-read resolves the column. The ALIAS namesake is WARNed once per table and column per process by `warnAliasNamesake` (spec 08.04 section 3.1).
  - **Blast radius**: the source value of the ALIAS column is not replicated, because ClickHouse computes the column and never stores the source's value. This is reported, not hidden, and the record's value is ignored by design.
  - **Recovery**: none needed.
  - **RTO**: 0.
  - **Test**: `SchemaCacheFailureModesTest.aliasColumnIsProbedOncePerDdlGenerationNotPerRecord()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentColumnIsRemembered()`, `CacheInvalidationProvenAbsentTest.testDdlInvalidatesTheProof()`.

- **A column the table lacks, retried with backoff**
  - **Trigger**: the record carries a column that is missing from ClickHouse, or MATERIALIZED and not convertible (spec 08.04).
  - **Behaviour**: the batch attempt probes once and throws `MissingTargetColumnException`; nothing is proven absent, so each retry (spec 10.02) re-probes once. The probe load is therefore about three catalog queries per retry per table, at most one retry per `batch.retry.backoff.max.ms` (30 s) once the backoff saturates.
  - **Detection**: as spec 08.04.
  - **Blast radius**: the table's worker stalls (spec 10.02 section 3.4); catalog load stays constant.
  - **Recovery**: fix the column in ClickHouse; the next retry sees it and writes the same batch.
  - **RTO**: <= 30 s after the fix; unmeasured.
  - **Test**: `SchemaCacheFailureModesTest.missingColumnFailsTheBatchAfterOneProbe()` (500 records, three queries; heals once the column is added).

- **The database probe fails**
  - **Trigger**: `SHOW DATABASES` fails while a writer is built (ClickHouse restarting, a dropped connection).
  - **Behaviour**: `DBMetadata.checkIfDatabaseExists` retries up to `MAX_RETRIES`, re-opening the pooled connection after a failure, and returns false when the budget is spent; the caller then attempts `CREATE DATABASE` (spec 08.01 for what follows).
  - **Detection**: ERROR `Retry attempt ({}/{}) failed` with the exception, then INFO `Retrying checkIfDatabaseExists for database {}, attempt {} of {}`.
  - **Blast radius**: the writer build is delayed; see spec 08.01 (the CREATE DATABASE path and the partial-init path).
  - **Recovery**: self-heals when ClickHouse answers.
  - **RTO**: as spec 08.01.
  - **Test**: `DBMetadataDatabaseExistsLogTest.retryAfterFailureIsLoggedOnce()`, `DBMetadataDatabaseExistsLogTest.firstAttemptThatSucceedsLogsNoRetry()`.

- **The stale-cache re-read fails and the column is dropped**
  - **Trigger**: a DDL added a column in ClickHouse that the writer's cached map predates, and the probe's re-read fails -- every listing attempt throws (202 TOO_MANY_SIMULTANEOUS_QUERIES, timeouts) or returns no rows -- while the INSERT that follows succeeds.
  - **Behaviour** (required; was a known problem on 2.11.1): a failed or empty re-read never keeps the cached map. `refreshIfRecordHasUnknownColumn` bumps the table's cache version and throws `StaleSchemaCacheException`, with the read failure as its cause, so the batch is retried against a rebuilt writer (spec 04.06, failed staleness re-read). The memo records only verdicts that were reached, so no INSERT is built without the column the record carries.
  - **Detection** (required): ERROR `Exception retrieving Column Metadata, retrying (<n>/10)` per attempt when the read fails, then ERROR `ClickHouseBatchRunnable exception` carrying `StaleSchemaCacheException`. No WARN claims the cached map is kept.
  - **Blast radius**: none for stored data. The batch is held and retried until metadata is readable.
  - **Recovery**: none required once ClickHouse answers the read; the retry rebuilds the writer and re-reads.
  - **RTO**: the duration of the ClickHouse metadata outage plus one backoff interval.
  - **Test**: `SchemaCacheFailureModesTest.failedReReadNeverDropsTheColumn()` (enabled; the batch fails loudly and nothing is grouped under the stale map). Also `GroupInsertQueryStaleCheckFailureTest.failedMetadataReReadDoesNotDropTheUnknownColumn()`.
  - **Base behaviour (2.11.1)**: a failed stale-cache re-read is indistinguishable from a consistent cache, so the batch is written without a column ClickHouse has and the record carries; the probe must fail the batch instead.

Summary: 4 failure modes; the 2.11.1 base defect in this section is fixed by this change; 0 open items.
