/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# DDL barrier covers every handoff path (Invariant I5)

Before a DDL is applied to ClickHouse, every row read from the source BEFORE that
DDL must already be written under the pre-DDL schema. A row that is still pending
anywhere when the ALTER runs is written against the post-DDL schema: the insert
succeeds, row counts match, and the contents are wrong (silent corruption).

Rows can be pending on three paths:

* the **legacy** shared handoff queue (`records`, `thread.pool.size == 1`);
* one of N **routed** per-thread queues (`routedQueues`, hash routing, the default
  `thread.pool.size = 10`);
* **outstanding** — a batch a worker has already taken off a queue but has not yet
  written and acknowledged (`DebeziumOffsetManagement.hasUnwrittenBatches`).

The pre-fix `drainBeforeDDL` waited only for the legacy queue to empty (and then
for the executor's `activeBatches` to reach 0, which is 0 between scheduled ticks
even while every routed queue holds work). This module states the barrier
predicate that covers all three paths, proves that a DDL step taken under it has
no pending pre-DDL row anywhere, and exhibits a concrete state showing that the
old predicate does NOT imply the new one.
-/

namespace Replication

/-- A batch of rows handed to the writers, identified by a number. -/
abbrev Batch := Nat

/-- Abstract state of the write pipeline as seen by the DDL barrier.
    `legacy`      = the shared legacy handoff queue;
    `routed`      = one queue per worker thread (hash routing);
    `outstanding` = batches a worker has dequeued but not yet acknowledged;
    `ddlApplied`  = whether the DDL under consideration has been applied. -/
structure Barrier where
  legacy : List Batch
  routed : List (List Batch)
  outstanding : Nat
  ddlApplied : Bool
deriving Repr

/-- The PRE-FIX guard: only the legacy queue is observed. -/
def legacyEmpty (s : Barrier) : Prop := s.legacy = []

/-- Every routed (per-thread) queue is empty. -/
def routedEmpty (s : Barrier) : Prop := ∀ q ∈ s.routed, q = []

/-- The barrier predicate implemented by `isPipelineQuiescent()` and used by
    `drainBeforeDDL` step 1: legacy queue empty, every routed queue empty, and no
    dequeued-but-unacknowledged batch. -/
def barrierReady (s : Barrier) : Prop :=
  s.legacy = [] ∧ (∀ q ∈ s.routed, q = []) ∧ s.outstanding = 0

/-- Number of batches queued across all routed queues. -/
def routedPending : List (List Batch) → Nat
  | [] => 0
  | q :: qs => q.length + routedPending qs

/-- Number of pre-DDL batches not yet written: queued on the legacy queue, queued
    on any routed queue, or dequeued but unacknowledged. -/
def pending (s : Barrier) : Nat :=
  s.legacy.length + routedPending s.routed + s.outstanding

/--
One transition of the pipeline.

* `handoffLegacy` / `handoffRouted` — the Debezium thread hands a batch to the
  writers (legacy queue, or routed queue `i`).
* `takeLegacy` / `takeRouted` — a worker dequeues a batch; it is now in flight
  (`outstanding + 1`).
* `ack` — a worker has written a batch and acknowledged it (`outstanding - 1`).
* `ddl` — the DDL is applied. Enabled ONLY when `barrierReady` holds: this is the
  guard `drainBeforeDDL` enforces before `performDDLOperation` runs.
-/
inductive Step : Barrier → Barrier → Prop
  | handoffLegacy (s : Barrier) (b : Batch) :
      Step s { s with legacy := s.legacy ++ [b] }
  | handoffRouted (s : Barrier) (i : Nat) (b : Batch) (h : i < s.routed.length) :
      Step s { s with routed := s.routed.set i (s.routed.get ⟨i, h⟩ ++ [b]) }
  | takeLegacy (s : Barrier) (b : Batch) (rest : List Batch) (h : s.legacy = b :: rest) :
      Step s { s with legacy := rest, outstanding := s.outstanding + 1 }
  | takeRouted (s : Barrier) (i : Nat) (b : Batch) (rest : List Batch)
      (h : i < s.routed.length) (hq : s.routed.get ⟨i, h⟩ = b :: rest) :
      Step s { s with routed := s.routed.set i rest, outstanding := s.outstanding + 1 }
  | ack (s : Barrier) (h : 0 < s.outstanding) :
      Step s { s with outstanding := s.outstanding - 1 }
  | ddl (s : Barrier) (h : barrierReady s) :
      Step s { s with ddlApplied := true }

/-! ## The barrier is sufficient -/

/-- If every routed queue is empty, nothing is queued across them. -/
theorem routedPending_eq_zero_of_all_empty (qs : List (List Batch))
    (h : ∀ q ∈ qs, q = []) : routedPending qs = 0 := by
  induction qs with
  | nil => rfl
  | cons q qs ih =>
      have hq : q = [] := h q (List.mem_cons_self q qs)
      have hrest : ∀ r ∈ qs, r = [] := fun r hr => h r (List.mem_cons_of_mem q hr)
      simp [routedPending, hq, ih hrest]

/-- A `barrierReady` state has no pending pre-DDL batch on any path. -/
theorem barrierReady_pending_zero (s : Barrier) (h : barrierReady s) : pending s = 0 := by
  obtain ⟨hl, hr, ho⟩ := h
  simp [pending, hl, ho, routedPending_eq_zero_of_all_empty s.routed hr]

/--
**A step that applies the DDL was taken from a `barrierReady` state.** The only
transition that can flip `ddlApplied` from `false` to `true` is `ddl`, and it is
guarded by `barrierReady`.
-/
theorem ddl_step_barrierReady (s s' : Barrier) (hstep : Step s s')
    (hbefore : s.ddlApplied = false) (hafter : s'.ddlApplied = true) :
    barrierReady s := by
  cases hstep with
  | ddl h => exact h
  | handoffLegacy _ => simp [hbefore] at hafter
  | handoffRouted _ _ _ => simp [hbefore] at hafter
  | takeLegacy _ _ _ => simp [hbefore] at hafter
  | takeRouted _ _ _ _ _ => simp [hbefore] at hafter
  | ack _ => simp [hbefore] at hafter

/--
**Invariant I5.** If a step applies the DDL, then at the moment it is applied no
pre-DDL batch is pending anywhere: the legacy queue is empty, every routed queue
is empty, and no dequeued batch is awaiting acknowledgement.
-/
theorem ddl_applies_only_when_no_pending_rows (s s' : Barrier) (hstep : Step s s')
    (hbefore : s.ddlApplied = false) (hafter : s'.ddlApplied = true) :
    pending s = 0 :=
  barrierReady_pending_zero s (ddl_step_barrierReady s s' hstep hbefore hafter)

/-! ## The pre-fix predicate is NOT sufficient -/

/-- Concrete counterexample: the legacy queue is empty, but routed queue 0 still
    holds batch `1` (hash-routing mode with one pre-DDL batch not yet dequeued). -/
def routedBacklog : Barrier :=
  { legacy := [], routed := [[1]], outstanding := 0, ddlApplied := false }

/--
**The old guard is insufficient.** There is a state in which the legacy queue is
empty — so the pre-fix `drainBeforeDDL` would proceed to apply the DDL — yet the
barrier is NOT ready, because a routed queue still holds a pre-DDL batch.
-/
theorem old_predicate_insufficient : ∃ s, legacyEmpty s ∧ ¬ barrierReady s := by
  refine ⟨routedBacklog, rfl, ?_⟩
  intro h
  have hq := h.2.1 [1] (by simp [routedBacklog])
  simp at hq

/-- The same witness has a pending row: applying the DDL there writes that row
    against the post-DDL schema. -/
theorem old_predicate_admits_pending_rows : ∃ s, legacyEmpty s ∧ 0 < pending s := by
  refine ⟨routedBacklog, rfl, ?_⟩
  simp [pending, routedPending, routedBacklog]

/-- Concrete counterexample for the in-flight path: both queue sets are empty, but
    one batch has been dequeued by a worker and not yet acknowledged. -/
def inFlightBacklog : Barrier :=
  { legacy := [], routed := [[], []], outstanding := 1, ddlApplied := false }

/--
**Empty queues alone are not sufficient either.** A batch a worker has dequeued
but not yet written is invisible to both queues; the barrier must also wait for
the unacknowledged-batch count to reach zero.
-/
theorem queues_empty_insufficient :
    ∃ s, legacyEmpty s ∧ routedEmpty s ∧ ¬ barrierReady s := by
  refine ⟨inFlightBacklog, ?_, ?_, ?_⟩
  · rfl
  · intro q hq
    simp [inFlightBacklog] at hq
    exact hq
  · intro h
    have ho := h.2.2
    simp [inFlightBacklog] at ho

end Replication
