/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.Binlog
import Replication.ClickHouse
import Replication.Engine

namespace Replication

/--
Well-formedness condition for a binary log stream:
All events in the stream must possess strictly ascending binary log coordinates.
-/
def StrictlyMonotonicStream : List BinlogEvent → Prop
  | [] => True
  | [_] => True
  | e1 :: e2 :: rest =>
      e1.pos < e2.pos ∧ StrictlyMonotonicStream (e2 :: rest)

/--
Invariant I1 & I2: Deterministic Version Monotonicity.
A strictly advancing binary log coordinate guarantees a strictly greater version number.
-/
def VersionMonotonicityProp (p1 p2 : BinlogPos) : Prop :=
  p1 < p2 → encodeVersion p1 < encodeVersion p2

/--
Invariant I3: Master Replication Convergence.
For any stream of events, evaluating ClickHouse with ReplacingMergeTree `FINAL` semantics
yields the exact same row state as executing the events on the source MySQL database.
-/
def ReplicationConvergence (events : List BinlogEvent) : Prop :=
  ∀ k : Key, chFinalView (replicateStream events) k = evalMySQL events emptyMySQL k

/--
Invariant I4: Sorting Key Relocation Soundness.
When an update relocates a primary key from k_old to k_new (where k_old ≠ k_new),
ClickHouse FINAL view reflects `none` on k_old and `some row` on k_new.
-/
def PKRelocationSoundness (events : List BinlogEvent) (k_old k_new : Key) (row : Row) (pos : BinlogPos) : Prop :=
  k_old ≠ k_new →
  let ev := { pos := pos, op := BinlogOp.update k_old k_new row, ts := 0 }
  let table := replicateStream (events ++ [ev])
  chFinalView table k_old = none ∧ chFinalView table k_new = some row

/--
Replay Idempotency (redelivery stability, spec 02.04; supports Invariant I3 under
at-least-once delivery). This is NOT Constitution Invariant I8 — I8 is Durable
Offset Quiescence, which is modelled on the control-record side in `Snapshot.lean`.
Re-executing an earlier prefix of the binlog stream does not mutate or regress the
current FINAL state. Stated as a proposition only; no theorem proves it yet.
-/
def ReplayIdempotency (events replayed : List BinlogEvent) : Prop :=
  (∀ e ∈ replayed, ∃ e' ∈ events, e.pos.fileSeq < e'.pos.fileSeq ∨ (e.pos.fileSeq = e'.pos.fileSeq ∧ e.pos.offset ≤ e'.pos.offset)) →
  ∀ k : Key, chFinalView (replicateStream (events ++ replayed)) k = chFinalView (replicateStream events) k

end Replication
