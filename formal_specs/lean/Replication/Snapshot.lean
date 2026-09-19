/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# Snapshot completion & control-record offset commit (Invariant I12, issue #1379)

Debezium marks a snapshot complete only AFTER the last snapshot row is emitted, so
every snapshot ROW still carries `snapshot_completed = false`; the completed state
rides on a record emitted AFTER the snapshot — on an idle source, exclusively on a
heartbeat / transaction-boundary record that produces NO ClickHouse row. If that
control record's offset is never committed, `snapshot_completed = false` is
persisted forever and every restart re-runs the whole snapshot (issue #1379).

Two things must hold for the flow to be correct:

* **Safety** — a control-record offset is committed ONLY when the pipeline is
  quiescent (no rows handed to the writers are still unwritten). Committing a
  control offset while rows are outstanding would advance the durable position
  past data not yet in ClickHouse and lose it on a crash (issue #1285).
* **Liveness / progress** — a control record that produces no row, seen while the
  pipeline is quiescent, DOES have its offset committed. This is what lets the
  end-of-snapshot state persist and the snapshot terminate.

This module models the offset-commit pipeline abstractly and proves both, plus the
end-to-end #1379 property: after the snapshot's rows are written, the
end-of-snapshot control record commits its offset.
-/

namespace Replication

/-- Abstract state of the offset-commit pipeline.
    `outstanding` = rows handed to the writers but not yet written+acknowledged;
    `committed`   = the source offset durably committed to the offset store. -/
structure Pipe where
  outstanding : Nat
  committed : Nat
deriving Repr, DecidableEq

/-- Pipeline events. -/
inductive PEvent where
  | handoff : PEvent            -- a data row handed to the writers (unwritten++)
  | ackAll  : PEvent            -- the writers persist & acknowledge all in-flight rows
  | control : Nat → PEvent      -- a no-row record (heartbeat / tx boundary) at source offset p
deriving Repr

/--
One pipeline step. A `control p` record commits its source offset ONLY when the
pipeline is quiescent (`outstanding = 0`) — exactly the guard in
`commitControlRecordOffset` (`handedOffRows || !isPipelineQuiescent()` → skip).
A data row (`handoff`) never moves the committed offset; it is committed through
the row path once written (modelled by `ackAll` clearing the outstanding set).
-/
def step (s : Pipe) : PEvent → Pipe
  | PEvent.handoff   => { s with outstanding := s.outstanding + 1 }
  | PEvent.ackAll    => { s with outstanding := 0 }
  | PEvent.control p => if s.outstanding = 0 then { s with committed := p } else s

/-- Fold the pipeline over a sequence of events. -/
def run (s : Pipe) (evs : List PEvent) : Pipe := evs.foldl step s

/-! ## Safety -/

/--
**Safety.** If processing a control record changed the committed offset, the
pipeline was quiescent (`outstanding = 0`) — i.e. a control offset is never
committed while rows are still unwritten, so the durable position never advances
past data not yet in ClickHouse.
-/
theorem control_commit_safe (s : Pipe) (p : Nat)
    (h : (step s (PEvent.control p)).committed ≠ s.committed) : s.outstanding = 0 := by
  by_cases ho : s.outstanding = 0
  · exact ho
  · exfalso; apply h; simp [step, ho]

/--
While any row is outstanding, a control record leaves the committed offset
unchanged (no progress past unwritten data).
-/
theorem committed_stable_while_outstanding (s : Pipe) (p : Nat) (h : 0 < s.outstanding) :
    (step s (PEvent.control p)).committed = s.committed := by
  have : s.outstanding ≠ 0 := by omega
  simp [step, this]

/-! ## Liveness -/

/--
**Liveness.** A control record seen while the pipeline is quiescent commits its
offset. This is the property whose absence is issue #1379: without it the
end-of-snapshot heartbeat never advances the offset and `snapshot_completed`
never persists.
-/
theorem quiescent_control_commits (s : Pipe) (p : Nat) (h : s.outstanding = 0) :
    (step s (PEvent.control p)).committed = p := by
  simp [step, h]

/-! ## End-to-end: the snapshot terminates -/

/-- Handing off rows never changes the committed offset. -/
theorem handoffs_preserve_committed (s : Pipe) (n : Nat) :
    (run s (List.replicate n PEvent.handoff)).committed = s.committed := by
  induction n generalizing s with
  | zero => rfl
  | succ m ih =>
      rw [List.replicate_succ]
      have hstep := ih (step s PEvent.handoff)
      simpa [run, List.foldl_cons, step] using hstep

/--
**Issue #1379, end to end.** After the snapshot's `rows` rows are handed off and
then all written (`ackAll`), the end-of-snapshot control record at `snapPos`
commits its offset: `committed = snapPos`. So the snapshot's completed state is
durably persisted and a restart does NOT re-run the snapshot.
-/
theorem snapshot_completes (s : Pipe) (rows snapPos : Nat) :
    (run s (List.replicate rows PEvent.handoff
              ++ [PEvent.ackAll, PEvent.control snapPos])).committed = snapPos := by
  unfold run
  rw [List.foldl_append]
  simp [List.foldl_cons, List.foldl_nil, step]

end Replication
