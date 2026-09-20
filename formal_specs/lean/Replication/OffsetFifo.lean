/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# Offset acknowledgement FIFO by handoff sequence (Invariant I8, spec 09.01)

Worker threads write batches to ClickHouse in arbitrary wall-clock order, but the
Debezium offset store keeps the LAST offset staged per partition, so the ORDER in
which written batches are acknowledged decides whether the durable binlog position
can run ahead of rows that are still queued or in flight.

The connector orders acknowledgements by the **handoff sequence**: a monotone
counter assigned by the Debezium thread when a batch is handed to the writers
(binlog order). This module models that FIFO and proves:

* `commit_never_passes_outstanding` — in every reachable state, every acknowledged
  sequence is smaller than every outstanding one: the durable offset never passes
  a batch that is queued, in flight, or parked, on any worker.
* `acked_downward_closed`, `commitPoint_acked`, `outstanding_ge_commitPoint` — the
  acknowledged sequences are a prefix `0, 1, …, commitPoint-1` of the handoff
  order, and every outstanding sequence is `≥ commitPoint`.
* `write_at_most_once`, `written_batch_not_reexecuted` — a batch's write event
  happens at most once; a batch that is written but parked (waiting for older
  batches) is never executed again.
* `old_overlap_rule_unsafe` — a concrete counterexample to the rule this FIFO
  replaces: with two batches of equal timestamps, the strict timestamp-overlap
  predicate (`otherMinTs < currentMaxTs`) acknowledges the later-finished batch
  while the other is still outstanding; the FIFO parks it instead.

Everything is executable (membership is a `Bool` function), so the concrete
counterexamples are checked by `decide`.
-/

set_option autoImplicit false

namespace Replication
namespace OffsetFifo

/-! ## List helpers (kept local so the model depends on nothing beyond core) -/

/-- Executable membership test. -/
def mem (n : Nat) : List Nat → Bool
  | [] => false
  | x :: xs => if x = n then true else mem n xs

theorem mem_iff (n : Nat) (l : List Nat) : mem n l = true ↔ n ∈ l := by
  induction l with
  | nil => simp [mem]
  | cons x xs ih =>
    by_cases h : x = n
    · subst h; simp [mem]
    · have h' : n ≠ x := fun e => h e.symm
      simp [mem, h, h', ih]

/-- Remove every occurrence of `n`. -/
def remove (n : Nat) : List Nat → List Nat
  | [] => []
  | x :: xs => if x = n then remove n xs else x :: remove n xs

theorem mem_remove_of_ne {n m : Nat} {l : List Nat} (hne : m ≠ n) (h : m ∈ l) :
    m ∈ remove n l := by
  induction l with
  | nil => simp at h
  | cons x xs ih =>
    rcases List.mem_cons.mp h with h | h
    · subst h; simp [remove, hne]
    · by_cases hx : x = n
      · simp [remove, hx]; exact ih h
      · simp [remove, hx]; exact Or.inr (ih h)

/-- Every element of the list is strictly greater than `n`. -/
def Below (n : Nat) : List Nat → Prop
  | [] => True
  | x :: xs => n < x ∧ Below n xs

/-- Strictly ascending list. -/
def Sorted : List Nat → Prop
  | [] => True
  | x :: xs => Below x xs ∧ Sorted xs

/-- No element occurs twice. -/
def Distinct : List Nat → Prop
  | [] => True
  | x :: xs => x ∉ xs ∧ Distinct xs

theorem below_iff (n : Nat) (l : List Nat) : Below n l ↔ ∀ t ∈ l, n < t := by
  induction l with
  | nil => simp [Below]
  | cons x xs ih => simp [Below, ih]

theorem below_append_single {n m : Nat} {l : List Nat} (h : Below n l) (hm : n < m) :
    Below n (l ++ [m]) := by
  induction l with
  | nil => simp [Below, hm]
  | cons x xs ih => exact ⟨h.1, ih h.2⟩

theorem sorted_append_single {l : List Nat} {m : Nat} (hs : Sorted l)
    (hb : ∀ t ∈ l, t < m) : Sorted (l ++ [m]) := by
  induction l with
  | nil => simp [Sorted, Below]
  | cons x xs ih =>
    refine ⟨below_append_single hs.1 (hb x (List.mem_cons_self _ _)), ?_⟩
    exact ih hs.2 (fun t ht => hb t (List.mem_cons_of_mem _ ht))

/-! ## The model -/

/--
State of the handoff FIFO.
* `next`        — the next handoff sequence to assign (monotone counter);
* `outstanding` — sequences handed off and not yet acknowledged, ascending;
* `completed`   — sequences whose rows are written but whose offset is parked;
* `acked`       — sequences whose offset has been acknowledged (most recent first);
* `writes`      — log of every write the workers executed.
-/
structure Fifo where
  next : Nat
  outstanding : List Nat
  completed : List Nat
  acked : List Nat
  writes : List Nat
deriving Repr, DecidableEq

def init : Fifo := ⟨0, [], [], [], []⟩

inductive Ev where
  | handoff
  | write (s : Nat)
deriving Repr

/--
Acknowledge the head of `outstanding` if it is completed (one step of
`checkIfBatchCanBeCommitted`'s drain); `none` when the head is still unwritten
or nothing is outstanding.
-/
def ackHead (st : Fifo) : Option Fifo :=
  match st.outstanding with
  | [] => none
  | h :: rest =>
    if mem h st.completed then
      some { st with outstanding := rest,
                     completed := remove h st.completed,
                     acked := h :: st.acked }
    else none

/-- Repeat `ackHead` while it succeeds. Fuel bounds the recursion by `|outstanding|`. -/
def drain : Nat → Fifo → Fifo
  | 0, st => st
  | fuel + 1, st =>
    match ackHead st with
    | none => st
    | some st' => drain fuel st'

/--
One step.
* `handoff`: the Debezium thread assigns `next` and appends it (`registerHandoff`).
* `write s`: a worker finishes writing batch `s`. This is enabled only while `s`
  is outstanding and NOT yet completed — a written batch is dropped by its worker
  and never handed back — after which the drain runs.
-/
def step (st : Fifo) : Ev → Fifo
  | .handoff => { st with next := st.next + 1, outstanding := st.outstanding ++ [st.next] }
  | .write s =>
    if mem s st.outstanding && !mem s st.completed then
      drain st.outstanding.length
        { st with completed := s :: st.completed, writes := s :: st.writes }
    else st

def run (st : Fifo) (evs : List Ev) : Fifo := evs.foldl step st

def Reachable (st : Fifo) : Prop := ∃ evs, run init evs = st

/-- Number of leading sequences `0, 1, 2, …` that are all acknowledged. -/
def scan (acked : List Nat) : Nat → Nat → Nat
  | 0, k => k
  | fuel + 1, k => if mem k acked then scan acked fuel (k + 1) else k

def commitPoint (st : Fifo) : Nat := scan st.acked st.next 0

/-! ## The inductive invariant -/

structure Inv (st : Fifo) : Prop where
  acked_lt_outstanding : ∀ a ∈ st.acked, ∀ t ∈ st.outstanding, a < t
  sorted : Sorted st.outstanding
  outstanding_lt_next : ∀ t ∈ st.outstanding, t < st.next
  covered : ∀ t, t < st.next → t ∈ st.outstanding ∨ t ∈ st.acked
  writes_tracked : ∀ w ∈ st.writes, w ∈ st.completed ∨ w ∈ st.acked
  writes_distinct : Distinct st.writes
  acked_lt_next : ∀ a ∈ st.acked, a < st.next

theorem inv_init : Inv init := by
  refine ⟨?_, ?_, ?_, ?_, ?_, ?_, ?_⟩ <;> simp [init, Sorted, Distinct]

theorem inv_handoff {st : Fifo} (h : Inv st) : Inv (step st .handoff) := by
  show Inv { st with next := st.next + 1, outstanding := st.outstanding ++ [st.next] }
  refine ⟨?_, ?_, ?_, ?_, ?_, ?_, ?_⟩
  · intro a ha t ht
    rcases List.mem_append.mp ht with ht | ht
    · exact h.acked_lt_outstanding a ha t ht
    · have : t = st.next := List.mem_singleton.mp ht
      subst this; exact h.acked_lt_next a ha
  · exact sorted_append_single h.sorted h.outstanding_lt_next
  · intro t ht
    rcases List.mem_append.mp ht with ht | ht
    · exact Nat.lt_succ_of_lt (h.outstanding_lt_next t ht)
    · have : t = st.next := List.mem_singleton.mp ht
      subst this; exact Nat.lt_succ_self _
  · intro t ht
    by_cases he : t = st.next
    · subst he; exact Or.inl (List.mem_append.mpr (Or.inr (List.mem_singleton.mpr rfl)))
    · have : t < st.next := Nat.lt_of_le_of_ne (Nat.le_of_lt_succ ht) he
      rcases h.covered t this with hc | hc
      · exact Or.inl (List.mem_append.mpr (Or.inl hc))
      · exact Or.inr hc
  · exact h.writes_tracked
  · exact h.writes_distinct
  · intro a ha; exact Nat.lt_succ_of_lt (h.acked_lt_next a ha)

theorem inv_ackHead {st st' : Fifo} (h : Inv st) (hak : ackHead st = some st') : Inv st' := by
  unfold ackHead at hak
  split at hak
  · cases hak
  · rename_i hd rest hout
    split at hak
    · rename_i hc
      cases hak
      have hsorted : Sorted (hd :: rest) := hout ▸ h.sorted
      have hd_mem : hd ∈ st.outstanding := by rw [hout]; exact List.mem_cons_self _ _
      have rest_mem : ∀ t ∈ rest, t ∈ st.outstanding := by
        intro t ht; rw [hout]; exact List.mem_cons_of_mem _ ht
      refine ⟨?_, ?_, ?_, ?_, ?_, ?_, ?_⟩
      · intro a ha t ht
        rcases List.mem_cons.mp ha with ha | ha
        · subst ha; exact (below_iff _ _).mp hsorted.1 t ht
        · exact h.acked_lt_outstanding a ha t (rest_mem t ht)
      · exact hsorted.2
      · intro t ht; exact h.outstanding_lt_next t (rest_mem t ht)
      · intro t ht
        rcases h.covered t ht with hc' | hc'
        · rw [hout] at hc'
          rcases List.mem_cons.mp hc' with hc' | hc'
          · subst hc'; exact Or.inr (List.mem_cons_self _ _)
          · exact Or.inl hc'
        · exact Or.inr (List.mem_cons_of_mem _ hc')
      · intro w hw
        rcases h.writes_tracked w hw with hw' | hw'
        · by_cases hwe : w = hd
          · subst hwe; exact Or.inr (List.mem_cons_self _ _)
          · exact Or.inl (mem_remove_of_ne hwe hw')
        · exact Or.inr (List.mem_cons_of_mem _ hw')
      · exact h.writes_distinct
      · intro a ha
        rcases List.mem_cons.mp ha with ha | ha
        · subst ha; exact h.outstanding_lt_next _ hd_mem
        · exact h.acked_lt_next a ha
    · cases hak

theorem inv_drain (fuel : Nat) : ∀ {st : Fifo}, Inv st → Inv (drain fuel st) := by
  induction fuel with
  | zero => intro st h; exact h
  | succ fuel ih =>
    intro st h
    unfold drain
    cases hak : ackHead st with
    | none => exact h
    | some st' => exact ih (inv_ackHead h hak)

theorem inv_write {st : Fifo} (h : Inv st) (s : Nat) : Inv (step st (.write s)) := by
  show Inv (if mem s st.outstanding && !mem s st.completed then
              drain st.outstanding.length
                { st with completed := s :: st.completed, writes := s :: st.writes }
            else st)
  by_cases hg : (mem s st.outstanding && !mem s st.completed) = true
  · rw [if_pos hg]
    apply inv_drain
    have hout : s ∈ st.outstanding := by
      have hb : mem s st.outstanding = true := (Bool.and_eq_true _ _).mp hg |>.1
      exact (mem_iff _ _).mp hb
    have hnotc : s ∉ st.completed := by
      intro hc
      have hm : mem s st.completed = true := (mem_iff _ _).mpr hc
      simp [hm] at hg
    refine ⟨h.acked_lt_outstanding, h.sorted, h.outstanding_lt_next, h.covered, ?_, ?_,
      h.acked_lt_next⟩
    · intro w hw
      rcases List.mem_cons.mp hw with hw | hw
      · subst hw; exact Or.inl (List.mem_cons_self _ _)
      · rcases h.writes_tracked w hw with hw' | hw'
        · exact Or.inl (List.mem_cons_of_mem _ hw')
        · exact Or.inr hw'
    · refine ⟨?_, h.writes_distinct⟩
      intro hs
      rcases h.writes_tracked s hs with hc | ha
      · exact hnotc hc
      · exact Nat.lt_irrefl s (h.acked_lt_outstanding s ha s hout)
  · rw [if_neg hg]; exact h

theorem inv_step {st : Fifo} (h : Inv st) (e : Ev) : Inv (step st e) := by
  cases e with
  | handoff => exact inv_handoff h
  | write s => exact inv_write h s

theorem inv_run (evs : List Ev) : ∀ {st : Fifo}, Inv st → Inv (run st evs) := by
  induction evs with
  | nil => intro st h; exact h
  | cons e evs ih =>
    intro st h
    show Inv (List.foldl step (step st e) evs)
    exact ih (inv_step h e)

theorem inv_reachable {st : Fifo} (h : Reachable st) : Inv st := by
  obtain ⟨evs, hrun⟩ := h
  rw [← hrun]
  exact inv_run evs inv_init

/-! ## (a) The commit never passes an outstanding batch -/

/--
**Safety.** In every reachable state every acknowledged sequence is strictly
smaller than every outstanding one. The durable offset therefore never passes a
batch that is still queued, in flight, or parked — on any worker thread.
-/
theorem commit_never_passes_outstanding {st : Fifo} (h : Reachable st) :
    ∀ a ∈ st.acked, ∀ t ∈ st.outstanding, a < t :=
  (inv_reachable h).acked_lt_outstanding

/-- Acknowledgements form a prefix of the handoff order. -/
theorem acked_downward_closed {st : Fifo} (h : Reachable st) :
    ∀ a ∈ st.acked, ∀ t, t < a → t ∈ st.acked := by
  have hi := inv_reachable h
  intro a ha t hta
  have htn : t < st.next := Nat.lt_trans hta (hi.acked_lt_next a ha)
  rcases hi.covered t htn with ho | hk
  · exact absurd (Nat.lt_trans hta (hi.acked_lt_outstanding a ha t ho)) (Nat.lt_irrefl t)
  · exact hk

theorem scan_acked (acked : List Nat) (fuel : Nat) :
    ∀ k t, k ≤ t → t < scan acked fuel k → t ∈ acked := by
  induction fuel with
  | zero =>
    intro k t hk ht
    simp only [scan] at ht
    exact absurd ht (Nat.not_lt_of_le hk)
  | succ fuel ih =>
    intro k t hk ht
    simp only [scan] at ht
    by_cases hm : mem k acked = true
    · rw [if_pos hm] at ht
      by_cases hkt : t = k
      · subst hkt; exact (mem_iff _ _).mp hm
      · exact ih (k + 1) t (Nat.lt_of_le_of_ne hk (fun e => hkt e.symm)) ht
    · rw [if_neg hm] at ht
      exact absurd ht (Nat.not_lt_of_le hk)

/-- Every sequence below the commit point is acknowledged. -/
theorem commitPoint_acked (st : Fifo) : ∀ t, t < commitPoint st → t ∈ st.acked :=
  fun t ht => scan_acked st.acked st.next 0 t (Nat.zero_le _) ht

/-- No outstanding sequence lies below the commit point. -/
theorem outstanding_ge_commitPoint {st : Fifo} (h : Reachable st) :
    ∀ t ∈ st.outstanding, commitPoint st ≤ t := by
  intro t ht
  apply Nat.le_of_not_lt
  intro hlt
  have hack : t ∈ st.acked := commitPoint_acked st t hlt
  exact Nat.lt_irrefl t (commit_never_passes_outstanding h t hack t ht)

/-! ## Written-once -/

/-- **A batch's write event occurs at most once** in any reachable state. -/
theorem write_at_most_once {st : Fifo} (h : Reachable st) : Distinct st.writes :=
  (inv_reachable h).writes_distinct

/-- A written (parked) batch is never executed again: the write step is a no-op. -/
theorem written_batch_not_reexecuted (st : Fifo) (s : Nat) (hc : s ∈ st.completed) :
    step st (.write s) = st := by
  have hm : mem s st.completed = true := (mem_iff _ _).mpr hc
  show (if mem s st.outstanding && !mem s st.completed then
          drain st.outstanding.length
            { st with completed := s :: st.completed, writes := s :: st.writes }
        else st) = st
  simp [hm]

/-! ## (b) The timestamp-overlap rule this FIFO replaces is unsafe -/

/-- The envelope-timestamp range of a batch. -/
structure TsRange where
  minTs : Nat
  maxTs : Nat
deriving Repr, DecidableEq

/--
The deleted predicate (`checkIfThereAreInflightRequests`): `current` was BLOCKED
by an in-flight `other` iff `other.minTs < current.maxTs` (strict). Anything not
blocked was acknowledged immediately.
-/
def overlapBlocks (current other : TsRange) : Bool :=
  decide (other.minTs < current.maxTs)

/--
**Counterexample.** Batch A (sequence 0, handed off first) and batch B (sequence
1) both have every row in millisecond 100. B is written first while A is still
outstanding (queued or in flight):
* the old rule does NOT block B (`100 < 100` is false), so B's offset — which
  lies past A's rows — would be committed while A is unwritten;
* the FIFO parks B: nothing is acknowledged, A (sequence 0) is still outstanding.
-/
theorem old_overlap_rule_unsafe :
    overlapBlocks ⟨100, 100⟩ ⟨100, 100⟩ = false
    ∧ (run init [.handoff, .handoff, .write 1]).acked = []
    ∧ mem 0 (run init [.handoff, .handoff, .write 1]).outstanding = true
    ∧ mem 1 (run init [.handoff, .handoff, .write 1]).completed = true := by
  decide

/-- The FIFO acknowledges strictly in handoff order: once A is written, A is
acknowledged and only then B (`acked` lists the most recent first). -/
theorem fifo_acknowledges_in_handoff_order :
    (run init [.handoff, .handoff, .write 1, .write 0]).acked = [1, 0]
    ∧ (run init [.handoff, .handoff, .write 1, .write 0]).outstanding = [] := by
  decide

end OffsetFifo
end Replication
