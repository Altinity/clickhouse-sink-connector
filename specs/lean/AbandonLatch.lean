/-
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::reset
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::hasUnprovenAbandonment
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::noteAcknowledgedForAbandonment
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::commitControlRecordOffset
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::isPipelineQuiescent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java::failOnExecuteFailed
request: reset() empties outstandingSequences while the units it abandons are still unacknowledged, and
hasUnwrittenBatches() is exactly `!outstandingSequences.isEmpty()`. The instant the FIFO was emptied the
pipeline read as quiescent, so the next control record -- whose offset is the connector's CURRENT position,
past the abandoned rows -- was committed, and a restart after that commit resumed AFTER them. On 2026-11-10
one deployment logged 23 such resets in five hours while a missing target table forced the handoff hard cap.
Separately, a driver-reported `Statement.EXECUTE_FAILED` entry was only counted into an INFO line while every
staged row was marked applied, so the batch was reported written and its offset acknowledged past the row.
spec: specs/09-offset-management/01-fifo-batch-tracking.md, section 3.6
spec: specs/09-offset-management/04-quiescent-control-commit.md, section 3 and FM-09.04-4
spec: specs/03-execution-engine/06-jdbc-batch-flushing.md, section 3.2 and FM-03.06-4

TWO CHANGES, TWO PROPERTIES.

1. THE LATCH (spec 09.01 section 3.6, spec 09.04 FM-09.04-4). An abandoned unit is one the writer pool was
   handed and that no worker ever acknowledged. `reset()` drops every such unit from `outstandingSequences`,
   which is the whole of `hasUnwrittenBatches()`, so the FIFO said "quiescent" at the instant its contents
   were thrown away and the control record could commit the CURRENT position -- past rows that never reached
   ClickHouse. The latch is a predicate of its own, armed only by a reset that actually abandoned a unit and
   cleared only by an acknowledgement of a unit assigned AFTER that reset (the first moment a post-reset write
   proves the redelivered stream reached the FIFO's head). It is read by the control-record gate and by
   nothing else: the stop-drain, the DDL-drain wait and the start-time refusal must keep reading an abandoned
   FIFO as EMPTY, or the drain would hang and the restart that exists to recover from the abandonment would
   be refused.

   What is proved, for every FIFO depth and every sequence (no theorem, no axiom, no `sorry`):
     P1  a reset arms the latch exactly when it abandons a unit (or when it was already armed), and neither a
         handoff nor an acknowledgement ever arms it;
     P2  an armed latch withholds the control-record commit whatever the FIFO holds;
     P3  a unit assigned at or after the reset clears the latch, a unit that predates the watermark does not;
     P4  the drain predicates keep reading an abandoned FIFO as empty, so the latch never blocks the drain.

2. THE BATCH ANSWER (spec 03.06 section 3.2, FM-03.06-4). The JDBC contract lets a driver report a failed
   statement inside the `int[]` that `executeBatch()` returns instead of throwing. Counting that entry into a
   log line and then marking every staged row applied reports a batch written when one of its rows is not in
   ClickHouse. The check runs before any row is marked applied, and only `EXECUTE_FAILED` fails the chunk --
   `SUCCESS_NO_INFO` (-2) is a success whose row count the driver did not know.

Only Lean core is used; every proof is kernel-checked. The same model is restated in
specs/dafny/AbandonLatch.dfy, specs/tla/AbandonLatch.tla (the behavioural half: the offset never leads the
writes across a sequence of handoffs, resets and commits) and specs/hypothesis/test_abandon_latch.py (the
random walk over the real transition function). The lake-built library model is
formal_specs/lean/Replication/AbandonLatch.lean.
Run: `lean AbandonLatch.lean`.
-/

namespace AbandonLatch

/-! ## 1. The latch -/

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
    something. `handoffCounter` is deliberately NOT reset, so sequences stay unique for the life of the JVM
    and a post-reset sequence is always at or above the watermark. -/
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

/-- The drain predicates -- `drainBeforeStop`, `awaitPipelineQuiescent` and the start-time refusal in
    `setup()` -- which must keep reading an abandoned FIFO as empty (spec 09.01 section 3.6). -/
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

/-- P2, over the abandoned state: the FIFO is empty and the commit is still withheld. -/
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

/-! ## 2. The driver's batch answer -/

/-- `java.sql.Statement.EXECUTE_FAILED`: the statement did not happen. -/
def EXECUTE_FAILED : Int := -3

/-- `java.sql.Statement.SUCCESS_NO_INFO`: the statement happened and the driver did not know the row count. -/
def SUCCESS_NO_INFO : Int := -2

/-- The driver's answer for one executed chunk: one entry per staged statement. -/
abbrev BatchResult := List Int

/-- Entries the driver flagged as not executed. -/
def failedCount : BatchResult → Nat
  | [] => 0
  | x :: xs => (if x = EXECUTE_FAILED then 1 else 0) + failedCount xs

/-- `failOnExecuteFailed`: the chunk is reported written only when the driver flagged nothing. -/
def batchReportedWritten (r : BatchResult) : Bool := decide (failedCount r = 0)

/-- The pre-fix executor: the flagged entry was counted into the INFO line and the chunk still counted as
    written, so every staged row was marked applied. -/
def oldBatchReportedWritten (_ : BatchResult) : Bool := true

/-- Rows marked `setAppliedToClickHouse(true)`: every staged row, but only when the chunk is reported
    written. The check runs BEFORE the marking, which is what keeps a flagged row out of the acked set. -/
def rowsMarkedApplied (r : BatchResult) : Nat :=
  if failedCount r = 0 then r.length else 0

/-- A single `EXECUTE_FAILED` entry fails the chunk. -/
theorem a_flagged_entry_fails_the_batch : batchReportedWritten [EXECUTE_FAILED] = false := by
  decide

/-- ... and leaves no row marked applied, so the offset cannot be acknowledged past it. -/
theorem a_flagged_entry_marks_no_row_applied : rowsMarkedApplied [EXECUTE_FAILED] = 0 := by
  decide

/-- A chunk whose entries are all row counts is reported written. -/
theorem a_clean_chunk_is_reported_written : batchReportedWritten [1, 2, 3] = true := by
  decide

/-- `SUCCESS_NO_INFO` is a success: only `EXECUTE_FAILED` fails the chunk. -/
theorem success_no_info_does_not_fail_the_batch : batchReportedWritten [SUCCESS_NO_INFO] = true := by
  decide

/-- One flagged entry among clean ones is still a failure. -/
theorem one_flagged_entry_fails_a_mixed_chunk : batchReportedWritten [1, EXECUTE_FAILED, 3] = false := by
  decide

/-- The pre-fix executor reported that same chunk written. -/
theorem old_executor_reports_a_flagged_chunk_written :
    oldBatchReportedWritten [EXECUTE_FAILED] = true := by
  decide

/-- The general form: a chunk the driver flagged is not reported written. -/
theorem every_flagged_chunk_is_unwritten (r : BatchResult) (h : failedCount r > 0) :
    batchReportedWritten r = false := by
  unfold batchReportedWritten
  by_cases hc : failedCount r = 0
  · omega
  · simp [hc]

/-- ... and is never marked applied. -/
theorem a_flagged_chunk_marks_no_row_applied (r : BatchResult) (h : failedCount r > 0) :
    rowsMarkedApplied r = 0 := by
  unfold rowsMarkedApplied
  by_cases hc : failedCount r = 0
  · omega
  · simp [hc]

/-- A chunk the driver flagged nothing in is reported written in full. -/
theorem a_clean_chunk_marks_every_row_applied (r : BatchResult) (h : failedCount r = 0) :
    rowsMarkedApplied r = r.length := by
  unfold rowsMarkedApplied
  simp [h]

end AbandonLatch
