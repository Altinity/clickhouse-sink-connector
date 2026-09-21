# Spec-Driven Development Constitution: ClickHouse Sink Connector (2.11.0)

## Preamble

This Constitution establishes the foundational laws, architectural invariants, and engineering governance for the `clickhouse-sink-connector`.

The primary mission of this software is strictly defined:
> **The ClickHouse Sink Connector is an exact, high-performance, strictly ordered, zero-loss replication engine from transactional MySQL databases to analytical ClickHouse clusters using the MySQL binary log (binlog).**

Every component, data structure, concurrency primitive, and operational protocol in this repository exists solely to fulfill this replication mission with mathematical precision and zero data divergence.

---

## 1. The Prime Directive: Absolute Source of Truth

Replication is fundamentally an asymmetric contract between a canonical system of record and an analytical replica. This relationship is governed by the four non-negotiable principles of the Prime Directive:

1. **MySQL Is the Absolute Source of Truth**:
   The source MySQL database defines what the data *is*. There is no circumstance, configuration, or operational condition under which the connector considers MySQL to be in error.
2. **ClickHouse Conforms to MySQL**:
   ClickHouse is the replica. Its schemas, table definitions, column types, nullabilities, default behaviors, and row states exist exclusively to mirror the MySQL state.
3. **Every Mismatch Is Resolved by Correcting ClickHouse**:
   Divergence is never resolved by suppressing, modifying, or reinterpreting source events. The direction of reconciliation is strictly one-way: ClickHouse moves to match MySQL.
4. **The Connector Is an Active Enforcer, Not a Passive Reporter**:
   When ClickHouse schema constraints or column definitions prevent storing source data (for example, a `MATERIALIZED` column shadowing source values), the connector automatically alters the ClickHouse schema (e.g. converting `MATERIALIZED` to `DEFAULT`) to allow ingestion. Logging a warning without attempting automated remediation is a failure of enforcement.

---

## 2. Spec-Driven Development (SDD) Mandate

To eliminate architectural regression, silent data corruption, and complexity drift, this repository operates under a strict **Spec-Driven Development** mandate, adopting the **Smart Ralph** protocol (inspired by https://github.com/tzachbon/smart-ralph).

### The Five Laws of Spec-Driven Engineering

1. **Law of Spec Precedence (No Code Without Spec)**:
   No code shall be added, modified, or deleted without an explicit, approved specification document in the `specs/` directory. Unspecified code is unauthorized code.
2. **Law of Spec Declaration**:
   Every change—whether a new feature, a bug fix, an optimization, or a refactor—must first declare its intent, invariants, exact change scope, failure modes, and verification criteria in a specification document before any implementation begins.
3. **Law of Strict Implementation Fidelity**:
   Implementation must match the declared specification exactly. Adding unrequested features, speculative abstractions, unused configuration flags, or extraneous refactorings violates fidelity.
4. **Law of Dual Verification (Empirical & Formal)**:
   Every specification must define two verification dimensions:
   - **Empirical Verification**: Unit tests, integration tests, and checksum comparisons against live databases.
   - **Formal Verification**: Alignment with the formal Lean 4 replication model (`formal_specs/lean/`), proving that the change preserves log monotonicity, version consistency, and convergence invariants.
5. **Law of Reviewed Immutability**:
   Once a specification is approved and merged, it serves as the ground truth. Subsequent changes require an explicit revision to the specification document.

---

## 3. Core System Invariants

The sink connector must maintain the following thirteen immutable invariants (I1–I13) across all operations:

### Invariant I1: Log Sequence Monotonicity
The relative ordering of transactional commits in the MySQL binary log must be strictly preserved during ingestion. Transactions committing at coordinate $(F_1, P_1)$ must be processed prior to or assigned an earlier version than transactions committing at $(F_2, P_2)$ where $(F_1, P_1) < (F_2, P_2)$.

### Invariant I2: Deterministic Version Monotonicity
For any given primary key $K$, every subsequent commit in MySQL must yield a strictly greater version number `_version` in ClickHouse:
$$\forall K, \quad \text{Commit}_1(K) \prec \text{Commit}_2(K) \implies \text{Version}_1(K) < \text{Version}_2(K)$$
Because MySQL `source.ts_ms` is the statement execution time and not commit time, the version generator must enforce high-water floor clamping to prevent version inversion on late-committing transactions.

### Invariant I3: Eventual Convergence Under ReplacingMergeTree
Given an initial empty state and a sequence of binlog events $S$, the evaluated state in ClickHouse under `ReplacingMergeTree` with `FINAL` semantics must be identical in row set and column values to the state of MySQL after applying $S$:
$$\forall K, \quad \text{ClickHouse}_{\text{FINAL}}(K) = \text{MySQL}(K)$$

### Invariant I4: Sorting Key Mutation Integrity
Because ClickHouse `ReplacingMergeTree` cannot replace rows across differing `ORDER BY` sorting keys, an `UPDATE` that modifies any sorting key column must execute as an atomic two-phase operation:
1. An explicit delete tombstone (`is_deleted = 1`, `_version = v`) for the old sorting key, written **after** the live row it retires.
2. A live insert (`is_deleted = 0`, `_version = v`) for the new sorting key.

Both rows carry the event's own version `v`. The tombstone must satisfy
`v_orig <= v` against the live row already stored at the old key; under GTID
versioning a relocation in the same transaction as the original INSERT has
`v_orig = v`, and the equal case is resolved by ClickHouse's ReplacingMergeTree
tie rule (equal version: the later-inserted row wins), which the tombstone wins
by being written later. A tombstone at `v - 1` would lose that tie and leave a
ghost row (Spec 05.02 §3.2).

### Invariant I5: DDL Barrier Quiescence (Zero Schema Inversion)
DDL statements alter the relational contract. A DDL event must establish an absolute execution barrier:
1. All DML records preceding the DDL in the binlog stream must be flushed and durably committed under the pre-DDL schema — on **every** handoff path: the legacy shared queue, **every** per-thread hash-routing queue, and the batches a worker has already dequeued but not yet acknowledged. The barrier predicate is `isPipelineQuiescent()` (spec 06.01); formally `Replication.DdlBarrier.barrierReady`. Observing only one path is not a barrier (`Replication.DdlBarrier.old_predicate_insufficient`).
2. Worker execution must quiesce (`activeBatches == 0`) with the pool paused.
3. DDL must be translated and executed on ClickHouse. A DDL that cannot be applied is terminal and loud (I9) regardless of `ddl.retry`, which only decides whether attempts are repeated first (spec 06.08 §3.2).
4. Schema caches must be invalidated before any post-DDL records are dispatched to workers.

### Invariant I6: Column Authority & Shadowing Prohibition
If a column exists in the MySQL source table, its value must be stored in ClickHouse:
- **ALIAS Columns**: Ignored (query-time expressions, not stored).
- **MATERIALIZED Columns**: If present in MySQL, the MySQL value wins. The connector must alter the ClickHouse column to `DEFAULT` to allow writing.
- **DEFAULT Columns**: Source values (including explicit `NULL`) must be bound. ClickHouse defaults apply only when the source column was omitted prior to an ALTER.

### Invariant I7: Value-Level Type Equivalence
All data types must preserve value fidelity across boundaries:
- Decimal scales and precisions must not suffer binary floating-point rounding.
- Nullability must be preserved: explicit `NULL` in MySQL must store as `NULL` in ClickHouse (never substituted by `0` or empty string).
- Timezones must be converted deterministically to match server/session expectations without daylight-saving shifts.

### Invariant I8: Durable Offset Quiescence
Kafka / Debezium offsets committed to persistent storage (`replica_source_info`) must be strictly monotonically advancing and must reflect only data that has been durably acknowledged by ClickHouse JDBC batches. Heartbeat / control record offsets may only commit when the entire pipeline is quiescent.

Concretely, batches handed to the writers are acknowledged in **handoff
sequence** order — the order the Debezium thread handed them off, which is
binlog order — never by wall-clock timestamp: a batch's offset is staged only
when every lower-sequence batch (queued, in flight, or parked) has been
acknowledged, and a batch written out of turn is parked, not re-executed. A
handed-off batch is outstanding from the instant of handoff until its
acknowledgement, and the pipeline is quiescent iff no sequence is outstanding.
An in-process engine restart (`stop()`) abandons what is still outstanding once
the pool has terminated — never anything acknowledged — so the next engine
starts quiescent and redelivers the abandoned rows (spec 09.01 §3.8).
Formalised in `formal_specs/lean/Replication/OffsetFifo.lean`
(`commit_never_passes_outstanding`, `acked_downward_closed`,
`outstanding_ge_commitPoint`, `write_at_most_once`, `old_overlap_rule_unsafe`,
`restart_quiescent`, `acked_never_rolled_back`, `old_restart_poisons_fifo`).

### Invariant I9: Loud Failure (Zero Silence)
Replication errors, checksum mismatches, and schema translation failures must fail loudly. No replication exception shall be caught and suppressed to allow a batch to proceed. Row count parity shall never substitute for value-level checksum verification.

### Invariant I10: Structural Separation of Concerns
The connector is strictly a replication tool. Business logic transformations, cross-database joins, and data enrichment belong in downstream transformation layers (views, dbt), not inside the replication pipeline.

### Invariant I11: Drop-in Upgrade Safety
A newer connector version MUST be a drop-in replacement for an older one:
upgrading in place (e.g. 2.8.0 / 2.9.1 / 2.10.x → 2.11.0) — same ClickHouse
tables, same persisted offset store, same schema history, same config — MUST NOT
ruin data already in ClickHouse. Rows written by the old version and rows written
by the new version coexist in one `ReplacingMergeTree` table and the `FINAL` view
must still equal the source. This holds iff the version assignment is preserved
across the boundary: both versions assign `_version` strictly increasing in source
commit order, and the new version continues that ordering ABOVE the last version
the old version wrote (across the restart the source commit clock only advances).
Concretely this requires: the `_version` formula precedence and arithmetic are
preserved; the persisted offset store and schema-history table formats (and the
Debezium version that serialises them) are compatible so committed positions are
readable; no config key is removed or renamed and no hardcoded default that maps
to an existing table column changes. Formalised as `upgrade_safe` in
`formal_specs/lean/Replication/Upgrade.lean`.

### Invariant I12: Snapshot Completion & Control-Record Offset Progress
A record that produces no ClickHouse row (a heartbeat or transaction-boundary
event) MUST be handled so that: (safety) its source offset is committed ONLY when
the pipeline is quiescent — no rows handed to the writers are still unwritten — so
the durable position never advances past data not yet in ClickHouse; and
(liveness) its offset IS committed once the pipeline is quiescent. Because a
snapshot's `snapshot_completed=true` state rides only on a post-snapshot control
record, violating liveness strands the snapshot and re-runs it on every restart
(issue #1379). Offset progress must not depend on where control records fall in a
Debezium batch: every list handed to the writers (a handoff unit) carries exactly
one terminal marker, on its last row in binlog order, and is acknowledged as a
whole — one `markBatchFinished()` — once all of its routed groups are written and
every earlier handoff unit is acknowledged, so its offset is flushed exactly once
regardless of which worker finished last. Formalised as `control_commit_safe`,
`quiescent_control_commits`, and `snapshot_completes` in
`formal_specs/lean/Replication/Snapshot.lean`.

### Invariant I13: Generated-Column Type Integrity
When translating a source column declared `GENERATED ALWAYS AS (expr)`, the
ClickHouse column MUST keep its DECLARED data type and the generation expression
MUST be emitted as a `DEFAULT` clause — never as the column type. This holds on
BOTH the CREATE TABLE and ALTER TABLE ADD/MODIFY paths (they share one extraction
helper so they cannot diverge). Mapping to `DEFAULT` rather than `MATERIALIZED`
keeps the source value authoritative (I6); mistaking the expression for the type
emits malformed DDL and breaks the stream. Formalised as `alter_preserves_type`,
`type_is_never_expression`, and `generated_has_default` in
`formal_specs/lean/Replication/GeneratedColumn.lean`.

---

## 4. Architectural Domain Taxonomy

Specifications are modularized into 11 specialized domains with fine-grained micro-encapsulations:

| Domain | Topic | Path | Scope |
|---|---|---|---|
| `01` | CDC Ingestion | `specs/01-cdc-engine/` | Debezium bootstrap, coordinates, dispatch loop, boundaries, queues, heartbeats |
| `02` | Versioning | `specs/02-versioning/` | Version formula, commit monotonicity floor, intra-second rollover, redelivery |
| `03` | Execution Engine | `specs/03-execution-engine/` | Batch executor, multi/single-threaded modes, routing, connection pooling, flushes |
| `04` | Query Generation | `specs/04-query-generation/` | Templates, parameterized insert formatting, explicit NULLs, update splitting |
| `05` | Sorting Key Mutation | `specs/05-sorting-key-mutation/` | Relocation detection, tombstone synthesis, live row insertion, sign binding |
| `06` | DDL Replication | `specs/06-ddl-replication/` | Pre-DDL drain, barrier pause, ANTLR parser, alter translation, nullability |
| `07` | Type System | `specs/07-type-system/` | Numerics, decimals, temporals, strings, bit endianness, spatial WKB, nullability |
| `08` | Schema Catalog | `specs/08-schema-catalog/` | DbWriter cache, multi-epoch invalidation, query storm prevention, MATERIALIZED |
| `09` | Offset Management | `specs/09-offset-management/` | FIFO batch tracking, OFFSET_COMMIT_LOCK, replica_source_info, control commits |
| `10` | Resilience & Monitoring | `specs/10-resilience-monitoring/` | Error taxonomy, backoff retries, replica status view, loud failure guarantee |
| `11` | Verification Tooling | `specs/11-verification-tooling/` | Spec validator, db_compare value checksums, Lean 4 formal simulation |

---

## 5. Formal Verification Alignment

To provide mathematical proof of system correctness, the invariants and state transitions declared in this Constitution are formalized in the Lean 4 proof assistant under `formal_specs/lean/`:
- `Replication.Basic`: Mathematical definition of relational tables, keys, values, and schemas.
- `Replication.Binlog`: Formal model of binlog events and MySQL state transitions.
- `Replication.ClickHouse`: Formal model of `ReplacingMergeTree` storage and `FINAL` evaluation.
- `Replication.Engine`: Operational semantics of event translation and PK update splitting.
- `Replication.Invariants`: Propositions for Invariants I1–I4 (ordinal / coordinate model) plus the stated-but-unproved `ReplayIdempotency`.
- `Replication.Proofs`: Machine-checked proofs of convergence, monotonicity, and PK update soundness.
- `Replication.Upgrade`: Invariant I11. `Replication.Snapshot`: Invariant I12. `Replication.GeneratedColumn`: Invariant I13.
- `Replication.DdlBarrier`: The pre-DDL barrier of Invariant I5 — the DDL step is enabled only when the legacy queue, every routed queue and the unacknowledged-batch counter are all empty, and a machine-checked counterexample showing that an empty legacy queue alone does not imply that.
- `Replication.OffsetFifo`: Handoff-sequence FIFO for offset acknowledgement (Invariant I8): commit never passes an outstanding batch, written-once, and the timestamp-overlap counterexample.
- `Replication.DdlTranslation`: ALTER clause classification for Specs 06.03/06.04/06.05/06.07 — no bare `ALTER TABLE`, an all-no-op statement is skipped, a widening key-column change is loud, every ADD COLUMN is preserved.

### 5.1 Coverage of the thirteen invariants
Honest status per invariant. "Lean" means a proposition and a machine-checked theorem exist; "model only" means the property holds in the abstract model but the shipped arithmetic is not modelled.

| Invariant | Lean status | Where |
|---|---|---|
| I1 Log Sequence Monotonicity | Lean (ordinal model; `encodeVersion` order-witness within `BinlogPos.WellFormed`) | `VersionMonotonicityProp`, `version_strictly_monotonic` |
| I2 Deterministic Version Monotonicity | Lean, model only — the ordinal scheme `liveVersion i = 2*i` is monotone by construction; the shipped `effectiveTs * 1e6 + seq` formula, the high-water floor and the counter seeds are **not** modelled (specs 02.01–02.04 record the known defects) | `Engine.lean`, `Proofs.lean` |
| I3 Eventual Convergence | Lean | `ReplicationConvergence`, `master_replication_convergence` |
| I4 Sorting Key Mutation Integrity | Lean | `PKRelocationSoundness`, `update_pk_relocation_soundness` |
| I5 DDL Barrier Quiescence | Lean | `DdlBarrier.lean`: `ddl_applies_only_when_no_pending_rows`, `old_predicate_insufficient`, `queues_empty_insufficient` |
| I6 Column Authority & Shadowing Prohibition | none (`ColumnKind` is modelled in `Basic.lean`; no theorem) | — |
| I7 Value-Level Type Equivalence | none | — |
| I8 Durable Offset Quiescence | Lean (handoff FIFO, including the in-process restart reset); the control-record half is covered under I12 | `OffsetFifo.lean`: `commit_never_passes_outstanding`, `write_at_most_once`, `old_overlap_rule_unsafe`, `restart_quiescent`, `acked_never_rolled_back`, `old_restart_poisons_fifo` |
| I9 Loud Failure | Lean (row half only: an unconvertible row record halts, its offset is never committed); the rest is empirical (spec 10.04) | `Snapshot.lean`: `unparsed_row_halts`, `unparsed_row_never_committed`, `old_rule_commits_unparsed_row` |
| I10 Structural Separation of Concerns | none (architectural rule, not a state-machine property) | — |
| I11 Drop-in Upgrade Safety | Lean, conditional on `GapMono` (spec 02.06 §6 lists where the code does not establish it) | `upgrade_safe`, `replicate_convergesV`, `liveVersion_gapMono` |
| I12 Snapshot Completion & Control-Record Offset Progress | Lean | `control_commit_safe`, `quiescent_control_commits`, `snapshot_completes` |
| I13 Generated-Column Type Integrity | Lean | `alter_preserves_type`, `type_is_never_expression`, `generated_has_default` |

`ReplayIdempotency` (`Invariants.lean`) is a stated proposition supporting I3 under at-least-once delivery (spec 02.04); it is not numbered as an invariant and has no theorem.
