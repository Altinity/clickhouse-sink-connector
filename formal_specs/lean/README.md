# Formal Verification of MySQL-to-ClickHouse Replication in Lean 4

## 1. Overview & Mathematical Grounding

The ClickHouse Sink Connector is an exact replication engine that maps a sequence of transactional MySQL binary log events into an append-only ClickHouse `ReplacingMergeTree` table.

Because this replication problem is a deterministic state machine transition system, its correctness can be **completely modeled, simulated, and mathematically verified using formal methods**.

This package contains a formal specification and machine-checked proof suite written in the [Lean 4 Theorem Prover](https://lean-lang.org/). It formally establishes that:
1. **Log Sequence Monotonicity**: strictly increasing stream order yields strictly
   increasing ClickHouse versions (and the coordinate encoding preserves order
   within its well-formed domain). *(proved)*
2. **Replication Convergence (Equivalence)**: for ANY sequence of transactions
   applied to MySQL, the ClickHouse table evaluated under `ReplacingMergeTree`
   `FINAL` produces a row state identical to MySQL. *(proved)*
3. **Primary Key Relocation Soundness**: when an `UPDATE` modifies a primary/sorting
   key, the synthesized tombstone on the old key plus the insert on the new key
   preserves exact relational equivalence. *(proved)*

The following are **modeled** in the specification but are not (yet) among the
machine-checked theorems:
4. **Replay Idempotency**: replaying an earlier prefix does not regress the `FINAL`
   state — defined as the proposition `ReplayIdempotency`.
5. **Source Authority**: column values present in MySQL are preserved over
   ClickHouse default expressions — modeled via `ColumnKind`.

---

## 2. Formal Architecture & Module Taxonomy

```
formal_specs/lean/
├── lean-toolchain                     # Pins Lean 4 version (v4.11.0)
├── lakefile.lean                      # Lake package build descriptor
├── README.md                          # Architecture and verification documentation
└── Replication/
    ├── Basic.lean                     # Foundation: Keys, Values, Rows, Schemas, MySQL State
    ├── Binlog.lean                    # Source Model: Coordinates, Operations, Event Stream, Eval
    ├── ClickHouse.lean                # Replica Model: ReplacingMergeTree storage, FINAL semantics
    ├── Engine.lean                    # Translation: Coordinate-to-version, PK splitting, Stream Replicator
    ├── Invariants.lean                # Formal Propositions: Monotonicity, Convergence, Invariants I1-I4 (+ stated ReplayIdempotency)
    ├── Proofs.lean                    # Machine-Checked Theorems: Inductive proofs of convergence
    ├── Upgrade.lean                   # Drop-in Upgrade Safety (Invariant I11): convergence for any gap-monotone version scheme
    ├── Snapshot.lean                  # Snapshot Completion & control-record offset commit (Invariant I12, issue #1379)
    ├── GeneratedColumn.lean           # Generated-Column Type Integrity (Invariant I13): expression maps to DEFAULT, never the type
    ├── DdlBarrier.lean                # DDL Barrier Quiescence (Invariant I5): the barrier covers legacy + routed queues + unacknowledged batches
    ├── OffsetFifo.lean                # Handoff-sequence FIFO for offset acknowledgement (Invariant I8, spec 09.01): commit never passes an outstanding batch, written-once, in-process restart abandons but never rolls back
    ├── DdlTranslation.lean            # ALTER TABLE clause classification (Specs 06.03/06.04/06.05/06.07): no bare ALTER, loud key widening, ADD COLUMN preserved
    ├── BatchOrder.lean                # Batch execution order around a replicated TRUNCATE (Spec 04.05): ordered segments reproduce binlog order; hash-map order does not
    └── VersionFloor.lean              # Version floor across a restart (Invariant I2 at the boundary, specs 02.02/02.04): seeded floor orders the new run above the old; heartbeats never touch the sequence
```

---

## 3. Mathematical Formulation

### 3.1 Source State & Binlog Transition System
- Let $\mathcal{K} = \mathbb{N}$ be the set of primary keys.
- Let $\mathcal{V}$ be the set of typed values (integers, strings, booleans, null).
- A row is a record $R : \text{String} \to \mathcal{V}$.
- The canonical MySQL state is a partial map:
  $$\sigma_{\text{MySQL}} : \mathcal{K} \to \text{Option}(R)$$
- A binlog event $e$ consists of a coordinate $p = (\text{fileSeq}, \text{offset}, \text{rowIdx}) \in \mathbb{N}^3$ and an operation:
  $$op \in \{ \text{Insert}(k, v), \text{Update}(k_{\text{old}}, k_{\text{new}}, v), \text{Delete}(k), \text{Truncate} \}$$
- Source evaluation $\text{eval}_{\text{MySQL}} : \text{List}(e) \to \sigma_{\text{MySQL}}$ executes each operation in sequence.

### 3.2 ClickHouse ReplacingMergeTree & FINAL Evaluation
- ClickHouse storage is an append-only multiset of records:
  $$\text{Record} = \{ \text{key} : \mathcal{K}, \text{row} : R, \text{version} : \mathbb{N}, \text{is\_deleted} : \text{Bool} \}$$
  $$\mathcal{T}_{\text{CH}} = \text{List}(\text{Record})$$
- The `FINAL` evaluation function collapses records sharing key $k$ to the record possessing the maximal version:
  $$\text{Record}_{\text{max}}(k) = \operatorname*{arg\,max}_{r \in \mathcal{T}_{\text{CH}}, r.\text{key} = k} (r.\text{version})$$
  $$\text{view}_{\text{CH}}(\mathcal{T}_{\text{CH}}, k) = \begin{cases}
    \text{None} & \text{if } \text{Record}_{\text{max}}(k).\text{is\_deleted} = \text{true} \lor \nexists r \\
    \text{Some}(r.\text{row}) & \text{otherwise}
  \end{cases}$$

### 3.3 Replication Translation Function
The replication engine maps each binlog event into ClickHouse insertions:
- $\text{translate}(\text{Insert}(k, v), p) = [ \{ k, v, \text{encode}(p), \text{is\_deleted} = \text{false} \} ]$
- $\text{translate}(\text{Update}(k_{\text{old}}, k_{\text{new}}, v), p) =$
  - If $k_{\text{old}} = k_{\text{new}}$:
    $[ \{ k_{\text{new}}, v, \text{encode}(p), \text{false} \} ]$
  - If $k_{\text{old}} \ne k_{\text{new}}$:
    $[ \{ k_{\text{old}}, \emptyset, \text{encode}(p), \text{true} \}, \{ k_{\text{new}}, v, \text{encode}(p), \text{false} \} ]$

    The tombstone and the live row carry the **same** version (the connector
    binds `record.getVersion()` for both). They never share a key, so they
    never compete; against an older live row at $k_{\text{old}}$ the tombstone
    wins by version, or — when that row was written by the same transaction
    and has the same version — by the `FINAL` tie rule below.
- `FINAL` tie rule: `maxStep` uses `>=`, so of two records with one key and
  equal version the one appended **later** wins (ClickHouse keeps the last
  inserted row). Proved as `tombstone_wins_version_tie`. The connector relies
  on this rule beyond relocation tombstones: under GTID versioning every row
  event of one transaction in one millisecond carries the same `_version`
  (spec 02.01 §3.1.1), so two writes to one key inside one transaction are
  ordered only by insertion order — which holds because the connector writes
  a table's rows in binlog order on one worker and never splits tied rows
  across workers. The model assumes that order (the list order of the
  table); it does not model the per-table routing that establishes it.
- $\text{translate}(\text{Delete}(k), p) = [ \{ k, \emptyset, \text{encode}(p), \text{true} \} ]$

---

## 4. Key Theorems Proven in `Proofs.lean`

All six theorems below are machine-checked with **no `sorry`**; each depends only
on Lean's standard axioms `[propext, Quot.sound]` (verified via `#print axioms`).

| Theorem Name | Mathematical Statement | Significance |
|---|---|---|
| `version_strictly_monotonic` | $\text{WellFormed}(p_1,p_2) \to p_1 < p_2 \implies \text{encode}(p_1) < \text{encode}(p_2)$ | Coordinate order is preserved without version inversion, within the encoding's well-formed domain. |
| `insert_convergence_single` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{insert}}])) = \text{eval}_{\text{MySQL}}([e_{\text{insert}}])$ | Single-row insertion equality. |
| `delete_convergence_single` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{insert}}, e_{\text{delete}}])) = \text{None}$ | Tombstones erase rows in `FINAL`. |
| `update_same_key_convergence` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{ins}}, e_{\text{upd}}])) = \text{Some}(v_{\text{new}})$ | In-place updates supersede earlier inserts. |
| `update_pk_relocation_soundness` | $\text{view}_{\text{CH}}(k_{\text{old}}) = \text{None} \land \text{view}_{\text{CH}}(k_{\text{new}}) = \text{Some}(v)$ | The two-phase tombstone protocol eliminates ghost rows on PK mutation. |
| `tombstone_wins_version_tie` | live row $(k, V)$ then tombstone $(k, V)$ appended later $\implies \text{view}_{\text{CH}}(k) = \text{None}$ | Equal versions resolve to the later insert, so a same-transaction relocation (same `_version`) still retires the old key. |
| `master_replication_convergence` | $\forall S, \forall k, \text{view}_{\text{CH}}(\text{replicate}(S), k) = \text{eval}_{\text{MySQL}}(S, k)$ | **Master Convergence Theorem**: inductive proof (via `replicate_converges_gen`) that ANY transaction stream — inserts, updates, relocations, deletes and truncates — achieves exact replica parity. |

> Versioning note: the engine assigns versions by strictly increasing **stream
> ordinal** (`liveVersion i = 2*i`; the relocation tombstone carries the same
> version, `tombstoneVersion i = liveVersion i`), so
> `master_replication_convergence` needs no monotonic-position hypothesis. The
> `encode` map above is a separate, documented order-witness proved monotonic only
> within `BinlogPos.WellFormed`; it is not used by the engine.
>
> `ReplayIdempotency` (in `Invariants.lean`) is defined as a proposition but is not
> among the proved theorems; it is retained as a stated invariant for future work.

### Drop-in upgrade safety (Invariant I11, `Upgrade.lean`)

| Theorem Name | Statement | Significance |
|---|---|---|
| `replicate_convergesV` | for any gap-monotone version scheme `v`, `view_CH(replicateStreamV v S) = eval_MySQL(S)` | Convergence depends only on version ORDER, not on the absolute numbers a given connector version emits. |
| `upgrade_safe` | replicating a stream whose first `n` ordinals use the OLD version scheme and the rest use the NEW scheme converges, when the combined scheme stays gap-monotone | **Upgrading never ruins data**: pre-upgrade and post-upgrade rows coexist and the correct row wins under `FINAL`. |
| `liveVersion_gapMono` | the shipped ordinal scheme `2*i` is gap-monotone | The general result specialises to the shipped engine. |

### Snapshot completion & control-record offset commit (Invariant I12, `Snapshot.lean`, issue #1379)

| Theorem Name | Statement | Significance |
|---|---|---|
| `control_commit_safe` | a control record advances the committed offset only when `outstanding = 0` | Safety: never commit past unwritten rows (no #1285 data loss). |
| `quiescent_control_commits` | a control record on a quiescent pipeline commits its offset | Liveness: the end-of-snapshot heartbeat's offset IS committed. |
| `snapshot_completes` | after the snapshot's rows are handed off and written, the end-of-snapshot control record commits its offset (`committed = snapPos`) | **Issue #1379**: `snapshot_completed` persists; a restart does not re-run the snapshot. |
| `unparsed_row_halts` / `unparsed_row_never_committed` | `dispatch s (row false p) = none`; any record list containing an unparsed row has no final state | A ROW record the parser cannot convert is terminal: its offset is never acknowledged (spec 01.06 §3.1, I9). |
| `old_rule_commits_unparsed_row` | the replaced rule (unparsed row treated as a control record) yields `committed = p` on a quiescent pipeline | Concrete witness of the silent loss the fix removes. |

### DDL barrier covers every handoff path (Invariant I5, `DdlBarrier.lean`)

| Theorem Name | Statement | Significance |
|---|---|---|
| `ddl_applies_only_when_no_pending_rows` | if a `Step` applies the DDL then `pending s = 0` (legacy queue, every routed queue and the in-flight counter are all empty) | No pre-DDL row can be written against the post-DDL schema. |
| `ddl_step_barrierReady` | a step that applies the DDL was taken from a state satisfying `barrierReady` | The guard is exactly `isPipelineQuiescent()` in `drainBeforeDDL`. |
| `old_predicate_insufficient` | `∃ s, legacyEmpty s ∧ ¬ barrierReady s` (witness: legacy empty, one routed queue holding a batch) | The pre-fix guard ("legacy queue empty") is NOT a barrier under hash routing. |
| `old_predicate_admits_pending_rows` | the same witness has `0 < pending s` | The pre-fix guard would apply the DDL over a pending row. |
| `queues_empty_insufficient` | both queue sets empty but `outstanding = 1` is not `barrierReady` | Dequeued-but-unacknowledged batches must be waited for too. |

### Offset acknowledgement FIFO by handoff sequence (Invariant I8, `OffsetFifo.lean`, spec 09.01)

The model: a monotone handoff counter; an ascending `outstanding` list of
sequences (handed off, not acknowledged); a `completed` list (written, parked);
an `acked` list; a `writes` log; and an `abandoned` list. `handoff` appends the
next sequence; `write s` (enabled only while `s` is outstanding and not yet
completed) parks `s` and then drains: while the head of `outstanding` is
completed it is acknowledged; `restart` (`stop()` after the pool has terminated,
spec 09.01 §3.8) moves everything outstanding or parked to `abandoned` without
touching `acked` or the counter. `commitPoint` is the number of leading
sequences `0,1,2,…` that are all acknowledged.

| Theorem Name | Statement | Significance |
|---|---|---|
| `commit_never_passes_outstanding` | in every reachable state, every acknowledged sequence is smaller than every outstanding one | The durable offset never passes a batch that is queued, in flight, or parked — on any worker. |
| `acked_downward_closed` | if `a` is acknowledged then every `t < a` is acknowledged or abandoned (never outstanding) | Acknowledgements form a prefix of the handoff (binlog) order, up to sequences an in-process restart abandoned. |
| `commitPoint_acked` / `outstanding_ge_commitPoint` | every `t < commitPoint` is acknowledged; every outstanding `t` satisfies `commitPoint ≤ t` | The commit point is exactly the boundary between acknowledged and outstanding. |
| `write_at_most_once` | the `writes` log has no duplicates in any reachable state | A batch's write event occurs at most once (no re-insertion of a parked batch). |
| `written_batch_not_reexecuted` | `write s` on an already-completed `s` leaves the state unchanged | A written, parked batch is never executed again. |
| `old_overlap_rule_unsafe` | with `A = B = (100,100)`, the strict overlap rule `otherMin < curMax` does not block `B`, while in the FIFO `B` is parked and `A` is outstanding | Concrete counterexample to the deleted timestamp-overlap predicate. |
| `fifo_acknowledges_in_handoff_order` | after handoff, handoff, write 1, write 0 the acknowledgement order is 0 then 1 | The drain acknowledges strictly in handoff order. |
| `restart_quiescent` | after `restart`, `outstanding = []` and `completed = []` | The next engine in the process starts from a quiescent FIFO: its heartbeat can be committed, its first unit is the head. |
| `acked_never_rolled_back` / `abandoned_not_acked` | in every reachable state no acknowledged sequence is abandoned and vice versa | A restart drops only work whose offset was never staged: redelivery (at-least-once), never a rolled-back offset. |
| `old_restart_poisons_fifo` | with the old `stop()` (no reset), `handoff, restart, handoff, write 1` leaves `acked = []`, `0` outstanding and `1` parked | Concrete witness of the poisoned FIFO: one unwritten batch of the old engine parks every batch of the new engine forever. |
| `restart_unblocks_next_engine` | with the reset the same run yields `acked = [1]`, nothing outstanding or parked, `abandoned = [0]` | The reset lets the new engine acknowledge; the abandoned sequence is redelivered under a new one. |

### Generated-column type integrity (Invariant I13, `GeneratedColumn.lean`)

| Theorem Name | Statement | Significance |
|---|---|---|
| `alter_preserves_type` | the translated ClickHouse column type is always the declared data type | The generated clause never overwrites the type. |
| `type_is_never_expression` | for a generated column, the emitted type is never the generation expression | The exact bug (`ADD COLUMN c AS(a+b)`) cannot recur. |
| `generated_has_default` | a generated column always emits a `DEFAULT` | The source value stays authoritative (I6). |

### ALTER TABLE clause classification (Specs 06.03 / 06.04 / 06.05 / 06.07, `DdlTranslation.lean`)

An `ALTER TABLE` is modelled as a list of classified clauses (`addColumn`,
`dropColumn`, `modifyDataColumn`, `modifyKeyColumnSameOrNarrower`,
`modifyKeyColumnWider`, `noOp`) and `translate` yields `skip`, `emit kept` or
`fail`, mirroring `enterAlterTable`.

| Theorem Name | Statement | Significance |
|---|---|---|
| `no_bare_alter` | `translate cs ≠ emit []` | The translator never sends a bare `ALTER TABLE db.t` (`Code: 62`); an all-no-op statement yields `skip`. |
| `all_noop_skips` | every clause unrepresentable and not loud → `translate cs = skip` | Index / key / constraint / charset / option-only statements are acknowledged, not sent. |
| `wider_key_change_is_loud` | `modifyKeyColumnWider n ∈ cs → translate cs = fail` | A sorting-key widening is refused with `DDLReplicationException` (I9), never emitted to fail with `Code: 524` after retries. |
| `add_columns_preserved` | `translate cs = emit kept → addColumn n ∈ cs → addColumn n ∈ kept` | Skipping an unrepresentable neighbour never drops an `ADD COLUMN` (I6). |
| `emitted_are_representable` | `translate cs = emit kept → kept = keep cs` (`keep` = the representable clauses, in source order) | Exactly the representable clauses are emitted, in source order. |

### Batch execution order around a replicated TRUNCATE (Spec 04.05, `BatchOrder.lean`)

A worker's batch is executed as statement groups. The pre-fix executor kept
the TRUNCATE group and the INSERT groups in one hash map, so the truncate ran
before or after the batch's inserts depending on the table name's hash; the
fixed executor splits the batch at every TRUNCATE into ordered segments
(`splitAtTruncate`) and runs them in order (`execSegments`).

| Theorem Name | Statement | Significance |
|---|---|---|
| `segments_match_source` | `execSegments s (splitAtTruncate evs) = evs.foldl applyBinlogEvent s` for every batch and start state | Executing the segments in order IS applying the events in binlog order: rows before the truncate reach the table first, rows after it survive. |
| `segmented_batch_converges` | `execSegments emptyMySQL (splitAtTruncate evs) = evalMySQL evs` | The same, against the source evaluator. |
| `every_truncate_is_its_own_segment` | `numTruncateSegments (splitAtTruncate evs) = numTruncates evs` | Two TRUNCATEs in one batch stay two segments (they collapsed onto one hash-map key before). |
| `truncate_last_loses_rows` | for `[INSERT k v, TRUNCATE, INSERT k v']`, truncate-after-inserts ≠ source at `k` | The pre-fix order that loses the rows following the truncate. |
| `truncate_first_resurrects_rows` | for `[INSERT k v, TRUNCATE]`, truncate-before-inserts ≠ source at `k` | The pre-fix order that resurrects the rows preceding the truncate. |

### Version floor across a restart (Invariant I2 at the restart boundary, `VersionFloor.lean`, specs 02.02 §3.5 / 02.04 §3.2)

The model is the shipped `nextSequenceNumber` state machine: `floor`
(`sequenceMaxSourceTs`), `anchor`, `counter` and `mark` (`sequenceHighWaterPosition`),
with `version = effTs * 1_000_000 + counter`, the 500m / 1000m seeds and the
`diff > 1` reset. `seed s v` raises the floor to `v / 1_000_000 + 1`.

| Theorem Name | Statement | Significance |
|---|---|---|
| `version_ge_floor` | a first delivery is versioned at least `floor * 1_000_000 + 1` | The clamp is what the boundary proof rests on; no counter bound is needed. |
| `floor_mono` / `seed_floor_ge` | the floor never decreases within a run, and seeding never lowers it | The seeded bound survives every later record. |
| `seed_floor_gt` | `v < (seed s v).floor * 1_000_000` | The seeded whole-second slot lies strictly above the high-water version. |
| `restart_boundary` | if every pre-restart version is `≤ v`, every first delivery of a run started from `seed initial v` is `> v` | **The restart fix**: the new run continues strictly above the old one, whatever the lag, seeds or clock skew. |
| `first_row_after_restart_is_first` | after a restart the mark is unset, so any positioned record is a first delivery | The boundary theorem applies from the very first row. |
| `dispatch_control_preserves_state` | a heartbeat / transaction-metadata record leaves the sequence state unchanged | Control records carry the connector clock; they must not feed the floor. |
| `old_dispatch_control_moves_floor` / `dispatch_control_keeps_source_floor` | the pre-fix loop pinned the floor to the connector clock `W`; the fixed loop leaves it at the source clock | Concrete counterexample to the pre-fix behaviour. |
| `seeded_restart_example` / `unseeded_restart_inverts` | the unit-test scenario (`W-40000`, heartbeat at `W`, `W-30000`, restart, `W-25000`) executed by `decide`: `v2 > v1` with the seed, `v2 < v1` without | The regression, machine-checked on the real constants. |

Not modelled here: within-run strict monotonicity of the shipped formula (spec
02.02 §3.3 states it with its counter bound) and the GTID precedence.

The `lean_lib` is now the package's `@[default_target]`, so a plain `lake build`
type-checks every module (previously it built only the lakefile; use
`lake build Replication` on older checkouts).

---

## 5. Coverage of the Constitution's thirteen invariants

Which of `specs/CONSTITUTION.md` I1–I13 have a Lean proposition and theorem today.
The same table is kept in the Constitution §5.1; this copy is the one next to the code.

| Invariant | Status | Declarations |
|---|---|---|
| I1 Log Sequence Monotonicity | proved in the ordinal model; `encodeVersion` order-witness within `BinlogPos.WellFormed` | `VersionMonotonicityProp`, `version_strictly_monotonic` |
| I2 Deterministic Version Monotonicity | proved at the restart boundary on the shipped statics (`VersionFloor.lean`); within-run monotonicity of the shipped formula is model only (`liveVersion i = 2*i`) | `restart_boundary`, `version_ge_floor`, `dispatch_control_preserves_state`; `Engine.lean` |
| I3 Eventual Convergence | proved | `ReplicationConvergence`, `master_replication_convergence` |
| I4 Sorting Key Mutation Integrity | proved | `PKRelocationSoundness`, `update_pk_relocation_soundness` |
| I5 DDL Barrier Quiescence | in progress (concurrent change) | — |
| I6 Column Authority | none (`ColumnKind` modelled, no theorem) | — |
| I7 Value-Level Type Equivalence | none | — |
| I8 Durable Offset Quiescence | in progress (concurrent change); control-record half under I12 | — |
| I9 Loud Failure | row half proved: an unconvertible row record halts the pipeline, never an acknowledged heartbeat (spec 01.06 §3.1) | `unparsed_row_halts`, `unparsed_row_never_committed`, `old_rule_commits_unparsed_row` |
| I10 Separation of Concerns | none (architectural rule) | — |
| I11 Drop-in Upgrade Safety | proved, conditional on `GapMono`; the boundary clause is proved for the seeded floor | `upgrade_safe`, `replicate_convergesV`, `liveVersion_gapMono`, `VersionFloor.restart_boundary` |
| I12 Snapshot Completion & Control-Record Offset Progress | proved | `control_commit_safe`, `quiescent_control_commits`, `snapshot_completes` |
| I13 Generated-Column Type Integrity | proved | `alter_preserves_type`, `type_is_never_expression`, `generated_has_default` |

`ReplayIdempotency` is a stated proposition (spec 02.04), not a numbered invariant and not yet proved.

CI (`.github/workflows/spec-governance.yml`) runs `lake build` with the toolchain
pinned in `lean-toolchain` on every pull request and on pushes to `2.11.0`, and
rejects any `sorry` / `admit` / `native_decide`.

## 6. Verification & Toolchain Instructions

### Prerequisites
Install `elan` (the Lean version manager):
```bash
curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
source $HOME/.elan/env
```

### Building & Checking the Proofs
Navigate to the Lean verification directory and run `lake build`:
```bash
cd formal_specs/lean
lake build
```

When Lean builds with zero errors or warnings, the formal proofs are machine-checked and confirmed by the Lean 4 kernel.
