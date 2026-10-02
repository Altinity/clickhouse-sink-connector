/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic

namespace Replication

/-- Monotonically ordered binary log coordinate: file sequence, byte offset, row index. -/
structure BinlogPos where
  fileSeq : Nat
  offset  : Nat
  rowIdx  : Nat
deriving DecidableEq, Repr

/-- Lexicographical total order on binary log positions. -/
def BinlogPos.lt (a b : BinlogPos) : Prop :=
  a.fileSeq < b.fileSeq ∨
  (a.fileSeq = b.fileSeq ∧ (a.offset < b.offset ∨ (a.offset = b.offset ∧ a.rowIdx < b.rowIdx)))

instance : LT BinlogPos where
  lt := BinlogPos.lt

/-- Binary log operations captured from MySQL row-based replication. -/
inductive BinlogOp where
  | insert (k : Key) (v : Row)                      : BinlogOp
  | update (k_old : Key) (k_new : Key) (v : Row)    : BinlogOp
  | delete (k : Key)                                : BinlogOp
  | truncate                                        : BinlogOp
deriving DecidableEq, Repr

/-- A single binary log event combining stream coordinate, operation, and timestamp. -/
structure BinlogEvent where
  pos : BinlogPos
  op  : BinlogOp
  ts  : Nat
deriving DecidableEq, Repr

/-- The operational state transition function of the source MySQL database. -/
def applyBinlogEvent (state : MySQLState) (event : BinlogEvent) : MySQLState :=
  match event.op with
  | BinlogOp.insert k v =>
      fun x => if x == k then some v else state x
  | BinlogOp.update k_old k_new v =>
      if k_old == k_new then
        fun x => if x == k_new then some v else state x
      else
        fun x =>
          if x == k_old then none
          else if x == k_new then some v
          else state x
  | BinlogOp.delete k =>
      fun x => if x == k then none else state x
  | BinlogOp.truncate =>
      fun _ => none

/-- Evaluates an entire sequence of binary log events sequentially against MySQL state. -/
def evalMySQL (events : List BinlogEvent) (init : MySQLState := emptyMySQL) : MySQLState :=
  events.foldl applyBinlogEvent init

end Replication
