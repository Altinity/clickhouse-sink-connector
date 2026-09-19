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
Position-order-preserving encoding of a binary log coordinate into a single
integer, kept for documentation of the coordinate ordering. Real deployments do
NOT version by position (the connector versions by source commit time and an
intra-second sequence counter); this encoding is a faithful *order* witness only
within its well-formed domain (see `BinlogPos.WellFormed` and
`version_strictly_monotonic`). The replication engine below assigns versions by
stream ordinal, which realises the same invariant — strictly increasing version
in commit order — without depending on the magnitude of the coordinate.
-/
def encodeVersion (pos : BinlogPos) : Nat :=
  pos.fileSeq * 1000000000000000000 + pos.offset * 100000 + pos.rowIdx

/--
Validity domain of `encodeVersion`: the offset and row index fit under the
multipliers so the lexicographic order is preserved. Chosen generously
(offset < 10^13 covers multi-gigabyte binary log files; rowIdx < 10^5 covers any
single row-based event). Within this domain `encodeVersion` is strictly
monotonic (proved in `version_strictly_monotonic`).
-/
def BinlogPos.WellFormed (p : BinlogPos) : Prop :=
  p.offset < 10000000000000 ∧ p.rowIdx < 100000

/--
The version assigned to the live record produced at 1-based stream ordinal `i`.
Using `2 * i` leaves the odd slot `2 * i - 1` for the tombstone half of a
primary-key relocation, so the tombstone strictly exceeds every version issued
before ordinal `i` (which are all `≤ 2 * (i - 1)`) yet stays below its paired
live row. This is the abstract form of the connector's guarantee that each
committed event receives a strictly greater `_version` than every event before
it.
-/
def liveVersion (i : Nat) : Nat := 2 * i

/-- The tombstone version paired with `liveVersion i` for a relocation at ordinal `i`. -/
def tombstoneVersion (i : Nat) : Nat := 2 * i - 1

/--
Translates a single MySQL binary log event, at 1-based stream ordinal `i`, into
the ClickHouse ReplacingMergeTree records it produces. Implements the two-phase
tombstone protocol for primary-key relocation. A table-clear event produces no
records here; it is handled by `replicateFrom`, which resets the accumulated
table (the formal-model image of the connector clearing the ClickHouse table).
-/
def translateEventAt (i : Nat) (e : BinlogEvent) : List CHRecord :=
  match e.op with
  | BinlogOp.insert k row =>
      [{ key := k, row := row, version := liveVersion i, is_deleted := false }]
  | BinlogOp.update k_old k_new row =>
      if k_old == k_new then
        [{ key := k_new, row := row, version := liveVersion i, is_deleted := false }]
      else
        [ { key := k_old, row := [],  version := tombstoneVersion i, is_deleted := true },
          { key := k_new, row := row, version := liveVersion i,      is_deleted := false } ]
  | BinlogOp.delete k =>
      [{ key := k, row := [], version := liveVersion i, is_deleted := true }]
  -- DESTRUCTIVE: formal-model arm only — the `truncate` constructor maps to the
  -- empty record list; no data is destroyed (this is a Lean term, not SQL).
  | BinlogOp.truncate =>
      []

/--
Replication engine as a tail recursion carrying the next stream ordinal `i` and
the accumulated ClickHouse table. A table-clear event resets the accumulator in
lock-step with `applyBinlogEvent` emptying the source; every other event appends
its translated records with strictly increasing versions.
-/
def replicateFrom (i : Nat) (acc : CHTable) : List BinlogEvent → CHTable
  | [] => acc
  | e :: rest =>
      match e.op with
      -- DESTRUCTIVE: formal-model arm — "clearing" is resetting the accumulator
      -- list to `[]` in a proof; no ClickHouse table is touched.
      | BinlogOp.truncate => replicateFrom (i + 1) [] rest
      | _                 => replicateFrom (i + 1) (acc ++ translateEventAt i e) rest

/--
Replication Engine State Machine: takes a stream of MySQL binary log events and
computes the resulting ClickHouse physical table. Ordinals start at 1 so the
first tombstone version (`2*1 - 1 = 1`) is positive.
-/
def replicateStream (events : List BinlogEvent) : CHTable :=
  replicateFrom 1 emptyCH events

end Replication
