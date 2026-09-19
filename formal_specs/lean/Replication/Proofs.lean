/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.Binlog
import Replication.ClickHouse
import Replication.Engine
import Replication.Invariants

namespace Replication

/-! ## Helper lemmas for the ReplacingMergeTree FINAL evaluation -/

/-- Boolean equality is `false` for distinct keys (core only, via `LawfulBEq`). -/
theorem beq_false_of_ne {a b : Key} (h : a ≠ b) : (a == b) = false := by
  cases hb : a == b with
  | false => rfl
  | true  => exact absurd (eq_of_beq hb) h

/-- The result of the max-version fold is either drawn from the list or is the
    starting accumulator. -/
theorem foldlMax_mem (xs : List CHRecord) (init : Option CHRecord) (m : CHRecord)
    (h : xs.foldl maxStep init = some m) : m ∈ xs ∨ init = some m := by
  induction xs generalizing init with
  | nil => exact Or.inr h
  | cons x xs ih =>
      have h' : xs.foldl maxStep (maxStep init x) = some m := h
      rcases ih (maxStep init x) h' with hmem | hstep
      · exact Or.inl (List.mem_cons_of_mem _ hmem)
      · cases init with
        | none =>
            have : x = m := by
              have := hstep
              simp [maxStep] at this
              exact this
            exact Or.inl (this ▸ List.mem_cons_self _ _)
        | some m0 =>
            by_cases hv : x.version ≥ m0.version
            · have : x = m := by
                have := hstep
                simp [maxStep, hv] at this
                exact this
              exact Or.inl (this ▸ List.mem_cons_self _ _)
            · have : m0 = m := by
                have := hstep
                simp [maxStep, hv] at this
                exact this
              exact Or.inr (by rw [this])

/-- Appending a record whose version strictly exceeds every version already
    present makes it the unique maximum. -/
theorem findMaxVersion_snoc_dom (xs : List CHRecord) (rec : CHRecord)
    (h : ∀ r ∈ xs, r.version < rec.version) :
    findMaxVersion (xs ++ [rec]) = some rec := by
  unfold findMaxVersion
  rw [List.foldl_append]
  simp only [List.foldl_cons, List.foldl_nil]
  cases hf : xs.foldl maxStep none with
  | none => simp [maxStep]
  | some m =>
      have hm : m ∈ xs := by
        rcases foldlMax_mem xs none m hf with h1 | h2
        · exact h1
        · exact absurd h2 (by simp)
      have : m.version < rec.version := h m hm
      simp [maxStep, Nat.le_of_lt this]

/-- `filter` distributes over concatenation (self-contained, core only). -/
theorem filter_append_self {α : Type _} (p : α → Bool) (l1 l2 : List α) :
    (l1 ++ l2).filter p = l1.filter p ++ l2.filter p := by
  induction l1 with
  | nil => rfl
  | cons x xs ih =>
      rw [List.cons_append, List.filter_cons, List.filter_cons]
      by_cases hpx : p x = true
      · rw [if_pos hpx, if_pos hpx, ih, List.cons_append]
      · rw [if_neg hpx, if_neg hpx, ih]

/-- Membership survives `filter` only for elements already present. -/
theorem mem_of_mem_filter' {α : Type _} {p : α → Bool} {a : α} {l : List α}
    (h : a ∈ l.filter p) : a ∈ l := by
  induction l with
  | nil => simp [List.filter] at h
  | cons x xs ih =>
      rw [List.filter_cons] at h
      by_cases hpx : p x = true
      · rw [if_pos hpx] at h
        rcases List.mem_cons.mp h with he | ht
        · exact he ▸ List.mem_cons_self _ _
        · exact List.mem_cons_of_mem _ (ih ht)
      · rw [if_neg hpx] at h
        exact List.mem_cons_of_mem _ (ih h)

/-- An element that survives `filter` satisfies the predicate. -/
theorem pred_of_mem_filter' {α : Type _} {p : α → Bool} {a : α} {l : List α}
    (h : a ∈ l.filter p) : p a = true := by
  induction l with
  | nil => simp [List.filter] at h
  | cons x xs ih =>
      rw [List.filter_cons] at h
      by_cases hpx : p x = true
      · rw [if_pos hpx] at h
        rcases List.mem_cons.mp h with he | ht
        · rw [he]; exact hpx
        · exact ih ht
      · rw [if_neg hpx] at h
        exact ih h

/-- `filterKey` distributes over list concatenation. -/
theorem filterKey_append (t1 t2 : CHTable) (k : Key) :
    filterKey (t1 ++ t2) k = filterKey t1 k ++ filterKey t2 k := by
  unfold filterKey
  exact filter_append_self _ t1 t2

/-- Membership in `filterKey` implies membership in the underlying table. -/
theorem mem_of_mem_filterKey {table : CHTable} {k : Key} {r : CHRecord}
    (h : r ∈ filterKey table k) : r ∈ table :=
  mem_of_mem_filter' h

/--
The workhorse: appending a single record `rec` whose version strictly dominates
every record already stored for the query key `k` updates the FINAL view for `k`
to reflect `rec` exactly, and leaves every other key untouched.
-/
theorem chFinalView_snoc (acc : CHTable) (rec : CHRecord) (k : Key)
    (h : ∀ r ∈ filterKey acc k, r.version < rec.version) :
    chFinalView (acc ++ [rec]) k
      = (if rec.key = k then (if rec.is_deleted then none else some rec.row)
         else chFinalView acc k) := by
  unfold chFinalView
  rw [filterKey_append]
  by_cases hk : rec.key = k
  · -- rec belongs to key k
    have hf1 : filterKey [rec] k = [rec] := by
      unfold filterKey
      simp [hk]
    rw [hf1]
    have hdom := findMaxVersion_snoc_dom (filterKey acc k) rec h
    rw [hdom]
    simp [hk]
  · -- rec belongs to a different key; the view for k is unchanged
    have hf1 : filterKey [rec] k = [] := by
      unfold filterKey
      simp [hk]
    rw [hf1]
    simp [hk]

/-! ## Version monotonicity of the coordinate encoding -/

/--
Theorem: within its well-formed domain, the binary-log coordinate encoding is
strictly order preserving: a lexicographically greater coordinate maps to a
strictly greater version. The bounds are what make the claim true — without them
a large enough `offset`/`rowIdx` carries into the next field. The engine itself
does not rely on this arithmetic (it versions by stream ordinal); this theorem
verifies the encoding stated in the specs is sound where it is used.
-/
theorem version_strictly_monotonic (p1 p2 : BinlogPos)
    (w1 : BinlogPos.WellFormed p1) (w2 : BinlogPos.WellFormed p2)
    (h : p1 < p2) : encodeVersion p1 < encodeVersion p2 := by
  simp only [BinlogPos.WellFormed] at w1 w2
  obtain ⟨w1o, w1r⟩ := w1
  obtain ⟨w2o, w2r⟩ := w2
  simp only [encodeVersion]
  have hlt : p1.fileSeq < p2.fileSeq ∨
      (p1.fileSeq = p2.fileSeq ∧
        (p1.offset < p2.offset ∨ (p1.offset = p2.offset ∧ p1.rowIdx < p2.rowIdx))) := h
  rcases hlt with h_file | ⟨h_file_eq, h_off | ⟨h_off_eq, h_row⟩⟩ <;> omega

/-! ## Convergence -/

/--
General convergence lemma. If the accumulated ClickHouse table is coherent with a
MySQL state `st` (same FINAL view for every key) and all its versions sit below
the next ordinal's slot, then replicating the remaining events from ordinal `i`
keeps the FINAL view equal to the MySQL state obtained by applying those events.
-/
theorem replicate_converges_gen :
    ∀ (events : List BinlogEvent) (i : Nat) (acc : CHTable) (st : MySQLState),
      0 < i →
      (∀ r ∈ acc, r.version < 2 * i - 1) →
      (∀ k, chFinalView acc k = st k) →
      ∀ k, chFinalView (replicateFrom i acc events) k
             = (events.foldl applyBinlogEvent st) k := by
  intro events
  induction events with
  | nil =>
      intro i acc st _ _ hview k
      simpa [replicateFrom] using hview k
  | cons e rest ih =>
      intro i acc st hi hbound hview k
      cases hop : e.op with
      -- DESTRUCTIVE: formal-model proof case for the `truncate` constructor — this
      -- is a Lean proof about list/state semantics; nothing is executed or dropped.
      | truncate =>
          -- Both sides reset: the table is cleared, MySQL is emptied.
          have hstep : replicateFrom i acc (e :: rest)
              = replicateFrom (i + 1) emptyCH rest := by
            simp [replicateFrom, hop, emptyCH]
          have hmysql : applyBinlogEvent st e = emptyMySQL := by
            funext x; simp [applyBinlogEvent, hop, emptyMySQL]
          rw [hstep]
          have hb : ∀ r ∈ (emptyCH : CHTable), r.version < 2 * (i + 1) - 1 := by
            intro r hr; simp [emptyCH] at hr
          have hv : ∀ q, chFinalView (emptyCH : CHTable) q = emptyMySQL q := by
            intro q; simp [chFinalView, emptyCH, filterKey, findMaxVersion, emptyMySQL]
          have := ih (i + 1) emptyCH emptyMySQL (by omega) hb hv k
          rw [this]
          simp [List.foldl, hmysql]
      | insert k0 v =>
          -- DESTRUCTIVE: `BinlogOp.truncate` appears only as a proof term here
          -- (proving this insert case is not the truncate constructor); no data op.
          have hne : e.op ≠ BinlogOp.truncate := by rw [hop]; intro h; cases h
          have hstep : replicateFrom i acc (e :: rest)
              = replicateFrom (i + 1) (acc ++ translateEventAt i e) rest := by
            simp [replicateFrom, hop]
          rw [hstep]
          have htr : translateEventAt i e
              = [{ key := k0, row := v, version := liveVersion i, is_deleted := false }] := by
            simp [translateEventAt, hop]
          -- coherence of the extended table
          have hview' : ∀ q, chFinalView (acc ++ translateEventAt i e) q = applyBinlogEvent st e q := by
            intro q
            rw [htr]
            have hdom : ∀ r ∈ filterKey acc q, r.version < liveVersion i := by
              intro r hr
              have := hbound r (mem_of_mem_filterKey hr)
              simp [liveVersion]; omega
            have := chFinalView_snoc acc { key := k0, row := v, version := liveVersion i, is_deleted := false } q hdom
            rw [this]
            by_cases hq : k0 = q
            · subst hq; simp [applyBinlogEvent, hop, beq_self_eq_true]
            · rw [if_neg hq, hview q]
              simp [applyBinlogEvent, hop, beq_false_of_ne (fun h => hq h.symm)]
          have hbound' : ∀ r ∈ (acc ++ translateEventAt i e), r.version < 2 * (i + 1) - 1 := by
            intro r hr
            rw [List.mem_append] at hr
            rcases hr with h1 | h2
            · have := hbound r h1; omega
            · rw [htr] at h2; simp at h2; subst h2; simp [liveVersion]; omega
          have := ih (i + 1) (acc ++ translateEventAt i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
          rw [this]; simp [List.foldl]
      | delete k0 =>
          have hstep : replicateFrom i acc (e :: rest)
              = replicateFrom (i + 1) (acc ++ translateEventAt i e) rest := by
            simp [replicateFrom, hop]
          rw [hstep]
          have htr : translateEventAt i e
              = [{ key := k0, row := [], version := liveVersion i, is_deleted := true }] := by
            simp [translateEventAt, hop]
          have hview' : ∀ q, chFinalView (acc ++ translateEventAt i e) q = applyBinlogEvent st e q := by
            intro q
            rw [htr]
            have hdom : ∀ r ∈ filterKey acc q, r.version < liveVersion i := by
              intro r hr
              have := hbound r (mem_of_mem_filterKey hr)
              simp [liveVersion]; omega
            have := chFinalView_snoc acc { key := k0, row := [], version := liveVersion i, is_deleted := true } q hdom
            rw [this]
            by_cases hq : k0 = q
            · subst hq; simp [applyBinlogEvent, hop, beq_self_eq_true]
            · rw [if_neg hq, hview q]
              simp [applyBinlogEvent, hop, beq_false_of_ne (fun h => hq h.symm)]
          have hbound' : ∀ r ∈ (acc ++ translateEventAt i e), r.version < 2 * (i + 1) - 1 := by
            intro r hr
            rw [List.mem_append] at hr
            rcases hr with h1 | h2
            · have := hbound r h1; omega
            · rw [htr] at h2; simp at h2; subst h2; simp [liveVersion]; omega
          have := ih (i + 1) (acc ++ translateEventAt i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
          rw [this]; simp [List.foldl]
      | update k_old k_new v =>
          have hstep : replicateFrom i acc (e :: rest)
              = replicateFrom (i + 1) (acc ++ translateEventAt i e) rest := by
            simp [replicateFrom, hop]
          rw [hstep]
          by_cases hkk : k_old = k_new
          · -- in-place update: single live record, same shape as insert
            have htr : translateEventAt i e
                = [{ key := k_new, row := v, version := liveVersion i, is_deleted := false }] := by
              simp [translateEventAt, hop, hkk]
            have hview' : ∀ q, chFinalView (acc ++ translateEventAt i e) q = applyBinlogEvent st e q := by
              intro q
              rw [htr]
              have hdom : ∀ r ∈ filterKey acc q, r.version < liveVersion i := by
                intro r hr
                have := hbound r (mem_of_mem_filterKey hr)
                simp [liveVersion]; omega
              have := chFinalView_snoc acc { key := k_new, row := v, version := liveVersion i, is_deleted := false } q hdom
              rw [this]
              by_cases hq : k_new = q
              · subst hq; simp [applyBinlogEvent, hop, hkk, beq_self_eq_true]
              · rw [if_neg hq, hview q]
                simp [applyBinlogEvent, hop, hkk, beq_false_of_ne (fun h => hq h.symm)]
            have hbound' : ∀ r ∈ (acc ++ translateEventAt i e), r.version < 2 * (i + 1) - 1 := by
              intro r hr
              rw [List.mem_append] at hr
              rcases hr with h1 | h2
              · have := hbound r h1; omega
              · rw [htr] at h2; simp at h2; subst h2; simp [liveVersion]; omega
            have := ih (i + 1) (acc ++ translateEventAt i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
            rw [this]; simp [List.foldl]
          · -- relocation: tombstone(k_old) + live(k_new)
            let tomb : CHRecord := { key := k_old, row := [], version := tombstoneVersion i, is_deleted := true }
            let live : CHRecord := { key := k_new, row := v, version := liveVersion i, is_deleted := false }
            have htomb : tomb = { key := k_old, row := [], version := tombstoneVersion i, is_deleted := true } := rfl
            have hlive : live = { key := k_new, row := v, version := liveVersion i, is_deleted := false } := rfl
            have htr : translateEventAt i e = [tomb, live] := by
              simp [translateEventAt, hop, beq_false_of_ne hkk, htomb, hlive]
            have hassoc : acc ++ translateEventAt i e = (acc ++ [tomb]) ++ [live] := by
              rw [htr]; simp [List.append_assoc]
            have hview' : ∀ q, chFinalView (acc ++ translateEventAt i e) q = applyBinlogEvent st e q := by
              intro q
              rw [hassoc]
              -- live dominates every record for q in (acc ++ [tomb])
              have hdom_live : ∀ r ∈ filterKey (acc ++ [tomb]) q, r.version < live.version := by
                intro r hr
                rw [filterKey_append, List.mem_append] at hr
                rcases hr with h1 | h2
                · have := hbound r (mem_of_mem_filterKey h1)
                  simp [hlive, liveVersion]; omega
                · have : r = tomb := by
                    have := mem_of_mem_filterKey h2; simpa using this
                  subst this; simp [htomb, hlive, tombstoneVersion, liveVersion]; omega
              rw [chFinalView_snoc (acc ++ [tomb]) live q hdom_live]
              -- tomb dominates every record for q in acc
              have hdom_tomb : ∀ r ∈ filterKey acc q, r.version < tomb.version := by
                intro r hr
                have := hbound r (mem_of_mem_filterKey hr)
                simp [htomb, tombstoneVersion]; omega
              rw [chFinalView_snoc acc tomb q hdom_tomb]
              -- now decide by q
              simp only [htomb, hlive]
              by_cases hqo : k_old = q
              · subst hqo
                simp [applyBinlogEvent, hop, beq_false_of_ne hkk, beq_self_eq_true, Ne.symm hkk]
              · by_cases hqn : k_new = q
                · subst hqn
                  simp [applyBinlogEvent, hop, beq_false_of_ne hkk, beq_self_eq_true,
                        beq_false_of_ne (fun h => hqo h.symm)]
                · rw [if_neg hqn, if_neg hqo, hview q]
                  simp [applyBinlogEvent, hop, beq_false_of_ne hkk,
                        beq_false_of_ne (fun h => hqo h.symm),
                        beq_false_of_ne (fun h => hqn h.symm)]
            have hbound' : ∀ r ∈ (acc ++ translateEventAt i e), r.version < 2 * (i + 1) - 1 := by
              intro r hr
              rw [htr, List.mem_append] at hr
              rcases hr with h1 | h2
              · have := hbound r h1; omega
              · simp [htomb, hlive] at h2
                rcases h2 with h2a | h2b
                · subst h2a; simp [tombstoneVersion]; omega
                · subst h2b; simp [liveVersion]; omega
            have := ih (i + 1) (acc ++ translateEventAt i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
            rw [this]; simp [List.foldl]

/--
Master Theorem (Invariant I3): for ANY binary log stream, ClickHouse
ReplacingMergeTree evaluation under FINAL equals MySQL execution for every
primary key. The ordinal versioning makes this hold without a monotonic-position
hypothesis; `_h_mono` is retained only to preserve the stated interface.
-/
theorem master_replication_convergence (events : List BinlogEvent)
    (_h_mono : StrictlyMonotonicStream events) :
    ReplicationConvergence events := by
  intro k
  have hb : ∀ r ∈ (emptyCH : CHTable), r.version < 2 * 1 - 1 := by
    intro r hr; simp [emptyCH] at hr
  have hv : ∀ q, chFinalView (emptyCH : CHTable) q = emptyMySQL q := by
    intro q; simp [chFinalView, emptyCH, filterKey, findMaxVersion, emptyMySQL]
  have := replicate_converges_gen events 1 emptyCH emptyMySQL (by omega) hb hv k
  simpa [replicateStream, evalMySQL] using this

/-! ## Single-event corollaries (derived from the master theorem) -/

/-- Theorem: a single insert converges. -/
theorem insert_convergence_single (k : Key) (v : Row) (pos : BinlogPos) (ts : Nat) :
    let ev : BinlogEvent := { pos := pos, op := BinlogOp.insert k v, ts := ts }
    ReplicationConvergence [ev] := by
  intro ev
  exact master_replication_convergence [ev] (by simp [StrictlyMonotonicStream])

/-- Theorem: an insert followed by a delete of the same key evaluates to `none`. -/
theorem delete_convergence_single (k : Key) (v : Row) (p1 p2 : BinlogPos) (t1 t2 : Nat)
    (h_order : p1 < p2) :
    let e_ins : BinlogEvent := { pos := p1, op := BinlogOp.insert k v, ts := t1 }
    let e_del : BinlogEvent := { pos := p2, op := BinlogOp.delete k, ts := t2 }
    chFinalView (replicateStream [e_ins, e_del]) k = none := by
  intro e_ins e_del
  have hmono : StrictlyMonotonicStream [e_ins, e_del] := by
    simp [StrictlyMonotonicStream]; exact h_order
  have hconv := master_replication_convergence [e_ins, e_del] hmono k
  rw [hconv]
  simp [evalMySQL, e_ins, e_del, applyBinlogEvent, emptyMySQL]

/-- Theorem: an insert followed by an in-place update converges to the new row. -/
theorem update_same_key_convergence (k : Key) (v_old v_new : Row) (p1 p2 : BinlogPos) (t1 t2 : Nat)
    (h_order : p1 < p2) :
    let e_ins : BinlogEvent := { pos := p1, op := BinlogOp.insert k v_old, ts := t1 }
    let e_upd : BinlogEvent := { pos := p2, op := BinlogOp.update k k v_new, ts := t2 }
    chFinalView (replicateStream [e_ins, e_upd]) k = some v_new := by
  intro e_ins e_upd
  have hmono : StrictlyMonotonicStream [e_ins, e_upd] := by
    simp [StrictlyMonotonicStream]; exact h_order
  have hconv := master_replication_convergence [e_ins, e_upd] hmono k
  rw [hconv]
  simp [evalMySQL, e_ins, e_upd, applyBinlogEvent, emptyMySQL]

/-- Theorem: a primary-key relocation leaves the old key absent and the new key present. -/
theorem update_pk_relocation_soundness (k_old k_new : Key) (v : Row) (p1 p2 : BinlogPos) (t1 t2 : Nat)
    (h_distinct : k_old ≠ k_new) (h_order : p1 < p2) :
    let e_ins : BinlogEvent := { pos := p1, op := BinlogOp.insert k_old v, ts := t1 }
    let e_upd : BinlogEvent := { pos := p2, op := BinlogOp.update k_old k_new v, ts := t2 }
    let table := replicateStream [e_ins, e_upd]
    chFinalView table k_old = none ∧ chFinalView table k_new = some v := by
  intro e_ins e_upd table
  have hmono : StrictlyMonotonicStream [e_ins, e_upd] := by
    simp [StrictlyMonotonicStream]; exact h_order
  constructor
  · have hconv := master_replication_convergence [e_ins, e_upd] hmono k_old
    rw [show table = replicateStream [e_ins, e_upd] from rfl, hconv]
    simp [evalMySQL, e_ins, e_upd, applyBinlogEvent, emptyMySQL, h_distinct]
  · have hconv := master_replication_convergence [e_ins, e_upd] hmono k_new
    rw [show table = replicateStream [e_ins, e_upd] from rfl, hconv]
    simp [evalMySQL, e_ins, e_upd, applyBinlogEvent, emptyMySQL, h_distinct, Ne.symm h_distinct]

end Replication
