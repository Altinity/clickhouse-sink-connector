# Spec 11.03: Lean 4 Formal Verification & Simulation Model

## 1. Executive Summary & Purpose
Specifies the mathematical formalization of the MySQL-to-ClickHouse replication engine in the Lean 4 proof assistant (`formal_specs/lean/`).

---

## 2. Codebase Mapping on 2.11.0
- **Package**: `formal_specs/lean/`
- **Modules**:
  - `Replication.Basic`: Relational foundational types
  - `Replication.Binlog`: Binlog positions and transition system
  - `Replication.ClickHouse`: ReplacingMergeTree and FINAL semantics
  - `Replication.Engine`: Translation semantics and state machine
  - `Replication.Invariants`: Mathematical definitions of system invariants
  - `Replication.Proofs`: Machine-checked theorems and proofs
  - `Replication.Upgrade`: Drop-in upgrade safety (Invariant I11)
  - `Replication.Snapshot`: Control-record offset commit & snapshot completion (Invariant I12)
  - `Replication.GeneratedColumn`: Generated-column type integrity (Invariant I13)
  - `Replication.OffsetFifo`: Handoff-sequence FIFO for offset acknowledgement (Invariant I8, spec 09.01)

---

## 3. Operational Specification

### 3.0 Model design (what is abstracted, and faithfully)
- **Versioning is by stream ordinal.** `replicateFrom` assigns each event a
  strictly increasing version (`liveVersion i = 2*i`; the relocation tombstone
  carries the SAME version, `tombstoneVersion i = liveVersion i`, exactly as
  the connector binds `record.getVersion()` for both rows). This is the
  abstract form of the connector's guarantee that every committed event
  receives a strictly greater `_version` than the events before it — the
  property the high-water floor and sequence counter enforce concretely.
  Because the ordinal is strictly monotonic by construction, convergence holds
  for ANY stream, with no monotonic-position hypothesis required.
- **`FINAL` resolves equal versions to the later-inserted row.** `maxStep`
  compares with `>=`, so among records of one key with equal version the one
  appended later wins — ClickHouse's ReplacingMergeTree tie rule. This is what
  makes a tombstone written at the same version as an earlier live row (the
  same-transaction relocation case, Spec 05.02 §3.2) retire it.
- **`encodeVersion` is a separate, documented order-witness.** The
  `(fileSeq, offset, rowIdx)` → integer encoding is proved strictly monotonic
  only within `BinlogPos.WellFormed` (offset/rowIdx bounded so the fields do
  not carry). It is NOT used by the engine — a single integer cannot encode an
  unbounded lexicographic triple monotonically, which is exactly why the real
  connector versions by time+sequence, not by position.
- **TRUNCATE clears the ClickHouse table** in `replicateFrom`, in lock-step with
  `applyBinlogEvent` emptying the source — matching the connector executing
  `TRUNCATE TABLE`. (A per-event translation cannot enumerate keys, so this must
  live in the fold, not in `translateEvent`.)

### 3.1 Theorem Inventory (all machine-checked, zero `sorry`)
1. `version_strictly_monotonic`: within `BinlogPos.WellFormed`, coordinate
   advance preserves strict version monotonicity ($p_1 < p_2 \implies v_1 < v_2$).
2. `insert_convergence_single`: single-row insert parity between MySQL and ClickHouse `FINAL`.
3. `delete_convergence_single`: tombstone erasure in ClickHouse `FINAL`.
4. `update_same_key_convergence`: in-place updates converge to the latest row.
5. `update_pk_relocation_soundness`: two-phase tombstoning cleans up the old key and establishes the new key (tombstone and live row share the version).
6. `tombstone_wins_version_tie`: a tombstone appended after a live row of the
   same key and the SAME version evaluates the key to `none` — the tie rule
   the equal-version relocation relies on.
7. `master_replication_convergence`: global convergence for ALL binlog streams
   (`chFinalView (replicateStream events) k = evalMySQL events emptyMySQL k`),
   proved via the general lemma `replicate_converges_gen` by induction on the
   stream with a coherence + version-bound invariant.
7. `OffsetFifo.commit_never_passes_outstanding`, `acked_downward_closed`,
   `commitPoint_acked`, `outstanding_ge_commitPoint`: in every reachable state
   of the handoff FIFO, acknowledged sequences are a prefix of the handoff order
   and no outstanding sequence lies below the commit point.
8. `OffsetFifo.write_at_most_once`, `written_batch_not_reexecuted`: a batch's
   write event occurs at most once; a written (parked) batch is never executed
   again.
9. `OffsetFifo.old_overlap_rule_unsafe`: concrete counterexample — two batches
   with equal timestamps where the strict timestamp-overlap rule acknowledges
   the later-finished one while the other is outstanding, and the FIFO does not.

### 3.2 Build & axiom verification
- `lake build` in `formal_specs/lean/` type-checks every theorem. The
  `lean_lib` is marked `@[default_target]` in `lakefile.lean`; without that
  attribute a bare `lake build` has no target, reports success and compiles
  nothing (observed before this was added), so the attribute is part of the
  verification contract. Proof of a real build is the per-module
  `Built Replication.<Module>` lines.
- Each theorem depends only on Lean's standard axioms `[propext, Quot.sound]` —
  verified with `#print axioms` — and on NO `sorryAx`. `scripts/validate_specs.py`
  additionally rejects any `sorry`/`admit` in the Lean sources.

---

## 4. Invariants Preserved
- **Mathematical Correctness**: Provides a complete, machine-checked proof of
  replication convergence (Invariant I3) and version monotonicity (I1/I2).

---

## 5. Verification Criteria
- `lake build` exits 0 with zero type errors and zero `sorry`.
- `#print axioms master_replication_convergence` lists only `[propext, Quot.sound]`.
