# Spec 02.04: Redelivery Stability & Offset Rewind Versioning

## 1. Executive Summary & Purpose
Specifies the versioning behaviour when the connector restarts or Debezium rewinds to an earlier offset and re-publishes events (at-least-once delivery), as implemented on 2.11.0: how an in-run redelivery keeps a redelivery-stable version, how a post-restart replay is versioned **above** everything the previous run wrote (and why that still converges), and which record-identity de-duplication exists on which path.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `nextSequenceNumber`, `seedVersionFloor`, `sequenceHighWaterPosition`, `sequenceMaxSourceTs`, `SEQUENCE_START_INITIAL = 500000000`, `SEQUENCE_START = 1000000000`
- **Durable high-water mark**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionHighWaterMark.java` (spec 02.02 §3.5, spec 09.03 §3.4)
- **Timestamp choice**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getSourceTsFromChangeEvent` (source `ts_ms` for streaming rows, identical on every redelivery; envelope `ts_ms` for snapshot rows and records without a source struct)
- **Offset manager Javadoc**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (replay-safety paragraphs)
- **Event-identity de-duplication (Kafka Connect sink only)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/deduplicator/DeDuplicator.java`, consulted by `ClickHouseSinkTask.put()` (spec 10.05)

---

## 3. Operational Specification

### 3.1 Start / resume: counter seed and floor seed
The first call to `nextSequenceNumber` after a JVM start sets `sequenceAnchorTs = recordTs` and `sequenceNumber = SEQUENCE_START_INITIAL` (500m), strictly below `SEQUENCE_START` (1000m); the counter leaves the 500m domain at the first reset, i.e. the first advance of the effective clock of at least 2000 ms past the anchor (spec 02.03). This seed is inherited 2.8.0 behaviour and is kept.

Independently of the counter, the **floor** is seeded before the first record: `setupDebeziumEventCapture` calls `seedVersionFloor(V_max)` with the durable high-water mark (spec 02.02 §3.5), so `sequenceMaxSourceTs = floorDiv(V_max, 10^6) + 1` before any record of the new run is versioned. The floor seed, not the counter seed, is what orders the new run against the old one (§3.2).

### 3.2 High-water gating: within a run, and after a restart
When `position == null` or `position.compareTo(sequenceHighWaterPosition) <= 0`:
- the record is treated as a redelivery or a positionless row;
- `sequenceHighWaterPosition` is **not** modified;
- the timestamp is **not** clamped: `effectiveTs = recordTs`, so the replayed copy reproduces a redelivery-stable version anchored on its own source timestamp (issue #1346: a replayed DELETE cannot out-rank a later re-INSERT);
- the floor `sequenceMaxSourceTs` **is still raised** when `effectiveTs > sequenceMaxSourceTs` (spec 02.02 §3.2). A typical redelivery carries an older timestamp and therefore leaves the floor untouched; it can never lower it.

This gate distinguishes redeliveries only **within one run**, because the mark is process-local. **After a restart the mark is empty**, so the events Debezium re-publishes from the last committed offset are, to the new run, first deliveries: each is above the (empty, then advancing) mark, its timestamp **is** clamped to the seeded floor, and it receives a version **above** every version the previous run assigned (spec 02.02 §3.5, `Replication.VersionFloor.restart_boundary`). The earlier wording of this section — that post-restart redeliveries are not clamped — described the in-run gate and was wrong for the restart case.

Why the replayed copies winning is safe: a replay is the **contiguous suffix** of the binlog from the committed offset onward, delivered in log order and versioned in strictly increasing order. For every key, the last replayed event is the same event that was last in the original run's write of that suffix, so `FINAL` resolves to the same row image whether the stored copy or the replayed copy wins; and every genuinely new event after the suffix is versioned above both. The DELETE-then-re-INSERT pattern of issue #1346 is covered by the same argument: a replay that contains the DELETE also contains the later re-INSERT (or the committed offset already lay past the DELETE). What replay safety requires is therefore not that a replayed copy loses, but that the new run's versions are **totally ordered above the old run's and in log order among themselves** — which the seeded floor provides and the unseeded floor did not.

### 3.3 What is guaranteed
- **In-run redelivery** (engine retry, rewind without a restart): the second copy carries the same source timestamp, is not clamped, and ranks at or below the stored row of the same event; it is discarded on merge.
- **Post-restart replay**: the replayed copies rank above the stored rows and carry the same data; subsequent new events rank above both. `DebeziumOffsetManagement` documents the measured behaviour of a hard kill mid-flight (a 3-record batch and an ALTER re-executed, no corruption).
- **Event-identity de-duplication** (`deduplication.policy`, spec 10.05) exists **only on the Kafka Connect sink path**: `DeDuplicator.isNew` keys on `(topic, kafkaPartition, kafkaOffset)`, is consulted only by `ClickHouseSinkTask.put()`, and defaults to `OFF`. The lightweight engine has **no** record-identity de-duplication: `DebeziumChangeEventCapture` never constructs a `DeDuplicator`, so lightweight replay safety rests entirely on the version ordering of this section and spec 02.02 §3.5. A redelivered row in the lightweight engine is always written again and resolved by `ReplacingMergeTree`.

---

## 4. Inherited behaviour (2.8.0 formula, preserved for upgrade/downgrade compatibility)

> **Compatibility constraint (governing rule).** The emitted `_version` domain — `ts_ms × 1_000_000 + counter` with the 2.8.0 seeds — is the contract shared with 2.8.0, 2.9.1 and 2.10.x. It MUST NOT change: a version written by any of those releases must rank consistently against one written by 2.11.0 in BOTH directions (upgrade and downgrade). The restart carry described here is therefore inherited 2.8.0 behaviour that is preserved deliberately; any improvement is confined to WHERE the restart floor starts (seeding the anchor from the target's `max(_version)` or a persisted last-emitted version) and must ship with an explicit upgrade/downgrade matrix against 2.8.0, 2.9.1 and 2.10.x.

The 500m seed is ten digits in a formula that leaves six digits below the timestamp, so the seed carries into the timestamp field (spec 02.01 §3.3):

```
older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
```

Without a seeded floor the newer post-restart event ranked below the older pre-restart one. With the floor seeded from the high-water mark the newer event is clamped to `floorDiv(T*1e6 + 1_000_000_000, 1e6) + 1 = T + 1001` and versioned at `(T + 1001) * 1e6 + 500_000_001 > T*1e6 + 1_000_000_000`. The arithmetic of the carry is unchanged and still pinned by `SequenceSeedOverflowTest` (over the raw constants, i.e. the unseeded case); the behavioural pin `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` now asserts the guarantee. `ReplaySafetyTest` covers engine identity and the auto-created engine family, not this arithmetic. The upgrade/downgrade matrix is in spec 02.06 §6.1.

---

## 5. Invariants Preserved
- **Invariant I3 (Eventual Convergence)** under at-least-once delivery: an in-run redelivery never supersedes the stored row; a post-restart replay reproduces the same `FINAL` image and every genuinely new event ranks above the replay and the old run (§3.2).
- **Invariant I2 (Deterministic Version Monotonicity)** across the restart boundary (spec 02.02 §3.5).
- **Invariant I8 (Durable Offset Quiescence)**: unaffected — offsets are committed only after rows are written, which is why a rewind re-publishes rather than loses events. The high-water mark is written ahead of handoff and is independent of the offset flush.

---

## 6. Verification Criteria
- `SourceTsVersionAnchorTest.redeliveredDeleteCannotOutrankReinsert()`, `SourceTsVersionAnchorTest.republicationPreservesOrderAcrossRotation()` — redelivery-stable versions for positionless rows and the unseeded in-run case (§3.2).
- `SourceTsVersionAnchorTest.resumeSeedsCounterAtInitial()`, `SourceTsVersionAnchorTest.initialSeedEscapesToNormalDomainAfterOneSecond()` — the 500m seed and its exit (§3.1, unseeded floor).
- `CommitOrderVersionClampTest.redeliveryKeepsRedeliveryStableVersion()`, `CommitOrderVersionClampTest.redeliveryDoesNotDisturbTheFloor()` — the in-run gate: the high-water mark is not moved and the floor is not lowered by a redelivery.
- `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` (lagging source) and `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()` (the 1 ms carry window of §4) — the seeded restart (§3.1/§3.2); the latter asserted the inversion before the fix.
- `DebeziumChangeEventCaptureTest.replayAfterSeededRestartIsClampedAboveTheOldRun()` — a re-published (lower-position, older-timestamp) event after a seeded restart is a first delivery to the new run and ranks above the old run's highest version (§3.2, second paragraph).
- `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()` — the raw carry arithmetic of §4 (unseeded), preserved deliberately.
- `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()` — the engine family that makes a replayed duplicate harmless.
- `DeDuplicatorTest.testRedeliveredEventIsDuplicate()` — event-identity de-duplication on the Kafka Connect path only (§3.3).
- Lean: `Replication.VersionFloor.restart_boundary`, `Replication.VersionFloor.first_row_after_restart_is_first`.
- Verification: a crash-restart integration test asserting no data degradation on redelivery is not yet covered by an automated test (gap).
