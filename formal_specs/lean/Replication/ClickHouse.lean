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
def CHTable := List CHRecord
deriving DecidableEq, Repr

def emptyCH : CHTable := []

/-- Filters records belonging to a specific primary key. -/
def filterKey (table : CHTable) (k : Key) : List CHRecord :=
  table.filter (fun r => r.key == k)

/-- Finds the record with the strictly maximal version among a list of records. -/
def findMaxVersion (records : List CHRecord) : Option CHRecord :=
  records.foldl (fun acc r =>
    match acc with
    | none   => some r
    | some m => if r.version >= m.version then some r else some m
  ) none

/--
Mathematical evaluation of ClickHouse ReplacingMergeTree `FINAL` semantics:
For a given key k, collapses all records to the one possessing the highest version.
If the highest version is marked deleted, the row evaluates to `none`; otherwise `some row`.
-/
def chFinalView (table : CHTable) (k : Key) : Option Row :=
  let keyRecords := filterKey table k
  match findMaxVersion keyRecords with
  | none   => none
  | some r => if r.is_deleted then none else some r.row

end Replication
