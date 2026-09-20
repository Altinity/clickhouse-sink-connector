# Spec 02.03: Intra-Window Sequence Counter Lifecycle & Reset

## 1. Executive Summary & Purpose
Specifies the lifecycle, reset boundary and seeds of the sequence counter that orders events sharing a timestamp component, as implemented by `nextSequenceNumber` on 2.11.0.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
- **Constants & Fields**:
  - `public static final long SEQUENCE_START = 1000000000` (seed after every reset)
  - `public static final long SEQUENCE_START_INITIAL = 500000000` (seed for the first record after start/resume)
  - `public static long sequenceNumber = SEQUENCE_START` — current counter value
  - `public static long sequenceAnchorTs = 0L` — anchor timestamp of the active counter window

---

## 3. Operational Specification

### 3.1 First call after start / resume
When `sequenceAnchorTs == 0L` the method sets `sequenceAnchorTs = recordTs` and `sequenceNumber = SEQUENCE_START_INITIAL`. The counter is then incremented like any other call, so the first emitted value is `effectiveTs * 1_000_000 + 500_000_001`.

### 3.2 Reset boundary (not one second)
For every call, with `effectiveTs` already floored (spec 02.02):
```
int diff = (int) ((effectiveTs - sequenceAnchorTs) / 1000);   // integer division, milliseconds -> whole seconds
if (diff > 1) { sequenceNumber = SEQUENCE_START; sequenceAnchorTs = effectiveTs; }
else          { sequenceNumber++; }
```
Consequences:
- A reset requires `diff >= 2`, i.e. **`effectiveTs - sequenceAnchorTs >= 2000 ms`**. An advance of 1000–1999 ms yields `diff == 1` and does **not** reset; the counter keeps incrementing across that boundary.
- The anchor moves only on a reset, and only forward: `effectiveTs` is floored for first deliveries (spec 02.02) and a redelivery with an older timestamp yields a negative `diff`, which is not `> 1`. The anchor is never keyed on the binlog file name or position, so it survives binlog rotations.
- There is no upper bound on the counter within a window; it is a `long` incremented once per record.

### 3.3 Thread Synchronization
`nextSequenceNumber` is `static synchronized`, so concurrent callers cannot produce duplicate values or tear the four static fields.

---

## 4. Invariants Preserved
- **Zero collision within a run**: two records versioned by the same JVM with the same `effectiveTs` receive distinct, increasing counters. This does not extend across a restart (the 500m seed re-arms and the seeds carry into the timestamp field — spec 02.01 §4).

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.shouldResetSequenceNumberWhenSecondHasPassed()` — the real boundary: `ts + 1500` does not reset, `ts + 2500` resets to `SEQUENCE_START`, via `nextSequenceNumber` on reset static state.
- `DebeziumChangeEventCaptureTest.shouldAssignUniqueSequenceNumbersWithinSameSecond()` — strictly increasing values within one window.
- `SourceTsVersionAnchorTest.counterResetsOnlyOnSourceClockAdvance()`, `SourceTsVersionAnchorTest.anchorNeverMovesBackward()`, `SourceTsVersionAnchorTest.counterKeptAcrossBinlogRotation()`, `SourceTsVersionAnchorTest.resumeSeedsCounterAtInitial()`, `SourceTsVersionAnchorTest.initialSeedEscapesToNormalDomainAfterOneSecond()` (the test advances the clock by 2001 ms, matching §3.2).
- `CommitOrderVersionClampTest.counterResetFollowsTheEffectiveClock()` — the reset is evaluated on the floored timestamp.
- Verification: a multi-threaded test asserting no duplicate values under concurrent callers is not yet covered by an automated test (gap; the method is `synchronized`).
