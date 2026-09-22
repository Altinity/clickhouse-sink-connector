/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.Binlog

/-!
# Batch execution order around a replicated TRUNCATE, spec 04.05

A worker receives a batch of binlog events for one table and executes it as
JDBC statement groups. Row events are grouped by INSERT template; a replicated
MySQL TRUNCATE, delivered as change event `op = t`, is a statement of its own.
The question this module settles is WHERE in the batch that statement runs.

* **The pre-fix executor** kept every group in one hash map and iterated it.
  The truncation group therefore ran either before every INSERT group of the
  batch or after them, decided by the hash of the table name. We model both
  outcomes (`execGroupedTruncateFirst`, `execGroupedTruncateLast`) and show
  that each disagrees with the source for a three-event batch
  `[INSERT r1, TRUNCATE, INSERT r2]`: one resurrects `r1`, the other loses
  `r2`.
* **The fixed executor** splits the batch at every TRUNCATE, giving an ordered
  list of segments (`splitAtTruncate`), and executes the segments in order
  (`execSegments`). `segments_match_source` proves this equals applying the
  events in binlog order, for every batch; `every_truncate_is_its_own_segment`
  proves two TRUNCATEs in one batch stay two segments (they collapsed onto one
  hash-map key before).

The formal-model arms below mention the `truncate` constructor of `BinlogOp`;
these are Lean terms about list semantics, nothing is executed.
-/

namespace Replication
namespace BatchOrder

/-- Whether an event is a replicated TRUNCATE. -/
def isTruncate (e : BinlogEvent) : Bool :=
  match e.op with
  -- DESTRUCTIVE: formal-model arm only — recognises the `truncate` constructor
  -- of a Lean inductive; no data is touched.
  | BinlogOp.truncate => true
  | _ => false

/-- A segment consisting of exactly one truncation event. -/
def isTruncateSegment : List BinlogEvent → Bool
  | [t] => isTruncate t
  | _ => false

/--
Split a batch at every TRUNCATE: rows keep their order inside a segment, each
truncation becomes a singleton segment, and a fresh segment starts after it.
This is `GroupInsertQueryWithBatchRecords.groupQueryWithRecords` producing
`List<Map<template, records>>`.
-/
def splitAtTruncate : List BinlogEvent → List (List BinlogEvent)
  | [] => []
  | e :: rest =>
    if isTruncate e then [e] :: splitAtTruncate rest
    else
      match splitAtTruncate rest with
      | [] => [[e]]
      | seg :: segs =>
        if isTruncateSegment seg then [e] :: seg :: segs else (e :: seg) :: segs

/-- Execute one segment: its events in order. -/
def execSegment (s : MySQLState) (seg : List BinlogEvent) : MySQLState :=
  seg.foldl applyBinlogEvent s

/-- Execute the segments strictly in order (`PreparedStatementExecutor.addToPreparedStatementBatch`). -/
def execSegments (s : MySQLState) (segs : List (List BinlogEvent)) : MySQLState :=
  segs.foldl execSegment s

/-- One event peeled off the front of a split batch is one event applied. -/
theorem execSegments_split_cons (s : MySQLState) (e : BinlogEvent) (rest : List BinlogEvent) :
    execSegments s (splitAtTruncate (e :: rest))
      = execSegments (applyBinlogEvent s e) (splitAtTruncate rest) := by
  cases ht : isTruncate e with
  | true => simp [splitAtTruncate, ht, execSegments, execSegment]
  | false =>
    cases hs : splitAtTruncate rest with
    | nil => simp [splitAtTruncate, ht, hs, execSegments, execSegment]
    | cons seg segs =>
      cases hseg : isTruncateSegment seg with
      | true => simp [splitAtTruncate, ht, hs, hseg, execSegments, execSegment]
      | false => simp [splitAtTruncate, ht, hs, hseg, execSegments, execSegment]

/--
**Segments in order reproduce binlog order.** Executing the split batch
segment by segment yields exactly the state obtained by applying the events
one after another — every row before a truncation is applied before it, every
row after it survives it.
-/
theorem segments_match_source (evs : List BinlogEvent) :
    ∀ s : MySQLState, execSegments s (splitAtTruncate evs) = evs.foldl applyBinlogEvent s := by
  induction evs with
  | nil => intro s; rfl
  | cons e rest ih =>
    intro s
    rw [execSegments_split_cons, ih]
    rfl

/-- The same statement against the source evaluator from an empty table. -/
theorem segmented_batch_converges (evs : List BinlogEvent) :
    execSegments emptyMySQL (splitAtTruncate evs) = evalMySQL evs := by
  unfold evalMySQL
  exact segments_match_source evs emptyMySQL

/-! ## Every truncation is its own segment (two TRUNCATEs never collapse) -/

def numTruncates : List BinlogEvent → Nat
  | [] => 0
  | e :: rest => (if isTruncate e then 1 else 0) + numTruncates rest

def numTruncateSegments : List (List BinlogEvent) → Nat
  | [] => 0
  | seg :: segs => (if isTruncateSegment seg then 1 else 0) + numTruncateSegments segs

theorem isTruncateSegment_singleton (t : BinlogEvent) : isTruncateSegment [t] = isTruncate t := rfl

theorem isTruncateSegment_cons_row (e : BinlogEvent) (seg : List BinlogEvent)
    (ht : isTruncate e = false) : isTruncateSegment (e :: seg) = false := by
  cases seg with
  | nil => show isTruncate e = false; exact ht
  | cons x xs => rfl

/--
**No collapse.** The split batch has exactly as many truncation segments as
the batch has truncation events. Under the pre-fix hash map two TRUNCATEs of
one table had equal keys and became one entry.
-/
theorem every_truncate_is_its_own_segment (evs : List BinlogEvent) :
    numTruncateSegments (splitAtTruncate evs) = numTruncates evs := by
  induction evs with
  | nil => rfl
  | cons e rest ih =>
    cases ht : isTruncate e with
    | true =>
      simp [splitAtTruncate, ht, numTruncateSegments, numTruncates, isTruncateSegment_singleton, ih]
    | false =>
      cases hs : splitAtTruncate rest with
      | nil =>
        rw [hs] at ih
        simp [numTruncateSegments] at ih
        simp [splitAtTruncate, ht, hs, numTruncateSegments, numTruncates,
          isTruncateSegment_singleton] <;> omega
      | cons seg segs =>
        rw [hs] at ih
        cases hseg : isTruncateSegment seg with
        | true =>
          simp [hseg, numTruncateSegments] at ih
          simp [splitAtTruncate, ht, hs, hseg, numTruncateSegments, numTruncates,
            isTruncateSegment_singleton] <;> omega
        | false =>
          simp [hseg, numTruncateSegments] at ih
          simp [splitAtTruncate, ht, hs, hseg, numTruncateSegments, numTruncates,
            isTruncateSegment_cons_row e seg ht] <;> omega

/-! ## The pre-fix executor: one hash map, truncation first or last -/

/-- The row events of a batch, in order (the INSERT groups). -/
def rowsOf (evs : List BinlogEvent) : List BinlogEvent :=
  evs.filter (fun e => !isTruncate e)

def hasTruncate (evs : List BinlogEvent) : Bool :=
  evs.any isTruncate

/-- Hash order put the truncation group after every INSERT group. -/
def execGroupedTruncateLast (s : MySQLState) (evs : List BinlogEvent) : MySQLState :=
  if hasTruncate evs then emptyMySQL else execSegment s (rowsOf evs)

/-- Hash order put the truncation group before every INSERT group. -/
def execGroupedTruncateFirst (s : MySQLState) (evs : List BinlogEvent) : MySQLState :=
  execSegment (if hasTruncate evs then emptyMySQL else s) (rowsOf evs)

def p0 : BinlogPos := { fileSeq := 0, offset := 0, rowIdx := 0 }

def insertEv (k : Key) (v : Row) : BinlogEvent := { pos := p0, op := BinlogOp.insert k v, ts := 0 }

-- DESTRUCTIVE: formal-model event value carrying the `truncate` constructor;
-- a Lean term used in counterexamples, nothing is executed.
def truncateEv : BinlogEvent := { pos := p0, op := BinlogOp.truncate, ts := 0 }

/--
**Truncation last loses the rows that followed it.** For
`[INSERT k v, TRUNCATE, INSERT k v']` the source holds `v'` at `k`; running
the truncation after both inserts leaves nothing.
-/
theorem truncate_last_loses_rows (k : Key) (v v' : Row) :
    execGroupedTruncateLast emptyMySQL [insertEv k v, truncateEv, insertEv k v'] k
      ≠ (evalMySQL [insertEv k v, truncateEv, insertEv k v']) k := by
  simp [execGroupedTruncateLast, hasTruncate, isTruncate, truncateEv, insertEv, emptyMySQL,
    evalMySQL, applyBinlogEvent]

/--
**Truncation first resurrects the rows that preceded it.** For
`[INSERT k v, TRUNCATE]` the source holds nothing at `k`; running the
truncation before the insert leaves `v` alive.
-/
theorem truncate_first_resurrects_rows (k : Key) (v : Row) :
    execGroupedTruncateFirst emptyMySQL [insertEv k v, truncateEv] k
      ≠ (evalMySQL [insertEv k v, truncateEv]) k := by
  simp [execGroupedTruncateFirst, hasTruncate, rowsOf, isTruncate, truncateEv, insertEv,
    emptyMySQL, evalMySQL, applyBinlogEvent, execSegment]

end BatchOrder
end Replication
