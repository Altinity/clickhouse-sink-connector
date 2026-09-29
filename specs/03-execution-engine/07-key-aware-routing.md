# Spec 03.07: Key-Aware Row Routing

## 1. Executive Summary & Purpose

The connector hands each Debezium poll batch to a pool of writer threads by
routing every record to a worker queue and draining each queue in FIFO order
(spec 03.03). Until this spec, the routing key was the table alone, so **all**
of a table's rows were pinned to **one** worker. A single hot table therefore
saturated one thread while every other worker sat idle — measured in
production as one table taking 143.8M rows in two hours on a single worker
thread pegged at 94% CPU while eight peers were below 0.2%.

This spec changes the routing key to **(table + primary-key identity)**. A
table's rows spread across all workers, while every occurrence of the **same
row** stays on **one** worker, drained in binlog order. This is exactly the
safety rule MySQL's own multi-threaded replication uses under
`replica_parallel_type=LOGICAL_CLOCK` with
`binlog_transaction_dependency_tracking=WRITESET`: transactions that touch
disjoint row identities run in parallel; two changes to the same row are
serialized in source order; anything not row-identifiable is a barrier that
falls back to commit order. ClickHouse is the replica and MySQL is the source
of truth (`AGENTS.md`); this change only affects the *concurrency* of applying
the source's changes, never their content or order-per-row.

The behaviour is governed by the config key `routing.by.primary.key`
(default enabled). Setting it to `false` restores the exact prior table-level
routing.

## 2. Codebase Mapping on 2.11.0

- **Routing token**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/RoutedBatch.java`
  - `createShardKey(record, keyRoutingEnabled)` — the new per-record token:
    `db.table` + `\u0001` + row key when key routing is on and the record
    carries a usable key (a non-empty primary-key field list and a non-empty
    Debezium key); otherwise `db.table` (the base shard), i.e. the prior
    table-level token.
  - `createRoutingKey(topic)` — unchanged; produces the `db.table` base token.
  - `calculateThreadId(token, poolSize)` — unchanged;
    `Math.floorMod(token.hashCode(), poolSize)`.
- **Routing path**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
  - `appendToRecordsWithHashRouting` — groups a poll batch by the shard token
    (was: by `db.table`), preserving each group's binlog order, then enqueues
    each group on its owning worker queue as one group of a single handoff
    unit.
  - `appendToRecords` — brackets a batch that carries a TRUNCATE row-event with
    `awaitPipelineQuiescent()` before and after the handoff (the cross-shard
    barrier, section 3.4).
  - `awaitPipelineQuiescent` — the pipeline-quiescence wait extracted from
    `drainBeforeDDL`, reused as the TRUNCATE barrier; predicate
    `isPipelineQuiescent`.
- **Config**:
  `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java`
  (enum constant for `routing.by.primary.key`) and
  `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`
  (default `true`).
- **Offset acknowledgement** is unchanged: `Replication.OffsetFifo` (spec 09.01)
  already tracks N groups per handoff unit and acknowledges offsets strictly in
  handoff (binlog) order; key-shards are simply more groups within the same
  unit.

## 3. Operational Specification

### 3.1 The routing token

For a record `r` with topic `server.db.table`:

- key routing **off** → token = `db.table`.
- key routing **on** and `r` is a TRUNCATE row-event → token = `db.table`.
- key routing **on** and `r` has an empty primary-key list or an absent/empty
  Debezium key (a table with no primary key, or a tombstone without a key)
  → token = `db.table`.
- otherwise → token = `db.table` + `\u0001` + `r.key` (the Debezium key
  struct's stable string form).

`calculateThreadId(token, poolSize)` then maps the token into `[0, poolSize)`.

### 3.2 What this guarantees, and why it is safe

- **Same row → one worker → binlog order.** Every change to a given row
  produces the same token, so it always maps to the same worker queue, which is
  drained in FIFO order. Per-row order — the only order ReplacingMergeTree
  `_version` collapsing depends on (spec 02.01) — is preserved. This is MySQL
  WRITESET's "same key ⇒ serialized in source order" rule.
- **Disjoint rows → parallel.** Different rows of one table map to different
  workers and carry no ordering relationship, matching MySQL WRITESET's
  "transactions on disjoint row identities run in parallel". Two changes to
  different rows commute.
- **A table is uniformly sharded or uniformly single-worker.** A table with a
  primary key emits a key on every DML, so all its DML is key-sharded; only its
  (rare) TRUNCATE row-events are null-key. A table with no primary key emits no
  key, so all its rows fall back to the base shard — exactly the prior
  behaviour, safe by construction. The routing mode of a table is therefore
  stable and schema-driven; there is no per-record flip-flop that could
  reorder a table's stream.

### 3.3 No-key fallback (MySQL's `has_missing_keys` → COMMIT_ORDER)

A record with no usable row identity cannot be safely parallelized against
other rows of its table, because the connector cannot prove which rows it
touches. Such records route to the table base shard (a single worker),
serializing the whole table — the direct analogue of MySQL reverting a
no-primary-key or otherwise unidentifiable transaction to COMMIT_ORDER.

### 3.4 TRUNCATE cross-shard barrier

A TRUNCATE affects an entire table, so under key-aware routing it must be
ordered against **every** shard of that table, not just the base shard it
routes to — and, critically, against the DML of the **same** poll batch, not
only against other batches. A TRUNCATE that arrives as a DDL statement is
already handled by the global `drainBeforeDDL` barrier (Invariant I5). A
TRUNCATE that arrives as a row-event rides the normal batch path, so a poll
batch containing one is **split into ordered segments at each TRUNCATE**
(`splitAtTruncate`): each TRUNCATE becomes its own singleton segment and the
runs of DML between truncates are their own segments, preserving source order.
`appendSegmentedAtTruncate` then hands the segments off in order, bracketing
every truncate segment with `awaitPipelineQuiescent()`:

1. Hand off the pre-truncate DML segment, then drain (all shards flush
   everything that precedes the truncate).
2. Hand off the truncate alone (to the table base shard), then drain (the
   truncate is fully applied).
3. Continue with the post-truncate segment.

Bracketing the truncate *segment* — not the whole mixed batch — is what orders
it against same-batch DML: a single whole-batch bracket would still shard the
DML and the truncate of that batch onto different workers concurrently, so a
keyed insert from the same batch could be applied on the wrong side of the
truncate (deleting a post-truncate row, or resurrecting a pre-truncate one).
With segmentation, no keyed row on any shard can be applied before a truncate
that precedes it in source order, nor before a truncate that follows it. A
batch with no TRUNCATE is handed off as a single unit exactly as before, so
segmentation has no steady-state cost.

### 3.5 Recovery from any offset

Delivery is at-least-once (`OffsetFifo`, spec 09.01, and the redelivery
invariant): after a restart the connector redelivers from the last committed
offset. Routing is a pure deterministic function of the record, so a
redelivered event lands on the same shard as before — same-row order is
preserved across the restart — and ReplacingMergeTree `_version` collapses the
duplicate. Ordering keys on the binlog position, never on `source.ts_ms` (spec
02.01), so a late-committing long transaction cannot invert order. Offset
acknowledgement is unchanged: a batch's offset is staged only when every
lower-sequence batch (queued, in flight, or parked) has been acknowledged, so
the committed offset remains a low-water mark from which replay is safe.

## 4. Invariants Preserved

- **Invariant I1 (Log Sequence Monotonicity)** — per-row order is preserved by
  same-token-same-worker FIFO; the truncate barrier preserves the relative
  order of a truncate against every record. Formalised as
  `Replication.KeyRouting.same_key_same_shard`,
  `Replication.KeyRouting.per_key_order_preserved`, and
  `Replication.KeyRouting.barrier_totally_orders_truncate`.
- **Invariant I3 (Eventual Convergence Under ReplacingMergeTree)** —
  disjoint-row changes commute, so any parallel interleaving of different rows
  yields the same FINAL state as the source; same-row order (which `_version`
  collapsing relies on) is preserved. Recovery under redelivery converges:
  `Replication.KeyRouting.disjoint_keys_independent`,
  `Replication.KeyRouting.recovery_converges`.
- **Invariant I5 (DDL Barrier Quiescence)** — unchanged: `drainBeforeDDL`
  still runs the same global quiescence wait (now via `awaitPipelineQuiescent`)
  before pausing the pool; the TRUNCATE row-event reuses the same quiescence
  barrier.
- **Invariant I8 (Durable Offset Quiescence)** — unchanged: offsets are
  acknowledged in handoff (binlog) order by `Replication.OffsetFifo`;
  key-shards are additional groups within the existing handoff unit, so a
  batch's offset is still staged only when every lower-sequence batch is
  acknowledged.
- **Invariant I11 (Drop-in Upgrade Safety)** — key-aware routing changes only
  WHICH worker applies a row, never the row's bytes or its `_version`, so a
  connector build (or a `routing.by.primary.key` config flip) can be upgraded
  and downgraded freely. The converged per-key version is a max over the
  events, independent of routing mode and of the order two segments — one under
  each routing, e.g. before and after the restart — are applied:
  `Replication.KeyRouting.finalVersion_append`,
  `Replication.KeyRouting.upgrade_downgrade_converges`. See section 6.

## 5. Verification Criteria

- `RoutedBatchTest.testKeyedRecordsOfSameTableSplitAcrossShards` — two rows of
  one table with different keys produce different shard tokens (and can land on
  different worker queues).
- `RoutedBatchTest.testSameRowKeyAlwaysSameShard` — the same table+key+op
  always produces the same shard token and thread id across pool sizes.
- `RoutedBatchTest.testNullKeyFallsBackToTableShard` — a record with no key
  routes to the `db.table` base shard.
- `RoutedBatchTest.testTruncateFallsBackToTableShard` — a TRUNCATE row-event
  routes to the `db.table` base shard even when a key is present.
- `RoutedBatchTest.testKeyRoutingDisabledUsesTableShard` — with key routing
  off, a keyed record routes to the `db.table` base shard (exact prior
  behaviour).
- `HashRoutingPerTableOrderingTest.differentRowsOfOneTableCanRouteToDifferentQueues`
  — under key routing, distinct rows of one table are enqueued on more than one
  worker queue.
- `HashRoutingPerTableOrderingTest.sameRowKeyStaysOnOneQueue` — every batch for
  one row lands on exactly one worker queue, in order.
- `HashRoutingPerTableOrderingTest.truncateInMixedBatchSplitsIntoOrderedSegments`
  — a poll batch of `[INSERT, TRUNCATE, INSERT]` splits into three ordered
  segments with the TRUNCATE isolated in its own singleton segment, so it is
  drained-bracketed against the same-batch DML.
- `Replication.KeyRouting.same_key_same_shard` — same table + same key + DML map
  to the same shard.
- `Replication.KeyRouting.disjoint_keys_independent` — records on different
  shards carry no cross-shard ordering constraint.
- `Replication.KeyRouting.per_key_order_preserved` — the routed sub-stream for
  one shard is a sublist of the source, preserving binlog order.
- `Replication.KeyRouting.barrier_totally_orders_truncate` — with the barrier,
  a truncate is applied after every earlier record and before every later one,
  on every shard; the counterexample shows an unbarriered truncate can reorder.
- `Replication.KeyRouting.recovery_converges` — replaying a redelivered prefix
  never lowers the converged per-key version (idempotent under `_version`
  selection).
- `Replication.KeyRouting.finalVersion_append` — the converged per-key version
  of a concatenation is the max of the parts', so it does not depend on how the
  stream is split across workers.
- `Replication.KeyRouting.upgrade_downgrade_converges` — a stream applied partly
  under one routing mode and partly under the other converges to the same
  per-key version, in either direction (Invariant I11).
- `RoutedBatchTest.testRoutingModeDoesNotChangeRowIdentity` — the same record
  produces the same shard token under a fixed mode, and switching the mode
  changes only the shard, never the record — so no data differs across an
  upgrade/downgrade.
- Existing Docker end-to-end and kill/restart-recovery ITs
  (`NullColumnValueRoundTripIT`, `PostgresSnapshotCompletionIT`) also exercise
  the routing path with key routing enabled by default.


## 6. Upgrade & downgrade compatibility

Key-aware routing is **data-format-neutral**: it changes only which worker
applies a given row, never the row's bytes, its column set, or its
ReplacingMergeTree `_version`. Two connectors -- one routing by table, one
routing by (table + key) -- write the *same* rows with the *same* versions;
they differ only in concurrency. This is what makes upgrade and downgrade safe.

- **Upgrade** (a build or config that turns key routing on) and **downgrade**
  (turning it off, e.g. `routing.by.primary.key=false`, or rolling back to a
  build without this change) are each just a restart. The connector resumes
  from the last committed offset -- a low-water mark (spec 09.01) -- and replays
  everything above it. Replay is idempotent: routing is a deterministic
  function of the record, and `_version` (derived from binlog position, never
  `source.ts_ms`) collapses any duplicate, so a row re-applied by a *different*
  worker after the switch converges to the same value
  (`Replication.KeyRouting.recovery_converges`).
- **Order across the switch is preserved.** Within any single run a table's
  routing mode is fixed (all key-sharded, or all single-worker), so its stream
  is never internally reordered. Across the switch, the converged per-key
  version is a max over the events and is independent of the routing mode and
  of the order the pre- and post-switch segments are applied
  (`Replication.KeyRouting.finalVersion_append`,
  `Replication.KeyRouting.upgrade_downgrade_converges`) -- so table-routed then
  key-routed, or the reverse, reach the identical ClickHouse FINAL state
  (Invariant I11).
- **CollapsingMergeTree** tables carry the same pre-existing at-least-once
  caveat as before this change: a redelivered `sign` (+1/-1) pair after any
  restart must be replay-additive. Routing does not affect this -- it changes
  the worker, not what is written -- so upgrade/downgrade behaviour on a
  CollapsingMergeTree table is exactly what it was without key routing.
- **No migration, no version-domain change.** Because nothing about the stored
  data changes, there is no backfill, no rewrite, and no coordinated cutover:
  the config can be flipped, or the build rolled forward/back, on a running
  replica at any time.

This is proven at the model level by
`Replication.KeyRouting.upgrade_downgrade_converges` and is additionally
covered by an end-to-end integration test that runs the connector with key
routing on, restarts it with it off (downgrade) and on again (upgrade), and
checks ClickHouse converges value-identically to MySQL across both switches.
