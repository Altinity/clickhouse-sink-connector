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
    ├── OffsetFifo.lean                # Handoff-sequence FIFO for offset acknowledgement (Invariant I8, spec 09.01): commit never passes an outstanding batch, written-once
    ├── DdlTranslation.lean            # ALTER TABLE clause classification (Specs 06.03/06.04/06.05/06.07): no bare ALTER, loud key widening, ADD COLUMN preserved
    └── CreateTable.lean               # CREATE TABLE sorting-key selection (Specs 06.05 §3.6 / 08.05 §3.2): never ORDER BY tuple(), declared key wins, nullable fallback key needs allow_nullable_key
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
  inserted row). Proved as `tombstone_wins_version_tie`.
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
an `acked` list; and a `writes` log. `handoff` appends the next sequence;
`write s` (enabled only while `s` is outstanding and not yet completed) parks
`s` and then drains: while the head of `outstanding` is completed it is
acknowledged. `commitPoint` is the number of leading sequences `0,1,2,…` that
are all acknowledged.

| Theorem Name | Statement | Significance |
|---|---|---|
| `commit_never_passes_outstanding` | in every reachable state, every acknowledged sequence is smaller than every outstanding one | The durable offset never passes a batch that is queued, in flight, or parked — on any worker. |
| `acked_downward_closed` | if `a` is acknowledged then every `t < a` is acknowledged | Acknowledgements form a prefix of the handoff (binlog) order. |
| `commitPoint_acked` / `outstanding_ge_commitPoint` | every `t < commitPoint` is acknowledged; every outstanding `t` satisfies `commitPoint ≤ t` | The commit point is exactly the boundary between acknowledged and outstanding. |
| `write_at_most_once` | the `writes` log has no duplicates in any reachable state | A batch's write event occurs at most once (no re-insertion of a parked batch). |
| `written_batch_not_reexecuted` | `write s` on an already-completed `s` leaves the state unchanged | A written, parked batch is never executed again. |
| `old_overlap_rule_unsafe` | with `A = B = (100,100)`, the strict overlap rule `otherMin < curMax` does not block `B`, while in the FIFO `B` is parked and `A` is outstanding | Concrete counterexample to the deleted timestamp-overlap predicate. |
| `fifo_acknowledges_in_handoff_order` | after handoff, handoff, write 1, write 0 the acknowledgement order is 0 then 1 | The drain acknowledges strictly in handoff order. |

### Generated-column type integrity (Invariant I13, `GeneratedColumn.lean`)

| Theorem Name | Statement | Significance |
|---|---|---|
| `alter_preserves_type` | the translated ClickHouse column type is always the declared data type | The generated clause never overwrites the type. |
| `type_is_never_expression` | for a generated column, the emitted type is never the generation expression | The exact bug (`ADD COLUMN c AS(a+b)`) cannot recur. |
| `generated_has_default` | a generated column always emits a `DEFAULT` | The source value stays authoritative (I6). |

### ALTER TABLE clause classification (Specs 06.03 / 06.04 / 06.05 / 06.07, `DdlTranslation.lean`)

An `ALTER TABLE` is modelled as a list of classified clauses (`addColumn`,
`dropColumn`, `modifyDataColumn`, `modifyKeyColumnSameOrNarrower`,
`modifyKeyColumnWider`, `primaryKeyChange`, `noOp`) and `translate` yields
`skip`, `emit kept` or `fail`, mirroring `enterAlterTable`.

| Theorem Name | Statement | Significance |
|---|---|---|
| `no_bare_alter` | `translate cs ≠ emit []` | The translator never sends a bare `ALTER TABLE db.t` (`Code: 62`); an all-no-op statement yields `skip`. |
| `all_noop_skips` | every clause unrepresentable and not loud → `translate cs = skip` | Index / key / constraint / charset / option-only statements are acknowledged, not sent. |
| `wider_key_change_is_loud` | `modifyKeyColumnWider n ∈ cs → translate cs = fail` | A sorting-key widening is refused with `DDLReplicationException` (I9), never emitted to fail with `Code: 524` after retries. |
| `primary_key_change_is_loud` | `primaryKeyChange cols ∈ cs → translate cs = fail` | An `ADD`/`DROP PRIMARY KEY` that changes the row identity the replica is keyed by is refused and names the rebuild (Spec 06.07 §3.1); a restatement is a `noOp`. |
| `add_columns_preserved` | `translate cs = emit kept → addColumn n ∈ cs → addColumn n ∈ kept` | Skipping an unrepresentable neighbour never drops an `ADD COLUMN` (I6). |
| `emitted_are_representable` | `translate cs = emit kept → kept = keep cs` (`keep` = the representable clauses, in source order) | Exactly the representable clauses are emitted, in source order. |

### CREATE TABLE sorting-key selection (Specs 06.05 §3.6 / 08.05 §3.2, `CreateTable.lean`)

A source table is modelled as its column list (name, nullable, generated), its
`PRIMARY KEY` column list and its first `UNIQUE` key; `sortingKey` mirrors the
precedence both creation paths use (declared `PRIMARY KEY`, then a fully
`NOT NULL` `UNIQUE` key, then every non-generated column in declaration order)
and `nullableSortingKey` says when `SETTINGS allow_nullable_key=1` is emitted.

| Theorem Name | Statement | Significance |
|---|---|---|
| `sorting_key_nonempty` | a table with at least one non-generated column has `sortingKey t ≠ []` | `ORDER BY tuple()` is never produced for a table that can hold a row: no keyless table collapses to one row. |
| `primary_key_wins` | `t.primaryKey ≠ [] → sortingKey t = t.primaryKey` | The declared identity is always used when there is one. |
| `unique_key_wins_when_not_null` | no `PRIMARY KEY`, fully `NOT NULL` `UNIQUE` key → `sortingKey t = t.uniqueKey` | A real identity is preferred to the value-derived one. |
| `fallback_key_is_every_stored_column` | no declared identity → `sortingKey t` = the names of every non-generated column, in order | The fallback reproduces MySQL's own semantics for a table without an identity: rows are distinguished by value. |
| `declared_key_never_needs_nullable_setting` | `t.primaryKey ≠ [] → nullableSortingKey t = false` | `allow_nullable_key` is never emitted where ClickHouse is right to reject a nullable key. |
| `nullable_fallback_gets_setting` | fallback key with a nullable stored column → `nullableSortingKey t = true` | The CREATE that names a nullable key column carries the setting, so it is not rejected with `Code: 44` and retried forever. |

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
| I2 Deterministic Version Monotonicity | model only: `liveVersion i = 2*i` is monotone by construction; the shipped `effectiveTs * 1e6 + seq` formula, floor and seeds are not modelled | `Engine.lean` |
| I3 Eventual Convergence | proved | `ReplicationConvergence`, `master_replication_convergence` |
| I4 Sorting Key Mutation Integrity | proved | `PKRelocationSoundness`, `update_pk_relocation_soundness` |
| I5 DDL Barrier Quiescence | in progress (concurrent change) | — |
| I6 Column Authority | none (`ColumnKind` modelled, no theorem) | — |
| I7 Value-Level Type Equivalence | none | — |
| I8 Durable Offset Quiescence | in progress (concurrent change); control-record half under I12 | — |
| I9 Loud Failure | none | — |
| I10 Separation of Concerns | none (architectural rule) | — |
| I11 Drop-in Upgrade Safety | proved, conditional on `GapMono` | `upgrade_safe`, `replicate_convergesV`, `liveVersion_gapMono` |
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
