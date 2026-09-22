# Spec 02.06: Drop-in Upgrade Compatibility (2.8.0 / 2.9.1 / 2.10.x → 2.11.0)

## 1. Executive Summary & Purpose
Specifies the contract that makes a newer connector version a **drop-in
replacement** for an older one: an in-place upgrade — same ClickHouse tables, same
persisted offset store, same schema history, same config — must NOT ruin data
already in ClickHouse (Invariant I11). Rows written by the old version and rows
written by the new version coexist in one `ReplacingMergeTree` table, and the
`FINAL` view must still equal the source database.

---

## 2. Codebase Mapping on 2.11.0
- **Version assignment**: `sink-connector-lightweight/.../cdc/DebeziumChangeEventCapture.java#nextSequenceNumber` and `sink-connector/.../model/ClickHouseStruct.java#calculateVersion`.
- **Persisted offset store / schema history**: Debezium JDBC storage into `replica_source_info` / schema-history tables, configured by `offset.storage.*` / `schema.history.internal.*`.
- **Config surface**: `ClickHouseSinkConnectorConfigVariables` (key names) and `ClickHouseSinkConnectorConfig` (hardcoded defaults).
- **Formal model**: `formal_specs/lean/Replication/Upgrade.lean` (`upgrade_safe`, `replicate_convergesV`).

---

## 3. Operational Specification

### 3.1 The upgrade-safety condition (version ordering across the boundary)
`_version` must remain strictly increasing in source commit order across the
upgrade, and the new version's assignments must continue ABOVE the last version
the old one wrote. An upgrade is a restart, and 2.11.0 establishes the "continue
above" clause explicitly rather than assuming it from the source clock: at engine
start the version floor is seeded from a durable high-water mark of the versions
already handed to the writers (spec 02.02 §3.5), so every post-upgrade first
delivery is versioned strictly above every pre-upgrade row — regardless of
replication lag, of the clock skew between MySQL and the connector host, and of
which timestamp the previous release anchored on. Re-delivered events
(at-least-once) are versioned above the copies already stored and carry the same
data (spec 02.04 §3.2). Formalised: `upgrade_safe` proves that a stream whose
first `n` ordinals use the OLD version scheme and the rest use the NEW scheme
converges, provided the combined scheme stays gap-monotone;
`Replication.VersionFloor.restart_boundary` proves that seeding makes the new
scheme's first deliveries exceed the old scheme's versions. §6 records the one
window the code still leaves and the downgrade behaviour.

### 3.2 Verified compatibility surfaces (2.8.0 / 2.9.1 / 2.10.3 vs 2.11.0)
1. **`_version` formula precedence preserved**: `gtid` (SnowFlakeId(ts, gtid) when
   `snowflake.id`, else raw gtid) → `sequenceNumber` (`ts_ms * 1e6 + counter`) →
   `lsn` → `SnowFlakeId(ts, kafkaOffset)`. 2.8.0 bound this inline in
   `PreparedStatementExecutor`; 2.11.0 moved it to `ClickHouseStruct.calculateVersion`
   with the SAME precedence and arithmetic (only ADDING the lsn and
   ts+offset-fallback branches). The lightweight no-GTID sequence anchor moved
   from the envelope `debezium_ts_ms` (2.8.0) to `source.ts_ms` (#1346), which is
   ≤ the envelope value; see §6.2 for the lagging-connector consequence of that
   anchor change.
2. **Persisted formats identical**: Debezium is `3.1.3.Final` in all four
   versions, so the offset-store and schema-history serialisation is byte-compatible
   and 2.11.0 reads the committed position written by any of them.
3. **Config keys stable**: no key removed or renamed between 2.8.0 and 2.11.0.
4. **Column contract stable**: the hardcoded `replacingmergetree.delete.column`
   default is `"sign"` in both 2.8.0 and 2.11.0 (only the example `config.properties`
   changed); an existing deployment keeps its own config, so its delete/sign/version
   column names are unchanged.
5. **Additive high-water table**: 2.11.0 creates `replica_version_high_water` in
   the offset database (spec 09.03 §3.4) and reads it at start. Older releases do
   not know the table and ignore it; nothing they read or write changes. No config
   key is added: the table's database is derived from
   `offset.storage.jdbc.table.name`.

#### 3.2.1 `snowflake.id=false` with a data snapshot
With `snowflake.id=false` a GTID-bearing record is versioned with the **raw GTID
transaction number** (order $10^6$–$10^{10}$), while snapshot rows and no-GTID
rows take the sequence path (order $1.7 \times 10^{18}$). A streaming row of a
key that was also read by the snapshot therefore always **loses** to the snapshot
row, permanently. This is a data-destroying configuration whenever
`snapshot.mode` reads data (`initial`, `initial_only`, `always`, `when_needed`,
and `configuration_based` / `custom` when they snapshot data). Under I11 an
existing configuration may not be refused on upgrade, so the lightweight engine
does not fail: `DebeziumChangeEventCapture.setup` evaluates
`rawGtidVersioningWithDataSnapshot(props, config)` and, when `snowflake.id=false`
is combined with a `snapshot.mode` other than `never`, `no_data`, `schema_only`,
`recovery` or `schema_only_recovery` (an unset `snapshot.mode` is Debezium's
default `initial` and counts as a data snapshot), logs at **ERROR** naming both
keys and the consequence — once per start, before the engine is created. The
remediation named in the log is `snowflake.id=true` or a no-data snapshot mode.
Tagged GTIDs (`uuid:tag:n`, MySQL 8.3+) are parsed from their last segment
(spec 02.01 §3.1), so a tagged transaction no longer falls through to the
sequence path with `gtid = -1` and loses to its snowflake-versioned neighbours.

### 3.3 Behaviour changes on restart (surfaced, not data-format breaks)
The merged fixes change failure/ordering behaviour but not any persisted format:
DDL failures now halt loudly instead of being swallowed; `TOO_MANY_PARTS` retries
instead of stopping; multi-thread mode now routes per table for FIFO ordering;
the version floor is seeded at start from the high-water table and heartbeats no
longer enter the version sequence (spec 02.02 §3.2, §3.5). None alters
`_version`'s formula or encoding, the offset store, or the schema — all
upgrade-safe.

---

## 4. Invariants Preserved
- **Invariant I11 (Drop-in Upgrade Safety)**: formats, config keys and version precedence are preserved across the upgrade; pre- and post-upgrade rows coexist and the latest row wins under `FINAL`. Version-order continuity across the boundary is established by the seeded floor (§3.1); §6 records the one remaining window and the downgrade behaviour.
- **Invariant I2/I3**: version monotonicity and convergence hold across the version boundary under the same conditions.

---

## 5. Verification Criteria
- `Replication.Upgrade.upgrade_safe` — a mixed old-scheme/new-scheme stream converges when the combined scheme is gap-monotone.
- `Replication.Upgrade.replicate_convergesV` — convergence for ANY gap-monotone version scheme (absolute version numbers are irrelevant; only order matters).
- `Replication.Upgrade.liveVersion_gapMono` — the shipped ordinal scheme is gap-monotone.
- `Replication.VersionFloor.restart_boundary` — with the floor seeded from a mark at or above every old-run version, every first delivery of the new run exceeds every old-run version (the "continue above" clause of §3.1).
- `#print axioms` on the above lists only `[propext, Quot.sound]` (no `sorryAx`).
- `SequenceSeedOverflowTest` — the unseeded carry arithmetic of §6.1, preserved deliberately.
- `DebeziumChangeEventCaptureTest.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent()` — the seeded restart on a lagging source (§6.1, §6.2); `DebeziumChangeEventCaptureTest.newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent()` — the seeded restart inside the 1 ms carry window (§6.1).
- `VersionHighWaterMarkTest.scanSeedsFromTheHighestPlausibleTargetVersion()` — the first start after an upgrade, with no mark row, seeds from `max(_version)` of the targets in either version domain (§6.1).
- `VersionFallbackWithoutGtidTest.bindMustNotWriteUint64Max()`, `VersionFallbackWithoutGtidTest.calculateVersionMustNotFallThroughToSentinel()` — 2.11.0 no longer writes the sentinel of §6.3.
- `SnowflakeIdSnapshotWarningTest.rawGtidVersioningWithDataSnapshotIsLoud()`, `SnowflakeIdSnapshotWarningTest.noDataSnapshotModesAreSilent()` — §3.2.1: the combination is detected (including the unset default) and logged at ERROR; no-data modes and `snowflake.id=true` are silent.
- `ClickHouseStructTest.taggedGtidIsParsed()` — §3.2.1, tagged GTIDs take the GTID path.
- Verification: an end-to-end upgrade test (2.10.x writes, 2.11.0 restarts on a lagging source) is not yet covered by an automated test (gap).

---

## 6. Caveats: the theorem's hypothesis across the boundary
`upgrade_safe` is conditional on `GapMono (upgradeScheme vOld vNew n)`: the combined
old/new version scheme must be strictly increasing (gap of at least two per
ordinal) across the upgrade boundary. The seeded floor establishes the boundary
part of that hypothesis (§6.1); §6.2 states what remains for a first start with no
seed source, and §6.3 the rows no scheme can continue above.

### 6.1 The restart carry (inherited 2.8.0 behaviour) and the upgrade / downgrade matrix

> **Compatibility constraint (governing rule).** The emitted `_version` domain — `ts_ms × 1_000_000 + counter` with the 2.8.0 seeds — is the contract shared with 2.8.0, 2.9.1 and 2.10.x. It MUST NOT change: a version written by any of those releases must rank consistently against one written by 2.11.0 in BOTH directions (upgrade and downgrade). The restart carry described here is therefore inherited 2.8.0 behaviour that is preserved deliberately; any improvement is confined to WHERE the restart floor starts (seeding the anchor from the target's `max(_version)` or a persisted last-emitted version) and must ship with an explicit upgrade/downgrade matrix against 2.8.0, 2.9.1 and 2.10.x.

**What 2.11.0 does and does not change in the `_version` domain.** The formula
(`effectiveTs * 1_000_000 + counter`), the 2.8.0 seeds (`SEQUENCE_START_INITIAL`
= 500m, `SEQUENCE_START` = 1e9) and the snowflake bit layout
(`SnowFlakeId.generate(ts, gtid)`: 41 timestamp bits above a 22-bit transaction
number) are byte-identical to 2.8.0, 2.9.1 and 2.10.x — `SnowFlakeId` only gained
public constants (`SNOWFLAKE_EPOCH`, `GTID_FIELD_BITS`) so the startup seed can
decode stored values. Only what feeds the formula changed: the floor is seeded
across a restart (spec 02.02 §3.5), control records no longer move it (spec 02.02
§3.2), the high-water position resets on a log basename change (spec 01.02
§3.1.1), and the GTID path feeds the clamped `effectiveTs` instead of the raw
`source.ts_ms` (spec 02.01 §3.1). A version written by any of the four releases
is therefore comparable with any other by plain integer order, in both
directions.

The carry is unchanged: the first record after any start is still versioned with
the counter seeded at `SEQUENCE_START_INITIAL` (500m) in
`effectiveTs * 1_000_000 + seq`, whose six low digits the ten-digit seeds
overflow (spec 02.01 §3.3). What differs in 2.11.0 is `effectiveTs`: the floor
is seeded from the high-water mark, so the first post-restart record is clamped
to `floorDiv(V_max, 1e6) + 1` and its version exceeds `V_max` (spec 02.02 §3.5).
The window this closes was never ~1 ms: on a lagging source it was the lag plus
the ~0.5 s carry, because the previous run's floor had been pinned to the
connector clock by heartbeats.

**Upgrade (2.8.0 / 2.9.1 / 2.10.x → 2.11.0).** The first 2.11.0 start finds no
`replica_version_high_water` row and seeds from `max(_version)` over the target
tables (sequence-domain and snowflake-domain values are both decoded, spec 02.02
§3.5 (2)); that maximum is, by definition, at or above every version the old
release wrote — whichever timestamp it anchored on (envelope time in 2.8.0,
`source.ts_ms` since #1346, or a heartbeat-pinned connector clock). Every
post-upgrade first delivery therefore ranks above every pre-upgrade row
(`Replication.VersionFloor.restart_boundary`). From the second start on, the
table itself supplies the seed.

**Downgrade (2.11.0 → 2.10.x / 2.9.1 / 2.8.0).** The old release ignores the
table and starts with the floor at `0`; whether its first rows rank above
2.11.0's last rows depends on the timestamp it anchors on:
- **2.8.0** anchors the no-GTID version on the envelope (processing) timestamp,
  which is at or after the source time 2.11.0 anchored on, so 2.8.0's first rows
  rank above 2.11.0's last rows except within the ~0.5 s counter carry
  (`SEQUENCE_START_INITIAL` versus `SEQUENCE_START`) — the same window a 2.8.0
  restart always had.
- **Releases anchoring on `source.ts_ms` without a seeded floor** re-open the
  restart window they had before: roughly the carry (~0.5 s) plus, if 2.11.0
  was restarted within the previous few seconds, the horizon head-room (≤ 5 s of
  source time, spec 02.02 §3.5 (1)). Because 2.11.0 no longer pins the floor to
  the connector clock through heartbeats, its steady-state versions track the
  source clock and the lag no longer widens that window. A downgrade should be
  performed with the source quiescent for a few seconds to fall outside it.
- **GTID path**: 2.11.0 feeds the floored `effectiveTs` into the snowflake
  (spec 02.01 §3.1); an older release feeds the raw `source.ts_ms`. Its first
  rows rank above 2.11.0's last rows once the source clock passes the last floor
  2.11.0 used — normally immediately, since in steady state the floor is the
  source clock — with the same few-seconds caveat after a recent 2.11.0 restart.

### 6.2 A first start with no seed source
§3.1's guarantee needs a seed. It is absent only when there is no
`replica_version_high_water` row **and** the target scan finds nothing usable:
`database.include.list` is empty or contains a pattern, the targets carry no
`_version` column, or every candidate is implausible (spec 02.02 §3.5 (2)). The
engine then logs at WARN and runs unseeded, i.e. with the pre-2.11.0 behaviour
for that one start: for a connector lagging N seconds at that moment, the first
N seconds of versions can fall below processing-time-anchored rows of the
previous release. The mark is written from the first handoff on, so every later
start is seeded.

### 6.3 UInt64-max sentinel rows written by older versions
Before the timestamp+offset fallback in `ClickHouseStruct.calculateVersion`
existed, a record with no gtid, sequence number or lsn kept `version = -1`,
which `setLong` stored into the `UInt64` `_version` column as
`18446744073709551615` (the Javadoc on `calculateVersion` records this, issue
#1213). Such a row holds the maximum possible version: no later row — from any
connector version — can ever supersede it under `ReplacingMergeTree`. Upgrading
does not repair those rows; they must be found and rewritten out-of-band.
`upgrade_safe` cannot cover them because no gap-monotone scheme can continue
above the maximum value.

The honest reading of I11 is therefore: the upgrade preserves formats and
precedence; `upgrade_safe` proves convergence **given** version order continuity;
and continuity across the boundary is established by the seeded floor
(`Replication.VersionFloor.restart_boundary`) on every start that has a seed
source (§6.1), and is not established on a first start without one (§6.2) or
above sentinel rows (§6.3).
