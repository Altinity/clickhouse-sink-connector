/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.ClickHouse
import Replication.Proofs

-- DESTRUCTIVE: none -- this module is an abstract model of the rebuild; the
-- table drops it describes are constructors of a proof, never executed statements.
/-!
# Primary-key change: replica rebuild under a new key (Spec 06.09 §3.3 steps 5–7, §3.6, §4)

When a source `ALTER TABLE` changes the row identity, the connector rebuilds the
ClickHouse table under the new sorting key inside the DDL barrier: it creates a
table with the new `ORDER BY`, copies the **live** rows of `T FINAL`
(`is_deleted = 0`, `_version` unchanged), count-reconciles the copy against the
`SELECT` that fed it, and swaps the tables.

This module models that copy on the stored-table model of `ClickHouse.lean`
(`CHRecord`, `CHTable`, `findMaxVersion`, `chFinalView`):

* `live t` — the `FINAL`-collapsed records of `t` (the max-version record of
  each distinct key, ties resolved exactly as `findMaxVersion` does) whose
  delete flag is `false`;
* `rebuild t f` — the live records re-keyed by `f : Key → Key`, the map from
  the old identity to the new one (a function of the old identity, because the
  old key was the source primary key when the rows were written: Spec 06.09
  §3.4). `f` is injective on keys — that is what "the new key is an identity"
  means — and it is a hypothesis of the view theorems.

Proved:

* `rebuild_preserves_live_rows` — the rebuilt table holds no tombstone and has
  exactly `(live t).length` rows (the count reconciliation of §3.3 step 6);
* `rebuild_view_eq` — for every key `k`, the `FINAL` view of the rebuilt table
  at `f k` equals the `FINAL` view of `t` at `k` (live rows are preserved
  exactly once; tombstoned or absent keys stay absent);
* `rebuild_final_record` — the collapsed record at `f k` carries the SAME
  version, row and delete flag as the live record at `k` (Invariant I2 is
  untouched, §3.6);
* `rebuild_view_outside_image` — a key outside the image of `f` has no row.

`Key` is `Nat` in this model; injectivity is stated explicitly
(`∀ a b, f a = f b → a = b`) because core Lean has no `Function.Injective`.
-/

namespace Replication

/-! ## The FINAL live set of a stored table -/

/-- The `FINAL`-collapsed record for key `k`: the max-version record among the
    stored records of `k` (`none` when `k` was never written). -/
def finalRecord (t : CHTable) (k : Key) : Option CHRecord :=
  findMaxVersion (filterKey t k)

/-- The live record for `k`: its `FINAL` record unless that record is a tombstone. -/
def liveRecord (t : CHTable) (k : Key) : Option CHRecord :=
  match finalRecord t k with
  | none   => none
  | some r => if r.is_deleted then none else some r

/-- The distinct keys of a table (each key listed once). -/
def keysOf : CHTable → List Key
  | []      => []
  | r :: rs => if r.key ∈ keysOf rs then keysOf rs else r.key :: keysOf rs

/-- A key list without repetitions (self-contained, core only). -/
def NoDupKeys : List Key → Prop
  | []      => True
  | k :: ks => k ∉ ks ∧ NoDupKeys ks

/-- The live rows of `t FINAL`: one record per distinct key, the max-version
    record of that key, tombstones excluded. This is the result set of
    `SELECT ... FROM T FINAL WHERE is_deleted = 0`. -/
def live (t : CHTable) : List CHRecord :=
  (keysOf t).filterMap (liveRecord t)

/-- Re-key one record; version, row and delete flag are untouched. -/
def rekey (f : Key → Key) (r : CHRecord) : CHRecord :=
  { r with key := f r.key }

/-- The rebuilt table: the live rows of `t`, re-keyed by `f`, versions preserved
    (`INSERT INTO S SELECT ... FROM T FINAL WHERE is_deleted = 0`, §3.3 step 5). -/
def rebuild (t : CHTable) (f : Key → Key) : CHTable :=
  (live t).map (rekey f)

/-- What the rebuilt table stores for the image of one old key. -/
def liveImage (t : CHTable) (f : Key → Key) (k : Key) : List CHRecord :=
  match liveRecord t k with
  | some r => [rekey f r]
  | none   => []

/-! ## Helper lemmas -/

theorem filterKey_nil (k : Key) : filterKey [] k = [] := rfl

theorem filterKey_cons (r : CHRecord) (l : CHTable) (k : Key) :
    filterKey (r :: l) k = if r.key = k then r :: filterKey l k else filterKey l k := by
  unfold filterKey
  rw [List.filter_cons]
  by_cases h : r.key = k
  · simp [h]
  · simp [h, beq_false_of_ne h]

/-- A table none of whose records carries `k` has nothing for `k`. -/
theorem filterKey_eq_nil_of_ne (l : CHTable) (k : Key) (h : ∀ r ∈ l, r.key ≠ k) :
    filterKey l k = [] := by
  induction l with
  | nil => rfl
  | cons d ds ih =>
    rw [filterKey_cons, if_neg (h d (List.mem_cons_self _ _))]
    exact ih (fun r hr => h r (List.mem_cons_of_mem _ hr))

/-- A live record for `k` carries key `k`, is not a tombstone, and is stored in `t`. -/
theorem liveRecord_some {t : CHTable} {k : Key} {r : CHRecord}
    (h : liveRecord t k = some r) : r.key = k ∧ r.is_deleted = false ∧ r ∈ t := by
  unfold liveRecord at h
  cases hf : finalRecord t k with
  | none =>
    simp only [hf] at h
  | some s =>
    simp only [hf] at h
    by_cases hd : s.is_deleted = true
    · rw [if_pos hd] at h
      exact absurd h (by simp)
    · rw [if_neg hd] at h
      have hsr : s = r := Option.some.inj h
      subst hsr
      have hmem : s ∈ filterKey t k := by
        unfold finalRecord findMaxVersion at hf
        rcases foldlMax_mem _ none s hf with h1 | h2
        · exact h1
        · exact absurd h2 (by simp)
      refine ⟨?_, ?_, mem_of_mem_filterKey hmem⟩
      · have hp := pred_of_mem_filter' hmem
        exact eq_of_beq hp
      · simpa using hd

/-- The `FINAL` view of a live key is its live row. -/
theorem chFinalView_of_liveRecord_some (t : CHTable) (k : Key) (r : CHRecord)
    (h : liveRecord t k = some r) : chFinalView t k = some r.row := by
  unfold liveRecord finalRecord at h
  unfold chFinalView
  cases hf : findMaxVersion (filterKey t k) with
  | none =>
    simp only [hf] at h
  | some s =>
    simp only [hf] at h
    show (if s.is_deleted = true then none else some s.row) = some r.row
    by_cases hd : s.is_deleted = true
    · rw [if_pos hd] at h
      exact absurd h (by simp)
    · rw [if_neg hd] at h
      rw [if_neg hd, Option.some.inj h]

/-- The `FINAL` view of a key without a live record is `none` (never written, or tombstoned). -/
theorem chFinalView_of_liveRecord_none (t : CHTable) (k : Key)
    (h : liveRecord t k = none) : chFinalView t k = none := by
  unfold liveRecord finalRecord at h
  unfold chFinalView
  cases hf : findMaxVersion (filterKey t k) with
  | none => rfl
  | some s =>
    simp only [hf] at h
    show (if s.is_deleted = true then none else some s.row) = none
    by_cases hd : s.is_deleted = true
    · rw [if_pos hd]
    · rw [if_neg hd] at h
      exact absurd h (by simp)

/-- Every stored record's key is among the distinct keys. -/
theorem mem_keysOf {t : CHTable} {r : CHRecord} (h : r ∈ t) : r.key ∈ keysOf t := by
  induction t with
  | nil => simp at h
  | cons d ds ih =>
    by_cases hd : d.key ∈ keysOf ds
    · have e : keysOf (d :: ds) = keysOf ds := by simp [keysOf, hd]
      rw [e]
      rcases List.mem_cons.mp h with heq | hmem
      · rw [heq]; exact hd
      · exact ih hmem
    · have e : keysOf (d :: ds) = d.key :: keysOf ds := by simp [keysOf, hd]
      rw [e]
      rcases List.mem_cons.mp h with heq | hmem
      · rw [heq]; exact List.mem_cons_self _ _
      · exact List.mem_cons_of_mem _ (ih hmem)

/-- The distinct keys really are distinct. -/
theorem keysOf_noDup (t : CHTable) : NoDupKeys (keysOf t) := by
  induction t with
  | nil => exact trivial
  | cons d ds ih =>
    by_cases hd : d.key ∈ keysOf ds
    · have e : keysOf (d :: ds) = keysOf ds := by simp [keysOf, hd]
      rw [e]
      exact ih
    · have e : keysOf (d :: ds) = d.key :: keysOf ds := by simp [keysOf, hd]
      rw [e]
      exact ⟨hd, ih⟩

/-- A live key is one of the distinct keys of the table. -/
theorem live_key_mem_keysOf {t : CHTable} {k : Key} {r : CHRecord}
    (h : liveRecord t k = some r) : k ∈ keysOf t := by
  have hr := liveRecord_some h
  have hm := mem_keysOf hr.2.2
  rw [hr.1] at hm
  exact hm

/--
The workhorse: over a duplicate-free key list `ks`, the re-keyed live records
filtered at `f k` are exactly the (at most one) re-keyed live record of `k`,
provided `f` is injective. Each old key contributes at most one record, and
injectivity keeps two old keys from landing on one new key.
-/
theorem filterKey_rebuild_keys (t : CHTable) (f : Key → Key)
    (hf : ∀ a b, f a = f b → a = b) (k : Key) :
    ∀ ks : List Key, NoDupKeys ks →
      filterKey ((ks.filterMap (liveRecord t)).map (rekey f)) (f k)
        = if k ∈ ks then liveImage t f k else [] := by
  intro ks
  induction ks with
  | nil =>
    intro _
    simp [List.filterMap, List.map, filterKey_nil]
  | cons j js ih =>
    intro hnd
    have hnd' : j ∉ js ∧ NoDupKeys js := hnd
    have hj : j ∉ js := hnd'.1
    have ih' := ih hnd'.2
    cases hl : liveRecord t j with
    | none =>
      simp only [List.filterMap_cons, hl]
      rw [ih']
      by_cases hkj : k = j
      · subst hkj
        simp [hj, liveImage, hl]
      · simp [List.mem_cons, hkj]
    | some r =>
      have hr := liveRecord_some hl
      simp only [List.filterMap_cons, hl, List.map_cons]
      rw [filterKey_cons]
      have hkey : (rekey f r).key = f j := by
        simp [rekey, hr.1]
      rw [hkey, ih']
      by_cases hkj : k = j
      · subst hkj
        simp [hj, liveImage, hl]
      · have hne : f j ≠ f k := fun h => hkj (hf _ _ h).symm
        simp [hne, List.mem_cons, hkj]

/-- The rebuilt table stores, at `f k`, exactly the re-keyed live record of `k` (if any). -/
theorem filterKey_rebuild (t : CHTable) (f : Key → Key)
    (hf : ∀ a b, f a = f b → a = b) (k : Key) :
    filterKey (rebuild t f) (f k) = if k ∈ keysOf t then liveImage t f k else [] := by
  unfold rebuild live
  exact filterKey_rebuild_keys t f hf k (keysOf t) (keysOf_noDup t)

/-! ## The theorems -/

/--
**Count reconciliation (Spec 06.09 §3.3 step 6).** The rebuilt table contains no
tombstone, and it has exactly as many rows as the live set of the old table —
the `count()` of `S` equals the count of the `SELECT ... FROM T FINAL WHERE
is_deleted = 0` that fed it.
-/
theorem rebuild_preserves_live_rows (t : CHTable) (f : Key → Key) :
    (∀ r ∈ rebuild t f, r.is_deleted = false) ∧ (rebuild t f).length = (live t).length := by
  refine ⟨?_, List.length_map _ _⟩
  intro r hr
  unfold rebuild at hr
  rcases List.mem_map.mp hr with ⟨s, hs, hsr⟩
  unfold live at hs
  rcases List.mem_filterMap.mp hs with ⟨k, _, hk⟩
  rw [← hsr]
  exact (liveRecord_some hk).2.1

/--
**Versions preserved (Spec 06.09 §3.6, Invariant I2).** For a live key `k` of
`t`, the `FINAL`-collapsed record of the rebuilt table at `f k` is the live
record of `k` with only its key re-mapped: same version, same row, not deleted.
-/
theorem rebuild_final_record (t : CHTable) (f : Key → Key)
    (hf : ∀ a b, f a = f b → a = b) (k : Key) (r : CHRecord)
    (hk : liveRecord t k = some r) :
    finalRecord (rebuild t f) (f k) = some (rekey f r)
      ∧ (rekey f r).key = f k ∧ (rekey f r).version = r.version
      ∧ (rekey f r).row = r.row ∧ (rekey f r).is_deleted = false := by
  refine ⟨?_, ?_, rfl, rfl, (liveRecord_some hk).2.1⟩
  · unfold finalRecord
    rw [filterKey_rebuild t f hf k, if_pos (live_key_mem_keysOf hk)]
    simp [liveImage, hk, findMaxVersion, maxStep]
  · simp [rekey, (liveRecord_some hk).1]

/-- A key without a live record in `t` has no record at all in the rebuilt table. -/
theorem rebuild_final_record_dead (t : CHTable) (f : Key → Key)
    (hf : ∀ a b, f a = f b → a = b) (k : Key)
    (hk : liveRecord t k = none) : finalRecord (rebuild t f) (f k) = none := by
  unfold finalRecord
  rw [filterKey_rebuild t f hf k]
  by_cases hm : k ∈ keysOf t
  · rw [if_pos hm]
    simp [liveImage, hk, findMaxVersion]
  · rw [if_neg hm]
    rfl

/--
**View equality (Spec 06.09 §4, Invariant I3).** Re-keying the live set by an
injective map preserves the `FINAL` view exactly: for EVERY old key `k`, the
rebuilt table evaluated at `f k` shows the row `t` shows at `k` — a live row is
carried over exactly once, and a tombstoned or never-written key stays absent.
-/
theorem rebuild_view_eq (t : CHTable) (f : Key → Key)
    (hf : ∀ a b, f a = f b → a = b) (k : Key) :
    chFinalView (rebuild t f) (f k) = chFinalView t k := by
  cases hk : liveRecord t k with
  | none =>
    rw [chFinalView_of_liveRecord_none t k hk]
    unfold chFinalView
    rw [show findMaxVersion (filterKey (rebuild t f) (f k)) = none from
      rebuild_final_record_dead t f hf k hk]
  | some r =>
    rw [chFinalView_of_liveRecord_some t k r hk]
    unfold chFinalView
    rw [show findMaxVersion (filterKey (rebuild t f) (f k)) = some (rekey f r) from
      (rebuild_final_record t f hf k r hk).1]
    have hd : r.is_deleted = false := (liveRecord_some hk).2.1
    simp [rekey, hd]

/-- A new key that is not the image of any old key holds no row: the rebuild
    invents nothing. -/
theorem rebuild_view_outside_image (t : CHTable) (f : Key → Key) (k' : Key)
    (h : ∀ k, f k ≠ k') : chFinalView (rebuild t f) k' = none := by
  unfold chFinalView
  rw [filterKey_eq_nil_of_ne]
  · rfl
  · intro r hr
    unfold rebuild at hr
    rcases List.mem_map.mp hr with ⟨s, _, hs⟩
    rw [← hs]
    exact h s.key

end Replication
