# Spec 01.04: Transaction Boundaries & Timestamp Semantics

## 1. Executive Summary & Purpose
Specifies how transaction boundaries (`BEGIN`, `COMMIT`, `XID`) in the MySQL binary log relate to CDC events, formalizes the discrepancy between MySQL statement start time and transaction commit time, and states exactly what the connector does about it on 2.11.0 — including what it does not yet do.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `nextSequenceNumber(long recordTs, SourcePosition position)` and the static fields `sequenceMaxSourceTs`, `sequenceHighWaterPosition`, `sequenceAnchorTs`, `sequenceNumber`.
- **Model**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getSourceTsFromChangeEvent` (timestamp choice) and `calculateVersion(boolean useSnowflakeId)` (version precedence).
- **Offset manager Javadoc**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (replay-safety paragraphs).
- **Durable floor**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionHighWaterMark.java` and `DebeziumChangeEventCapture.seedVersionFloor` (spec 02.02 §3.5).

---

## 3. Statement vs. Commit Timestamp Discrepancy

In MySQL row-based replication:
- `source.ts_ms` populated by Debezium is the **statement execution time**, *not* the commit timestamp.
- **The Late-Commit Inversion Hazard**:
  1. Transaction $T_1$ begins at $t = 100$ and executes statements modifying row $K$.
  2. Transaction $T_2$ begins at $t = 110$, executes statements modifying row $K$, and immediately commits at $t = 111$.
  3. Transaction $T_1$ finally commits at $t = 120$.
  4. In the binlog, $T_2$'s events appear *before* $T_1$'s events.
  5. However, $T_1$'s raw timestamp ($100$) is *older* than $T_2$'s timestamp ($110$).
  6. If raw timestamps decide `_version`, $T_1$ ranks below $T_2$ and ClickHouse `ReplacingMergeTree` keeps $T_2$'s row, silently discarding $T_1$'s later commit.

### 3.1 The High-Water Floor as implemented
`nextSequenceNumber` keeps `sequenceMaxSourceTs`, the highest effective timestamp versioned in this run (seeded at engine start, §3.2), and `sequenceHighWaterPosition`, the highest log position versioned in this run:
- A record whose position is above the high-water mark is a **first delivery**: the mark moves to it and its timestamp component is clamped up to the floor, $\text{effectiveTs} = \max(\text{recordTs}, \text{sequenceMaxSourceTs})$.
- A record at or below the mark, or a row without a position, is treated as a **redelivery / positionless row**: its timestamp is not clamped (`effectiveTs = recordTs`), so an in-run replay reproduces a redelivery-stable version (spec 02.04).
- The floor is then **raised by every record that enters the sequence** — first delivery, redelivery or positionless row — whenever `effectiveTs > sequenceMaxSourceTs` (spec 02.02 §3.2).
- **Control records never enter the sequence.** A heartbeat or transaction-metadata record (spec 01.06 §3.1) carries the connector's wall clock, not a source time, and produces no row; `handleChangeEventBatch` does not call `nextSequenceNumber` for it, so it cannot move the floor, the anchor, the counter or the mark (spec 02.02 §3.2).
Within one process lifetime this gives $T_1$ an `effectiveTs \ge 110$ and a version above $T_2$'s: commit order wins over statement time.

### 3.2 Scope of the guarantee
The floor is process-local state, but it is **re-established at engine start** from a durable high-water mark of the versions handed to the writers (`VersionHighWaterMark`, spec 02.02 §3.5): `sequenceMaxSourceTs = floorDiv(V_max, 10^6) + 1`, so the first deliveries of a new run rank above every version of the previous run. This closes the restart window of §4.1 and, with it, the **clock-skew** hazard: the source's `ts_ms` comes from the MySQL host clock while snapshot rows and (before 2.11.0) heartbeats were anchored on the connector host's clock; a skew between the two could make a new run's raw source timestamps fall below the previous run's connector-anchored versions. Because the seed is derived from the highest version actually assigned — whatever clock produced it — and control records no longer inject the connector clock at all, ordering across a restart no longer depends on the two clocks agreeing. The floor also governs the GTID path since 2.11.0 (§4.2). The Lean model of the floor is `Replication.VersionFloor` (§4.3).

---

## 4. Inherited behaviour (2.8.0 formula, preserved for upgrade/downgrade compatibility)

> **Compatibility constraint (governing rule).** The emitted `_version` domain — `ts_ms × 1_000_000 + counter` with the 2.8.0 seeds — is the contract shared with 2.8.0, 2.9.1 and 2.10.x. It MUST NOT change: a version written by any of those releases must rank consistently against one written by 2.11.0 in BOTH directions (upgrade and downgrade). The restart carry described here is therefore inherited 2.8.0 behaviour that is preserved deliberately; any improvement is confined to WHERE the restart floor starts (seeding the anchor from the target's `max(_version)` or a persisted last-emitted version) and must ship with an explicit upgrade/downgrade matrix against 2.8.0, 2.9.1 and 2.10.x.


### 4.1 Sequence carry across a restart (no-GTID path)
The version is `effectiveTs * 1_000_000 + sequenceNumber`, leaving six decimal digits for the counter, but the seeds `SEQUENCE_START = 1_000_000_000` and `SEQUENCE_START_INITIAL = 500_000_000` are ten digits, so the addition carries into the timestamp field and acts as a ~1000 ms shift:

```
older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
```

The `diff > 1` reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies. The arithmetic is inherited and preserved (constraint above; pinned by `SequenceSeedOverflowTest` over the real constants). The inversion it produced on a restart is closed by seeding the floor (§3.2, spec 02.02 §3.5): the post-restart event is clamped to `T + 1001` and ranks above. `ReplaySafetyTest` covers engine identity and the auto-created engine family, not this arithmetic. The behavioural pin is `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()`.

### 4.2 GTID path is floored through `versionTs`
`ClickHouseStruct.calculateVersion` prefers `gtid` over `sequenceNumber`: with a GTID present the version is `SnowFlakeId.generate(versionTs > 0 ? versionTs : ts_ms, gtid, false)` (`snowflake.id` defaults to `true` in `ClickHouseSinkConnectorConfig`) or the raw gtid. `versionTs` is the clamped `effectiveTs` of the sequence, set by the lightweight dispatch loop next to the sequence number (spec 02.01 §3.1), so the snowflake's timestamp field is floored exactly like the sequence path and the hazard in §3 is closed under GTID as well: $T_1$'s row events are clamped to at least $T_2$'s timestamp and, on a tie, ordered by their higher GTID transaction number. Before 2.11.0 the raw `source.ts_ms` was used and a late-committing long transaction lost to a shorter one that committed before it. `LateCommitVersionOrderIT` exercises the sequence path with `gtid_mode=OFF`; the GTID path is covered by `GtidLateCommitVersionTest`.

### 4.3 What the Lean model proves
`Replication.VersionFloor` models the four statics and the assignment algorithm as shipped and proves the restart boundary (`restart_boundary`: with the floor seeded from a mark at or above every previous version, every first delivery of the new run exceeds them) and that control records leave the state unchanged (`dispatch_control_preserves_state`). `Replication.Proofs.version_strictly_monotonic` is a theorem about `encodeVersion` — the `(fileSeq, offset, rowIdx)` coordinate encoding — within `BinlogPos.WellFormed`; the convergence model versions by stream ordinal (`liveVersion i = 2 * i`, `Engine.lean`). Within-run strict monotonicity of the shipped formula (spec 02.02 §3.3) is not yet machine-checked.

---

## 5. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: preserved within one run by the floor on both the sequence-number path and the GTID path (§4.2), and across a restart by the seeded floor (§3.2, §4.1).

---

## 6. Verification Criteria
- `LateCommitVersionOrderIT` — MySQL with `gtid_mode=OFF`: a long transaction committing after newer-timestamped transactions wins in ClickHouse.
- `CommitOrderVersionClampTest.lateCommitWithOlderStatementTimestampRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.lateDeleteRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.floorAppliesWithinOneBatch()` — the floor on first deliveries.
- `GtidLateCommitVersionTest.lateCommittingTransactionOutranksEarlierCommitUnderGtid()` — §4.2: `T2 (ts 110, gtid 200, pos 100)` then `T1 (ts 100, gtid 201, pos 200)` gives `version(T1) > version(T2)`.
- `DebeziumChangeEventCaptureTest.heartbeatAndTransactionMetadataDoNotTouchTheSequenceState()` — control records never enter the sequence (§3.1).
- `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()` — the unseeded carry of §4.1; `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()` — the same 1 ms scenario through the real statics with the seeded floor; `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` — the seeded restart on a lagging source.
- Lean: `Replication.VersionFloor.restart_boundary`, `Replication.VersionFloor.dispatch_control_preserves_state` (§4.3); `Replication.Proofs.version_strictly_monotonic` covers only the coordinate encoding.
- Verification: a GTID-mode end-to-end late-commit test (`LateCommitVersionOrderIT` runs with `gtid_mode=OFF` only) is not yet covered by an automated test (gap).
