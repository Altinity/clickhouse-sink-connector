// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::reset
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::hasUnprovenAbandonment
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::noteAcknowledgedForAbandonment
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::isPipelineQuiescent
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java::failOnExecuteFailed
// request: reset() empties outstandingSequences while the units it abandons are still unacknowledged, and
//          hasUnwrittenBatches() is exactly `!outstandingSequences.isEmpty()`, so the pipeline read as
//          quiescent at the instant its contents were thrown away and the control record committed the
//          connector's CURRENT position -- past rows that never reached ClickHouse (2026-11-10: 23 resets
//          in five hours, each abandoning ~51 units while a missing target table forced the handoff cap)
// spec: specs/09-offset-management/01-fifo-batch-tracking.md, section 3.6
// spec: specs/09-offset-management/04-quiescent-control-commit.md, section 3 and FM-09.04-4
// spec: specs/03-execution-engine/06-jdbc-batch-flushing.md, section 3.2 and FM-03.06-4
//
// TWO PROPERTIES, the same model as specs/lean/AbandonLatch.lean.
//
// 1. THE LATCH. An abandoned unit is one the writer pool was handed and that no worker ever acknowledged.
//    reset() drops every such unit from outstandingSequences, which is the whole of hasUnwrittenBatches(),
//    so the FIFO said "quiescent" at the instant its contents were discarded. The latch is a predicate of
//    its own: armed only by a reset that actually abandoned a unit, cleared only by an acknowledgement of a
//    unit assigned AFTER that reset, and read by the control-record gate and by nothing else. The stop-drain,
//    the DDL-drain wait and the start-time refusal must keep reading an abandoned FIFO as EMPTY, or the drain
//    would hang and the restart that exists to recover from the abandonment would be refused.
//
//    P1 a reset arms the latch exactly when it abandons a unit (or when it was already armed), and neither a
//       handoff nor an acknowledgement ever arms it;
//    P2 an armed latch withholds the control-record commit whatever the FIFO holds;
//    P3 a unit assigned at or after the reset clears the latch, one that predates the watermark does not;
//    P4 the drain predicates keep reading an abandoned FIFO as empty, so the latch never blocks the drain.
//
// 2. THE BATCH ANSWER. The JDBC contract lets a driver report a failed statement inside the int[] that
//    executeBatch() returns instead of throwing. Counting that entry into a log line and then marking every
//    staged row applied reports a batch written when one of its rows is not in ClickHouse. The check runs
//    before any row is marked applied, and only EXECUTE_FAILED fails the chunk: SUCCESS_NO_INFO (-2) is a
//    success whose row count the driver did not know.
//
// Run: dafny verify AbandonLatch.dfy

// ---------------------------------------------------------------- 1. the latch

datatype St = St(outstanding: nat, armed: bool, watermark: nat, nextSeq: nat, ackedSeq: nat)

// Well-formed: the watermark never runs ahead of the counter, and neither does an acknowledgement.
predicate Valid(s: St)
{
  s.watermark <= s.nextSeq && s.ackedSeq <= s.nextSeq
}

// A producer hands a unit off: a fresh sequence, one more outstanding unit. `armed` is untouched.
function Handoff(s: St): St
{
  St(s.outstanding + 1, s.armed, s.watermark, s.nextSeq + 1, s.ackedSeq)
}

// reset(): retire every sequence assigned so far, empty the FIFO, and arm the latch when it abandoned
// something. handoffCounter is deliberately NOT reset, so a post-reset sequence is always at or above
// the watermark.
function Reset(s: St): St
{
  if s.outstanding > 0 then
    St(0, true, s.nextSeq, s.nextSeq, s.ackedSeq)
  else
    St(0, s.armed, s.nextSeq, s.nextSeq, s.ackedSeq)
}

// noteAcknowledgedForAbandonment(seq): a unit assigned at or after the retirement watermark proves the
// redelivered stream reached the FIFO's head, so that is the only acknowledgement that clears the latch.
// The parameter is `sq`, not the Java `seq`: `seq` is a Dafny keyword (the built-in sequence type), and
// using it as a name is a parse error, not a shadowing warning.
function Ack(s: St, sq: nat): St
{
  var a := if sq > s.ackedSeq then sq else s.ackedSeq;
  if s.armed && sq >= s.watermark then
    St(s.outstanding, false, s.watermark, s.nextSeq, a)
  else
    St(s.outstanding, s.armed, s.watermark, s.nextSeq, a)
}

// The control-record gate before this change: the FIFO's emptiness alone.
function OldGate(s: St): bool
{
  s.outstanding == 0
}

// The fixed control-record gate: the FIFO's emptiness AND the latch (spec 09.04 section 3).
function CommitAllowed(s: St): bool
{
  s.outstanding == 0 && !s.armed
}

// The drain predicates -- drainBeforeStop, awaitPipelineQuiescent and the start-time refusal in setup()
// -- which must keep reading an abandoned FIFO as empty (spec 09.01 section 3.6).
function DrainAllowed(s: St): bool
{
  s.outstanding == 0
}

// The replacement engine arrives: a unit handed off after the reset.
function Restart(s: St): St
{
  Handoff(Reset(s))
}

// P1: a reset arms the latch exactly when it abandons a unit, or when something had already armed it.
lemma LatchArmedOnlyOnAbandonment(s: St)
  ensures ((Reset(s)).armed == true) <==> (s.outstanding > 0 || s.armed == true)
{}

// P1: a handoff is a read, not a write: it never arms the latch.
lemma HandoffNeverArms(s: St)
  ensures (Handoff(s)).armed == s.armed
{}

// P1: an acknowledgement never arms the latch; it can only lower it.
lemma AckNeverArms(s: St, sq: nat)
  ensures (Ack(s, sq)).armed == true ==> s.armed == true
{}

// P2: an armed latch withholds the control-record commit, whatever the FIFO holds.
lemma LatchBlocksCommit(s: St)
  requires s.armed == true
  ensures CommitAllowed(s) == false
{}

// P2, over the abandoned state: the FIFO is empty and the commit is still withheld.
lemma FixedGateWithholdsOverAnAbandonment(s: St)
  requires s.outstanding > 0
  ensures (Reset(s)).outstanding == 0
  ensures (Reset(s)).armed == true
  ensures CommitAllowed(Reset(s)) == false
{}

// P2, the pre-fix gate over the same state: emptiness alone commits over the abandonment.
lemma OldGateCommitsOverAnAbandonment(s: St)
  requires s.outstanding > 0
  ensures OldGate(Reset(s)) == true
{}

// P3: a unit assigned at or after the reset is written and acknowledged, so the latch clears.
lemma LatchClearedByPostResetAck(s: St, sq: nat)
  requires (Reset(s)).armed == true
  requires sq >= (Reset(s)).watermark
  ensures (Ack(Reset(s), sq)).armed == false
{}

// P3: a unit that predates the reset proves nothing about the redelivered stream, so the latch stays armed.
lemma PreResetAckKeepsTheLatch(s: St, sq: nat)
  requires s.armed == true
  requires sq < s.watermark
  ensures (Ack(s, sq)).armed == true
{}

// P4: the latch never blocks the drain. A reset always leaves the drain predicates reading empty.
lemma LatchNeverBlocksDrain(s: St)
  ensures DrainAllowed(Reset(s)) == true
{}

// P4: the two predicates are independent -- the drain may proceed while the commit is withheld.
lemma DrainDoesNotReleaseTheCommit(s: St)
  requires s.armed == true
  requires DrainAllowed(s) == true
  ensures CommitAllowed(s) == false
{}

// An engine that abandons a unit and is replaced: Restart leaves the latch armed.
lemma RestartArmed(s: St)
  requires s.outstanding > 0
  ensures (Restart(s)).armed == true
{}

// A reset that abandons nothing leaves an unarmed latch unarmed.
lemma EmptyResetDoesNotArm(s: St)
  requires s.outstanding == 0
  requires s.armed == false
  ensures (Reset(s)).armed == false
{}

// An armed latch survives a later reset that abandons nothing: only an acknowledgement clears it.
lemma ArmedLatchSurvivesAnEmptyReset(s: St)
  requires s.armed == true
  ensures (Reset(s)).armed == true
{}

// The run recorded on 2026-11-10: an engine hands one unit off, is reset before any worker writes it.
function AbandonedRun(): St
{
  Reset(Handoff(St(0, false, 0, 0, 0)))
}

// Witness: the abandoned FIFO reads empty, and the two predicates disagree about committing over it.
lemma AbandonedRunIsEmptyAndArmed()
  ensures (AbandonedRun()).outstanding == 0
  ensures OldGate(AbandonedRun()) == true
  ensures CommitAllowed(AbandonedRun()) == false
  ensures DrainAllowed(AbandonedRun()) == true
{}

// ---------------------------------------------------------------- 2. the batch answer

// java.sql.Statement.EXECUTE_FAILED: the statement did not happen.
const EXECUTE_FAILED: int := -3
// java.sql.Statement.SUCCESS_NO_INFO: the statement happened and the driver did not know the row count.
const SUCCESS_NO_INFO: int := -2

// Entries the driver flagged as not executed, one per staged statement.
function FailedCount(r: seq<int>): nat
  decreases |r|
{
  if |r| == 0 then 0
  else (if r[0] == EXECUTE_FAILED then 1 else 0) + FailedCount(r[1..])
}

// failOnExecuteFailed: the chunk is reported written only when the driver flagged nothing.
function BatchReportedWritten(r: seq<int>): bool
{
  FailedCount(r) == 0
}

// Rows marked setAppliedToClickHouse(true): the check runs BEFORE the marking, so a flagged chunk marks none.
function RowsMarkedApplied(r: seq<int>): nat
{
  if FailedCount(r) == 0 then |r| else 0
}

// The pre-fix executor: the flagged entry was counted into the INFO line and the chunk still counted written.
function OldBatchReportedWritten(r: seq<int>): bool
{
  true
}

lemma AFlaggedEntryFailsTheBatch()
  ensures BatchReportedWritten([EXECUTE_FAILED]) == false
{
  assert FailedCount([EXECUTE_FAILED]) == 1;
}

lemma AFlaggedEntryMarksNoRowApplied()
  ensures RowsMarkedApplied([EXECUTE_FAILED]) == 0
{
  assert FailedCount([EXECUTE_FAILED]) == 1;
}

lemma ACleanChunkIsReportedWritten()
  ensures BatchReportedWritten([1, 2, 3]) == true
{
  assert FailedCount([1, 2, 3]) == 0;
}

lemma SuccessNoInfoDoesNotFailTheBatch()
  ensures SUCCESS_NO_INFO == -2
  ensures BatchReportedWritten([SUCCESS_NO_INFO]) == true
{
  assert FailedCount([SUCCESS_NO_INFO]) == 0;
}

lemma OneFlaggedEntryFailsAMixedChunk()
  ensures BatchReportedWritten([1, EXECUTE_FAILED, 3]) == false
{
  // One unfold per element: the middle entry is the only one the driver flagged.
  assert FailedCount([1, EXECUTE_FAILED, 3]) == 0 + FailedCount([EXECUTE_FAILED, 3]);
  assert FailedCount([EXECUTE_FAILED, 3]) == 1 + FailedCount([3]);
  assert FailedCount([3]) == 0;
}

lemma OldExecutorReportsAFlaggedChunkWritten()
  ensures OldBatchReportedWritten([EXECUTE_FAILED]) == true
{}

// The general form: a chunk the driver flagged is not reported written, and marks no row applied.
lemma EveryFlaggedChunkIsUnwritten(r: seq<int>)
  requires FailedCount(r) > 0
  ensures BatchReportedWritten(r) == false
  ensures RowsMarkedApplied(r) == 0
{}

// A chunk the driver flagged nothing in is reported written in full.
lemma ACleanChunkMarksEveryRowApplied(r: seq<int>)
  requires FailedCount(r) == 0
  ensures BatchReportedWritten(r) == true
  ensures RowsMarkedApplied(r) == |r|
{}

// FailedCount never exceeds the number of entries the driver answered.
lemma FailedCountIsBounded(r: seq<int>)
  ensures FailedCount(r) <= |r|
{
  if r != [] {
    FailedCountIsBounded(r[1..]);
  }
}
