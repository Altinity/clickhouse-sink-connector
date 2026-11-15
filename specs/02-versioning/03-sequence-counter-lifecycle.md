# Spec 02.03: Intra-Window Sequence Counter Lifecycle & Reset

## 1. Executive Summary & Purpose
Specifies the lifecycle, reset boundary and seeds of the sequence counter that orders events sharing a timestamp component, as implemented by `nextSequenceNumber` on 2.11.0.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionSequencer.java` — package-private, extracted from `DebeziumChangeEventCapture`.
- **Method**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
- **Constants & Fields**:
  - `public static final long SEQUENCE_START = 1000000000` (seed after every reset)
  - `public static final long SEQUENCE_START_INITIAL = 500000000` (seed for the first record after start/resume)
  - `public static long sequenceNumber = SEQUENCE_START` — current counter value
  - `public static long sequenceAnchorTs = 0L` — anchor timestamp of the active counter window
  - `private static long sequenceMaxAssignedVersion = 0L` — maximum sequence-domain value assigned in this counter lifecycle; cleared when `sequenceAnchorTs == 0L` reinitializes the counter

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

### 3.2.1 Preserve counter carry for timestamp-floor-eligible deliveries
The reset above proposes a counter; it must not discard ordering already encoded by a large preceding window. Track the maximum **assigned sequence-domain value**, including rows without positions and redeliveries, in `sequenceMaxAssignedVersion`. After the existing timestamp floor and counter lifecycle rules, compute `candidate = effectiveTs * 1_000_000 + sequenceNumber`. For a delivery eligible for the existing timestamp floor under spec 02.02 §3.1 (usable, comparable position beyond the mark, the first position of a differently named log, or the remaining rows/statements of the marked event), if `candidate <= sequenceMaxAssignedVersion`, set:

```
sequenceNumber = sequenceMaxAssignedVersion - effectiveTs * 1_000_000 + 1
candidate = effectiveTs * 1_000_000 + sequenceNumber
```

Then retain `max(sequenceMaxAssignedVersion, candidate)` for every call. This also guards the first delivery after a replay or positionless row reset the counter; protecting only the call that performs a reset would miss that case. Equality needs the same guard: two different source events must not tie.

The guard changes only a timestamp-floor-eligible delivery whose unguarded version would not advance. This includes later statements of a compressed payload at the same coordinate, whose row index can restart at zero. Older events, incompatible coordinates and positionless records keep their existing assignment rules; their assigned values update the maximum but are not promoted to it. The existing classification cannot distinguish a replay of the marked event from its remaining rows; that ambiguity is unchanged, not claimed solved. On `sequenceAnchorTs == 0L`, clear this maximum with counter initialization so an explicitly reinitialized lifecycle does not inherit unrelated values. Preserve source/effective timestamps, seeds, multiplier, reset anchor rules, and the durable horizon format. GTID snowflake ordering and replay identity are separate unresolved defects; this guard does not fix or change their version precedence.

For representable sequence arithmetic, when the guard fires the eligible delivery is exactly the previous maximum plus one, and its recovered counter is positive because the proposed counter was positive. The existing write-ahead horizon covers that returned value before handoff. No target scan or new persisted field is required. `formal_specs/lean/Replication/VersionFloor.lean` models this guard with strict-advance theorem statements for both first deliveries and remaining rows of the marked event over natural-number arithmetic. The extended model has not yet completed Lean validation; Java overflow beyond the existing numeric domain is not claimed as covered. If incrementing the maximum exceeds `long`, `Math.addExact` fails loudly instead of wrapping that increment.

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
  - **Behaviour**: `VersionSequencer.nextVersionAssignment` returns `effectiveTs × 10^6 + counter`; after N rows the counter is `SEQUENCE_START + N`, i.e. `N / 10^6` ms carried into the timestamp field. At the reset it restarts at `SEQUENCE_START` with the new `effectiveTs`, so the first rows after the reset are versioned up to `N / 10^6` ms below the last rows before it.
  - **Detection / prevention**: timestamp-floor-eligible deliveries, including remaining rows/statements of the marked event, retain at least the previous maximum assigned version plus one (§3.2.1). The ordinary seeds and reset boundary remain unchanged when their candidate already advances.
  - **Blast radius**: keys written both by the rows before the reset and by the commits of the next `N / 10^6` ms keep the older value under `FINAL` (for a 540 000 000-row clamp: 540 ms of commits). Silent, matching row counts, not self-healing until each key is written again.
  - **Recovery**: identify the window (a multi-million-row transaction in the binlog, or the restart/upgrade instant); run the value checksum (spec 11.02) and `ch-mysql-resync` (spec 11.04) on the tables written around it. Operator prevention: upgrade and restart with the connector caught up (lag under a few seconds), so no long clamp forms.
  - **RTO**: unbounded (resync); unmeasured.
  - **Tests**: `CounterCarryResetInversionTest.resetAfterMoreThanAMillionRowsInOneWindowStillRanksAbove()` and `CounterCarryResetInversionTest.resetAfterALongClampStillRanksAbove()` are enabled regression tests; both fail without §3.2.1. `underAMillionRowsPerWindowTheResetStillRanksAbove()` retains the ordinary reset seed assertion. `laterStatementOfTheSameCompressedEventRetainsCounterCarryAtReset()` covers more than a million rows and a reset within one marked coordinate, including restarted row indices. Additional tests cover an exact-million-row tie, replay and positionless resets before the next first delivery, lifecycle reinitialization, unchanged effective timestamps and existing durable-horizon reseeding.
  - **Status**: fixed forward for timestamp-floor-eligible deliveries by §3.2.1. Previously diverged values still require reconciliation; GTID-domain and replay-identity defects are not resolved by this counter fix.

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

Summary: 3 failure modes; FM-02.03-1 is fixed forward for timestamp-floor-eligible deliveries by the carry guard. Existing affected values need reconciliation. No claim is made here about the separate version-domain or replay-identity defects.
