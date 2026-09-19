/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic

namespace Replication

/-- A physical row stored in a ClickHouse ReplacingMergeTree table. -/
structure CHRecord where
  key        : Key
  row        : Row
  version    : Nat
  is_deleted : Bool
deriving DecidableEq, Repr

/-- ClickHouse table storage represented as an append-only sequence of records. -/
abbrev CHTable := List CHRecord

def emptyCH : CHTable := []

/-- Filters records belonging to a specific primary key. -/
def filterKey (table : CHTable) (k : Key) : List CHRecord :=
  table.filter (fun r => r.key == k)

/-- One step of the max-version fold: keep the record with the larger version
    (ties resolve to the later record via `>=`). -/
def maxStep (acc : Option CHRecord) (r : CHRecord) : Option CHRecord :=
  match acc with
  | none   => some r
  | some m => if r.version >= m.version then some r else some m

/-- Finds the record with the maximal version among a list of records. -/
def findMaxVersion (records : List CHRecord) : Option CHRecord :=
  records.foldl maxStep none

/--
Mathematical evaluation of ClickHouse ReplacingMergeTree `FINAL` semantics:
For a given key k, collapses all records to the one possessing the highest version.
If the highest version is marked deleted, the row evaluates to `none`; otherwise `some row`.
-/
def chFinalView (table : CHTable) (k : Key) : Option Row :=
  match findMaxVersion (filterKey table k) with
  | none   => none
  | some r => if r.is_deleted then none else some r.row

end Replication
