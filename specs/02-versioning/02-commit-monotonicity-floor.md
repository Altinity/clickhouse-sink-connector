# Spec 02.02: Commit Monotonicity Floor & Late-Commit Inversion Prevention

## 1. Executive Summary & Purpose
Specifies the high-water floor (`sequenceMaxSourceTs`) that gives events committing later in the MySQL binary log a timestamp component no lower than earlier commits within one connector run, even when MySQL statement timestamps are non-monotonic — and states the boundaries of that guarantee as implemented on 2.11.0.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
- **Fields** (all `public static`, process-wide, reset only by a JVM restart):
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
                           or position > sequenceHighWaterPosition):
      sequenceHighWaterPosition = position        # first delivery
      if effectiveTs < sequenceMaxSourceTs:
          effectiveTs = sequenceMaxSourceTs       # clamp up to the floor
  if effectiveTs > sequenceMaxSourceTs:
      sequenceMaxSourceTs = effectiveTs           # raised by EVERY record
  diff = (int)((effectiveTs - sequenceAnchorTs) / 1000)
  if diff > 1:                                    # >= 2000 ms past the anchor (spec 02.03)
      sequenceNumber   = SEQUENCE_START
      sequenceAnchorTs = effectiveTs
  else:
      sequenceNumber += 1
  return effectiveTs * 1_000_000 + sequenceNumber
```

### 3.1 Who is clamped
Only a **first delivery** — a record with a position strictly above `sequenceHighWaterPosition` — has its timestamp clamped up to the floor. A record at or below the mark (redelivery) or with no position (heartbeat, transaction boundary, snapshot row without coordinates) keeps `effectiveTs = recordTs`.

### 3.2 Who raises the floor
**Every** record raises `sequenceMaxSourceTs` when its `effectiveTs` exceeds it, positioned or not, first delivery or redelivery. The class Javadoc gives the reason: a heartbeat carrying a newer envelope timestamp moves the anchor and resets the counter exactly like a newer commit does, so it must move the floor too — otherwise the next late first delivery would again be versioned in its own older second. A redelivery cannot lower the floor; a redelivery whose timestamp exceeds the floor raises it.

### 3.3 What holds within one run
Let $E_1$ precede $E_2$ in the binlog and both be first deliveries in the same process lifetime. When $E_1$ is versioned the floor becomes $T_1 \ge \text{ts}(E_1)$; $E_2$ gets $T_2 = \max(\text{ts}(E_2), \text{floor}) \ge T_1$. If $T_2 = T_1$ the counter has only incremented, so $V(E_2) > V(E_1)$; if $T_2 \ge T_1 + 2000$ the counter resets to `SEQUENCE_START`, and $V(E_2) - V(E_1) \ge 2{,}000{,}000{,}000 - k > 0$ for any realistic increment count $k$. First deliveries therefore receive strictly increasing versions within one run.

### 3.4 What does not hold
- **Across a restart**: the floor, mark and anchor are static fields and start empty; the first record after a resume seeds the counter at `SEQUENCE_START_INITIAL`. Combined with the ten-digit seeds carrying into the timestamp field (spec 02.01 §3.3) a newer post-restart event can rank below an older pre-restart event. See §5.
- **On the GTID path**: `ClickHouseStruct.calculateVersion` prefers the GTID and never consults `nextSequenceNumber`'s result (spec 02.01 §3.1); the floor does not apply there.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: binlog commit order dominates statement timestamps for first deliveries **within one run** on the sequence-number path. Cross-restart and GTID-path monotonicity are open defects (§5).

---

## 5. Known Defect (tracked, not yet fixed)
The cross-restart inversion is documented in `DebeziumOffsetManagement` (quoted verbatim):

> A genuinely NEWER event arriving just after a resume can then rank BELOW an older pre-restart event and be discarded:
> ```
> older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
> newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
> ```
> The `diff > 1` second reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies.

The pins for this defect are `SequenceSeedOverflowTest` and `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` (not `ReplaySafetyTest`, despite that Javadoc's reference).

### 5.1 Lean status
`Replication.Proofs.version_strictly_monotonic` proves that the coordinate encoding `encodeVersion (fileSeq, offset, rowIdx)` is order-preserving within `BinlogPos.WellFormed`. The engine model versions by stream ordinal (`liveVersion i = 2 * i`) and does not use that encoding. Neither models the floor, the anchor, the seeds, or the shipped formula; the algorithm in §3 has no machine-checked proof yet.

---

## 6. Verification Criteria
- `CommitOrderVersionClampTest.lateCommitWithOlderStatementTimestampRanksAboveEarlierWrite()`, `CommitOrderVersionClampTest.lateDeleteRanksAboveEarlierWrite()` — clamping of first deliveries (§3.1).
- `CommitOrderVersionClampTest.unpositionedCounterResetRaisesTheFloor()` — a positionless record raises the floor (§3.2).
- `CommitOrderVersionClampTest.redeliveryKeepsRedeliveryStableVersion()`, `CommitOrderVersionClampTest.redeliveryDoesNotDisturbTheFloor()` — redeliveries are not clamped and cannot lower the floor.
- `CommitOrderVersionClampTest.floorAppliesWithinOneBatch()`, `CommitOrderVersionClampTest.rotationIsAFirstDelivery()`, `CommitOrderVersionClampTest.rowsWithinOneEventAreFirstDeliveries()`, `CommitOrderVersionClampTest.postgresLsnIsAPosition()`.
- `LateCommitVersionOrderIT` — end to end with `gtid_mode=OFF`.
- `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` — §5 pinned as present behaviour.
- Lean: `Replication.Proofs.version_strictly_monotonic` (coordinate encoding only — §5.1).
