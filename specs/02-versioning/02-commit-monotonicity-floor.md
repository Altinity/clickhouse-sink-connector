# Spec 02.02: Commit Monotonicity Floor & Late-Commit Inversion Prevention

## 1. Executive Summary & Purpose
Specifies the high-water floor (`sequenceMaxSourceTs`) that gives events committing later in the MySQL binary log a timestamp component no lower than earlier commits, even when MySQL statement timestamps are non-monotonic: within one connector run by clamping, and **across a restart** by seeding the floor from a durable high-water mark of the versions already handed to the writers. It also states which records may touch the sequence state (rows and DDL — never heartbeats or transaction metadata) and the boundaries of the guarantee as implemented on 2.11.0.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**:
  - `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)` — the assignment (§3); delegates to `nextVersionAssignment`, which also returns the clamped `effectiveTs` for the GTID path (spec 02.01 §3.1).
  - `static synchronized long seedVersionFloor(long highWaterVersion)` — restart seeding from a high-water version (§3.5); raises `sequenceMaxSourceTs` to `Math.floorDiv(highWaterVersion, 1_000_000L) + 1`, never lowers it, ignores `<= 0`, returns the floor in force.
  - `seedVersionFloorFromDurableMark(Properties, ClickHouseSinkConnectorConfig)` — engine-start seeding (§3.5 (2)): `VersionHighWaterMark.seedFloor()` then `raiseVersionFloor(seed.floorMs)`. Exactly two statements, both on the mark table; no target table is read (Invariant I14, spec 10.06).
  - `handleChangeEventBatch` — decides which records enter the sequence (§3.2): DDL and row records do, control records (`isControlRecord`) do not.
- **Durable high-water mark**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionHighWaterMark.java` — table `replica_version_high_water` in the offset database (spec 09.03 §3.4), written ahead of handoff (§3.5), read at engine start by `seedFloor()`; a start without a mark is seeded from the connector clock plus `CLOCK_SEED_HEADROOM_MS` (5 000 ms) — never from a scan of the targets (Invariant I14, spec 10.06).
- **Fields** (all `public static`, process-wide; the floor is re-established from the durable mark at engine start, the other three start empty):
  - `public static long sequenceMaxSourceTs = 0L`
  - `public static SourcePosition sequenceHighWaterPosition = null`
  - `public static long sequenceAnchorTs = 0L`
  - `public static long sequenceNumber = SEQUENCE_START`

---

## 3. The Algorithm as Implemented

```
nextSequenceNumber(recordTs, position):
  if sequenceAnchorTs == 0:                       # first call after start/resume
      sequenceAnchorTs = recordTs
      sequenceNumber   = SEQUENCE_START_INITIAL   # 500m seed (spec 02.04)
  effectiveTs = recordTs
  if position != null and (sequenceHighWaterPosition == null
                           or not position.sameLog(sequenceHighWaterPosition)   # log basename changed (spec 01.02 §3.1.1)
                           or position > sequenceHighWaterPosition):
      sequenceHighWaterPosition = position        # first delivery
      if effectiveTs < sequenceMaxSourceTs:
          effectiveTs = sequenceMaxSourceTs       # clamp up to the floor
  if effectiveTs > sequenceMaxSourceTs:
      sequenceMaxSourceTs = effectiveTs           # raised by every record that enters
  diff = (int)((effectiveTs - sequenceAnchorTs) / 1000)
  if diff > 1:                                    # >= 2000 ms past the anchor (spec 02.03)
      sequenceNumber   = SEQUENCE_START
      sequenceAnchorTs = effectiveTs
  else:
      sequenceNumber += 1
  return effectiveTs * 1_000_000 + sequenceNumber
```

The formula, the seeds and the multiplier are the 2.8.0 contract and are unchanged (§5). What this specification governs is what feeds the formula: which records enter it (§3.2) and where the floor starts after a restart (§3.5).

### 3.1 Who is clamped
Only a **first delivery** — a record with a position strictly above `sequenceHighWaterPosition`, or whose position belongs to a differently named binary log than the mark (`!position.sameLog(mark)`: the log basename changed, spec 01.02 §3.1.1, so the two positions are not comparable and the mark is reset to the new log) — has its timestamp clamped up to the floor. A record at or below the mark (redelivery) or a row without a position (a source without log coordinates) keeps `effectiveTs = recordTs`. After a restart the mark is empty, so the first positioned record of the run — and, in log order, every record after it — is a first delivery and is clamped (spec 02.04 §3.2).

### 3.2 Who enters the sequence, and who raises the floor
`handleChangeEventBatch` calls `nextSequenceNumber` for **row records and DDL records only**. A **control record** — a heartbeat or a transaction-metadata record, recognised by `isControlRecord` (spec 01.06 §3.1) — produces no ClickHouse row, needs no `_version`, and is **not** run through the sequence: it leaves `sequenceMaxSourceTs`, `sequenceHighWaterPosition`, `sequenceAnchorTs` and `sequenceNumber` exactly as they were.

The reason is the timestamp such records carry. A heartbeat's only timestamp is the envelope `ts_ms` — the **connector's wall clock**, not a source commit time. Before this rule every heartbeat went through `nextSequenceNumber(envelopeTs, null)` and raised the floor to the connector clock: on a source lagging by $L$ seconds every following row was clamped $L$ seconds into the future of the source clock. Within one run that was harmless; across a restart it was the largest part of the inversion window (§3.5), and it also made the emitted versions depend on the connector host's clock (spec 01.04 §3.2, clock skew).

Among the records that do enter the sequence, **every** one raises `sequenceMaxSourceTs` when its `effectiveTs` exceeds it — first delivery, redelivery or positionless row. A record that moves the anchor and resets the counter must move the floor with it, otherwise the next late first delivery would again be versioned in its own older second. A redelivery cannot lower the floor; a redelivery whose timestamp exceeds the floor raises it.

### 3.3 What holds within one run
Let $E_1$ precede $E_2$ in the binlog and both be first deliveries in the same process lifetime. When $E_1$ is versioned the floor becomes $T_1 \ge \text{ts}(E_1)$; $E_2$ gets $T_2 = \max(\text{ts}(E_2), \text{floor}) \ge T_1$. If $T_2 = T_1$ the counter has only incremented, so $V(E_2) > V(E_1)$. If a reset happens between them ($T_2 \ge \text{anchor} + 2000$, and the anchor is never above $T_1$) then $T_2 \ge T_1 + 1$ and $V(E_2) - V(E_1) \ge 1{,}000{,}000 + 1{,}000{,}000{,}000 - c_1$, where $c_1$ is $E_1$'s counter. This is positive as long as fewer than $1{,}000{,}000$ records were versioned in $E_1$'s counter window (counters start at $10^9$, or $5 \cdot 10^8$ after a start, and grow by one per record); a window of two source seconds holding a million rows is beyond the connector's throughput, but the bound is stated because it is the only condition. First deliveries therefore receive strictly increasing versions within one run.

### 3.4 What does not hold within one run
- **On the GTID path before 2.11.0**: `ClickHouseStruct.calculateVersion` preferred the raw `source.ts_ms`; the floor did not apply there. 2.11.0 feeds the clamped `effectiveTs` into the GTID version instead (spec 02.01 §3.1), so the floor governs both paths.
- **In-run redeliveries** (engine retry without a restart) are deliberately not clamped (§3.1); they carry the same data as the stored copy they lose to (spec 02.04 §3.3).

### 3.5 Across a restart: the floor is seeded from a durable high-water mark
The four statics are process-local, so without help a restart put the floor back at `0`: the first rows of the new run were versioned in their own source second, which on a lagging source lies **below** the second the previous run had been clamped to. Rows the previous run wrote for the same keys then kept winning under `FINAL` until a later update arrived — silent, with matching row counts. The window was not the ~1 ms of spec 02.01 §4's arithmetic example but **the replication lag plus the seed carry** (about 0.5 s): with a 30 s lag and the floor pinned by heartbeats at the connector clock $W$, the last pre-restart row carried $W \cdot 10^6 + 10^9 + k$ while the first post-restart row carried $(W - 25000) \cdot 10^6 + 5 \cdot 10^8 + 1$.

2.11.0 closes the window with two rules:

1. **Write-ahead high-water mark.** `VersionHighWaterMark.cover(v)` is called on the dispatch thread for every version the sequence assigns to a row, **before** that row is handed to the writers. It keeps a persisted **horizon** $H$ — a version such that every version ever handed off is $\le H$ — in the table `replica_version_high_water` next to the offset table (spec 09.03 §3.4). When an assigned version exceeds the horizon, the horizon is moved to `v + 5_000 * 1_000_000` (five source seconds ahead) and written synchronously before the row proceeds; while versions stay below the horizon nothing is written, so the table receives at most one row per ~5 s of source time under load and none on an idle source. A write that fails is retried, and if it cannot be made durable the batch fails loudly and the engine stops (I9): no row is ever handed off with a version above the durable horizon. The mark is written **ahead of handoff, not on acknowledgement**, because a unit that a worker has already written can still be parked behind an older unacknowledged sequence (spec 09.01) — its rows are in ClickHouse before any acknowledgement exists, so an acknowledgement-time mark would not cover them.
2. **Seeding at engine start.** `setupDebeziumEventCapture` reads the mark ($V_{max}$) and calls `seedVersionFloor(V_max)`, which sets `sequenceMaxSourceTs = floorDiv(V_max, 10^6) + 1` (never lowering it). Because $V_{max} < (\lfloor V_{max}/10^6 \rfloor + 1) \cdot 10^6$ and every first delivery is versioned at least `floor * 1_000_000 + 1`, **every first delivery of the new run ranks strictly above every version the previous run assigned** — whatever the lag, the seeds, or the clock skew between MySQL and the connector host (spec 01.04 §3.2). The anchor and counter are left to their start-of-run rules (spec 02.04 §3.1); they are not needed for the bound.
   When no mark exists yet — the first start after an upgrade from 2.8.0 / 2.9.1 / 2.10.x, or a ClickHouse replica that never saw this connector's writes — the seed is the **connector clock plus `CLOCK_SEED_HEADROOM_MS` (5 000 ms)**: `sequenceMaxSourceTs = now + 5000`. Every version a previous run assigned decodes to an instant of the past: the timestamp field of `ts * 10^6 + counter` is a source statement time, an envelope (processing) time or a heartbeat-pinned connector clock — each a wall-clock instant at or before the restart — plus at most one second of counter carry (spec 02.01 §3.3); the snowflake domain likewise carries a past millisecond in its timestamp field. A floor five seconds past the present is therefore at or above all of them (`Replication.VersionFloor.below_clock_seed`, `clock_restart_boundary`), and the same theorem as for the mark applies. This is the one seed whose guarantee has a precondition: the connector host's clock must not be more than the head-room behind the clock that stamped the previous run's versions (the source host's, or the previous connector host's). NTP keeps that skew at milliseconds; a host that is seconds behind would let a pre-restart row stamped within the skew window keep winning under `FINAL` for the same key, so the seed is logged at WARN with the floor it chose and the operator can compare it with the source clock. The head-room is not a substitute for a mark: the mark is written from the first handoff on, so this precondition applies to one start per deployment. A too-high floor only delays the source clock catching up (the first rows are clamped to it), a too-low one is the defect, so the higher choice is the safe side. The seed is logged at WARN because it is the one start whose floor did not come from the mark.
   **The seed never reads a target table** (Invariant I14, spec 10.06). The previous design read `max(_version)` over every `ReplacingMergeTree` table with a `_version` column in the target databases, decoding each value in both version domains. On a production replica that was a full-column scan of every replicated table — tables of 30 billion rows took over 40 s each, past the JDBC read timeout, and the server finished them anyway — issued on the event thread ahead of the first delivery and **re-issued by every engine retry** (rule 3), so a single poison binlog event turned the seed into a permanent load of thousands of scans per hour on the ClickHouse side while replication stood still. A replication engine's bookkeeping must be bounded by the bookkeeping, never by the size of the data it replicates; MySQL replication seeds nothing from a scan of the replica's tables, and neither does this connector.
3. **A re-setup in the same JVM** (the engine's completion-callback retry) seeds again; because seeding only raises the floor, the in-memory state is never rolled back.

What seeding does to the domain: nothing. The emitted value is still `effectiveTs * 1_000_000 + counter` with the 2.8.0 seeds; only the floor's starting value differs, exactly as §5's constraint allows. The first rows after a restart are clamped to $\lfloor V_{max}/10^6 \rfloor + 1$ — about one second above the last pre-restart source second, plus up to five seconds when the horizon was recently moved — and track the source clock again as soon as it passes the floor. After a clock seed the clamp is to the connector clock plus five seconds: on a source lagging by $L$ seconds at that start, $L$ seconds' worth of rows are versioned in that one slot, ordered by the counter — which never resets while the clamp holds, because `diff` is measured on `effectiveTs` (§3) — so within-run monotonicity (§3.3) is unaffected, and the mark written from the first handoff on makes every later start a mark seed again.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: binlog commit order dominates statement timestamps for first deliveries within one run (§3.3) and across a restart (§3.5) on the sequence-number path, and — through the clamped `effectiveTs` — on the GTID path (spec 02.01 §3.1).
- **Invariant I11 (Drop-in Upgrade Safety)**: the seed establishes the "continues ABOVE the last version the old version wrote" clause across an upgrade restart (spec 02.06 §3.1, §6.1).
- **Invariant I9 (Loud Failure)**: a horizon that cannot be made durable stops the engine instead of handing off rows the next start could not order.

---

## 5. Inherited behaviour (2.8.0 formula, preserved for upgrade/downgrade compatibility)

> **Compatibility constraint (governing rule).** The emitted `_version` domain — `ts_ms × 1_000_000 + counter` with the 2.8.0 seeds — is the contract shared with 2.8.0, 2.9.1 and 2.10.x. It MUST NOT change: a version written by any of those releases must rank consistently against one written by 2.11.0 in BOTH directions (upgrade and downgrade). The restart carry described here is therefore inherited 2.8.0 behaviour that is preserved deliberately; any improvement is confined to WHERE the restart floor starts (a persisted last-emitted version — or, on a start that has none, the connector clock; never a scan of the targets, Invariant I14 / spec 10.06) and must ship with an explicit upgrade/downgrade matrix against 2.8.0, 2.9.1 and 2.10.x.

The carry itself is unchanged: the ten-digit seeds still add ~1000 ms (`SEQUENCE_START`) or ~500 ms (`SEQUENCE_START_INITIAL`) to the timestamp field (spec 02.01 §3.3), and `SequenceSeedOverflowTest` still pins that arithmetic. What §3.5 changes is that the carry can no longer produce a cross-restart inversion, because the new run starts above the old run's highest version rather than at its own raw source second. The upgrade/downgrade matrix is in spec 02.06 §6.1.

### 5.1 Lean status
`Replication.VersionFloor` models the four statics and the algorithm of §3 as written, and proves: `version_ge_floor` (a first delivery is versioned at least `floor * 1_000_000 + 1`), `floor_mono` (the floor never decreases), `seed_floor_gt` (`v < (v / 1_000_000 + 1) * 1_000_000`), `restart_boundary` (every first delivery of a run seeded from a high-water mark at or above all previous versions exceeds every one of them), `dispatch_control_preserves_state` (a control record leaves the state unchanged) with the pre-fix `old_dispatch_control_moves_floor` counterexample, and the executable scenario `seeded_restart_example` / `unseeded_restart_inverts`. For the clock seed of §3.5 (2): `clockSeed` models `seedFloor()` without a mark, `below_clock_seed` (a version `ts * M + c` with `ts ≤ clock` and `c < headroom * M` lies below the seeded slot), `clock_restart_boundary` (every first delivery of a clock-seeded run exceeds every such pre-restart version) and the executable `clock_seed_example` (a row still five hours behind the clock out-ranks the pre-restart row). Within-run strict monotonicity of the shipped formula (§3.3, with its counter bound) is stated here but not yet machine-checked; `Replication.Proofs.version_strictly_monotonic` remains a theorem about the coordinate encoding only.

---

## 6. Verification Criteria
- `CommitOrderVersionClampTest.lateCommitWithOlderStatementTimestampRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.lateDeleteRanksAboveEarlierWrite()` — clamping of first deliveries (§3.1).
- `CommitOrderVersionClampTest.unpositionedCounterResetRaisesTheFloor()` — a positionless **row** that resets the counter raises the floor (§3.2, second paragraph).
- `DebeziumChangeEventCaptureTest.heartbeatAndTransactionMetadataDoNotTouchTheSequenceState()` — a heartbeat-only and a transaction-metadata-only batch through the real `handleChangeEventBatch` leave all four statics unchanged (§3.2); fails on the pre-fix loop, which raised the floor to the heartbeat's envelope timestamp.
- `CommitOrderVersionClampTest.redeliveryKeepsRedeliveryStableVersion()`, `CommitOrderVersionClampTest.redeliveryDoesNotDisturbTheFloor()` — redeliveries are not clamped and cannot lower the floor.
- `CommitOrderVersionClampTest.floorAppliesWithinOneBatch()`, `CommitOrderVersionClampTest.rotationIsAFirstDelivery()`, `CommitOrderVersionClampTest.rowsWithinOneEventAreFirstDeliveries()`, `CommitOrderVersionClampTest.postgresLsnIsAPosition()`.
- `CommitOrderVersionClampTest.binlogBasenameChangeResetsTheHighWaterMark()` — a positioned record of a differently named binary log is a first delivery: clamped to the floor, and the mark moves to the new log (§3.1; pre-fix it compared below the mark and was never clamped).
- `LateCommitVersionOrderIT` — end to end with `gtid_mode=OFF`.
- `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` — §3.5 through the real statics: `nextSequenceNumber(W-40000, p400)`, a heartbeat at the connector clock `W` through `handleChangeEventBatch`, `v1 = nextSequenceNumber(W-30000, p500)`, reset of the statics, `seedVersionFloor(v1)`, `v2 = nextSequenceNumber(W-25000, p600)`, `v2 > v1`. Fails on the pre-fix code (`v2 < v1`).
- `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()` — the seed carry window (spec 02.01 §4): an older event in the 1000m counter domain, a restart, and a newer event 1 ms later; with the seeded floor the newer event is clamped to `T + 1001` and wins. This is the former known-defect pin flipped into the guarantee; the control-record exclusion alone does not make it pass.
- `DebeziumChangeEventCaptureTest.seedVersionFloorRaisesButNeverLowersTheFloor()` — the seed is `floorDiv(v, 1e6) + 1`, a lower seed is ignored, `<= 0` is ignored.
- `VersionHighWaterMarkTest.horizonIsWrittenAheadOfHandoffAndReusedUntilExceeded()`, `VersionHighWaterMarkTest.loadReadsTheHighestPersistedMark()`, `VersionHighWaterMarkTest.horizonWriteFailureIsLoud()` — the write-ahead horizon of §3.5 (1).
- `VersionHighWaterMarkTest.seedFloorUsesThePersistedMark()`, `VersionHighWaterMarkTest.seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable()`, `VersionHighWaterMarkTest.implausiblePersistedMarkFallsBackToTheClock()` — the seed of §3.5 (2): the mark when plausible, the clock plus head-room otherwise, and in every case exactly two statements, both on the mark table (Invariant I14; the second test goes red the moment a discovery or target read is added back).
- Lean: `Replication.VersionFloor.restart_boundary`, `Replication.VersionFloor.version_ge_floor`, `Replication.VersionFloor.floor_mono`, `Replication.VersionFloor.seed_floor_gt`, `Replication.VersionFloor.dispatch_control_preserves_state`, `Replication.VersionFloor.old_dispatch_control_moves_floor`, `Replication.VersionFloor.seeded_restart_example`, `Replication.VersionFloor.unseeded_restart_inverts`, `Replication.VersionFloor.clockSeed`, `Replication.VersionFloor.below_clock_seed`, `Replication.VersionFloor.clock_restart_boundary`, `Replication.VersionFloor.clock_seed_example` (§5.1); `Replication.Proofs.version_strictly_monotonic` (coordinate encoding only).
- Verification: a crash-restart integration test that kills the connector mid-stream on a lagging source and checks the first post-restart updates win under `FINAL` is not yet covered by an automated test (gap).
