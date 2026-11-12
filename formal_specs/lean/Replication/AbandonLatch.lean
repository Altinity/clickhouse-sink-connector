/-
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::reset
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::isPipelineQuiescent
request: reset() empties outstandingSequences while the units it abandons are still unacknowledged, and
hasUnwrittenBatches() is exactly `!outstandingSequences.isEmpty()`. The instant the FIFO was emptied the pipeline
read as quiescent, so the next control record -- whose offset is the connector's CURRENT position, past the
abandoned rows -- was committed, and a restart after that commit resumed AFTER them, so the rows were never
written. On 2026-11-10 one deployment logged 23 such resets in five hours while a missing target table forced the
handoff hard cap.
property: P1 a reset that abandons at least one unit arms the latch, and no other transition ever arms it (an armed
latch also survives a later reset that abandons nothing: only an acknowledgement clears it). P2 an armed latch
withholds the control-record commit, whatever the FIFO holds. P3 a unit assigned at or after the reset clears the
latch; a unit that predates the retirement watermark does not. P4 the drain predicates keep reading an abandoned
FIFO as empty: the latch never blocks the stop-drain, the DDL-drain wait or the start-time refusal in setup(),
because a latch folded into hasUnwrittenBatches() would hang the drain and refuse the restart that exists to
recover from the abandonment.
model: the state is the FIFO's count of outstanding units, the latch flag, the retirement watermark reset() stamps
(every sequence below it belongs to the replaced engine) and the counter that assigns sequences. Bounds: none; the
properties hold for every count and every sequence. The pre-fix gate is kept as a passing witness
(`old_gate_commits_over_an_abandonment`): the FIFO's emptiness alone commits over an unproven abandonment.
-/

namespace AbandonLatch

/-- The state the latch decision reads and writes. -/
structure St where
  /-- Units handed off and neither acknowledged nor retired: `outstandingSequences.size()`. -/
  outstanding : Nat
  /-- `hasUnprovenAbandonment()`. -/
  armed : Bool
  /-- `unprovenAbandonmentBelowSequence`: every sequence below it belongs to the replaced engine. -/
  watermark : Nat
  /-- `handoffCounter`: the sequence the next handoff is assigned. -/
  nextSeq : Nat
  /-- The highest sequence a worker has reported durably written and acknowledged. -/
  ackedSeq : Nat
deriving Repr, DecidableEq

/-- Well-formed: the watermark never runs ahead of the counter, and neither does an acknowledgement. -/
def Valid (s : St) : Prop := s.watermark ≤ s.nextSeq ∧ s.ackedSeq ≤ s.nextSeq

/-- A producer hands a unit off: a fresh sequence, one more outstanding unit. `armed` is untouched. -/
def handoff (s : St) : St :=
  { s with outstanding := s.outstanding + 1, nextSeq := s.nextSeq + 1 }

/-- `reset()`: retire every sequence assigned so far, empty the FIFO, and arm the latch when it abandoned
    something. `handoffCounter` is deliberately NOT reset, so sequences stay unique for the life of the JVM. -/
def reset (s : St) : St :=
  if s.outstanding > 0 then
    { s with outstanding := 0, watermark := s.nextSeq, armed := true }
  else
    { s with outstanding := 0, watermark := s.nextSeq }

/-- A worker reports its unit durably written and acknowledged: `noteAcknowledgedForAbandonment(seq)`. A unit
    assigned at or after the retirement watermark proves the redelivered stream reached the FIFO's head. -/
def ack (s : St) (seq : Nat) : St :=
  if s.armed = true ∧ seq ≥ s.watermark then
    { s with ackedSeq := max seq s.ackedSeq, armed := false }
  else
    { s with ackedSeq := max seq s.ackedSeq }

/-- The pre-fix control-record gate: the FIFO's emptiness alone. -/
def oldGate (s : St) : Bool := decide (s.outstanding = 0)

/-- The fixed control-record gate: the FIFO's emptiness AND the latch (spec 09.04 section 3). -/
def commitAllowed (s : St) : Bool := decide (s.outstanding = 0) && !s.armed

/-- The drain predicates -- `drainBeforeStop`, `awaitPipelineQuiescent` and the start-time refusal in `setup()`
    -- which must keep reading an abandoned FIFO as empty (spec 09.01 section 3.6). -/
def drainAllowed (s : St) : Bool := decide (s.outstanding = 0)

/-- The replacement engine arrives: a unit handed off after the reset. -/
def restart (s : St) : St := handoff (reset s)

/-- P1: a reset arms the latch exactly when it abandons a unit, or when something had already armed it. -/
theorem latch_armed_only_on_abandonment (s : St) :
    (reset s).armed = true ↔ (s.outstanding > 0 ∨ s.armed = true) := by
  unfold reset
  by_cases h : s.outstanding > 0 <;> simp [h]

/-- P1: a handoff is a read, not a write: it never arms the latch. -/
theorem handoff_never_arms (s : St) : (handoff s).armed = s.armed := by
  simp [handoff]

/-- P1: an acknowledgement never arms the latch; it can only lower it. -/
theorem ack_never_arms (s : St) (seq : Nat) : (ack s seq).armed = true → s.armed = true := by
  unfold ack
  by_cases h : s.armed = true ∧ seq ≥ s.watermark <;> simp [h]

/-- P2: an armed latch withholds the control-record commit, whatever the FIFO holds. -/
theorem latch_blocks_commit (s : St) (h : s.armed = true) : commitAllowed s = false := by
  unfold commitAllowed
  rw [h]
  simp

/-- P2, the fixed gate over the abandoned state: the FIFO is empty and the commit is still withheld. -/
theorem fixed_gate_withholds_over_an_abandonment (s : St) (h : s.outstanding > 0) :
    commitAllowed (reset s) = false := by
  have harmed : (reset s).armed = true := (latch_armed_only_on_abandonment s).mpr (Or.inl h)
  exact latch_blocks_commit (reset s) harmed

/-- P3: a unit assigned at or after the reset is written and acknowledged, so the latch clears. -/
theorem latch_cleared_by_post_reset_ack (s : St) (seq : Nat)
    (harmed : (reset s).armed = true) (hge : seq ≥ (reset s).watermark) :
    (ack (reset s) seq).armed = false := by
  unfold ack
  simp [harmed, hge]

/-- P3: a unit that predates the reset proves nothing about the redelivered stream, so the latch stays armed. -/
theorem pre_reset_ack_keeps_the_latch (s : St) (seq : Nat)
    (harmed : s.armed = true) (hlt : seq < s.watermark) :
    (ack s seq).armed = true := by
  unfold ack
  simp [harmed, Nat.not_le.mpr hlt]

/-- P4: the latch never blocks the drain. A reset always leaves the drain predicates reading empty, armed or
    not -- the drain and `setup()` must act on the abandoned FIFO as if nothing were outstanding. -/
theorem latch_never_blocks_drain (s : St) : drainAllowed (reset s) = true := by
  unfold drainAllowed reset
  by_cases h : s.outstanding > 0 <;> simp [h]

/-- P4: the two predicates are independent -- the drain may proceed while the commit is withheld. -/
theorem drain_does_not_release_the_commit (s : St) (h : s.armed = true) :
    drainAllowed s = true → commitAllowed s = false := by
  intro _
  exact latch_blocks_commit s h

/-- An engine that abandons a unit and is replaced: `restart` leaves the latch armed. -/
theorem restart_armed (s : St) (h : s.outstanding > 0) :
    (restart s).armed = true := by
  unfold restart
  rw [handoff_never_arms]
  exact (latch_armed_only_on_abandonment s).mpr (Or.inl h)

/-- The run recorded on 2026-11-10: an engine hands one unit off, is reset before any worker writes it, and the
    replacement engine's heartbeat arrives. Pre-fix the FIFO's emptiness alone committed the CURRENT position. -/
def abandonedRun : St := reset (handoff ⟨0, false, 0, 0, 0⟩)

/-- Witness for the pre-fix behaviour: with the latch absent from the gate, the abandoned FIFO reads quiescent
    and the heartbeat commits past rows that never reached ClickHouse. -/
theorem old_gate_commits_over_an_abandonment :
    abandonedRun.outstanding = 0 ∧ oldGate abandonedRun = true := by
  decide

/-- The fixed gate withholds the same commit for the same input. -/
theorem fixed_gate_withholds_the_same_commit :
    abandonedRun.armed = true ∧ commitAllowed abandonedRun = false := by
  decide

/-- The drain still proceeds over that abandoned state, so the replacement engine starts. -/
theorem abandoned_run_still_drains : drainAllowed abandonedRun = true := by
  decide

end AbandonLatch
