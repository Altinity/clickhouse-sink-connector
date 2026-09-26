# Spec 02.01: 64-Bit Monotonic Version Formula & Bit Allocation

## 1. Executive Summary & Purpose
Specifies how the lightweight connector derives the 64-bit `_version` value (bound as `UInt64` in ClickHouse) used for `ReplacingMergeTree` deduplication: which ordering key is chosen, which timestamp feeds each path, the arithmetic of the sequence-number formula, its digit budget, and the inherited carry in that budget.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`; `static synchronized VersionAssignment nextVersionAssignment(long recordTs, SourcePosition position)` — the same assignment, also returning the clamped `effectiveTs` (`VersionAssignment.effectiveTs`) that the dispatch loop stores on the record as `versionTs`.
- **Constants**: `SEQUENCE_START = 1000000000L` (one billion), `SEQUENCE_START_INITIAL = 500000000L` (five hundred million), multiplier `1_000_000L` (inline in the return statement)
- **Precedence**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `calculateVersion(boolean useSnowflakeId)`, field `versionTs` (`setVersionTs` / `getVersionTs`, default `0`); `snowflake.id` defaults to `true` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`
- **GTID parsing**: `ClickHouseStruct.setAdditionalMetaData` (`source.gtid` → `gtid`)
- **History mode**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/ReplicationHistoryHandler.java` (`resolveVersion`, `buildUpdateQueryParams`), `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java` (`getValueFromStruct`, `createHistoryTableSyntax`) — §3.5

---

## 3. Version Selection and Formula

### 3.1 Precedence (`ClickHouseStruct.calculateVersion`)
1. **`gtid` present** — `SnowFlakeId.generate(versionTs > 0 ? versionTs : ts_ms, gtid, false)` when `snowflake.id` is true (the default), else the raw gtid. `versionTs` is the **clamped `effectiveTs`** of the sequence (spec 02.02 §3): the lightweight dispatch loop calls `chStruct.setVersionTs(effectiveTs)` next to `setSequenceNumber`, so under GTID the timestamp field of the snowflake is the commit-order-floored timestamp, not the raw statement time. On the Kafka Connect path `versionTs` is never set and the raw `source.ts_ms` is used, as before. The sequence number itself is not used on this path, but the floor it was computed with is.
2. **`sequenceNumber` present** (lightweight streaming path, no GTID) — the value returned by `nextSequenceNumber`, i.e. the formula in §3.2.
3. **`lsn` present** (PostgreSQL) — the LSN.
4. **otherwise, `ts_ms > 0`** — `SnowFlakeId.generate(ts_ms, kafkaOffset, false)` (spec 02.05).

Why path 1 needs the floored timestamp: `SnowFlakeId.generate` places 41 bits of the timestamp above 22 bits of the GTID transaction number, so the timestamp dominates. With the raw `source.ts_ms` (statement time, spec 01.04 §3) a long transaction that commits after a shorter one carries the OLDER timestamp and lost under `FINAL` although its commit is the newer state — the same late-commit inversion the floor prevents on path 2. Feeding `effectiveTs` gives the late commit a timestamp field at least that of every earlier first delivery; two commits clamped to the same millisecond are then ordered by their GTID transaction numbers, which increase in commit order on one source. The encoding of the snowflake is unchanged, so GTID-path versions stay in the 2.8.0 domain.

**GTID text formats.** `source.gtid` is `uuid:n` for a classic MySQL GTID and `uuid:tag:n` for a tagged GTID (MySQL 8.3+). `setAdditionalMetaData` takes the transaction number from the **last** colon-separated segment of any value with at least two segments, so both forms yield the same `gtid`. Before this rule only the two-segment form was parsed (`split(":").length == 2`) and a tagged GTID left `gtid` unset: the record silently took path 2 and ranked in the sequence domain (~$1.8 \times 10^{18}$) while its neighbours ranked in the snowflake domain (~$2 \times 10^{18}$), so every tagged transaction lost to the untagged ones around it. A value with no colon (MariaDB `domain-server-seq`) still leaves `gtid` unset and takes path 2.

Sections §3.2–§3.4 concern path 2 only; §3.5 concerns history mode.

### 3.1.1 Same-millisecond ties on the GTID path (V9)
The snowflake's low 22 bits hold the GTID transaction number, which is one number per **transaction**: every row event of one transaction whose clamped timestamp falls in the same millisecond receives the **same** `_version`. Two writes to one key inside one transaction (an UPDATE that touches the row twice; an INSERT followed by a relocating UPDATE, spec 05.02) therefore tie on version and are resolved by the `ReplacingMergeTree` tie rule — the row inserted **later** wins (`Replication.Proofs.tombstone_wins_version_tie`; `maxStep` uses `>=`). That rule is honoured only when the tied rows are written in binlog order, which the connector guarantees within one insert batch and, per table, across batches on one worker (spec 09.01). `PreparedStatementExecutor` splits a group into insert batches with `Lists.partition(records, buffer.max.records)`, in binlog order and executed sequentially on one connection, so a tie straddling a partition boundary is still applied in order; two tied rows must never be routed to different workers, which per-table hash routing (spec 03.02) already prevents. The GTID path does not increment a per-row counter, so within-transaction ordering under GTID rests on the tie rule alone.

### 3.2 The sequence-number formula
$$V = \text{effectiveTs} \times 1{,}000{,}000 + \text{sequenceNumber}$$
- `effectiveTs`: the record's source timestamp in milliseconds, clamped up to the run's high-water floor when the record is a first delivery (spec 02.02).
- `sequenceNumber`: the intra-window counter (spec 02.03), seeded at `SEQUENCE_START_INITIAL` for the first record after start/resume and at `SEQUENCE_START` after every counter reset.

### 3.3 Digit budget as it actually is
- `effectiveTs` is 13 decimal digits in 2026 ($\approx 1.79 \times 10^{12}$); multiplied by $10^6$ it occupies $\approx 1.79 \times 10^{18}$, within the signed 64-bit range until the year 2262.
- The multiplier leaves **six** decimal digits below the timestamp for the counter, but both seeds are **ten** digits. The addition therefore **carries into the timestamp field**: `SEQUENCE_START` adds 1,000 ms worth of timestamp and `SEQUENCE_START_INITIAL` adds 500 ms worth. The formula does not partition the integer into a timestamp part and a counter part; it is a sum in which the counter shifts the effective time by roughly one second.

### 3.4 What feeds the formula across a restart
The floor `effectiveTs` is clamped to is seeded at engine start from the durable high-water mark of the versions already handed to the writers (spec 02.02 §3.5), so the first record of a new run is versioned above the last record of the previous one whatever its raw timestamp. The formula itself is not involved in that guarantee; it is entirely a property of the floor.

### 3.5 History mode (`replication.history.enable`, V13)
History mode writes the SCD2 tables of spec 04.04 and derives its own versions; they are outside the sequence of §3.2 and are recorded here so the gaps are visible:
- (a) **Composite primary keys**: `ReplicationHistoryHandler.buildUpdateQueryParams` collects **every** primary-key column of the record with its value (`UpdateQueryParams.getPrimaryKey()`, in key order), and `QueryFormatter.getInsertQueryForUpdate` / `getInsertQueryForDelete` close the previous history row with the conjunction `` `c1`=v1 AND `c2`=v2 ... `` (`QueryFormatter.formatPrimaryKeyPredicate`, each value formatted for its column type) in both table-reading `SELECT`s. Before this rule only the first column was used (`getPrimaryKey().get(0)`), so on a table whose primary key has several columns every history row sharing that first column's value was closed together. A record without a primary key cannot be closed and is refused with `IllegalStateException` naming the table (I9).
- (b) **Version**: every row an event writes into an SCD2 table — the INSERT row, close row, after row, key-change marker, delete marker, bulk-close rows (spec 12.03 §3.5) — carries the **history version** `ReplicationHistoryHandler.historyVersion(record)`: the snowflake encoding `SnowFlakeId.generate(ts_ms, d, false)` of the ordering key of the record's standard version (`(versionTs | ts_ms, gtid)` on the GTID path; `(effectiveTs, counter)` decoded from the sequence of §3.2; the source millisecond with the low 22 bits of the standard version otherwise). The encoding is strictly monotone in `(ts_ms, d)`, so the history rows order exactly as the floor-clamped, monotonic standard version of §3.1–§3.4 / spec 02.02 does — on the GTID path with `snowflake.id=true` it **is** the standard version. `ReplicationHistoryHandler.resolveVersion` derives the standard version lazily with the same `snowflake.id` flag as the field mapper when the statement is built before binding, refuses a record whose version cannot be derived (spec 02.05 §3.2), and returns its history version; `PreparedStatementFieldMapper.handleVersionColumn` binds the history version in history mode. The domain is the one releases up to 2.11.0 already wrote for the UPDATE and DELETE rows of a history table, which is what makes an upgrade and a downgrade on the same tables safe without migration (spec 12.03 §3.5.1, S10): binding the raw sequence number instead would have left every key a 2.11.0 connector had updated or deleted frozen behind its `≈ 2.1·10^18` legacy row until 2032. There is one version domain per table and one version per event; the previous revision's `SnowFlakeId.generate(ts_ms, gtid, false)` plus `V+1` (raw statement time and raw GTID, ignoring the sequence number and the floor, colliding within a millisecond without GTIDs and with the next event's version on the sequence path) is gone (spec 12.03 §6, G-12.03-4). The **audit table** of spec 12.04 still versions its rows with `SnowFlakeId.generate(ts_ms, gtid, false)` (`BinLogHistory.getValueFromStruct` `VERSION_COLUMN`); there the version only decides between redelivered duplicates of one binlog coordinate (12.04 §3.2).
- (c) **Operation code**: `BinLogHistory.getValueFromStruct` writes `_operation` as the enum **name** (`UPDATE`) while `QueryFormatter.getInsertQueryForUpdate` writes the single-letter code (`'U'`, via `cdcOperation.getOperation()`); consumers must accept both spellings. **Gap, not fixed here.**
- (d) **Kafka Connect path row identity**: the history table's sorting key is `(server_id, logfile, position, sequence, _time)` (`BinLogHistory.createHistoryTableSyntax`). On the lightweight path `sequence` is the unique sequence number; on the Kafka Connect path no sequence number is assigned, so `BinLogHistory.getValueFromStruct` writes the record's **row index within its event** (`ClickHouseStruct.getRow()`) into `sequence` instead of the `-1` sentinel, which is unique per `(logfile, position)`. The rows of one multi-row statement therefore keep distinct sorting keys; before this rule they shared one key and collapsed into a single row under `ReplacingMergeTree`.

---

## 4. Inherited behaviour (2.8.0 formula, preserved for upgrade/downgrade compatibility)

> **Compatibility constraint (governing rule).** The emitted `_version` domain — `ts_ms × 1_000_000 + counter` with the 2.8.0 seeds — is the contract shared with 2.8.0, 2.9.1 and 2.10.x. It MUST NOT change: a version written by any of those releases must rank consistently against one written by 2.11.0 in BOTH directions (upgrade and downgrade). The restart carry described here is therefore inherited 2.8.0 behaviour that is preserved deliberately; any improvement is confined to WHERE the restart floor starts (a persisted last-emitted version — or, on a start that has none, the connector clock; never a scan of the targets, Invariant I14 / spec 10.06) and must ship with an explicit upgrade/downgrade matrix against 2.8.0, 2.9.1 and 2.10.x.

Because of the carry in §3.3, a run that starts with the floor at `0` versions a genuinely newer event (counter seeded at 500m) **below** an older pre-restart event (counter in the 1000m range):

```
older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
```

The `diff > 1` reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies. The arithmetic is deliberately kept (the constraint above) and remains pinned by `SequenceSeedOverflowTest` over the real constants. What 2.11.0 changes is the floor: seeded from the high-water mark, the newer event is clamped to `T + 1001` and versioned at `(T + 1001) * 1e6 + 500_000_001`, above the older one (spec 02.02 §3.5; `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()`, which asserted the inversion before the fix). `ReplaySafetyTest` covers engine identity and the auto-created engine family, not this arithmetic. The upgrade/downgrade matrix is in spec 02.06 §6.1.

The GTID path no longer bypasses the floor: §3.1 feeds the clamped `effectiveTs` into the snowflake (spec 01.04 §4.2).

---

## 5. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: holds within a single run on the sequence-number path (consecutive first deliveries receive strictly ascending versions, spec 02.02 §3.3), across a restart through the seeded floor (§3.4, spec 02.02 §3.5), and on the GTID path through the floored timestamp field (§3.1; same-millisecond ties per §3.1.1). SCD2 history rows carry the strictly monotone snowflake encoding of this same version (§3.5 b, spec 12.03 §3.5.1), so the invariant covers them — one version domain per history table, shared with the rows earlier releases wrote.
- **Invariant I11 (Drop-in Upgrade Safety)**: the formula, seeds, multiplier and snowflake encoding are unchanged (§4).

---

## 6. Verification Criteria
- `SourceTsVersionAnchorTest.staysInTwoEightZeroDomain()` — the emitted value is `ts_ms * 1_000_000 + counter`.
- `SequenceSeedOverflowTest.testSeedDoesNotFitBeneathTheMultiplier()`, `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()`, `SequenceSeedOverflowTest.testResumeSeedStaysBelowTheNormalSeed()` — §3.3 / §4 pinned against the real constants (the unseeded arithmetic).
- `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()` — §4's 1 ms example through `nextSequenceNumber` and `seedVersionFloor`: the newer event wins; `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` — §3.4 on a lagging source.
- `GtidLateCommitVersionTest.lateCommittingTransactionOutranksEarlierCommitUnderGtid()` — §3.1 path 1: `T2 (ts 110, gtid 200, pos 100)` then `T1 (ts 100, gtid 201, pos 200)` yields `version(T1) > version(T2)`; fails with the raw `ts_ms` (pre-fix).
- `GtidLateCommitVersionTest.gtidVersionStaysInTheSnowflakeDomain()` — the GTID version equals `SnowFlakeId.generate(effectiveTs, gtid, false)`: same encoding, floored timestamp.
- `ClickHouseStructTest.versionTsFallsBackToTsMsWhenUnset()` — the Kafka Connect path (no `versionTs`) is unchanged.
- `ClickHouseStructTest.testGtid()` — the classic `uuid:n` form is parsed; `ClickHouseStructTest.taggedGtidIsParsed()` — `uuid:tag:42` yields `gtid = 42` and a colon-less value leaves it unset.
- `VersionFallbackWithoutGtidTest.sequenceNumberVersionUnchanged()`, `VersionFallbackWithoutGtidTest.gtidSnowflakeVersionUnchanged()`, `VersionFallbackWithoutGtidTest.gtidPlainVersionUnchanged()` — the precedence in §3.1.
- `ReplicationHistoryHandlerTest.compositePrimaryKeyClosesOnlyTheMatchingRow()`, `QueryFormatterTest.updateAndDeleteQueriesUseEveryPrimaryKeyColumn()` — §3.5 (a): both table-reading `SELECT`s carry the full composite predicate; the first-column-only predicate is gone.
- `BinLogHistoryTest.kafkaPathUsesRowIndexAsSequence()` — §3.5 (d): a record without a sequence number binds its row index; one with a sequence number binds it unchanged.
- Lean: `Replication.VersionFloor.restart_boundary` (§3.4); `Replication.Proofs.tombstone_wins_version_tie` (the tie rule of §3.1.1). `formal_specs/lean/Replication/Engine.lean` versions the convergence model by stream ordinal `liveVersion i = 2 * i`; the formula in §3.2 is modelled only at the restart boundary (`Replication.VersionFloor`), not for within-run monotonicity.
- `ReplicationHistoryHandlerTest.everyHistoryRowCarriesTheEventsHistoryVersion()`, `ReplicationHistoryHandlerTest.underivableVersionIsRefused()`, `QueryFormatterTest.allRowsOfOneEventShareOneVersion()` — §3.5 (b): the history statements embed `historyVersion(record)` in every row and refuse an underivable version.
- `ReplicationHistoryVersionDomainTest.sequenceSourceEncodesItsMillisecondSlotAndCounter()`, `ReplicationHistoryVersionDomainTest.sequenceEncodingIsStrictlyMonotoneLikeTheSequence()`, `ReplicationHistoryVersionDomainTest.gtidSourceIsTheStandardSnowflakeVersionItself()`, `ReplicationHistoryVersionDomainTest.legacyOpenRowIsSupersededOnUpgrade()`, `ReplicationHistoryVersionDomainTest.fixedOpenRowIsSupersededOnDowngrade()` — §3.5 (b): the encoding on each path, its monotonicity, and the ranking against rows written by 2.11.0 in both directions (Lean: `Replication.History.snowflake_encode_strict_mono`, `Replication.History.legacy_open_row_superseded_by_later_event`, `Replication.History.fixed_open_row_superseded_by_later_legacy_event`).
- Verification: the GTID same-millisecond tie across an insert-batch boundary (§3.1.1) is not yet covered by an automated test (gap). The `_operation` spelling mismatch (§3.5 c) is a recorded gap without a test.
