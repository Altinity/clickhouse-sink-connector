/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic

/-!
# Replication history: SCD Type 2 tables and replication-log-only mode

With `replication.history.enable=true` every target table becomes a slowly
changing dimension of type 2. Each physical row carries `_valid_from`,
`_valid_to`, `_operation`, `is_deleted` and `_version`; the engine is
`ReplacingMergeTree(_version, is_deleted)` with SORTING KEY `(pk, _valid_to)`.
`FINAL` therefore collapses rows per `(key, validTo)` to the maximal `_version`
(ties resolved to the later inserted row, exactly as `maxStep` in
`Replication.ClickHouse`), and hides the winner when `is_deleted = 1`. The
*current* row of a key is the live row at sorting key `(key, openEnd)`, where
`openEnd` is the sentinel `_valid_to` default `'2100-01-01 00:00:00'`.

This module models the CORRECTED protocol (the design every row-writing path
implements after the fix) and keeps the shipped 2.11.0 behaviour only as
`old*` definitions with machine-checked counterexamples:

* **Spec 12.03 — SCD2 write protocol.** The row sets emitted by the single
  `INSERT ... SELECT ... UNION ALL SELECT ...` statements built by
  `QueryFormatter.getInsertQueryForUpdate` / `getInsertQueryForDelete` and by
  the column-agnostic bulk close used for `TRUNCATE-TABLE` / `DROP-TABLE`, all
  executed by `PreparedStatementExecutor` against the flushed (visible) table.
  Every row one event emits carries the event's ONE standard version `V`
  (`record.getVersion()`, spec 02.01); an UPDATE closes the open row at the
  BEFORE-image key and, when the key changes, also writes a delete marker at
  the old key; the deleted copy of the before image no longer exists.
-- DESTRUCTIVE: none -- this module MODELS the statements; nothing in it executes against any database.
* **Spec 12.01 / 12.05 — modes and routing.** The gating of data-table writes,
  history-table writes, target-database routing and database-level DDL on the
  two mode flags, now identical on `ClickHouseBatchRunnable` and
  `ClickHouseBatchWriter`.

Abstractions: a row's payload is an opaque `image` identity; timestamps are UTC
epoch seconds (`Nat`); a table is an append-only list of physical rows, and
`FINAL` is evaluated mathematically by `finalAt` / `liveAt`. Batches on the
routing side are opaque lists.
-/

namespace Replication

/-! ## Rows, tables and the FINAL evaluation at a sorting key -/

-- DESTRUCTIVE: none -- model constructors only; nothing is truncated or dropped by this file.
/-- The `_operation` column of a history row (`truncate` is the `'T'` marker
    written by the bulk close of a replicated `TRUNCATE-TABLE`). -/
inductive HOp where
  | create | read | update | delete | truncate
deriving DecidableEq, Repr

/-- One physical row of an SCD2 history table. -/
structure HRow where
  key       : Key
  image     : Nat        -- abstract payload identity (which source image the row carries)
  validFrom : Nat
  validTo   : Nat
  isDeleted : Bool
  version   : Nat
  op        : HOp
deriving DecidableEq, Repr

/-- An SCD2 history table: append-only physical row storage. -/
abbrev HTable := List HRow

/-- The `_valid_to` sentinel `'2100-01-01 00:00:00'` as UTC epoch seconds. -/
def openEnd : Nat := 4102444800

/-- Two rows share a sorting key `(pk, _valid_to)`. -/
def sameSortKey (a b : HRow) : Bool := a.key == b.key && a.validTo == b.validTo

/-- Rows of `t` at sorting key `(k, vt)`. -/
def filterSortKey (t : HTable) (k : Key) (vt : Nat) : List HRow :=
  t.filter (fun r => r.key == k && r.validTo == vt)

/-- One step of the max-version fold: keep the row with the larger `_version`
    (ties resolve to the later row via `>=`), mirroring `maxStep`. -/
def hMaxStep (acc : Option HRow) (r : HRow) : Option HRow :=
  match acc with
  | none   => some r
  | some m => if r.version >= m.version then some r else some m

/-- The row `FINAL` keeps at sorting key `(k, vt)` (before the `is_deleted` mask). -/
def finalAt (t : HTable) (k : Key) (vt : Nat) : Option HRow :=
  (filterSortKey t k vt).foldl hMaxStep none

/-- The row `FINAL` shows at sorting key `(k, vt)`: `finalAt` unless it is deleted. -/
def liveAt (t : HTable) (k : Key) (vt : Nat) : Option HRow :=
  match finalAt t k vt with
  | none   => none
  | some r => if r.isDeleted then none else some r

/-- The current (open) row of key `k`: the live row at `(k, openEnd)`. -/
def openRow (t : HTable) (k : Key) : Option HRow := liveAt t k openEnd

/-- Every row of `t` that is the open row of its own key (what the bulk close
    of `TRUNCATE-TABLE` / `DROP-TABLE` reads with `FINAL WHERE _valid_to = S`). -/
def openRows (t : HTable) : List HRow :=
  t.filter (fun r => openRow t r.key == some r)

/-! ## The rows each source event writes (corrected protocol) -/

/-- INSERT: the field mapper binds `_valid_from = ts`, `_valid_to = sentinel`,
    `is_deleted = 0`, `_operation = 'C'`, `_version = v` (the standard version). -/
def insertRow (k : Key) (image ts v : Nat) : HRow :=
  ⟨k, image, ts, openEnd, false, v, HOp.create⟩

/-- First SELECT of every UPDATE / DELETE statement and of the bulk close: the
    visible open row copied with `_valid_to = ts`, `is_deleted = 0`, `_version = V`. -/
def closeRow (r : HRow) (ts V : Nat) : HRow :=
  { r with validTo := ts, isDeleted := false, version := V }

/-- Second SELECT of the UPDATE (no FROM clause): the after image at the AFTER key,
    `_valid_from = ts`, `_valid_to = sentinel`, `is_deleted = 0`, `_operation = 'U'`,
    `_version = V` — the SAME version as the close row, not `V+1`. -/
def afterRow (k : Key) (image ts V : Nat) : HRow :=
  ⟨k, image, ts, openEnd, false, V, HOp.update⟩

/-- Second SELECT of the DELETE: the open row's tombstone at the sentinel,
    `_version = V`, `_operation = 'D'`. -/
def deleteMarker (r : HRow) (ts V : Nat) : HRow :=
  { r with validFrom := ts, validTo := openEnd, isDeleted := true, version := V,
           op := HOp.delete }

/-- Third SELECT of a key-changing UPDATE: a delete marker at the BEFORE key's
    open sorting key, `_operation = 'U'`, `_version = V`. -/
def keyChangeMarker (r : HRow) (ts V : Nat) : HRow :=
  { r with validFrom := ts, validTo := openEnd, isDeleted := true, version := V,
           op := HOp.update }

/-- The marker the bulk close writes per open row: `_valid_from = ts`,
    `_valid_to = sentinel`, `is_deleted = 1`, `_version = V`, `_operation = op`
    (`'T'` for `TRUNCATE-TABLE`, `'D'` for `DROP-TABLE`). -/
def bulkMarker (r : HRow) (ts V : Nat) (op : HOp) : HRow :=
  { r with validFrom := ts, validTo := openEnd, isDeleted := true, version := V, op := op }

/-- The row set ONE UPDATE statement emits against the visible (flushed) table:
    the close row at the BEFORE-image key `kb` (when an open row is visible), the
    after row at the AFTER-image key `ka`, and — only when the key changed — the
    delete marker at `(kb, sentinel)`. -/
def updateRows (visible : HTable) (kb ka : Key) (image ts V : Nat) : List HRow :=
  match openRow visible kb with
  | some r => closeRow r ts V :: afterRow ka image ts V ::
              (if kb = ka then [] else [keyChangeMarker r ts V])
  | none   => [afterRow ka image ts V]

/-- The row set ONE DELETE statement emits against the visible table. -/
def deleteRows (visible : HTable) (k : Key) (ts V : Nat) : List HRow :=
  match openRow visible k with
  | some r => [closeRow r ts V, deleteMarker r ts V]
  | none   => []

/-- Close row plus marker for every row of `rs`, in table order. -/
def closeEach (rs : List HRow) (ts V : Nat) (op : HOp) : List HRow :=
  match rs with
  | []        => []
  | r :: rest => closeRow r ts V :: bulkMarker r ts V op :: closeEach rest ts V op

/-- The row set the column-agnostic bulk-close statement of `TRUNCATE-TABLE` /
    `DROP-TABLE` emits: for every visible open row, its close row and its marker. -/
def bulkCloseRows (visible : HTable) (ts V : Nat) (op : HOp) : List HRow :=
  closeEach (openRows visible) ts V op

/-- Apply an INSERT. -/
def applyInsert (t : HTable) (k : Key) (image ts v : Nat) : HTable :=
  t ++ [insertRow k image ts v]

/-- Apply an UPDATE (before key `kb`, after key `ka`) whose SELECTs read the
    flushed table `t` — the executor flushes the staged INSERTs first (S6). -/
def applyUpdate (t : HTable) (kb ka : Key) (image ts V : Nat) : HTable :=
  t ++ updateRows t kb ka image ts V

/-- Apply a DELETE whose SELECTs read the flushed table `t`. -/
def applyDelete (t : HTable) (k : Key) (ts V : Nat) : HTable :=
  t ++ deleteRows t k ts V

/-- Apply the bulk close of `TRUNCATE-TABLE` (`op = truncate`) or `DROP-TABLE`
    (`op = delete`): the SCD2 table is never truncated or dropped. -/
def applyBulkClose (t : HTable) (ts V : Nat) (op : HOp) : HTable :=
  t ++ bulkCloseRows t ts V op

/-! ## The shipped 2.11.0 behaviour, kept only as counterexample material -/

/-- OLD third SELECT of the UPDATE: the open row re-read with `_valid_to = ts`,
    `is_deleted = 1`, `_version = V`, `_operation = 'U'` — the same sorting key
    and version as the close row. Removed by the fix. -/
def oldBeforeRow (r : HRow) (ts V : Nat) : HRow :=
  { r with validTo := ts, isDeleted := true, version := V, op := HOp.update }

/-- OLD UPDATE row set: close row (`V`) + after row (`V+1`) + deleted before copy
    (`V`), every predicate built from the AFTER-image key `k` only. -/
def oldUpdateRows (visible : HTable) (k : Key) (image ts V : Nat) : List HRow :=
  (match openRow visible k with | some r => [closeRow r ts V] | none => [])
    ++ [afterRow k image ts (V + 1)]
    ++ (match openRow visible k with | some r => [oldBeforeRow r ts V] | none => [])

/-- OLD UPDATE against a flushed table. -/
def oldApplyUpdate (t : HTable) (k : Key) (image ts V : Nat) : HTable :=
  t ++ oldUpdateRows t k image ts V

/-- OLD executor path: the UPDATE statement ran INLINE while the batch's INSERTs
    were still staged on the PreparedStatement (`staged`), so its SELECTs read only
    `t`; the staged rows landed at the following `executeBatch()`. -/
def oldApplyUpdateInline (t staged : HTable) (k : Key) (image ts V : Nat) : HTable :=
  t ++ staged ++ oldUpdateRows t k image ts V

/-! ## Helper lemmas on the FINAL evaluation -/

/-- Boolean equality is `false` for distinct values (core only, via `LawfulBEq`). -/
theorem hbeq_false_of_ne {α : Type _} [BEq α] [LawfulBEq α] {a b : α} (h : a ≠ b) :
    (a == b) = false := by
  cases hb : a == b with
  | false => rfl
  | true  => exact absurd (eq_of_beq hb) h

/-- The result of the max-version fold is drawn from the list or is the accumulator. -/
theorem foldl_hMaxStep_mem (xs : List HRow) (init : Option HRow) (m : HRow)
    (h : xs.foldl hMaxStep init = some m) : m ∈ xs ∨ init = some m := by
  induction xs generalizing init with
  | nil => exact Or.inr h
  | cons x xs ih =>
      have h' : xs.foldl hMaxStep (hMaxStep init x) = some m := h
      rcases ih (hMaxStep init x) h' with hmem | hstep
      · exact Or.inl (List.mem_cons_of_mem _ hmem)
      · cases init with
        | none =>
            have : x = m := by
              have := hstep
              simp [hMaxStep] at this
              exact this
            exact Or.inl (this ▸ List.mem_cons_self _ _)
        | some m0 =>
            by_cases hv : x.version ≥ m0.version
            · have : x = m := by
                have := hstep
                simp [hMaxStep, hv] at this
                exact this
              exact Or.inl (this ▸ List.mem_cons_self _ _)
            · have : m0 = m := by
                have := hstep
                simp [hMaxStep, hv] at this
                exact this
              exact Or.inr (by rw [this])

/-- Folding a non-empty list whose rows are all `m` from an accumulator whose
    version does not exceed `m.version` yields `m` (ties go to the later row). -/
theorem foldl_hMaxStep_const (xs : List HRow) (m : HRow) (init : Option HRow)
    (hall : ∀ x ∈ xs, x = m) (hne : xs ≠ [])
    (hinit : ∀ m0, init = some m0 → m0.version ≤ m.version) :
    xs.foldl hMaxStep init = some m := by
  induction xs generalizing init with
  | nil => exact absurd rfl hne
  | cons x rest ih =>
      have hx : x = m := hall x (List.mem_cons_self _ _)
      subst hx
      have hstep : hMaxStep init x = some x := by
        cases init with
        | none => rfl
        | some m0 =>
            have := hinit m0 rfl
            simp [hMaxStep, this]
      rw [List.foldl_cons, hstep]
      cases rest with
      | nil => rfl
      | cons y rest' =>
          exact ih (some x) (fun z hz => hall z (List.mem_cons_of_mem _ hz)) (List.cons_ne_nil _ _)
            (fun m0 h => by rw [Option.some.inj h]; exact Nat.le_refl _)

/-- `filterSortKey` distributes over concatenation. -/
theorem filterSortKey_append (t u : HTable) (k : Key) (vt : Nat) :
    filterSortKey (t ++ u) k vt = filterSortKey t k vt ++ filterSortKey u k vt := by
  simp [filterSortKey, List.filter_append]

/-- `finalAt` over a concatenation continues the fold from the prefix's result. -/
theorem finalAt_append (t u : HTable) (k : Key) (vt : Nat) :
    finalAt (t ++ u) k vt = (filterSortKey u k vt).foldl hMaxStep (finalAt t k vt) := by
  simp [finalAt, filterSortKey_append, List.foldl_append]

/-- The row `finalAt` picks is a row of the table at that sorting key. -/
theorem finalAt_some_mem {t : HTable} {k : Key} {vt : Nat} {r : HRow}
    (h : finalAt t k vt = some r) : r ∈ t ∧ r.key = k ∧ r.validTo = vt := by
  unfold finalAt at h
  rcases foldl_hMaxStep_mem _ none r h with hmem | hnone
  · unfold filterSortKey at hmem
    rw [List.mem_filter] at hmem
    rcases hmem with ⟨hm, hp⟩
    rw [Bool.and_eq_true] at hp
    exact ⟨hm, eq_of_beq hp.1, eq_of_beq hp.2⟩
  · exact absurd hnone (by simp)

/-- A live row is the `finalAt` row and is not deleted. -/
theorem liveAt_some {t : HTable} {k : Key} {vt : Nat} {r : HRow}
    (h : liveAt t k vt = some r) : finalAt t k vt = some r ∧ r.isDeleted = false := by
  unfold liveAt at h
  cases hf : finalAt t k vt with
  | none => rw [hf] at h; exact absurd h (by simp)
  | some m =>
      rw [hf] at h
      cases hd : m.isDeleted with
      | true  => simp [hd] at h
      | false =>
          simp [hd] at h
          subst h
          exact ⟨rfl, hd⟩

/-- A row whose `_valid_to` is not `vt` is invisible at sorting key `(k, vt)`. -/
theorem filterSortKey_cons_of_validTo_ne (r : HRow) (u : HTable) (k : Key) (vt : Nat)
    (h : r.validTo ≠ vt) : filterSortKey (r :: u) k vt = filterSortKey u k vt := by
  have hb : (r.key == k && r.validTo == vt) = false := by
    rw [hbeq_false_of_ne h]; simp
  simp [filterSortKey, List.filter_cons, hb]

/-- A row whose key is not `k` is invisible at sorting key `(k, vt)`. -/
theorem filterSortKey_cons_of_key_ne (r : HRow) (u : HTable) (k : Key) (vt : Nat)
    (h : r.key ≠ k) : filterSortKey (r :: u) k vt = filterSortKey u k vt := by
  have hb : (r.key == k && r.validTo == vt) = false := by
    rw [hbeq_false_of_ne h]; simp
  simp [filterSortKey, List.filter_cons, hb]

/-- A row at sorting key `(k, vt)` survives the filter. -/
theorem filterSortKey_cons_of_match (r : HRow) (u : HTable) (k : Key) (vt : Nat)
    (hk : r.key = k) (hv : r.validTo = vt) :
    filterSortKey (r :: u) k vt = r :: filterSortKey u k vt := by
  simp [filterSortKey, List.filter_cons, hk, hv]

/-- A table with no row of key `k` closed at `ts` has nothing at `(k, ts)`. -/
theorem filterSortKey_nil_of_fresh (t : HTable) (k : Key) (ts : Nat)
    (hfresh : ∀ r ∈ t, r.key = k → r.validTo ≠ ts) :
    filterSortKey t k ts = [] := by
  induction t with
  | nil => rfl
  | cons r rest ih =>
      have ih' := ih (fun x hx hk => hfresh x (List.mem_cons_of_mem _ hx) hk)
      by_cases hk : r.key = k
      · rw [filterSortKey_cons_of_validTo_ne r rest k ts (hfresh r (List.mem_cons_self _ _) hk), ih']
      · rw [filterSortKey_cons_of_key_ne r rest k ts hk, ih']

/-- Equation: the UPDATE row set when an open row is visible at the before key. -/
theorem updateRows_some {t : HTable} {kb : Key} {r : HRow} (ka : Key) (image ts V : Nat)
    (h : openRow t kb = some r) :
    updateRows t kb ka image ts V
      = closeRow r ts V :: afterRow ka image ts V ::
          (if kb = ka then [] else [keyChangeMarker r ts V]) := by
  simp [updateRows, h]

/-- Equation: the UPDATE row set when no open row is visible at the before key. -/
theorem updateRows_none {t : HTable} {kb : Key} (ka : Key) (image ts V : Nat)
    (h : openRow t kb = none) :
    updateRows t kb ka image ts V = [afterRow ka image ts V] := by
  simp [updateRows, h]

/-- At `(ka, openEnd)` an UPDATE statement contributes exactly its after-image row,
    whatever the before key: the close row sits at `_valid_to = ts` and the
    key-change marker (if any) sits at the before key. -/
theorem filterSortKey_updateRows_after (t : HTable) (kb ka : Key) (image ts V : Nat)
    (hts : ts ≠ openEnd) :
    filterSortKey (updateRows t kb ka image ts V) ka openEnd = [afterRow ka image ts V] := by
  cases hopen : openRow t kb with
  | none =>
      rw [updateRows_none ka image ts V hopen,
          filterSortKey_cons_of_match (afterRow ka image ts V) [] ka openEnd rfl rfl]
      rfl
  | some r =>
      rcases liveAt_some hopen with ⟨hfin, _⟩
      rcases finalAt_some_mem hfin with ⟨_, hrk, _⟩
      rw [updateRows_some ka image ts V hopen,
          filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ ka openEnd hts,
          filterSortKey_cons_of_match (afterRow ka image ts V) _ ka openEnd rfl rfl]
      by_cases hk : kb = ka
      · rw [if_pos hk]; rfl
      · rw [if_neg hk,
            filterSortKey_cons_of_key_ne (keyChangeMarker r ts V) [] ka openEnd
              (by show r.key ≠ ka; rw [hrk]; exact hk)]
        rfl

/-- The after-image wins `FINAL` at `(k, openEnd)` against any prefix whose open rows
    for `k` have `_version ≤ V`: strictly by version, or on a tie as the later row. -/
theorem hMaxStep_afterRow_wins (t : HTable) (k : Key) (image ts V : Nat)
    (hb : ∀ r ∈ t, r.key = k → r.validTo = openEnd → r.version ≤ V) :
    hMaxStep (finalAt t k openEnd) (afterRow k image ts V) = some (afterRow k image ts V) := by
  cases hf : finalAt t k openEnd with
  | none => rfl
  | some m =>
      rcases finalAt_some_mem hf with ⟨hm, hk, hv⟩
      have hle : m.version ≤ V := hb m hm hk hv
      simp [hMaxStep, afterRow, hle]

/-- Membership in the bulk-close row list: a close row or a marker of some listed row. -/
theorem mem_closeEach {rs : List HRow} {ts V : Nat} {op : HOp} {x : HRow} :
    x ∈ closeEach rs ts V op ↔
      ∃ r, r ∈ rs ∧ (x = closeRow r ts V ∨ x = bulkMarker r ts V op) := by
  induction rs with
  | nil => simp [closeEach]
  | cons r rest ih =>
      simp only [closeEach, List.mem_cons, ih]
      constructor
      · intro h
        rcases h with h | h | ⟨r', hr', h'⟩
        · exact ⟨r, Or.inl rfl, Or.inl h⟩
        · exact ⟨r, Or.inl rfl, Or.inr h⟩
        · exact ⟨r', Or.inr hr', h'⟩
      · intro h
        rcases h with ⟨r', hr' | hr', h'⟩
        · subst hr'
          rcases h' with h' | h'
          · exact Or.inl h'
          · exact Or.inr (Or.inl h')
        · exact Or.inr (Or.inr ⟨r', hr', h'⟩)

/-- Membership in `openRows`: a row of the table that is its key's open row. -/
theorem mem_openRows {t : HTable} {r : HRow} :
    r ∈ openRows t ↔ r ∈ t ∧ openRow t r.key = some r := by
  unfold openRows
  rw [List.mem_filter]
  constructor
  · intro h; exact ⟨h.1, eq_of_beq h.2⟩
  · intro h; exact ⟨h.1, by rw [h.2]; exact beq_self_eq_true _⟩

/-- Every row the bulk close leaves at some `(k, openEnd)` is the marker of `k`'s
    open row. -/
theorem filterSortKey_bulk_mem {t : HTable} {ts V : Nat} {op : HOp} {k : Key} {x : HRow}
    (hts : ts ≠ openEnd)
    (hx : x ∈ filterSortKey (bulkCloseRows t ts V op) k openEnd) :
    ∃ r, openRow t k = some r ∧ x = bulkMarker r ts V op := by
  unfold filterSortKey at hx
  rw [List.mem_filter] at hx
  rcases hx with ⟨hm, hp⟩
  rw [Bool.and_eq_true] at hp
  have hk : x.key = k := eq_of_beq hp.1
  have hv : x.validTo = openEnd := eq_of_beq hp.2
  unfold bulkCloseRows at hm
  rcases mem_closeEach.mp hm with ⟨r, hr, hc | hb⟩
  · subst hc
    exact absurd hv hts
  · rcases mem_openRows.mp hr with ⟨_, hopen⟩
    refine ⟨r, ?_, hb⟩
    have hrk : r.key = k := by subst hb; exact hk
    rw [← hrk]
    exact hopen

/-- The marker of `k`'s open row is among the bulk-close rows at `(k, openEnd)`. -/
theorem bulkMarker_mem_filter {t : HTable} {ts V : Nat} {op : HOp} {k : Key} {r : HRow}
    (hopen : openRow t k = some r) :
    bulkMarker r ts V op ∈ filterSortKey (bulkCloseRows t ts V op) k openEnd := by
  rcases liveAt_some hopen with ⟨hfin, _⟩
  rcases finalAt_some_mem hfin with ⟨hm, hk, _⟩
  unfold filterSortKey
  rw [List.mem_filter]
  refine ⟨?_, ?_⟩
  · unfold bulkCloseRows
    exact mem_closeEach.mpr
      ⟨r, mem_openRows.mpr ⟨hm, by rw [hk]; exact hopen⟩, Or.inr rfl⟩
  · simp [bulkMarker, hk]

/-! ## Spec 12.03 — SCD2 write protocol (corrected) -/

/--
After a same-key UPDATE the current row of the key is the new image: it wins `FINAL`
at `(k, openEnd)` because every earlier open row of `k` carries a version `≤ V`
(the previous event's version, I2) and a tie goes to the later row. The close row
carries `_valid_to = ts ≠ openEnd`, so it never competes at the open sorting key.
-/
theorem update_supersedes_open_row (t : HTable) (k : Key) (image ts V : Nat)
    (hts : ts < openEnd)
    (hb : ∀ r ∈ t, r.key = k → r.validTo = openEnd → r.version ≤ V) :
    openRow (applyUpdate t k k image ts V) k = some (afterRow k image ts V) := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  have hfin : finalAt (applyUpdate t k k image ts V) k openEnd = some (afterRow k image ts V) := by
    unfold applyUpdate
    rw [finalAt_append, filterSortKey_updateRows_after t k k image ts V hne]
    rw [List.foldl_cons, List.foldl_nil]
    exact hMaxStep_afterRow_wins t k image ts V hb
  unfold openRow liveAt
  rw [hfin]
  rfl

/-- The close predicate is built from the BEFORE-image key: whenever an open row is
    visible at `kb`, the statement emits its close row, whatever the after key. -/
theorem update_closes_before_image_key (t : HTable) (kb ka : Key) (image ts V : Nat) (r : HRow)
    (h : openRow t kb = some r) :
    closeRow r ts V ∈ updateRows t kb ka image ts V := by
  rw [updateRows_some ka image ts V h]
  exact List.mem_cons_self _ _

/--
A key-changing UPDATE retires the old key: the delete marker at `(kb, openEnd)`
wins `FINAL` (version `V ≥` the open row's version, later on a tie) and is deleted,
so `kb` has no current row. When `kb` had no open row, nothing is added at
`(kb, openEnd)` and it stays closed.
-/
theorem key_change_retires_old_key (t : HTable) (kb ka : Key) (image ts V : Nat)
    (hkk : kb ≠ ka) (hts : ts < openEnd)
    (hb : ∀ r ∈ t, r.key = kb → r.validTo = openEnd → r.version ≤ V) :
    openRow (applyUpdate t kb ka image ts V) kb = none := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  have hka : ka ≠ kb := fun e => hkk e.symm
  cases hopen : openRow t kb with
  | none =>
      have hfilt : filterSortKey (updateRows t kb ka image ts V) kb openEnd = [] := by
        rw [updateRows_none ka image ts V hopen,
            filterSortKey_cons_of_key_ne (afterRow ka image ts V) [] kb openEnd hka]
        rfl
      unfold applyUpdate openRow liveAt
      rw [finalAt_append, hfilt]
      unfold openRow liveAt at hopen
      exact hopen
  | some r =>
      rcases liveAt_some hopen with ⟨hfin, _⟩
      rcases finalAt_some_mem hfin with ⟨hm, hrk, hv⟩
      have hle : r.version ≤ V := hb r hm hrk hv
      have hfilt : filterSortKey (updateRows t kb ka image ts V) kb openEnd
          = [keyChangeMarker r ts V] := by
        rw [updateRows_some ka image ts V hopen, if_neg hkk,
            filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ kb openEnd hne,
            filterSortKey_cons_of_key_ne (afterRow ka image ts V) _ kb openEnd hka,
            filterSortKey_cons_of_match (keyChangeMarker r ts V) [] kb openEnd hrk rfl]
        rfl
      have hfin' : finalAt (applyUpdate t kb ka image ts V) kb openEnd
          = some (keyChangeMarker r ts V) := by
        unfold applyUpdate
        rw [finalAt_append, hfilt, hfin, List.foldl_cons, List.foldl_nil]
        simp [hMaxStep, keyChangeMarker, hle]
      unfold openRow liveAt
      rw [hfin']
      rfl

/--
A key-changing UPDATE opens the new key: at `(ka, openEnd)` the statement
contributes only the after row (the marker sits at `kb ≠ ka`), which wins `FINAL`
against every earlier open row of `ka`.
-/
theorem key_change_opens_new_key (t : HTable) (kb ka : Key) (image ts V : Nat)
    (hkk : kb ≠ ka) (hts : ts < openEnd)
    (hb : ∀ r ∈ t, r.key = ka → r.validTo = openEnd → r.version ≤ V) :
    openRow (applyUpdate t kb ka image ts V) ka = some (afterRow ka image ts V) := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  have hfilt : filterSortKey (updateRows t kb ka image ts V) ka openEnd
      = [afterRow ka image ts V] := by
    cases hopen : openRow t kb with
    | none =>
        rw [updateRows_none ka image ts V hopen,
            filterSortKey_cons_of_match (afterRow ka image ts V) [] ka openEnd rfl rfl]
        rfl
    | some r =>
        rcases liveAt_some hopen with ⟨hfin, _⟩
        rcases finalAt_some_mem hfin with ⟨_, hrk, _⟩
        rw [updateRows_some ka image ts V hopen, if_neg hkk,
            filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ ka openEnd hne,
            filterSortKey_cons_of_match (afterRow ka image ts V) _ ka openEnd rfl rfl,
            filterSortKey_cons_of_key_ne (keyChangeMarker r ts V) [] ka openEnd
              (by show r.key ≠ ka; rw [hrk]; exact hkk)]
        rfl
  have hfin : finalAt (applyUpdate t kb ka image ts V) ka openEnd = some (afterRow ka image ts V) := by
    unfold applyUpdate
    rw [finalAt_append, hfilt, List.foldl_cons, List.foldl_nil]
    exact hMaxStep_afterRow_wins t ka image ts V hb
  unfold openRow liveAt
  rw [hfin]
  rfl

/--
The closed version is visible: with the deleted before copy gone, the close row is
the ONLY row of `k` at `_valid_to = ts` (no earlier version of `k` was closed at
that very second — `hfresh`, the second-granularity limitation of 12.03 §3.9), so
`FINAL` at `(k, ts)` shows it and the as-of history is complete.
-/
theorem closed_row_visible_at_close_key (t : HTable) (k : Key) (image ts V : Nat) (r : HRow)
    (hts : ts < openEnd) (hopen : openRow t k = some r)
    (hfresh : ∀ r' ∈ t, r'.key = k → r'.validTo ≠ ts) :
    liveAt (applyUpdate t k k image ts V) k ts = some (closeRow r ts V) := by
  have hne : openEnd ≠ ts := fun e => Nat.ne_of_lt hts e.symm
  rcases liveAt_some hopen with ⟨hfin, _⟩
  rcases finalAt_some_mem hfin with ⟨_, hrk, _⟩
  have hfilt : filterSortKey (updateRows t k k image ts V) k ts = [closeRow r ts V] := by
    rw [updateRows_some k image ts V hopen, if_pos rfl,
        filterSortKey_cons_of_match (closeRow r ts V) _ k ts hrk rfl,
        filterSortKey_cons_of_validTo_ne (afterRow k image ts V) [] k ts hne]
    rfl
  have hfin' : finalAt (applyUpdate t k k image ts V) k ts = some (closeRow r ts V) := by
    unfold applyUpdate
    rw [finalAt_append, hfilt]
    unfold finalAt
    rw [filterSortKey_nil_of_fresh t k ts hfresh]
    rfl
  unfold liveAt
  rw [hfin']
  rfl

/-- One version per event: every row an UPDATE emits carries `V`. -/
theorem update_rows_share_one_version (t : HTable) (kb ka : Key) (image ts V : Nat) :
    ∀ r ∈ updateRows t kb ka image ts V, r.version = V := by
  intro r hr
  cases hopen : openRow t kb with
  | none =>
      rw [updateRows_none ka image ts V hopen, List.mem_singleton] at hr
      rw [hr]; rfl
  | some r0 =>
      rw [updateRows_some ka image ts V hopen] at hr
      rcases List.mem_cons.mp hr with h | hr
      · rw [h]; rfl
      rcases List.mem_cons.mp hr with h | hr
      · rw [h]; rfl
      by_cases hk : kb = ka
      · rw [if_pos hk] at hr
        exact absurd hr (List.not_mem_nil _)
      · rw [if_neg hk, List.mem_singleton] at hr
        rw [hr]; rfl

/-- One version per event: both rows a DELETE emits carry `V`. -/
theorem delete_rows_share_one_version (t : HTable) (k : Key) (ts V : Nat) :
    ∀ r ∈ deleteRows t k ts V, r.version = V := by
  intro r hr
  unfold deleteRows at hr
  cases hopen : openRow t k with
  | none => rw [hopen] at hr; exact absurd hr (List.not_mem_nil _)
  | some r0 =>
      rw [hopen] at hr
      rcases List.mem_cons.mp hr with h | hr
      · rw [h]; rfl
      · rw [List.mem_singleton] at hr
        rw [hr]; rfl

/--
After a DELETE the key has no current row: the delete marker at `(k, openEnd)` wins
`FINAL` with `_version = V` (`≥` the open row's version; later on a tie) and carries
`is_deleted = 1`.
-/
theorem delete_hides_open_row (t : HTable) (k : Key) (ts V : Nat) (r : HRow)
    (hts : ts < openEnd) (hopen : openRow t k = some r)
    (hb : ∀ r' ∈ t, r'.key = k → r'.validTo = openEnd → r'.version ≤ V) :
    openRow (applyDelete t k ts V) k = none := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  rcases liveAt_some hopen with ⟨hfin, _⟩
  rcases finalAt_some_mem hfin with ⟨hm, hk, hv⟩
  have hle : r.version ≤ V := hb r hm hk hv
  have hfin' : finalAt (applyDelete t k ts V) k openEnd = some (deleteMarker r ts V) := by
    unfold applyDelete deleteRows
    rw [hopen, finalAt_append,
        filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ k openEnd hne,
        filterSortKey_cons_of_match (deleteMarker r ts V) [] k openEnd hk rfl]
    simp only [filterSortKey, List.filter_nil, List.foldl_cons, List.foldl_nil]
    rw [hfin]
    simp [hMaxStep, deleteMarker, hle]
  unfold openRow liveAt
  rw [hfin']
  rfl

/--
The closed row's `_valid_to` equals the successor's `_valid_from`: the history has
neither a gap nor an overlap at the event time.
-/
theorem closed_row_continuity (r : HRow) (ts V : Nat) (k : Key) (image : Nat) :
    (closeRow r ts V).validTo = (afterRow k image ts V).validFrom := rfl

/-- The delete marker is a deleted row at the open sentinel. -/
theorem no_open_row_after_delete_marker (r : HRow) (ts V : Nat) :
    (deleteMarker r ts V).isDeleted = true ∧ (deleteMarker r ts V).validTo = openEnd :=
  ⟨rfl, rfl⟩

/-- `FINAL` is a function of the sorting key: at most one live row per `(k, vt)`. -/
theorem at_most_one_live_row_per_sort_key (t : HTable) (k : Key) (vt : Nat) (a b : HRow)
    (ha : liveAt t k vt = some a) (hb : liveAt t k vt = some b) : a = b :=
  Option.some.inj (ha.symm.trans hb)

/-! ## Spec 12.03 §3.4 — TRUNCATE-TABLE / DROP-TABLE bulk close -/

/--
The bulk close leaves NO key with a current row: for every key that had an open
row its marker (version `V ≥` the open row's version, deleted) wins `FINAL` at
`(k, openEnd)`; a key without an open row receives nothing at `(k, openEnd)`.
-/
theorem bulk_close_hides_every_open_row (t : HTable) (ts V : Nat) (op : HOp)
    (hts : ts < openEnd)
    (hb : ∀ r ∈ t, r.validTo = openEnd → r.version ≤ V) :
    ∀ k, openRow (applyBulkClose t ts V op) k = none := by
  intro k
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  cases hopen : openRow t k with
  | none =>
      have hfilt : filterSortKey (bulkCloseRows t ts V op) k openEnd = [] := by
        cases hf : filterSortKey (bulkCloseRows t ts V op) k openEnd with
        | nil => rfl
        | cons x xs =>
            have hx : x ∈ filterSortKey (bulkCloseRows t ts V op) k openEnd := by
              rw [hf]; exact List.mem_cons_self x xs
            rcases filterSortKey_bulk_mem hne hx with ⟨r, hr, _⟩
            rw [hopen] at hr
            exact absurd hr (by simp)
      unfold applyBulkClose openRow liveAt
      rw [finalAt_append, hfilt]
      unfold openRow liveAt at hopen
      exact hopen
  | some r =>
      rcases liveAt_some hopen with ⟨hfin, _⟩
      rcases finalAt_some_mem hfin with ⟨hm, _, hv⟩
      have hle : r.version ≤ V := hb r hm hv
      have hfold : finalAt (applyBulkClose t ts V op) k openEnd = some (bulkMarker r ts V op) := by
        unfold applyBulkClose
        rw [finalAt_append, hfin]
        apply foldl_hMaxStep_const
        · intro x hx
          rcases filterSortKey_bulk_mem hne hx with ⟨r', hr', hx'⟩
          rw [hopen] at hr'
          rw [hx', Option.some.inj hr']
        · exact List.ne_nil_of_mem (bulkMarker_mem_filter hopen)
        · intro m0 h
          rw [← Option.some.inj h]
          exact hle
      unfold openRow liveAt
      rw [hfold]
      rfl

/-- The bulk close is append-only: every physical row of the table survives it —
    no version is truncated or dropped. -/
theorem bulk_close_preserves_history (t : HTable) (ts V : Nat) (op : HOp) :
    ∀ r ∈ t, r ∈ applyBulkClose t ts V op := by
  intro r hr
  exact List.mem_append_left _ hr

/-! ## Spec 12.03 — the shipped defects, as machine-checked counterexamples -/

/--
**Resolved gap G-12.03-1 (old behaviour).** The OLD UPDATE ran inline while the
row's INSERT was still staged, so its SELECTs saw no open row and wrote only the
after row: the pre-update version never entered the history. The corrected
executor flushes first, and the same statement against the flushed table does
emit the close row.
-/
theorem old_inline_update_writes_no_closed_row (t staged : HTable) (k : Key)
    (image ts V : Nat) (r : HRow)
    (hvisible : openRow t k = none) (hstaged : openRow (t ++ staged) k = some r) :
    oldApplyUpdateInline t staged k image ts V = t ++ staged ++ [afterRow k image ts (V + 1)]
      ∧ closeRow r ts V ∈ updateRows (t ++ staged) k k image ts V := by
  constructor
  · simp [oldApplyUpdateInline, oldUpdateRows, hvisible]
  · rw [updateRows_some k image ts V hstaged]
    exact List.mem_cons_self _ _

/--
**Resolved gap G-12.03-2 (old behaviour).** The OLD close row and before copy
shared the sorting key `(pk, ts)` and the version `V` and differed only in
`is_deleted`, so `ReplacingMergeTree(_version, is_deleted)` had to break the tie
by physical order.
-/
theorem old_close_and_before_shared_sort_key_and_version (r : HRow) (ts V : Nat) :
    sameSortKey (closeRow r ts V) (oldBeforeRow r ts V) = true
      ∧ (closeRow r ts V).version = (oldBeforeRow r ts V).version
      ∧ (closeRow r ts V).isDeleted ≠ (oldBeforeRow r ts V).isDeleted := by
  refine ⟨?_, rfl, ?_⟩
  · simp [sameSortKey, closeRow, oldBeforeRow]
  · simp [closeRow, oldBeforeRow]

/--
**Resolved gap G-12.03-2 (old behaviour).** Which of the two rows `FINAL` kept was
decided by the physical order inside one `INSERT ... SELECT UNION ALL SELECT`: in
one order the closed history row was hidden, in the other it survived.
-/
theorem old_closed_history_row_depended_on_insert_order (r : HRow) (ts V : Nat) :
    liveAt [closeRow r ts V, oldBeforeRow r ts V] r.key ts = none
      ∧ liveAt [oldBeforeRow r ts V, closeRow r ts V] r.key ts = some (closeRow r ts V) := by
  constructor
  · simp [liveAt, finalAt, filterSortKey, List.filter_cons, hMaxStep, closeRow, oldBeforeRow]
  · simp [liveAt, finalAt, filterSortKey, List.filter_cons, hMaxStep, closeRow, oldBeforeRow]

/--
**Resolved gap G-12.03-3 (old behaviour).** The OLD close predicate was built from
the AFTER image's key, so an UPDATE that moved a row from `k` to `k'` left `k`'s
open row untouched.
-/
theorem old_update_only_closed_after_image_key (t : HTable) (k k' : Key) (image ts V : Nat)
    (hkk : k ≠ k') :
    openRow (oldApplyUpdate t k' image ts V) k = openRow t k := by
  have hk'k : k' ≠ k := fun e => hkk e.symm
  have hfilt : filterSortKey (oldUpdateRows t k' image ts V) k openEnd = [] := by
    unfold oldUpdateRows
    cases hopen : openRow t k' with
    | none =>
        simp only [List.nil_append, List.append_nil]
        rw [filterSortKey_cons_of_key_ne (afterRow k' image ts (V + 1)) [] k openEnd hk'k]
        rfl
    | some r =>
        rcases liveAt_some hopen with ⟨hfin, _⟩
        rcases finalAt_some_mem hfin with ⟨_, hrk, _⟩
        have hrk' : r.key ≠ k := by rw [hrk]; exact hk'k
        simp only [List.singleton_append, List.cons_append, List.nil_append]
        rw [filterSortKey_cons_of_key_ne (closeRow r ts V) _ k openEnd hrk',
            filterSortKey_cons_of_key_ne (afterRow k' image ts (V + 1)) _ k openEnd hk'k,
            filterSortKey_cons_of_key_ne (oldBeforeRow r ts V) [] k openEnd hrk']
        rfl
  unfold openRow liveAt oldApplyUpdate
  rw [finalAt_append, hfilt]
  rfl

/-! ## Spec 12.01 / 12.05 — modes, database routing and database-level DDL -/

/-- The two mode flags. -/
structure ModeFlags where
  enable  : Bool   -- replication.history.enable
  logOnly : Bool   -- replication.history.replication_log_only
deriving DecidableEq, Repr

/-- `ClickHouseBatchRunnable.processBatch` skips `processRecordsByTopic` only when
    BOTH flags are true. -/
def runnableDataWrites (f : ModeFlags) (batch : List Nat) : List Nat :=
  if f.logOnly && f.enable then [] else batch

/-- `ClickHouseBatchWriter.persistRecords` now applies the same skip. -/
def writerDataWrites (f : ModeFlags) (batch : List Nat) : List Nat :=
  runnableDataWrites f batch

/-- Both executors call `addRecordsToHistoryTable`, gated on `enable` only. -/
def historyWrites (f : ModeFlags) (batch : List Nat) : List Nat :=
  if f.enable then batch else []

/-- `ClickHouseBatchRunnable.resolveDatabaseName`. -/
def runnableDatabase (f : ModeFlags) (src hist : String) : String :=
  if f.enable then hist else src

/-- `ClickHouseBatchWriter.resolveDatabaseName` now routes on `enable` alone. -/
def writerDatabase (f : ModeFlags) (src hist : String) : String :=
  runnableDatabase f src hist

/-- OLD `ClickHouseBatchWriter.persistRecords`: no log-only skip. -/
def oldWriterDataWrites (_f : ModeFlags) (batch : List Nat) : List Nat := batch

/-- OLD `ClickHouseBatchWriter.resolveDatabaseName`: keyed on `enable || logOnly`. -/
def oldWriterDatabase (f : ModeFlags) (src hist : String) : String :=
  if f.enable || f.logOnly then hist else src

/-- The level a replayed DDL statement acts on. -/
inductive DdlKind where
  | table | database
deriving DecidableEq, Repr

/-- Whether the DDL translator applies a statement of kind `d`: in history mode
    (`enable`) the history database is fixed by configuration, so database-level
    DDL (`CREATE DATABASE`, `DROP-DATABASE`) is ignored and only table-level DDL
    is applied; outside history mode every kind is applied. -/
def ddlApplied (f : ModeFlags) (d : DdlKind) : Bool :=
  if f.enable then (d == DdlKind.table) else true

def standard : ModeFlags := ⟨false, false⟩
def scd2 : ModeFlags := ⟨true, false⟩
def logOnlyMode : ModeFlags := ⟨true, true⟩

/-- In log-only mode the batch runnable writes no data-table rows. -/
theorem log_only_runnable_writes_no_data_rows (b : List Nat) :
    runnableDataWrites logOnlyMode b = [] := rfl

/-- In log-only mode the history table is still written. -/
theorem log_only_history_still_written (b : List Nat) :
    historyWrites logOnlyMode b = b := rfl

/-- `replication_log_only` without `enable` is standard mode on the runnable path. -/
theorem log_only_flag_alone_is_standard_on_runnable (b : List Nat) :
    runnableDataWrites ⟨false, true⟩ b = b ∧ historyWrites ⟨false, true⟩ b = [] :=
  ⟨rfl, rfl⟩

/-- Parity (spec 03.02): in log-only mode BOTH execution engines skip the data tables. -/
theorem both_engines_skip_data_in_log_only (b : List Nat) :
    runnableDataWrites logOnlyMode b = [] ∧ writerDataWrites logOnlyMode b = [] :=
  ⟨rfl, rfl⟩

/-- Parity (spec 03.02): the two engines route and gate identically for every flag
    combination, and the degenerate `enable=false, log_only=true` is standard
    routing on both. -/
theorem both_engines_route_alike (f : ModeFlags) (src hist : String) (b : List Nat) :
    writerDatabase f src hist = runnableDatabase f src hist
      ∧ writerDataWrites f b = runnableDataWrites f b
      ∧ writerDatabase ⟨false, true⟩ src hist = src :=
  ⟨rfl, rfl, rfl⟩

/-- In SCD2 mode both executors route every table to the history database. -/
theorem scd2_routes_every_table_to_history_database (src hist : String) :
    runnableDatabase scd2 src hist = hist ∧ writerDatabase scd2 src hist = hist :=
  ⟨rfl, rfl⟩

/-- Standard mode writes no history rows. -/
theorem standard_mode_writes_no_history (b : List Nat) :
    historyWrites standard b = [] := rfl

/-- Database-level DDL is ignored in both history modes and applied in standard
    mode; table-level DDL is applied in history mode. -/
theorem database_ddl_ignored_in_history_mode :
    ddlApplied scd2 DdlKind.database = false
      ∧ ddlApplied logOnlyMode DdlKind.database = false
      ∧ ddlApplied standard DdlKind.database = true
      ∧ ddlApplied scd2 DdlKind.table = true := by
  decide

/--
**Resolved gap G-12.05-1 (old behaviour).** The OLD single-threaded writer had no
log-only skip, so it wrote data-table rows in log-only mode.
-/
theorem old_writer_path_ignored_log_only (b : List Nat) :
    oldWriterDataWrites logOnlyMode b = b := rfl

/--
**Resolved gap G-12.01-1 (old behaviour).** The OLD writer keyed routing on
`enable || logOnly` while the runnable keyed on `enable`, so with only the
log-only flag set the two engines wrote to different databases.
-/
theorem old_routing_diverged_on_log_only_alone (src hist : String) :
    runnableDatabase ⟨false, true⟩ src hist = src
      ∧ oldWriterDatabase ⟨false, true⟩ src hist = hist :=
  ⟨rfl, rfl⟩

/-! ## The history version domain (Spec 12.03 §3.5.1)

Every row of an SCD2 table carries the snowflake encoding of the event's
ordering key `(ts_ms, d)`: `SnowFlakeId.generate(ts_ms, d, false)`, i.e.
`(ts_ms - epoch) * 2^22 + d` for a discriminator `d < 2^22`. The encoding is
strictly monotone, so it orders events exactly as the standard version does,
and it is the domain releases up to 2.11.0 already used for the UPDATE and
DELETE rows of a history table -- which is what makes an upgrade and a
downgrade safe on the same tables. -/

/-- The size of the snowflake discriminator field, `2 ^ SnowFlakeId.GTID_FIELD_BITS = 2^22`. -/
def snowflakeDiscriminatorSpace : Nat := 4194304

/-- `SnowFlakeId.generate(ts, d, false)` on timestamps already shifted past the
    snowflake epoch: the timestamp in the high bits, `d` in the low 22 bits. -/
def snowflakeEncode (ts d : Nat) : Nat := ts * snowflakeDiscriminatorSpace + d

/-- The lightweight sequence version of spec 02.02: `ts * 10^6 + counter`. -/
def sequenceVersion (ts counter : Nat) : Nat := ts * 1000000 + counter

/-- The 2.11.0 after row / delete marker at the open sorting key:
    `SnowFlakeId(ts, gtid) + 1`. -/
def legacyOpenRowVersion (ts gtid : Nat) : Nat := snowflakeEncode ts gtid + 1

/-- The encoding is strictly monotone in the lexicographic order of `(ts, d)`
    when the discriminator fits its field: a later millisecond wins whatever the
    discriminators, and within one millisecond the larger discriminator wins.
    This is why the history version orders events exactly as the standard
    version does. -/
theorem snowflake_encode_strict_mono (ts₁ d₁ ts₂ d₂ : Nat)
    (h₁ : d₁ < snowflakeDiscriminatorSpace) (h₂ : d₂ < snowflakeDiscriminatorSpace)
    (hlt : ts₁ < ts₂ ∨ (ts₁ = ts₂ ∧ d₁ < d₂)) :
    snowflakeEncode ts₁ d₁ < snowflakeEncode ts₂ d₂ := by
  unfold snowflakeEncode snowflakeDiscriminatorSpace at *
  rcases hlt with hts | ⟨hts, hd⟩
  · omega
  · subst hts
    omega

/-- **Upgrade safety (S10).** An open row or delete marker a 2.11.0 connector
    left at `(k, S)` carries `SnowFlakeId(ts_old, gtid) + 1`. The fixed
    connector's next event on that key is a later millisecond and is written as
    `SnowFlakeId(ts_new, d)`, which is at least as great whatever `gtid` and `d`
    are (equal only for the all-ones discriminator a GTID-less 2.11.0 wrote,
    against `d = 0` in the very next millisecond -- and on a tie `FINAL` keeps
    the later inserted row, `hMaxStep`), so the legacy row is superseded and the
    key does not freeze: this is exactly the hypothesis "the visible open row
    has version `<= V`" of `update_supersedes_open_row`. -/
theorem legacy_open_row_superseded_by_later_event (tsOld gtid tsNew d : Nat)
    (hg : gtid < snowflakeDiscriminatorSpace) (hts : tsOld < tsNew) :
    legacyOpenRowVersion tsOld gtid ≤ snowflakeEncode tsNew d := by
  have hg' : gtid < 4194304 := hg
  have hmul : (tsOld + 1) * 4194304 ≤ tsNew * 4194304 := Nat.mul_le_mul_right _ hts
  show tsOld * 4194304 + gtid + 1 ≤ tsNew * 4194304 + d
  omega

/-- **Downgrade safety (S10).** A row the fixed connector wrote at `(k, S)` is
    `SnowFlakeId(ts, d)`; a 2.11.0 connector's later event writes
    `SnowFlakeId(ts_later, gtid) + 1`, which is strictly greater. -/
theorem fixed_open_row_superseded_by_later_legacy_event (ts d tsLater gtid : Nat)
    (hd : d < snowflakeDiscriminatorSpace) (hts : ts < tsLater) :
    snowflakeEncode ts d < legacyOpenRowVersion tsLater gtid := by
  have hd' : d < 4194304 := hd
  have hmul : (ts + 1) * 4194304 ≤ tsLater * 4194304 := Nat.mul_le_mul_right _ hts
  show ts * 4194304 + d < tsLater * 4194304 + gtid + 1
  omega

/-- **Sequence rows step one millisecond down (Spec 12.03 §3.5.1).** A sequence
    row (a snapshot row on any source, every row on a GTID-less source) is written
    at `(effectiveTs - 1, counter - seed)`; a GTID row floored to the same
    effective millisecond is written at `(effectiveTs, gtid)` and ranks above it
    whatever the two discriminators are -- the ordering the standard domain gets
    from "any snowflake is above any sequence", reproduced inside one domain. -/
theorem sequence_row_below_gtid_row_of_its_millisecond (effectiveTs counterLessSeed gtid : Nat)
    (hts : 1 ≤ effectiveTs) (hc : counterLessSeed < snowflakeDiscriminatorSpace) :
    snowflakeEncode (effectiveTs - 1) counterLessSeed < snowflakeEncode effectiveTs gtid := by
  have hc' : counterLessSeed < 4194304 := hc
  show (effectiveTs - 1) * 4194304 + counterLessSeed < effectiveTs * 4194304 + gtid
  have hsub : (effectiveTs - 1) * 4194304 + 4194304 = effectiveTs * 4194304 := by
    have : effectiveTs - 1 + 1 = effectiveTs := Nat.sub_add_cancel hts
    calc (effectiveTs - 1) * 4194304 + 4194304
        = (effectiveTs - 1 + 1) * 4194304 := by rw [Nat.succ_mul]
      _ = effectiveTs * 4194304 := by rw [this]
  omega

/--
**Resolved gap S10 (old behaviour, the upgrade freeze).** Had the fixed
connector bound the RAW sequence number as `_version`, a legacy open row of an
EARLIER millisecond would still have outranked it: on 2026 timestamps the
sequence `ts * 10^6 + counter` is below the snowflake of any instant after
mid-2023, so every key a 2.11.0 connector had updated or deleted would have
frozen at the upgrade. Witness at the epoch-shifted millisecond of the
end-to-end run (`2026-09-26`): the legacy row is at `ts_old = 500_000_000_000`
(≈ 15.8 years past the epoch), the new event one full second later.
-/
theorem old_raw_sequence_version_loses_to_legacy_open_row :
    sequenceVersion (1288834974657 + 500000000000 + 1000) 999999
      < legacyOpenRowVersion 500000000000 4194303 := by
  decide

end Replication
