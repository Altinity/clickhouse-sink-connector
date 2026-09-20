# Spec 01.04: Transaction Boundaries & Timestamp Semantics

## 1. Executive Summary & Purpose
Specifies how transaction boundaries (`BEGIN`, `COMMIT`, `XID`) in the MySQL binary log relate to CDC events, formalizes the discrepancy between MySQL statement start time and transaction commit time, and states exactly what the connector does about it on 2.11.0 — including what it does not yet do.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `nextSequenceNumber(long recordTs, SourcePosition position)` and the static fields `sequenceMaxSourceTs`, `sequenceHighWaterPosition`, `sequenceAnchorTs`, `sequenceNumber`.
- **Model**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getSourceTsFromChangeEvent` (timestamp choice) and `calculateVersion(boolean useSnowflakeId)` (version precedence).
- **Offset manager Javadoc**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (the "KNOWN DEFECT" paragraph quoted in §4).

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
`nextSequenceNumber` keeps `sequenceMaxSourceTs`, the highest effective timestamp versioned in this run, and `sequenceHighWaterPosition`, the highest log position versioned in this run:
- A record whose position is above the high-water mark is a **first delivery**: the mark moves to it and its timestamp component is clamped up to the floor, $\text{effectiveTs} = \max(\text{recordTs}, \text{sequenceMaxSourceTs})$.
- A record at or below the mark, or without a position, is treated as a **redelivery / positionless record**: its timestamp is not clamped (`effectiveTs = recordTs`), so a replay reproduces a redelivery-stable version (spec 02.04).
- The floor is then **raised by every record** — first delivery, redelivery or positionless (heartbeat) — whenever `effectiveTs > sequenceMaxSourceTs` (spec 02.02 §3.2).
Within one process lifetime this gives $T_1$ an `effectiveTs \ge 110$ and a version above $T_2$'s: commit order wins over statement time.

### 3.2 Scope of the guarantee
The floor is process-local state. It is not persisted, and it is bypassed entirely on the GTID version path (§4.2). The Lean model does not model it (§4.3).

---

## 4. Known Defects (tracked, not yet fixed)

### 4.1 Sequence carry across a restart (no-GTID path)
The version is `effectiveTs * 1_000_000 + sequenceNumber`, leaving six decimal digits for the counter, but the seeds `SEQUENCE_START = 1_000_000_000` and `SEQUENCE_START_INITIAL = 500_000_000` are ten digits, so the addition carries into the timestamp field and acts as a ~1000 ms shift. `DebeziumOffsetManagement` documents the consequence (quoted verbatim):

> **KNOWN DEFECT, not fixed here.** The guarantee above holds only for the SAME event re-delivered. It does not generalise, because the encoding `sourceTsMs * 1_000_000 + sequence` leaves six decimal digits for the sequence while the seeds are ten digits, so the addition carries into the timestamp field and acts as a ~1000 ms shift. A genuinely NEWER event arriving just after a resume can then rank BELOW an older pre-restart event and be discarded:
> ```
> older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
> newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
> ```
> The `diff > 1` second reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies.

That Javadoc says `ReplaySafetyTest` pins the arithmetic; it does not — its two tests cover engine identity and the auto-created engine family. The arithmetic pin lives in `SequenceSeedOverflowTest` (lightweight module, bound to the real constants) and the behavioural restart-inversion pin in `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()`; both assert the inversion EXISTS today and must be flipped when the scheme is fixed.

### 4.2 GTID path bypasses the floor
`ClickHouseStruct.calculateVersion` prefers `gtid` over `sequenceNumber`: with a GTID present the version is `SnowFlakeId.generate(ts_ms, gtid, false)` (`snowflake.id` defaults to `true` in `ClickHouseSinkConnectorConfig`) or the raw gtid, and `nextSequenceNumber`'s result is unused. That path is anchored on `source.ts_ms` — statement time — with no commit-order floor, so the hazard in §3 is live under GTID: a late-committing long transaction can lose to a shorter one that committed before it. `LateCommitVersionOrderIT` exercises the floor and therefore requires `gtid_mode=OFF`; there is no equivalent coverage for the GTID path.

### 4.3 What the Lean model proves
`Replication.Proofs.version_strictly_monotonic` is a theorem about `encodeVersion` — the `(fileSeq, offset, rowIdx)` coordinate encoding — within `BinlogPos.WellFormed`; the engine model itself versions by stream ordinal (`liveVersion i = 2 * i`, `Engine.lean`) and, as `Proofs.lean` states, does not rely on that arithmetic. Neither theorem models `sequenceMaxSourceTs`, the counter seeds, or the shipped `effectiveTs * 1e6 + seq` formula. The floor algorithm has no machine-checked proof today.

---

## 5. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: preserved within one run on the sequence-number path by the floor; **not established** across a restart (§4.1) or on the GTID path (§4.2). Those gaps are tracked defects, not accepted behaviour.

---

## 6. Verification Criteria
- `LateCommitVersionOrderIT` — MySQL with `gtid_mode=OFF`: a long transaction committing after newer-timestamped transactions wins in ClickHouse.
- `CommitOrderVersionClampTest.lateCommitWithOlderStatementTimestampRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.lateDeleteRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.floorAppliesWithinOneBatch()` — the floor on first deliveries.
- `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()` and `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` — §4.1 pinned as a present defect.
- Verification: the GTID-path late-commit hazard (§4.2) is not yet covered by an automated test (gap).
- Lean: `Replication.Proofs.version_strictly_monotonic` covers only the coordinate encoding (§4.3); the floor is not modelled.
