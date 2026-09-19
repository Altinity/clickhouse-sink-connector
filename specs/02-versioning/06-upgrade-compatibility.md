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
provided the combined scheme stays gap-monotone.

### 3.2 Verified compatibility surfaces (2.8.0 / 2.9.1 / 2.10.3 vs 2.11.0)
1. **`_version` formula precedence preserved**: `gtid` (SnowFlakeId(ts, gtid) when
   `snowflake.id`, else raw gtid) → `sequenceNumber` (`ts_ms * 1e6 + counter`) →
   `lsn` → `SnowFlakeId(ts, kafkaOffset)`. 2.8.0 bound this inline in
   `PreparedStatementExecutor`; 2.11.0 moved it to `ClickHouseStruct.calculateVersion`
   with the SAME precedence and arithmetic (only ADDING the lsn and
   ts+offset-fallback branches). The lightweight no-GTID sequence anchor moved
   from the envelope `debezium_ts_ms` (2.8.0) to `source.ts_ms` (#1346), which is
   ≤ the envelope value; combined with the restart gap this cannot invert a
   post-upgrade row against a pre-upgrade one.
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
- **Invariant I11 (Drop-in Upgrade Safety)**: upgrading in place never ruins data already in ClickHouse; pre- and post-upgrade rows coexist and the correct (latest) row wins under `FINAL`.
- **Invariant I2/I3**: version monotonicity and convergence hold across the version boundary.

---

## 5. Verification Criteria
- `Replication.Upgrade.upgrade_safe` — a mixed old-scheme/new-scheme stream converges when the combined scheme is gap-monotone.
- `Replication.Upgrade.replicate_convergesV` — convergence for ANY gap-monotone version scheme (absolute version numbers are irrelevant; only order matters).
- `Replication.Upgrade.liveVersion_gapMono` — the shipped ordinal scheme is gap-monotone.
- `#print axioms` on the above lists only `[propext, Quot.sound]` (no `sorryAx`).
