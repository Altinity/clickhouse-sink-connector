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

This module models, faithfully to the connector code and without improving it:

* **Spec 12.03 — SCD2 write protocol.** The row sets emitted by the single
  `INSERT ... SELECT ... UNION ALL SELECT ... UNION ALL SELECT ...` statements
  built by `QueryFormatter.getInsertQueryForUpdate` and
  `QueryFormatter.getInsertQueryForDelete`, and the `PreparedStatementExecutor`
  paths that run them against the already flushed (visible) table state.
* **Spec 12.05 — replication-log-only.** The gating of data-table writes,
  history-table writes and target-database routing on the two mode flags in
  `ClickHouseBatchRunnable` and `ClickHouseBatchWriter`.

Abstractions: a row's payload is an opaque `image` identity; timestamps are UTC
epoch seconds (`Nat`); a table is an append-only list of physical rows, and
`FINAL` is evaluated mathematically by `finalAt` / `liveAt`. Batches on the
routing side are opaque lists.
-/

namespace Replication

/-! ## Rows, tables and the FINAL evaluation at a sorting key -/

/-- The `_operation` column of a history row. -/
inductive HOp where
  | create | read | update | delete
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

/-! ## The rows each source event writes -/

/-- INSERT: the field mapper binds `_valid_from = ts`, `_valid_to = sentinel`,
    `is_deleted = 0`, `_operation = 'C'`. -/
def insertRow (k : Key) (image ts v : Nat) : HRow :=
  ⟨k, image, ts, openEnd, false, v, HOp.create⟩

/-- First SELECT of `getInsertQueryForUpdate` / `getInsertQueryForDelete`: the
    visible open row copied with `_valid_to = ts`, `is_deleted = 0`, `_version = V`. -/
def closeRow (r : HRow) (ts V : Nat) : HRow :=
  { r with validTo := ts, isDeleted := false, version := V }

/-- Second SELECT of the UPDATE (no FROM clause): the new image, `_valid_from = ts`,
    `_valid_to = sentinel`, `is_deleted = 0`, `_version = V+1`, `_operation = 'U'`. -/
def afterRow (k : Key) (image ts V : Nat) : HRow :=
  ⟨k, image, ts, openEnd, false, V + 1, HOp.update⟩

/-- Third SELECT of the UPDATE: the visible open row re-read with `_valid_to = ts`,
    `is_deleted = 1`, `_version = V`, `_operation = 'U'`. -/
def beforeRow (r : HRow) (ts V : Nat) : HRow :=
  { r with validTo := ts, isDeleted := true, version := V, op := HOp.update }

/-- Second SELECT of the DELETE: the open row's tombstone at the sentinel. -/
def deleteMarker (r : HRow) (ts V : Nat) : HRow :=
  { r with validFrom := ts, validTo := openEnd, isDeleted := true, version := V + 1,
           op := HOp.delete }

/-- The row set ONE UPDATE statement emits. The two table-reading SELECTs read the
    VISIBLE (already flushed) table `visible`; the middle SELECT has no FROM clause. -/
def updateRows (visible : HTable) (k : Key) (image ts V : Nat) : List HRow :=
  (match openRow visible k with | some r => [closeRow r ts V] | none => [])
    ++ [afterRow k image ts V]
    ++ (match openRow visible k with | some r => [beforeRow r ts V] | none => [])

/-- The row set ONE DELETE statement emits against the visible table. -/
def deleteRows (visible : HTable) (k : Key) (ts V : Nat) : List HRow :=
  match openRow visible k with
  | some r => [closeRow r ts V, deleteMarker r ts V]
  | none   => []

/-- Apply an INSERT. -/
def applyInsert (t : HTable) (k : Key) (image ts v : Nat) : HTable :=
  t ++ [insertRow k image ts v]

/-- Apply an UPDATE whose SELECTs read the flushed table `t`. -/
def applyUpdate (t : HTable) (k : Key) (image ts V : Nat) : HTable :=
  t ++ updateRows t k image ts V

/-- Apply a DELETE whose SELECTs read the flushed table `t`. -/
def applyDelete (t : HTable) (k : Key) (ts V : Nat) : HTable :=
  t ++ deleteRows t k ts V

/-- The UPDATE path in `PreparedStatementExecutor.executePreparedStatement`: the
    history statement runs INLINE without flushing the rows already staged on the
    PreparedStatement (`staged`), so its SELECTs read only `t`; the staged rows land
    at the following `executeBatch()`. (The DELETE path flushes first, i.e. it is
    `applyDelete` against `t ++ staged`.) -/
def applyUpdateStaged (t staged : HTable) (k : Key) (image ts V : Nat) : HTable :=
  t ++ staged ++ updateRows t k image ts V

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

/-- At `(k, openEnd)` an UPDATE statement contributes exactly its after-image row. -/
theorem filterSortKey_updateRows (t : HTable) (k : Key) (image ts V : Nat)
    (hts : ts ≠ openEnd) :
    filterSortKey (updateRows t k image ts V) k openEnd = [afterRow k image ts V] := by
  unfold updateRows
  cases openRow t k with
  | none =>
      simp only [List.nil_append, List.append_nil]
      rw [filterSortKey_cons_of_match (afterRow k image ts V) [] k openEnd rfl rfl]
      rfl
  | some r =>
      simp only [List.singleton_append, List.cons_append, List.nil_append]
      rw [filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ k openEnd hts,
          filterSortKey_cons_of_match (afterRow k image ts V) _ k openEnd rfl rfl,
          filterSortKey_cons_of_validTo_ne (beforeRow r ts V) [] k openEnd hts]
      rfl

/-- The after-image wins `FINAL` at `(k, openEnd)` against any prefix whose open rows
    for `k` have `_version ≤ V`. -/
theorem hMaxStep_afterRow_wins (t : HTable) (k : Key) (image ts V : Nat)
    (hb : ∀ r ∈ t, r.key = k → r.validTo = openEnd → r.version ≤ V) :
    hMaxStep (finalAt t k openEnd) (afterRow k image ts V) = some (afterRow k image ts V) := by
  cases hf : finalAt t k openEnd with
  | none => rfl
  | some m =>
      rcases finalAt_some_mem hf with ⟨hm, hk, hv⟩
      have hle : m.version ≤ V := hb m hm hk hv
      have : V + 1 ≥ m.version := Nat.le_succ_of_le hle
      simp [hMaxStep, afterRow, this]

/-! ## Spec 12.03 — SCD2 write protocol -/

/--
After an UPDATE the current row of the key is the new image: it wins `FINAL` at
`(k, openEnd)` by `_version = V+1`, and even on a tie it is the later row. The
close and before rows carry `_valid_to = ts ≠ openEnd`, so they never compete at
the open sorting key.
-/
theorem update_supersedes_open_row (t : HTable) (k : Key) (image ts V : Nat)
    (hts : ts < openEnd)
    (hb : ∀ r ∈ t, r.key = k → r.validTo = openEnd → r.version ≤ V) :
    openRow (applyUpdate t k image ts V) k = some (afterRow k image ts V) := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  have hfin : finalAt (applyUpdate t k image ts V) k openEnd = some (afterRow k image ts V) := by
    unfold applyUpdate
    rw [finalAt_append, filterSortKey_updateRows t k image ts V hne]
    simp only [List.foldl_cons, List.foldl_nil]
    exact hMaxStep_afterRow_wins t k image ts V hb
  unfold openRow liveAt
  rw [hfin]
  rfl

/--
After a DELETE the key has no current row: the delete marker at `(k, openEnd)` wins
`FINAL` with `_version = V+1` and carries `is_deleted = 1`.
-/
theorem delete_hides_open_row (t : HTable) (k : Key) (ts V : Nat) (r : HRow)
    (hts : ts < openEnd) (hopen : openRow t k = some r)
    (hb : ∀ r' ∈ t, r'.key = k → r'.validTo = openEnd → r'.version ≤ V) :
    openRow (applyDelete t k ts V) k = none := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  rcases liveAt_some hopen with ⟨hfin, _⟩
  rcases finalAt_some_mem hfin with ⟨hm, hk, hv⟩
  have hle : r.version ≤ V := hb r hm hk hv
  have hge : V + 1 ≥ r.version := Nat.le_succ_of_le hle
  have hfin' : finalAt (applyDelete t k ts V) k openEnd = some (deleteMarker r ts V) := by
    unfold applyDelete deleteRows
    rw [hopen, finalAt_append,
        filterSortKey_cons_of_validTo_ne (closeRow r ts V) _ k openEnd hne,
        filterSortKey_cons_of_match (deleteMarker r ts V) [] k openEnd hk rfl]
    simp only [filterSortKey, List.filter_nil, List.foldl_cons, List.foldl_nil]
    rw [hfin]
    simp [hMaxStep, deleteMarker, hge]
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

/-! ## Spec 12.03 — gaps (machine-checked counterexamples) -/

/--
**Gap: the close row and the before row collide.** `getInsertQueryForUpdate` emits,
in ONE statement, the first SELECT (`_valid_to = ts`, `is_deleted = 0`, `_version = V`)
and the third SELECT (`_valid_to = ts`, `is_deleted = 1`, `_version = V`) for the
same open row. They share the sorting key `(pk, ts)` and the same `_version` and
differ only in `is_deleted`, so `ReplacingMergeTree(_version, is_deleted)` must
break the tie by physical order.
-/
theorem close_and_before_share_sort_key_and_version (r : HRow) (ts V : Nat) :
    sameSortKey (closeRow r ts V) (beforeRow r ts V) = true
      ∧ (closeRow r ts V).version = (beforeRow r ts V).version
      ∧ (closeRow r ts V).isDeleted ≠ (beforeRow r ts V).isDeleted := by
  refine ⟨?_, rfl, ?_⟩
  · simp [sameSortKey, closeRow, beforeRow]
  · simp [closeRow, beforeRow]

/--
**Gap: the closed history row depends on insertion order.** Same sorting key, same
`_version`, opposite `is_deleted`: which row `FINAL` keeps is decided by the
physical order of the rows inside one `INSERT ... SELECT UNION ALL SELECT`, which
ClickHouse does not guarantee. In one order the closed history row is hidden; in
the other it survives.
-/
theorem closed_history_row_depends_on_insert_order (r : HRow) (ts V : Nat) :
    liveAt [closeRow r ts V, beforeRow r ts V] r.key ts = none
      ∧ liveAt [beforeRow r ts V, closeRow r ts V] r.key ts = some (closeRow r ts V) := by
  constructor
  · simp [liveAt, finalAt, filterSortKey, List.filter_cons, hMaxStep, closeRow, beforeRow]
  · simp [liveAt, finalAt, filterSortKey, List.filter_cons, hMaxStep, closeRow, beforeRow]

/--
**Gap: a staged INSERT is never closed.** In
`PreparedStatementExecutor.executePreparedStatement` the UPDATE's history statement
runs inline while the row's INSERT is still staged on the PreparedStatement, so the
statement's SELECTs see no open row: the UPDATE closes nothing and the pre-update
image never enters the history.
-/
theorem staged_insert_writes_no_closed_row (t _staged : HTable) (k : Key) (image ts V : Nat)
    (h : openRow t k = none) :
    updateRows t k image ts V = [afterRow k image ts V] := by
  simp [updateRows, h]

/-- Once the INSERT is flushed, the UPDATE does write the closed history row. -/
theorem flushed_insert_writes_closed_row (t : HTable) (k : Key) (image ts V : Nat) (r : HRow)
    (h : openRow t k = some r) :
    closeRow r ts V ∈ updateRows t k image ts V := by
  simp [updateRows, h]

/--
**Gap: the current-state view survives the missing flush.** Even when the UPDATE
runs against `t` while `staged` is unflushed, the after-image still becomes the
current row once the staged rows land — only the history row is lost. Row-count
and current-value checks therefore do not detect the gap.
-/
theorem staged_update_current_view_still_correct (t staged : HTable) (k : Key)
    (image ts V : Nat) (hts : ts < openEnd)
    (hb : ∀ r ∈ t ++ staged, r.key = k → r.validTo = openEnd → r.version ≤ V) :
    openRow (applyUpdateStaged t staged k image ts V) k = some (afterRow k image ts V) := by
  have hne : ts ≠ openEnd := Nat.ne_of_lt hts
  have hfin : finalAt (applyUpdateStaged t staged k image ts V) k openEnd
      = some (afterRow k image ts V) := by
    unfold applyUpdateStaged
    rw [finalAt_append, filterSortKey_updateRows t k image ts V hne]
    simp only [List.foldl_cons, List.foldl_nil]
    exact hMaxStep_afterRow_wins (t ++ staged) k image ts V hb
  unfold openRow liveAt
  rw [hfin]
  rfl

/--
**Gap: a primary-key change leaves the old key open.** The close predicate in
`getInsertQueryForUpdate` is built from the AFTER image's primary key, so an UPDATE
that changes the primary key from `k` to `k'` never closes `k`'s open row.
-/
theorem update_only_closes_after_image_key (t : HTable) (k k' : Key) (image ts V : Nat)
    (hkk : k ≠ k') :
    openRow (applyUpdate t k' image ts V) k = openRow t k := by
  have hk'k : k' ≠ k := fun e => hkk e.symm
  have hfilt : filterSortKey (updateRows t k' image ts V) k openEnd = [] := by
    unfold updateRows
    cases hopen : openRow t k' with
    | none =>
        simp only [List.nil_append, List.append_nil]
        rw [filterSortKey_cons_of_key_ne (afterRow k' image ts V) [] k openEnd hk'k]
        rfl
    | some r =>
        rcases liveAt_some hopen with ⟨hfin, _⟩
        rcases finalAt_some_mem hfin with ⟨_, hrk, _⟩
        have hrk' : r.key ≠ k := by rw [hrk]; exact hk'k
        simp only [List.singleton_append, List.cons_append, List.nil_append]
        rw [filterSortKey_cons_of_key_ne (closeRow r ts V) _ k openEnd hrk',
            filterSortKey_cons_of_key_ne (afterRow k' image ts V) _ k openEnd hk'k,
            filterSortKey_cons_of_key_ne (beforeRow r ts V) [] k openEnd hrk']
        rfl
  unfold openRow liveAt applyUpdate
  rw [finalAt_append, hfilt]
  rfl

/-! ## Spec 12.05 — replication-log-only mode and database routing -/

/-- The two mode flags. -/
structure ModeFlags where
  enable  : Bool   -- replication.history.enable
  logOnly : Bool   -- replication.history.replication_log_only
deriving DecidableEq, Repr

/-- `ClickHouseBatchRunnable.processBatch` skips `processRecordsByTopic` only when
    BOTH flags are true. -/
def runnableDataWrites (f : ModeFlags) (batch : List Nat) : List Nat :=
  if f.logOnly && f.enable then [] else batch

/-- `ClickHouseBatchWriter.persistRecords` has no such skip (single-threaded path). -/
def writerDataWrites (_f : ModeFlags) (batch : List Nat) : List Nat := batch

/-- Both executors call `addRecordsToHistoryTable`, gated on `enable` only. -/
def historyWrites (f : ModeFlags) (batch : List Nat) : List Nat :=
  if f.enable then batch else []

/-- `ClickHouseBatchRunnable.resolveDatabaseName`. -/
def runnableDatabase (f : ModeFlags) (src hist : String) : String :=
  if f.enable then hist else src

/-- `ClickHouseBatchWriter.resolveDatabaseName`. -/
def writerDatabase (f : ModeFlags) (src hist : String) : String :=
  if f.enable || f.logOnly then hist else src

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

/--
**Gap (parity with spec 03.02): the writer path ignores log-only.**
`ClickHouseBatchWriter.persistRecords` has no log-only skip, so on the
single-threaded path data-table rows are written even in log-only mode.
-/
theorem writer_path_ignores_log_only (b : List Nat) :
    writerDataWrites logOnlyMode b = b := rfl

/--
**Gap: database routing diverges on `replication_log_only` alone.**
`ClickHouseBatchRunnable.resolveDatabaseName` keys on `enable` only while
`ClickHouseBatchWriter.resolveDatabaseName` keys on `enable || logOnly`, so with
only the log-only flag set the two executors write to different databases.
-/
theorem routing_diverges_on_log_only_alone (src hist : String) :
    runnableDatabase ⟨false, true⟩ src hist = src ∧ writerDatabase ⟨false, true⟩ src hist = hist :=
  ⟨rfl, rfl⟩

/-- In SCD2 mode both executors route every table to the history database. -/
theorem scd2_routes_every_table_to_history_database (src hist : String) :
    runnableDatabase scd2 src hist = hist ∧ writerDatabase scd2 src hist = hist :=
  ⟨rfl, rfl⟩

/-- Standard mode writes no history rows. -/
theorem standard_mode_writes_no_history (b : List Nat) :
    historyWrites standard b = [] := rfl

end Replication
