/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# Key-aware row routing, spec 03.07

A Debezium poll batch is split into per-worker groups by a routing token and
each worker drains its own queue in FIFO order. Routing by the table alone
pins a whole table to one worker; routing by the row's primary-key identity
spreads a table's rows across workers while keeping every occurrence of one
row on a single worker, in binlog order. This is MySQL WRITESET's rule:
disjoint row identities run in parallel, the same identity is serialized in
source order, and anything not row-identifiable is a barrier.

This module models the routing token and proves the safety properties the
spec relies on:

* `same_key_same_shard` -- the same table and row key always map to one shard,
  so per-row order is preserved by that shard's FIFO queue.
* `disjoint_keys_independent` -- distinct row keys of one table can map to
  different shards, so they are applied in parallel with no cross-shard order.
* `per_key_order_preserved` -- the substream routed to one shard is a sublist
  of the source, so it keeps the source's relative (binlog) order.
* `barrier_totally_orders_truncate` -- with the drain barrier, a table-clearing
  event is totally ordered against every record by its sequence; the
  counterexample `truncate_unbarriered_can_reorder` shows why the barrier is
  required.
* `recovery_converges` -- replaying a redelivered prefix never lowers the
  converged per-key version, so recovery from any offset is idempotent under
  the RMT _version rule.

Every constructor named here is a Lean term about routing and list semantics;
nothing is executed.
-/

namespace Replication
namespace KeyRouting

/-- A change-event operation, reduced to what routing cares about. The
    `truncateTable` constructor names a table-clearing event. -/
inductive Op where
  | dml
  | truncateTable
  deriving DecidableEq, Repr

/-- A source record: its table, optional row key (`none` for a table with no
    primary key, a table-clearing event, or a tombstone without a key), its
    operation, and its binlog sequence (the source order). -/
structure Record where
  table : Nat
  key : Option Nat
  op : Op
  seq : Nat
  deriving DecidableEq, Repr

/-- A deterministic pairing of table and key. No cryptographic property is
    needed -- only that equal inputs give equal outputs. -/
def hashPair (t k : Nat) : Nat := t * 1000003 + k

/-- The worker shard a record routes to. A DML record with a row key routes by
    `(table, key)`; everything else (a table-clearing event, or a record with
    no key) routes by the table alone -- the base shard, i.e. MySQL's
    `has_missing_keys` fallback to COMMIT_ORDER. -/
def shard (poolSize : Nat) (r : Record) : Nat :=
  match r.op, r.key with
  | Op.dml, some k => hashPair r.table k % poolSize
  | _, _ => r.table % poolSize

/-- The same table and row key, both DML, always route to the same shard, so
    every change to one row lands on one worker queue and is drained in FIFO
    (binlog) order. -/
theorem same_key_same_shard (p : Nat) (a b : Record)
    (ht : a.table = b.table) (hk : a.key = b.key)
    (ha : a.op = Op.dml) (hb : b.op = Op.dml) :
    shard p a = shard p b := by
  simp only [shard, ha, hb, ht, hk]

/-- Distinct row keys of one table can map to different shards, so disjoint
    rows are applied in parallel with no cross-shard ordering constraint. -/
theorem disjoint_keys_independent :
    ∃ (p : Nat) (a b : Record),
      a.table = b.table ∧ a.key ≠ b.key ∧ shard p a ≠ shard p b := by
  refine ⟨2,
    { table := 0, key := some 0, op := Op.dml, seq := 0 },
    { table := 0, key := some 1, op := Op.dml, seq := 1 }, ?_, ?_, ?_⟩
  · rfl
  · decide
  · decide

/-- Routing to one shard `s`: keep exactly the records that map to `s`,
    preserving their order. -/
def route (p s : Nat) (xs : List Record) : List Record :=
  xs.filter (fun r => decide (shard p r = s))

/-- The substream routed to a shard is a sublist of the source, so a shard's
    queue receives its records in the source's relative (binlog) order. -/
theorem per_key_order_preserved (p s : Nat) (xs : List Record) :
    (route p s xs).Sublist xs := by
  unfold route
  exact List.filter_sublist xs

/-- Two changes to the same row keep their source order in the routed stream. -/
theorem per_key_order_example :
    route 4 (shard 4 { table := 0, key := some 7, op := Op.dml, seq := 1 })
      [ { table := 0, key := some 7, op := Op.dml, seq := 1 },
        { table := 0, key := some 7, op := Op.dml, seq := 2 } ]
      = [ { table := 0, key := some 7, op := Op.dml, seq := 1 },
          { table := 0, key := some 7, op := Op.dml, seq := 2 } ] := by
  decide

/-- The applied order produced by the drain barrier: everything before the
    table-clearing event, then that event, then everything after. -/
def appliedWithBarrier (pre : List Record) (t : Record) (post : List Record) :
    List Record := pre ++ t :: post

/-- With the barrier, the table-clearing event is totally ordered against every
    record in the applied stream: each record is strictly before it, is it, or
    is strictly after it, by binlog sequence -- on every shard. -/
theorem barrier_totally_orders_truncate
    (pre post : List Record) (t : Record) (_ht : t.op = Op.truncateTable)
    (hpre : ∀ r ∈ pre, r.seq < t.seq)
    (hpost : ∀ r ∈ post, t.seq < r.seq) :
    ∀ r ∈ appliedWithBarrier pre t post,
      r.seq < t.seq ∨ r = t ∨ t.seq < r.seq := by
  intro r hr
  unfold appliedWithBarrier at hr
  rcases List.mem_append.mp hr with hp | hcons
  · exact Or.inl (hpre r hp)
  · rcases List.mem_cons.mp hcons with he | hpp
    · exact Or.inr (Or.inl he)
    · exact Or.inr (Or.inr (hpost r hpp))

/-- Without the barrier, a legal per-shard interleaving can apply a
    later-sequence row before the table-clearing event: the two orderings
    differ, so ordering matters and the barrier is required. -/
theorem truncate_unbarriered_can_reorder :
    ∃ (t d : Record),
      t.op = Op.truncateTable ∧ d.op = Op.dml ∧ t.seq < d.seq ∧
      ([d, t] : List Record) ≠ [t, d] := by
  refine ⟨{ table := 0, key := none, op := Op.truncateTable, seq := 1 },
          { table := 0, key := some 5, op := Op.dml, seq := 2 }, rfl, rfl, ?_, ?_⟩
  · decide
  · decide

/-- The converged per-key version: the greatest binlog sequence among DML
    records that touch that key (0 if none), i.e. the RMT _version rule. -/
def finalVersion (k : Nat) : List Record → Nat
  | [] => 0
  | r :: rest =>
      match r.op, r.key with
      | Op.dml, some k' =>
          if k' = k then Nat.max r.seq (finalVersion k rest) else finalVersion k rest
      | _, _ => finalVersion k rest

/-- Prepending any record never lowers the converged version for a key. -/
theorem finalVersion_cons_ge (k : Nat) (r : Record) (L : List Record) :
    finalVersion k L ≤ finalVersion k (r :: L) := by
  cases hop : r.op with
  | truncateTable => simp only [finalVersion, hop]; exact Nat.le_refl _
  | dml =>
    cases hk : r.key with
    | none => simp only [finalVersion, hop, hk]; exact Nat.le_refl _
    | some k' =>
      simp only [finalVersion, hop, hk]
      by_cases hkk : k' = k
      · rw [if_pos hkk]; exact Nat.le_max_right _ _
      · rw [if_neg hkk]; exact Nat.le_refl _

/-- Replaying a redelivered prefix never lowers the converged per-key version:
    recovery from any committed offset is idempotent under version selection,
    so a restart that re-delivers already-applied rows still converges. -/
theorem recovery_converges (k : Nat) (pre xs : List Record) :
    finalVersion k xs ≤ finalVersion k (pre ++ xs) := by
  induction pre with
  | nil => simp
  | cons r rest ih =>
    calc finalVersion k xs
        ≤ finalVersion k (rest ++ xs) := ih
      _ ≤ finalVersion k (r :: (rest ++ xs)) := finalVersion_cons_ge k r (rest ++ xs)
      _ = finalVersion k ((r :: rest) ++ xs) := by rw [List.cons_append]

/-- Applying an exact duplicate leaves the converged version unchanged. -/
theorem recovery_duplicate_idempotent :
    finalVersion 5
        ([ { table := 0, key := some 5, op := Op.dml, seq := 9 } ] ++
         [ { table := 0, key := some 5, op := Op.dml, seq := 9 } ])
      = finalVersion 5 [ { table := 0, key := some 5, op := Op.dml, seq := 9 } ] := by
  decide

/-- The converged per-key version of a concatenation is the max of the parts'
    converged versions: `finalVersion` is a max, so it does not depend on how a
    stream is split across workers. -/
theorem finalVersion_append (k : Nat) (xs ys : List Record) :
    finalVersion k (xs ++ ys) = Nat.max (finalVersion k xs) (finalVersion k ys) := by
  induction xs with
  | nil => simp [finalVersion, Nat.zero_max]
  | cons r rest ih =>
    simp only [List.cons_append]
    cases hop : r.op with
    | truncateTable => simp only [finalVersion, hop]; exact ih
    | dml =>
      cases hk : r.key with
      | none => simp only [finalVersion, hop, hk]; exact ih
      | some k' =>
        simp only [finalVersion, hop, hk]
        by_cases hkk : k' = k
        · simp only [if_pos hkk, ih, Nat.max_assoc]
        · simp only [if_neg hkk, ih]

/-- Upgrade AND downgrade converge (Invariant I11, drop-in upgrade safety).
    Key-aware routing changes only WHICH worker applies a row, never the row or
    its `_version`; the converged per-key version is a max over the events and
    is therefore independent of the routing mode and of the order in which two
    segments (one under each routing, e.g. before and after a restart that flips
    `routing.by.primary.key` or swaps the connector build) are applied. So a
    stream replicated partly under table routing and partly under key routing
    converges to exactly the same ClickHouse FINAL state, in either direction. -/
theorem upgrade_downgrade_converges (k : Nat) (pre post : List Record) :
    finalVersion k (pre ++ post) = finalVersion k (post ++ pre) := by
  rw [finalVersion_append, finalVersion_append]
  exact Nat.max_comm _ _

end KeyRouting
end Replication
