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
# Primary-key change: replica rebuild under a new key (Spec 06.09 §3.3, §3.6, §4)

When a source `ALTER TABLE` changes the row identity, the connector rebuilds the
ClickHouse table under the new sorting key: inside the DDL barrier it creates a
table with the new `ORDER BY` and swaps it into place (§3.3.1); afterwards, online,
it copies the **live** rows of the retired table `R FINAL` (`is_deleted = 0`,
`_version` unchanged) into the new table while new events keep arriving, checks
that every live key of `R` is present, and only then retires `R` (§3.3.2).

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
  exactly `(live t).length` rows (the completeness check of §3.3.2 step 3);
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

/-! ## The online backfill (Spec 06.09 §3.3, §3.3.2)

After the swap the rebuilt table `T` is empty and keyed by the new identity;
replication resumes at once, so `T` accumulates the post-DDL records `n` (new
live rows, `DELETE` tombstones, the two halves of a relocation) while the
backfill copies the live rows of the retired table `R` into it. The backfill is
`rebuild r f` — the live rows of `r`, re-keyed, `_version` unchanged — and every
one of its records is versioned strictly below every post-DDL record of the same
key (the DDL barrier: Spec 02.02, Spec 06.09 §3.6). The stored table is then
`n ++ rebuild r f` (or, since the copy runs while inserts keep arriving, any
interleaving of the two; both ends are covered below).

The theorems: the backfill never changes the `FINAL` view of a key the new
table already holds, it supplies the view of the keys the new table lacks, and
running it twice (retry, restart) yields the same `FINAL` state. -/

/-- A record of `t` carrying key `k` is among the records `filterKey` keeps for `k`. -/
theorem mem_filterKey_of_mem {t : CHTable} {r : CHRecord} {k : Key}
    (hr : r ∈ t) (hk : r.key = k) : r ∈ filterKey t k := by
  induction t with
  | nil => simp at hr
  | cons d ds ih =>
    rw [filterKey_cons]
    rcases List.mem_cons.mp hr with heq | hmem
    · subst heq
      rw [if_pos hk]
      exact List.mem_cons_self _ _
    · by_cases hd : d.key = k
      · rw [if_pos hd]
        exact List.mem_cons_of_mem _ (ih hmem)
      · rw [if_neg hd]
        exact ih hmem

/-- A record kept by `filterKey t k` carries key `k`. -/
theorem key_of_mem_filterKey {t : CHTable} {k : Key} {r : CHRecord}
    (h : r ∈ filterKey t k) : r.key = k := by
  have hp := pred_of_mem_filter' h
  exact eq_of_beq hp

/-- The max-version fold started at `some m` ends at a record whose version is
    at least `m.version`: the accumulator's version never decreases. -/
theorem foldl_maxStep_some_ge (xs : List CHRecord) (m : CHRecord) :
    ∃ m', xs.foldl maxStep (some m) = some m' ∧ m.version ≤ m'.version := by
  induction xs generalizing m with
  | nil => exact ⟨m, rfl, Nat.le_refl _⟩
  | cons x xs ih =>
    rw [List.foldl_cons]
    by_cases h : x.version ≥ m.version
    · have e : maxStep (some m) x = some x := by simp [maxStep, h]
      rw [e]
      rcases ih x with ⟨m', hm', hle⟩
      exact ⟨m', hm', Nat.le_trans h hle⟩
    · have e : maxStep (some m) x = some m := by simp [maxStep, h]
      rw [e]
      exact ih m

/-- A non-empty record list has a max-version record. -/
theorem findMaxVersion_of_mem {xs : List CHRecord} {y : CHRecord} (h : y ∈ xs) :
    ∃ m, findMaxVersion xs = some m := by
  cases xs with
  | nil => simp at h
  | cons x xs =>
    unfold findMaxVersion
    rw [List.foldl_cons]
    have e : maxStep none x = some x := rfl
    rw [e]
    rcases foldl_maxStep_some_ge xs x with ⟨m, hm, _⟩
    exact ⟨m, hm⟩

/-- Folding records that are all strictly older than `m` over `some m` leaves `m`
    in place: an older record never displaces a newer one, whatever the order. -/
theorem foldl_maxStep_of_lt (xs : List CHRecord) (m : CHRecord)
    (h : ∀ x ∈ xs, x.version < m.version) : xs.foldl maxStep (some m) = some m := by
  induction xs with
  | nil => rfl
  | cons x xs ih =>
    have hx : ¬ (x.version ≥ m.version) :=
      fun hge => Nat.lt_irrefl _ (Nat.lt_of_lt_of_le (h x (List.mem_cons_self _ _)) hge)
    have e : maxStep (some m) x = some m := by simp [maxStep, hx]
    rw [List.foldl_cons, e]
    exact ih (fun y hy => h y (List.mem_cons_of_mem _ hy))

/-- A start record that is at most as new as the first record folded is
    forgotten by the first step (`maxStep` uses `>=`): the fold from `some x0`
    and the fold from `none` agree. -/
theorem foldl_maxStep_cons_of_le (x0 y : CHRecord) (ys : List CHRecord)
    (h : x0.version ≤ y.version) :
    (y :: ys).foldl maxStep (some x0) = (y :: ys).foldl maxStep none := by
  have e1 : maxStep (some x0) y = some y := by simp [maxStep, h]
  have e2 : maxStep none y = some y := rfl
  rw [List.foldl_cons, List.foldl_cons, e1, e2]

/-- **General form of the no-shadowing argument, backfill appended.** If every
    record of `b` is strictly older than every record of `n` with the same key,
    appending `b` to `n` leaves the `FINAL` view of every key that occurs in `n`
    unchanged. -/
theorem chFinalView_append_present (n b : CHTable) (k : Key)
    (hv : ∀ x ∈ b, ∀ y ∈ n, y.key = x.key → x.version < y.version)
    (hk : ∃ y ∈ n, y.key = k) :
    chFinalView (n ++ b) k = chFinalView n k := by
  rcases hk with ⟨y, hy, hyk⟩
  have hmem : y ∈ filterKey n k := mem_filterKey_of_mem hy hyk
  rcases findMaxVersion_of_mem hmem with ⟨m, hm⟩
  have hm' : (filterKey n k).foldl maxStep none = some m := hm
  have hmmem : m ∈ filterKey n k := by
    rcases foldlMax_mem _ none m hm' with h1 | h2
    · exact h1
    · exact absurd h2 (by simp)
  have hmn : m ∈ n := mem_of_mem_filterKey hmmem
  have hmk : m.key = k := key_of_mem_filterKey hmmem
  have hall : ∀ x ∈ filterKey b k, x.version < m.version := by
    intro x hx
    have hxb : x ∈ b := mem_of_mem_filterKey hx
    have hxk : x.key = k := key_of_mem_filterKey hx
    exact hv x hxb m hmn (by rw [hmk, hxk])
  have hfm : findMaxVersion (filterKey (n ++ b) k) = some m := by
    rw [filterKey_append]
    unfold findMaxVersion
    rw [List.foldl_append, hm']
    exact foldl_maxStep_of_lt _ m hall
  unfold chFinalView
  rw [hfm, hm]

/-- A key that occurs nowhere in `n` is seen through `b` alone (backfill appended). -/
theorem chFinalView_append_absent (n b : CHTable) (k : Key)
    (hk : ∀ y ∈ n, y.key ≠ k) :
    chFinalView (n ++ b) k = chFinalView b k := by
  unfold chFinalView
  rw [filterKey_append, filterKey_eq_nil_of_ne n k hk, List.nil_append]

/-- **General form of the no-shadowing argument, backfill first.** With the same
    version hypothesis, putting `b` BEFORE `n` also leaves the `FINAL` view of
    every key that occurs in `n` unchanged: the first record of `n` for that key
    is at least as new as whatever `b` accumulated, so the `>=` step forgets it. -/
theorem chFinalView_prepend_present (n b : CHTable) (k : Key)
    (hv : ∀ x ∈ b, ∀ y ∈ n, y.key = x.key → x.version < y.version)
    (hk : ∃ y ∈ n, y.key = k) :
    chFinalView (b ++ n) k = chFinalView n k := by
  rcases hk with ⟨y, hy, hyk⟩
  have hmem : y ∈ filterKey n k := mem_filterKey_of_mem hy hyk
  have hfm : findMaxVersion (filterKey (b ++ n) k) = findMaxVersion (filterKey n k) := by
    rw [filterKey_append]
    unfold findMaxVersion
    rw [List.foldl_append]
    cases hb : (filterKey b k).foldl maxStep none with
    | none => rfl
    | some x0 =>
      have hx0 : x0 ∈ filterKey b k := by
        rcases foldlMax_mem _ none x0 hb with h1 | h2
        · exact h1
        · exact absurd h2 (by simp)
      have hx0b : x0 ∈ b := mem_of_mem_filterKey hx0
      have hx0k : x0.key = k := key_of_mem_filterKey hx0
      have hne : filterKey n k ≠ [] := List.ne_nil_of_mem hmem
      cases hn : filterKey n k with
      | nil => exact absurd hn hne
      | cons z zs =>
        have hz : z ∈ filterKey n k := by
          rw [hn]
          exact List.mem_cons_self _ _
        have hzn : z ∈ n := mem_of_mem_filterKey hz
        have hzk : z.key = k := key_of_mem_filterKey hz
        have hlt : x0.version < z.version := hv x0 hx0b z hzn (by rw [hzk, hx0k])
        exact foldl_maxStep_cons_of_le x0 z zs (Nat.le_of_lt hlt)
  unfold chFinalView
  rw [hfm]

/-- A key that occurs nowhere in `n` is seen through `b` alone (backfill first). -/
theorem chFinalView_prepend_absent (n b : CHTable) (k : Key)
    (hk : ∀ y ∈ n, y.key ≠ k) :
    chFinalView (b ++ n) k = chFinalView b k := by
  unfold chFinalView
  rw [filterKey_append, filterKey_eq_nil_of_ne n k hk, List.append_nil]

/-- Folding the same records a second time over the result of the first fold
    changes nothing: the first pass ends at the last record of maximal version
    (ties go to the later record), and the second pass, which only ever meets
    records of at most that version, ends at the very same record — the
    duplicate that "wins" the tie is the same record. Stated for every starting
    accumulator so that the induction goes through. -/
theorem foldl_maxStep_twice (xs : List CHRecord) :
    ∀ init : Option CHRecord,
      xs.foldl maxStep (xs.foldl maxStep init) = xs.foldl maxStep init := by
  induction xs with
  | nil =>
    intro init
    rfl
  | cons x xs ih =>
    intro init
    rw [List.foldl_cons, List.foldl_cons]
    have hstep : ∃ m0, maxStep init x = some m0 ∧ (m0 = x ∨ x.version < m0.version) := by
      cases init with
      | none => exact ⟨x, rfl, Or.inl rfl⟩
      | some m =>
        by_cases h : x.version ≥ m.version
        · exact ⟨x, by simp [maxStep, h], Or.inl rfl⟩
        · exact ⟨m, by simp [maxStep, h], Or.inr (Nat.lt_of_not_le h)⟩
    rcases hstep with ⟨m0, hm0, hm0x⟩
    rw [hm0]
    rcases foldl_maxStep_some_ge xs m0 with ⟨a, ha, hle⟩
    rw [ha]
    by_cases hx : x.version ≥ a.version
    · have e : maxStep (some a) x = some x := by simp [maxStep, hx]
      rw [e]
      have hm0eq : m0 = x := by
        rcases hm0x with h1 | h2
        · exact h1
        · exact absurd (Nat.lt_of_lt_of_le h2 (Nat.le_trans hle hx)) (Nat.lt_irrefl _)
      rw [hm0eq] at ha
      exact ha
    · have e : maxStep (some a) x = some a := by simp [maxStep, hx]
      rw [e]
      have h2 := ih (some m0)
      rw [ha] at h2
      exact h2

/-- Appending a second copy of the tail `l2` does not change the max-version
    record (the `Option CHRecord` itself, not just the row it shows). -/
theorem findMaxVersion_append_dup (l1 l2 : List CHRecord) :
    findMaxVersion (l1 ++ l2 ++ l2) = findMaxVersion (l1 ++ l2) := by
  unfold findMaxVersion
  simp only [List.foldl_append]
  exact foldl_maxStep_twice l2 (l1.foldl maxStep none)

/-- **General idempotence.** Appending the same records twice yields the same
    `FINAL` view for every key. -/
theorem chFinalView_append_dup (t1 t2 : CHTable) (k : Key) :
    chFinalView (t1 ++ t2 ++ t2) k = chFinalView (t1 ++ t2) k := by
  have h : findMaxVersion (filterKey (t1 ++ t2 ++ t2) k)
      = findMaxVersion (filterKey (t1 ++ t2) k) := by
    simp only [filterKey_append]
    exact findMaxVersion_append_dup _ _
  unfold chFinalView
  rw [h]

/--
**The backfill never shadows a newer row (Spec 06.09 §3.3, §3.3.2 step 2; I2).**
`n` is the rebuilt table's content after the swap — every post-DDL record — and
`rebuild r f` the backfill: the live rows of the retired table `r`, re-keyed,
versions unchanged. Under the barrier hypothesis that every backfilled record is
strictly older than every post-DDL record of the same key:

* (a) a key the new table already holds — a newer live row, a `DELETE`
  tombstone, the tombstone half of a relocation — shows exactly what it showed
  before the backfill: the backfilled row has the smaller version and loses in
  `FINAL`;
* (b) a key the new table does not hold yet shows exactly what the backfill
  brings: the pre-DDL state of that row.
-/
theorem backfill_never_shadows_newer (n r : CHTable) (f : Key → Key)
    (hv : ∀ x ∈ rebuild r f, ∀ y ∈ n, y.key = x.key → x.version < y.version) (k : Key) :
    ((∃ y ∈ n, y.key = k) → chFinalView (n ++ rebuild r f) k = chFinalView n k)
    ∧ ((∀ y ∈ n, y.key ≠ k) → chFinalView (n ++ rebuild r f) k = chFinalView (rebuild r f) k) :=
  ⟨chFinalView_append_present n (rebuild r f) k hv, chFinalView_append_absent n (rebuild r f) k⟩

/--
**Order independence (Spec 06.09 §3.3: the copy runs online).** The backfill is
inserted while post-DDL events keep arriving, so the stored order is not
`n ++ rebuild r f` in general. With the backfill FIRST the conclusions are the
same: a strictly smaller version loses under `maxStep`'s `>=` whichever record
was inserted first, so the equal-version tie rule never comes into play. Any
interleaving lies between these two ends.
-/
theorem backfill_never_shadows_newer_prepend (n r : CHTable) (f : Key → Key)
    (hv : ∀ x ∈ rebuild r f, ∀ y ∈ n, y.key = x.key → x.version < y.version) (k : Key) :
    ((∃ y ∈ n, y.key = k) → chFinalView (rebuild r f ++ n) k = chFinalView n k)
    ∧ ((∀ y ∈ n, y.key ≠ k) → chFinalView (rebuild r f ++ n) k = chFinalView (rebuild r f) k) :=
  ⟨chFinalView_prepend_present n (rebuild r f) k hv, chFinalView_prepend_absent n (rebuild r f) k⟩

/--
**The backfill is idempotent (Spec 06.09 §3.3.2 steps 5–6).** A retried or
restarted copy re-inserts rows that are already there; appending the same
backfill twice yields the same `FINAL` view for every key. Under the `>=` tie
rule the second copy of a row wins over the first, but it is the same record
(same key, version, row and delete flag), so the collapsed record — and the view
— are unchanged (`findMaxVersion_append_dup`).
-/
theorem backfill_idempotent (n r : CHTable) (f : Key → Key) (k : Key) :
    chFinalView (n ++ rebuild r f ++ rebuild r f) k = chFinalView (n ++ rebuild r f) k :=
  chFinalView_append_dup n (rebuild r f) k

end Replication
