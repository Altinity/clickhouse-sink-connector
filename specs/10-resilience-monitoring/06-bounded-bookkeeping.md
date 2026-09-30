# Spec 10.06: Bounded Bookkeeping — No Scans of the Replicated Data (Invariant I14)

## 1. Executive Summary & Purpose
States and enforces Invariant I14: the connector's own bookkeeping — the version-floor seed at engine start, offset and mark reads and writes, schema-history and metadata reads, and anything that runs on the event thread or inside an engine-retry path — is bounded by the tables the connector owns and by the ClickHouse system catalog, never by the size of the data it replicates. It must not read, aggregate or scan a replicated (target) table. A replication engine that seeds itself from a scan of its own replica is unbounded, blocks the stream while it runs, and re-runs on every retry; MySQL replication does no such thing, and neither does this connector.

The rule was written after the version-floor seed of 2.11.0 (spec 02.02 §3.5 (2)) did exactly that: on a start with no `replica_version_high_water` row it read `max(_version)` over every `ReplacingMergeTree` table in the target databases. On a large replica the biggest tables (tens of billions of rows, hundreds of GiB per `_version` column) took over 40 s each — past the JDBC read timeout, after which the server finished the scan anyway — the seed ran on the Debezium event thread before the first delivery, and the engine's completion-callback retry re-ran it after every failure. One poison binlog event therefore produced a permanent retry loop that issued thousands of full-column scans per hour against the ClickHouse side while the connector's offset did not move.

---

## 2. Codebase Mapping on 2.11.0
- **Version-floor seed**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionHighWaterMark.java` — `seedFloor()` issues exactly two statements, both on the mark table (`ensureTable()`, `load()`), and returns the mark's floor or the connector clock plus `CLOCK_SEED_HEADROOM_MS`; there is no target scan. `targetDatabases(...)` remains for the primary-key backfill resume only.
- **Caller**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `seedVersionFloorFromDurableMark(...)`, run by `setupDebeziumEventCapture` on the initial start and on every engine re-setup; the same two statements each time.
- **Sanctioned reads over target tables** (marked `I14-scan-allowed`): `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/PrimaryKeyBackfill.java` — the count reconciliation and the leftover check of a primary-key rebuild (spec 06.09 §3.3.2), run on the backfill thread, cancellable, and only when the source's DDL asked for the rebuild.
- **Enforcement**: `scripts/validate_specs.py` — pass 8, `check_bookkeeping_scans`, run by the spec-governance CI workflow on every pull request.

---

## 3. Operational Specification

### 3.1 What bookkeeping may touch
Bookkeeping code — everything that is not the replicated write itself — may read and write:
1. the connector-owned tables in the offset database: the offset store, the schema history, `replica_version_high_water`, the error table (spec 09.03);
2. the ClickHouse system catalog (`system.tables`, `system.columns`, `system.databases`, `system.parts` metadata …) for schema and existence checks;
3. the connector's own clock and configuration.

It may **not** read a replicated table: no `max(...)`, `min(...)`, `count(...)`, `sum(...)`, `SELECT ... FINAL`, or any other full-table or full-column read over user data — on the event thread, on a startup thread, or in an engine-retry path. The cost of such a read is a function of the replica's size, which the connector does not control and which grows for as long as replication succeeds; a bookkeeping step whose cost grows with success is a defect.

### 3.2 The three properties every bookkeeping step must have
1. **Bounded**: a fixed, small number of statements against §3.1 sources, each answering from a key or a metadata lookup — never a scan.
2. **Retry-safe**: an engine re-setup (spec 10.02) repeats the step at the same bounded cost. A step that is cheap once but is re-issued by every retry is still unbounded in time; the previous floor seed was exactly that.
3. **Off the critical path or trivially fast**: nothing on the Debezium event thread waits on a read whose duration depends on data volume. Replication must not stand still for bookkeeping.

### 3.3 The version-floor seed under this rule
Spec 02.02 §3.5 (2): the floor is `floorDiv(V_max, 1e6) + 1` when a plausible mark row exists, otherwise the connector clock plus `CLOCK_SEED_HEADROOM_MS` (5 000 ms). Both are functions of the mark table and the clock only. The correctness argument that used to rest on `max(_version)` (the maximum is by definition above every stored version) now rests on time: every version a previous run assigned carries a past wall-clock instant in its timestamp field plus at most one second of counter carry, so a floor a few seconds past the present is above all of them (`Replication.VersionFloor.below_clock_seed`, `clock_restart_boundary`). A too-high floor only clamps the first rows until the source clock passes it; a too-low one is the defect, so the higher, scan-free choice is the safe one.

### 3.4 Sanctioned reads over replicated tables
A read over a replicated table is permitted only when all of the following hold:
1. it is part of a **data operation the source asked for** (a primary-key rebuild's count reconciliation, spec 06.09) or that an operator runs deliberately (the checksum tooling, spec 11.x — which is outside the connector process);
2. it runs **off the event thread** and is cancellable;
3. its cost is **proportional to that one operation**, not re-issued by engine retries;
4. the source line carries an `I14-scan-allowed: <spec reference>` comment within six lines above it, so the validator (§3.5) can list every such site and a reviewer can check the three conditions.

### 3.5 Enforcement
`scripts/validate_specs.py` pass 8 (`check_bookkeeping_scans`) scans every Java file under the main source trees for the two shapes that identified the defect — a string literal aggregating over a table (`SELECT max|min|count|sum|avg|uniq|any(`) whose `FROM` target is not a `system.*` table, and a string literal carrying a per-query execution cap (`SETTINGS max_execution_time`, the signature of a read its author knew could be heavy) — and fails unless an `I14-scan-allowed:` marker precedes the line within six lines. The check is deterministic, runs in the spec-governance CI workflow on every pull request, and has no allowlist file: the marker in the source is the only waiver, so the justification lives next to the query.

Unit level: `VersionHighWaterMarkTest.seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable()` pins the whole statement set of a mark-less seed (two statements, both naming the mark table, none naming `system.columns`, `system.tables`, `max(\`_version\`)` or `max_execution_time`); it goes red the moment a discovery or a target read is added back.

---

## 4. Invariants Preserved
- **Invariant I14 (Bounded Bookkeeping)**: this specification.
- **Invariant I2 (Deterministic Version Monotonicity)** at the restart boundary is kept without the scan: `Replication.VersionFloor.clock_restart_boundary` proves the clock seed continues above every pre-restart version whose timestamp field is a past instant.
- **Invariant I9 (Loud Failure)**: a mark table that cannot be read is logged at ERROR and the first handoff retries the mark before any row is written; the connector never compensates for a missing mark by scanning the targets.
- **Invariant I11 (Drop-in Upgrade Safety)**: the first start after an upgrade — the case the scan existed for — is covered by the clock seed (spec 02.06 §6.1).

---

## 5. Verification Criteria
- `VersionHighWaterMarkTest.seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable()` — §3.3 and §3.5: no mark row → floor is the clock plus head-room, exactly two statements, both on the mark table, none on a target.
- `VersionHighWaterMarkTest.seedFloorUsesThePersistedMark()` — a plausible mark row seeds `floorDiv(v, 1e6) + 1` with the same two statements.
- `VersionHighWaterMarkTest.implausiblePersistedMarkFallsBackToTheClock()` — an implausible mark is ignored in favour of the clock, still without a target read.
- `VersionHighWaterMarkTest.targetDatabasesFollowTheIncludeListAndOverrides()` — the database resolution kept for the backfill resume is unchanged.
- Lean: `Replication.VersionFloor.clockSeed`, `Replication.VersionFloor.below_clock_seed`, `Replication.VersionFloor.clock_restart_boundary`, `Replication.VersionFloor.clock_seed_example` — the clock seed's restart boundary, with no hypothesis about the targets.
- Validator: `scripts/validate_specs.py` pass 8 fails on any unmarked aggregate read over a non-system table or any `SETTINGS max_execution_time` literal under `*/src/main/**`; the two `PrimaryKeyBackfill` sites carry markers citing spec 06.09.
- Verification: an integration test that restarts the connector against a replica holding a multi-billion-row table and asserts no query touches that table during startup is not yet automated (gap); the statement-set pin above is the unit-level proxy.

---

## 6. Failure Modes & Recovery
The version-floor seed is two statements on the mark table on every start and every engine retry, so a failing bookkeeping step costs the same bounded amount each time and fails loudly. Two gaps remain: the schema history, a connector-owned table read in full on every engine start, grows with DDL volume; and the enforcement that keeps target scans out is a text heuristic that recognises only the shapes of the original defect.

- **FM-10.06a-1 The mark table cannot be read at start**
  - **Trigger**: ClickHouse unreachable, the offset database missing, or no privilege on `replica_version_high_water` when the engine starts.
  - **Behaviour**: `DebeziumChangeEventCapture.seedVersionFloorFromDurableMark` catches the failure; the start is unseeded (the floor is not raised) and the first handoff writes the mark before any row (`VersionHighWaterMark.cover`). No target table is read in either case. The version consequences of an unseeded start belong to spec 02.02.
  - **Detection**: ERROR `Could not establish the version high-water mark in {}; this start is unseeded and the first handoff will retry the mark before any row is written` at start.
  - **Blast radius**: see spec 02.02 section 6 for the version ordering across the boundary; bookkeeping cost unchanged.
  - **Recovery**: restore access to the offset database; the next start seeds normally.
  - **RTO**: one restart; unmeasured.
  - **Test**: `VersionHighWaterMarkTest.seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable()`, `VersionHighWaterMarkTest.implausiblePersistedMarkFallsBackToTheClock()`.

- **FM-10.06a-2 The mark cannot be written during replication**
  - **Trigger**: the offset database becomes read-only, full or unreachable while rows are being handed off.
  - **Behaviour**: `VersionHighWaterMark.cover` retries the INSERT 30 times, 2 s apart, on the Debezium thread (up to 60 s), then throws `IllegalStateException`; no row whose version is not covered is handed off; the engine stops and the retry budget of spec 10.04 applies (FM-10.04-3, or FM-10.04-1 when the cause carries a FATAL code such as 497).
  - **Detection**: ERROR `Could not persist the version high-water horizon {} to {} (attempt {}/{}); rows are held back until it is durable` every 2 s, then `The version high-water horizon {} could not be persisted to {} after {} attempts; refusing to hand off rows whose versions the next start could not order above. Restore ClickHouse write access to the offset database and restart.`
  - **Blast radius**: all replication pauses; nothing is handed off or committed without a durable mark.
  - **Recovery**: restore write access to the offset database; the engine retry (10 s) or a restart resumes.
  - **RTO**: <= 60 s of retries + 10 s engine retry after the fix; unmeasured.
  - **Test**: `VersionHighWaterMarkTest.horizonWriteFailureIsLoud()`, `VersionHighWaterMarkTest.horizonIsWrittenAheadOfHandoffAndReusedUntilExceeded()`.

- **FM-10.06a-3 The schema history grows with DDL volume and is replayed on every start**
  - **Trigger**: a source with frequent DDL (view refreshes, schema reloads, online schema-change tools) over months; every DDL is appended to the schema-history table unless `schema.history.internal.store.only.captured.tables.ddl=true`.
  - **Behaviour**: Debezium rebuilds its in-memory schema by reading the whole history table at every engine start, including every engine retry (spec 10.04 section 3.5 item 4 records the re-read on each retry); its cost is proportional to the number of DDL events since the last snapshot, not bounded by a key lookup (section 3.2 properties 1 and 2). Debezium's reader was not re-read in this run.
  - **Detection**: none; the start takes longer, visible only as the gap between the engine start and the first binlog event in the log.
  - **Blast radius**: every start and every engine retry is slower; a retry loop (spec 10.04 FM-10.04-3) multiplies it.
  - **Recovery**: rebuild the history from the current source schema: `sink-connector-client delete_schema_history`, then start with Debezium's schema-recovery snapshot mode (`recovery` in Debezium 3.x); valid only when no DDL was applied at the source after the committed offset.
  - **RTO**: start time grows without bound with history size; unmeasured.
  - **Test**: GAP: a test that seeds a schema history of N DDL events and asserts engine start time or statement count does not grow with N.
  - **DEFECT**: a connector-owned bookkeeping read (the schema history) grows with DDL volume and is repeated by every engine retry, contrary to section 3.2.

- **FM-10.06a-4 A new target scan evades the validator**
  - **Trigger**: a change adds a read over a replicated table in a shape pass 8 does not recognise: `SELECT argMax(...)`, `SELECT uniqExact(...)`, `SELECT x FROM t ORDER BY _version DESC LIMIT 1`, `SELECT ... FINAL`, or SQL assembled entirely at run time.
  - **Behaviour**: `scripts/validate_specs.py` pass 8 matches only `SELECT max|min|count|sum|avg|uniq|any(` literals and `SETTINGS max_execution_time`; other shapes pass review silently. At run time such a read behaves like the original defect (a scan on the event thread, re-run by every retry).
  - **Detection**: none at review time for those shapes; at run time, slow starts and heavy queries in ClickHouse's `system.query_log`.
  - **Blast radius**: as the original incident: stalled starts and scan load on the ClickHouse side.
  - **Recovery**: revert the change.
  - **RTO**: a release rollback; unmeasured.
  - **Test**: `VersionHighWaterMarkTest.seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable()` pins the seed's statement set (any new statement fails it); GAP: a statement-set pin for the other startup and retry paths (writer build, DDL drain, offset load).

Summary: 4 failure modes, 1 DEFECT, 2 GAP.
