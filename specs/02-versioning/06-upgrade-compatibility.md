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
the old one wrote. Because an upgrade is a restart, every post-upgrade event's
source commit time is later than any pre-upgrade event's, so post-upgrade rows
carry higher versions and correctly supersede pre-upgrade rows; re-delivered
events (at-least-once) carry versions that LOSE to the already-written rows and
are discarded. Formalised: `upgrade_safe` proves that a stream whose first `n`
ordinals use the OLD version scheme and the rest use the NEW scheme converges,
provided the combined scheme stays gap-monotone. §6 lists the situations in which
the shipped code does not yet establish that proviso.

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

### 3.3 Behaviour changes on restart (surfaced, not data-format breaks)
The merged fixes change failure/ordering behaviour but not any persisted format:
DDL failures now halt loudly instead of being swallowed; `TOO_MANY_PARTS` retries
instead of stopping; multi-thread mode now routes per table for FIFO ordering.
None alters `_version`, the offset store, or the schema — all upgrade-safe.

---

## 4. Invariants Preserved
- **Invariant I11 (Drop-in Upgrade Safety)**: formats, config keys and version precedence are preserved across the upgrade; pre- and post-upgrade rows coexist and the latest row wins under `FINAL` **wherever version order is continuous across the boundary** (see §6 for where it is not yet).
- **Invariant I2/I3**: version monotonicity and convergence hold across the version boundary under the same proviso.

---

## 5. Verification Criteria
- `Replication.Upgrade.upgrade_safe` — a mixed old-scheme/new-scheme stream converges when the combined scheme is gap-monotone.
- `Replication.Upgrade.replicate_convergesV` — convergence for ANY gap-monotone version scheme (absolute version numbers are irrelevant; only order matters).
- `Replication.Upgrade.liveVersion_gapMono` — the shipped ordinal scheme is gap-monotone.
- `#print axioms` on the above lists only `[propext, Quot.sound]` (no `sorryAx`).
- `SequenceSeedOverflowTest`, `DebeziumChangeEventCaptureTest.knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent()` — §6.1 pinned as a present defect.
- `VersionFallbackWithoutGtidTest.bindMustNotWriteUint64Max()`, `VersionFallbackWithoutGtidTest.calculateVersionMustNotFallThroughToSentinel()` — 2.11.0 no longer writes the sentinel of §6.3.
- Verification: §6.2 (lagging connector at upgrade time) is not yet covered by an automated test (gap).

---

## 6. Caveats: where the code does not yet establish the theorem's hypothesis
`upgrade_safe` is conditional on `GapMono (upgradeScheme vOld vNew n)`: the combined
old/new version scheme must be strictly increasing (gap of at least two per
ordinal) across the upgrade boundary. The shipped code does **not** establish that
hypothesis in three situations. None of them is a data-format break; all three are
version-ordering gaps that the theorem, by construction, does not cover.

### 6.1 The restart carry defect
An upgrade is a restart, so the first record after it is versioned with the
counter seeded at `SEQUENCE_START_INITIAL` (500m) in the formula
`effectiveTs * 1_000_000 + seq`, whose six low digits the ten-digit seeds
overflow. A genuinely newer post-upgrade event within ~1 s of the last
pre-upgrade event can rank below it (spec 02.01 §4, quoted from
`DebeziumOffsetManagement`). Across that window the combined scheme is not
gap-monotone, so `upgrade_safe` does not apply.

### 6.2 A connector that is lagging at upgrade time
§3.1 assumes every post-upgrade event's source commit time is later than every
pre-upgrade event's. That is true of the source clock, but not necessarily of the
*anchor* each release used: 2.8.0 anchored the no-GTID version on the envelope
(processing) timestamp `debezium_ts_ms`, and 2.11.0 still does so for snapshot
rows and records without a source struct. For a lagging connector the processing
time is later than the source time. After the upgrade the no-GTID path anchors on
`source.ts_ms` (#1346). If the connector was N seconds behind at the upgrade, the
first N seconds of post-upgrade versions are anchored on source times that can be
**below** the processing-time-anchored versions of the last pre-upgrade writes for
the same keys. The combined scheme is not gap-monotone over that range and the
pre-upgrade row can win under `FINAL` until a later update of the key arrives.

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

Until 6.1 and 6.2 are closed the honest reading of I11 is: the upgrade preserves
formats and precedence, and `upgrade_safe` proves convergence **given** version
order continuity; version order continuity itself is not yet guaranteed by the
code across the boundary in the two windows described above.
