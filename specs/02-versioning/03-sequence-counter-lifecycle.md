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
- **Zero collision within a run**: two records versioned by the same JVM with the same `effectiveTs` receive distinct, increasing counters. Across a restart the counter re-arms at the 500m seed (and the seeds carry into the timestamp field — spec 02.01 §4); collisions with the previous run are excluded not by the counter but by the seeded floor, which places every first delivery of the new run above the previous run's highest version (spec 02.02 §3.5).

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.shouldResetSequenceNumberWhenSecondHasPassed()` — the real boundary: `ts + 1500` does not reset, `ts + 2500` resets to `SEQUENCE_START`, via `nextSequenceNumber` on reset static state.
- `DebeziumChangeEventCaptureTest.shouldAssignUniqueSequenceNumbersWithinSameSecond()` — strictly increasing values within one window.
- `SourceTsVersionAnchorTest.counterResetsOnlyOnSourceClockAdvance()`, `SourceTsVersionAnchorTest.anchorNeverMovesBackward()`, `SourceTsVersionAnchorTest.counterKeptAcrossBinlogRotation()`, `SourceTsVersionAnchorTest.resumeSeedsCounterAtInitial()`, `SourceTsVersionAnchorTest.initialSeedEscapesToNormalDomainAfterOneSecond()` (the test advances the clock by 2001 ms, matching §3.2).
- `CommitOrderVersionClampTest.counterResetFollowsTheEffectiveClock()` — the reset is evaluated on the floored timestamp.
- Verification: a multi-threaded test asserting no duplicate values under concurrent callers was missing; it is now `SequenceCounterConcurrencyTest.concurrentCallersNeverReceiveTheSameVersion()` (§6 FM-02.03-2).

---

## 6. Failure Modes & Recovery
The counter is process-local, in-memory and `synchronized`: it cannot fail loudly and needs no recovery of its own after a crash (the floor orders the next run, spec 02.02 §3.5). Its one real failure mode is arithmetic — the six-digit budget of spec 02.01 §3.3 — and it is silent.

- **FM-02.03-1 Counter carry at the reset after a window of more than 10^6 rows**
  - **Trigger**: more than 1 000 000 rows are versioned in one anchor window, then a record crosses the reset boundary (§3.2) fewer than `rows / 10^6` ms of effective time after the window's last row. Production shapes: (a) one statement touching millions of rows — every row event of it carries the same statement time — followed by a commit that started just after it (e.g. a transaction that waited on its row locks); (b) a long clamp at the floor, during which `effectiveTs` is constant and the counter never resets: a restart on a lagging source with more than 10^6 rows in the ≤ 5 s head-room clamp (≥ 200 000 rows/s), a clock-seeded start (first start after an upgrade) on a lagging source — the clamp lasts the whole backlog — or a source clock stepped back.
  - **Behaviour**: `DebeziumChangeEventCapture.nextVersionAssignment` returns `effectiveTs × 10^6 + counter`; after N rows the counter is `SEQUENCE_START + N`, i.e. `N / 10^6` ms carried into the timestamp field. At the reset it restarts at `SEQUENCE_START` with the new `effectiveTs`, so the first rows after the reset are versioned up to `N / 10^6` ms below the last rows before it.
  - **Detection**: none. DEFECT.
  - **Blast radius**: keys written both by the rows before the reset and by the commits of the next `N / 10^6` ms keep the older value under `FINAL` (for a 540 000 000-row clamp: 540 ms of commits). Silent, matching row counts, not self-healing until each key is written again.
  - **Recovery**: identify the window (a multi-million-row transaction in the binlog, or the restart/upgrade instant); run the value checksum (spec 11.02) and `ch-mysql-resync` (spec 11.04) on the tables written around it. Operator prevention: upgrade and restart with the connector caught up (lag under a few seconds), so no long clamp forms.
  - **RTO**: unbounded (resync); unmeasured.
  - **Test**: `CounterCarryResetInversionTest.resetAfterMoreThanAMillionRowsInOneWindowStillRanksAbove()` and `CounterCarryResetInversionTest.resetAfterALongClampStillRanksAbove()` (both disabled; fail on 2.11.0); `CounterCarryResetInversionTest.underAMillionRowsPerWindowTheResetStillRanksAbove()` pins the bound that holds (900 000 rows, reset 1 ms later, still above).
  - **DEFECT**: a window of more than 10^6 rows — one large statement or a long clamp — inverts the order at the next counter reset.

- **FM-02.03-2 Concurrent callers of the sequence**
  - **Trigger**: more than one thread versions rows at once (the dispatch loop and `addVersion`, or two engines in one JVM).
  - **Behaviour**: `nextSequenceNumber` / `nextVersionAssignment` are `static synchronized`, so the four statics are never torn and no value repeats.
  - **Detection**: not applicable — the race cannot produce a wrong value.
  - **Blast radius**: none.
  - **Recovery**: none needed.
  - **RTO**: 0 — no failure to recover from.
  - **Test**: `SequenceCounterConcurrencyTest.concurrentCallersNeverReceiveTheSameVersion()` (8 threads × 25 000 calls on one millisecond: all distinct, each thread strictly increasing).

- **FM-02.03-3 Counter and anchor lost at a crash**
  - **Trigger**: `kill -9`, OOM kill or host crash.
  - **Behaviour**: the next process re-arms the counter at `SEQUENCE_START_INITIAL` (§3.1); ordering against the previous run comes from the seeded floor, not from the counter (spec 02.02 §3.5).
  - **Detection**: the process exit itself (supervisor); at start INFO `Version floor seeded to <ms> ms from the persisted high-water version <v>`.
  - **Blast radius**: none when the seed comes from the mark; see spec 02.02 §7 FM-02.02-2 to FM-02.02-5 for starts without one.
  - **Recovery**: restart the service; nothing to repair.
  - **RTO**: restart ~20 s (unmeasured) + replay of the unacknowledged units (spec 02.04 §7 FM-02.04-1).
  - **Test**: `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()`.

Summary: 3 failure modes, 1 DEFECT, 0 GAP.
