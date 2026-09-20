# Spec 02.04: Redelivery Stability & Offset Rewind Versioning

## 1. Executive Summary & Purpose
Specifies the versioning behaviour when the connector restarts or Debezium rewinds to an earlier offset and re-publishes events (at-least-once delivery), as implemented on 2.11.0: what keeps a replayed copy of an already-written event from superseding it, and the known case where the same mechanism inverts genuinely new events.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `nextSequenceNumber`, `sequenceHighWaterPosition`, `sequenceMaxSourceTs`, `SEQUENCE_START_INITIAL = 500000000`, `SEQUENCE_START = 1000000000`
- **Timestamp choice**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getSourceTsFromChangeEvent` (source `ts_ms` for streaming rows, identical on every redelivery; envelope `ts_ms` for snapshot rows and records without a source struct)
- **Offset manager Javadoc**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (replay-safety and known-defect paragraphs)

---

## 3. Operational Specification

### 3.1 Initial seeding on start / resume
The first call to `nextSequenceNumber` after a JVM start sets `sequenceAnchorTs = recordTs` and `sequenceNumber = SEQUENCE_START_INITIAL` (500m), strictly below `SEQUENCE_START` (1000m). Events re-published from the last committed offset that share a source timestamp with rows already written before the restart (counters in the 1000m range) therefore receive lower versions and lose under `ReplacingMergeTree` to the copies already stored. The counter leaves the 500m domain at the first reset, i.e. the first source-clock advance of at least 2000 ms past the anchor (spec 02.03).

### 3.2 High-water gating within a run
When `position == null` or `position.compareTo(sequenceHighWaterPosition) <= 0`:
- the record is treated as a redelivery or a positionless record;
- `sequenceHighWaterPosition` is **not** modified;
- the timestamp is **not** clamped: `effectiveTs = recordTs`, so the replayed copy reproduces a redelivery-stable version anchored on its own source timestamp (issue #1346: a replayed DELETE cannot out-rank a later re-INSERT);
- the floor `sequenceMaxSourceTs` **is still raised** when `effectiveTs > sequenceMaxSourceTs` — the code raises the floor for every record, positioned or not (spec 02.02 §3.2). A typical redelivery carries an older timestamp and therefore leaves the floor untouched; it can never lower it.

### 3.3 What is guaranteed
Replay safety holds for the **same event delivered twice**: the second copy carries the same source timestamp and, after a restart, a lower counter seed, so it ranks at or below the stored row. `DebeziumOffsetManagement` documents the measured behaviour (a hard kill mid-flight re-executed a 3-record batch and an ALTER with no corruption) and notes that this depends on the version being anchored to `source.ts_ms`.

---

## 4. Known Defect (tracked, not yet fixed)
The 500m seed is ten digits in a formula that leaves six digits below the timestamp, so the seed carries into the timestamp field (spec 02.01 §3.3). `DebeziumOffsetManagement` (quoted verbatim):

> **KNOWN DEFECT, not fixed here.** The guarantee above holds only for the SAME event re-delivered. It does not generalise, because the encoding `sourceTsMs * 1_000_000 + sequence` leaves six decimal digits for the sequence while the seeds are ten digits, so the addition carries into the timestamp field and acts as a ~1000 ms shift. A genuinely NEWER event arriving just after a resume can then rank BELOW an older pre-restart event and be discarded:
> ```
> older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
> newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
> ```
> The `diff > 1` second reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies.

The Javadoc's claim that `ReplaySafetyTest` pins this arithmetic is stale: that class tests engine identity and the auto-created engine family only. The pins are `SequenceSeedOverflowTest` (arithmetic over the real constants) and `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` (the real `nextSequenceNumber`, run before and after a simulated restart). Both assert the inversion exists today and must be inverted when the encoding is fixed.

---

## 5. Invariants Preserved
- **Invariant I3 (Eventual Convergence)** under at-least-once delivery: a redelivered copy of an already-written event never supersedes it. Convergence for genuinely new events within ~1 s of a restart boundary is **not** guaranteed (§4).
- **Invariant I8 (Durable Offset Quiescence)**: unaffected — offsets are committed only after rows are written, which is why a rewind re-publishes rather than loses events.

---

## 6. Verification Criteria
- `SourceTsVersionAnchorTest.redeliveredDeleteCannotOutrankReinsert()`, `SourceTsVersionAnchorTest.republicationPreservesOrderAcrossRotation()` — redelivery-stable versions (§3.2).
- `SourceTsVersionAnchorTest.resumeSeedsCounterAtInitial()`, `SourceTsVersionAnchorTest.initialSeedEscapesToNormalDomainAfterOneSecond()` — the 500m seed and its exit (§3.1).
- `CommitOrderVersionClampTest.redeliveryKeepsRedeliveryStableVersion()`, `CommitOrderVersionClampTest.redeliveryDoesNotDisturbTheFloor()` — the high-water mark is not moved and the floor is not lowered by a redelivery.
- `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()`, `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` — §4 pinned as present behaviour.
- `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()` — the engine family that makes a losing replay harmless.
- Verification: a crash-restart integration test asserting no data degradation on redelivery is not yet covered by an automated test (gap).
