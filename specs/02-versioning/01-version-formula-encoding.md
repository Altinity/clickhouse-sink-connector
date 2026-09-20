# Spec 02.01: 64-Bit Monotonic Version Formula & Bit Allocation

## 1. Executive Summary & Purpose
Specifies how the lightweight connector derives the 64-bit `_version` value (bound as `UInt64` in ClickHouse) used for `ReplacingMergeTree` deduplication: which ordering key is chosen, the arithmetic of the sequence-number formula, its digit budget, and the known defect in that budget.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
- **Constants**: `SEQUENCE_START = 1000000000L` (one billion), `SEQUENCE_START_INITIAL = 500000000L` (five hundred million), multiplier `1_000_000L` (inline in the return statement)
- **Precedence**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `calculateVersion(boolean useSnowflakeId)`; `snowflake.id` defaults to `true` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`

---

## 3. Version Selection and Formula

### 3.1 Precedence (`ClickHouseStruct.calculateVersion`)
1. **`gtid` present** — `SnowFlakeId.generate(ts_ms, gtid, false)` when `snowflake.id` is true (the default), else the raw gtid. `ts_ms` here is `source.ts_ms` (statement time). The sequence number computed by `nextSequenceNumber` is ignored on this path.
2. **`sequenceNumber` present** (lightweight streaming path, no GTID) — the value returned by `nextSequenceNumber`, i.e. the formula in §3.2.
3. **`lsn` present** (PostgreSQL) — the LSN.
4. **otherwise, `ts_ms > 0`** — `SnowFlakeId.generate(ts_ms, kafkaOffset, false)` (spec 02.05).

Everything below concerns path 2 only.

### 3.2 The sequence-number formula
$$V = \text{effectiveTs} \times 1{,}000{,}000 + \text{sequenceNumber}$$
- `effectiveTs`: the record's source timestamp in milliseconds, clamped up to the run's high-water floor when the record is a first delivery (spec 02.02).
- `sequenceNumber`: the intra-window counter (spec 02.03), seeded at `SEQUENCE_START_INITIAL` for the first record after start/resume and at `SEQUENCE_START` after every counter reset.

### 3.3 Digit budget as it actually is
- `effectiveTs` is 13 decimal digits in 2026 ($\approx 1.79 \times 10^{12}$); multiplied by $10^6$ it occupies $\approx 1.79 \times 10^{18}$, within the signed 64-bit range until the year 2262.
- The multiplier leaves **six** decimal digits below the timestamp for the counter, but both seeds are **ten** digits. The addition therefore **carries into the timestamp field**: `SEQUENCE_START` adds 1,000 ms worth of timestamp and `SEQUENCE_START_INITIAL` adds 500 ms worth. The formula does not partition the integer into a timestamp part and a counter part; it is a sum in which the counter shifts the effective time by roughly one second.

---

## 4. Known Defect (tracked, not yet fixed)
Because of the carry in §3.3 a genuinely newer event versioned just after a resume (counter seeded at 500m) can rank **below** an older pre-restart event (counter in the 1000m range). `DebeziumOffsetManagement` states it (quoted verbatim):

> **KNOWN DEFECT, not fixed here.** The guarantee above holds only for the SAME event re-delivered. It does not generalise, because the encoding `sourceTsMs * 1_000_000 + sequence` leaves six decimal digits for the sequence while the seeds are ten digits, so the addition carries into the timestamp field and acts as a ~1000 ms shift. A genuinely NEWER event arriving just after a resume can then rank BELOW an older pre-restart event and be discarded:
> ```
> older, pre-restart  (T)     -> T*1e6 + 1_000_000_000 = 1787635798000000000
> newer, post-restart (T+1ms) -> (T+1)*1e6 + 500_000_000 = 1787635797501000000
> ```
> The `diff > 1` second reset does not cover it: a 1 ms advance yields `diff == 0`, so the 500m seed still applies. Fixing it means widening the multiplier (or shrinking the seeds) so the sequence cannot carry -- a change to the version scheme itself, which needs its own review and a migration story for existing versions.

The same Javadoc names `ReplaySafetyTest` as the pin for this arithmetic; that class does not test it (its tests cover engine identity and the auto-created engine family). The pins are `SequenceSeedOverflowTest` (arithmetic over the real constants) and `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` (the real `nextSequenceNumber` across a simulated restart). Both assert that the inversion exists today and are named so they must be flipped when the scheme is fixed. The versioning code is deliberately not changed by this specification.

A second defect follows from §3.1: with a GTID present the version is anchored on statement time and bypasses the floor, so a late-committing long transaction can lose under GTID (spec 01.04 §4.2).

---

## 5. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: holds within a single run on the sequence-number path (consecutive first deliveries receive strictly ascending versions, spec 02.02 §3.3). It does **not** hold across a restart for events within ~1 s of the last pre-restart event (§4), and it is not enforced on the GTID path. Both are tracked defects.

---

## 6. Verification Criteria
- `SourceTsVersionAnchorTest.staysInTwoEightZeroDomain()` — the emitted value is `ts_ms * 1_000_000 + counter`.
- `SequenceSeedOverflowTest.testSeedDoesNotFitBeneathTheMultiplier()`, `SequenceSeedOverflowTest.testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent()`, `SequenceSeedOverflowTest.testResumeSeedStaysBelowTheNormalSeed()` — §3.3 / §4 pinned against the real constants.
- `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` — §4 reproduced through `nextSequenceNumber` itself.
- `VersionFallbackWithoutGtidTest.sequenceNumberVersionUnchanged()`, `VersionFallbackWithoutGtidTest.gtidSnowflakeVersionUnchanged()`, `VersionFallbackWithoutGtidTest.gtidPlainVersionUnchanged()` — the precedence in §3.1.
- Lean: `formal_specs/lean/Replication/Engine.lean` defines `encodeVersion` (a coordinate encoding) and versions the model by stream ordinal `liveVersion i = 2 * i`; the shipped formula in §3.2 is **not** modelled in Lean.
