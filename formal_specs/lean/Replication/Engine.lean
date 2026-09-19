/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.Binlog
import Replication.ClickHouse

namespace Replication

/--
Deterministic mapping from a binary log coordinate to a 64-bit monotonic ClickHouse version.
Encodes (fileSeq, offset, rowIdx) into a strictly order-preserving integer.
-/
def encodeVersion (pos : BinlogPos) : Nat :=
  pos.fileSeq * 1000000000000 + pos.offset * 10000 + pos.rowIdx

/--
Translates a single MySQL binary log event into one or more ClickHouse ReplacingMergeTree records.
Implements the two-phase tombstone protocol for primary key relocation.
-/
def translateEvent (e : BinlogEvent) : List CHRecord :=
  let v := encodeVersion e.pos
  match e.op with
  | BinlogOp.insert k row =>
      [{ key := k, row := row, version := v, is_deleted := false }]
  | BinlogOp.update k_old k_new row =>
      if k_old == k_new then
        [{ key := k_new, row := row, version := v, is_deleted := false }]
      else
        -- Primary / Sorting Key Relocation: Phase 1 tombstone (v-1) + Phase 2 insert (v)
        [ { key := k_old, row := [],  version := v - 1, is_deleted := true },
          { key := k_new, row := row, version := v,     is_deleted := false } ]
  | BinlogOp.delete k =>
      [{ key := k, row := [], version := v, is_deleted := true }]
  | BinlogOp.truncate =>
      []

/--
Replication Engine State Machine:
Takes a stream of MySQL binary log events and computes the resulting ClickHouse physical table.
-/
def replicateStream (events : List BinlogEvent) : CHTable :=
  events.foldl (fun table ev => table ++ translateEvent ev) emptyCH

end Replication
