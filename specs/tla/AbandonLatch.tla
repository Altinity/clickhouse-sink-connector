---------------------------- MODULE AbandonLatch ----------------------------
(***************************************************************************)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::reset *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::hasUnprovenAbandonment *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::noteAcknowledgedForAbandonment *)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::isPipelineQuiescent *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java::failOnExecuteFailed *)
(* request: reset() empties outstandingSequences while the units it abandons  *)
(*          are still unacknowledged, so the pipeline read as quiescent and  *)
(*          the control record committed the connector's CURRENT position,   *)
(*          past rows that never reached ClickHouse (2026-11-10: 23 resets   *)
(*          in five hours while a missing target table forced the cap)      *)
(* spec: specs/09-offset-management/01-fifo-batch-tracking.md, section 3.6   *)
(* spec: specs/09-offset-management/04-quiescent-control-commit.md,          *)
(*       section 3 and FM-09.04-4                                          *)
(*                                                                          *)
(* THE BEHAVIOURAL HALF. specs/lean and specs/dafny prove the latch as a pure *)
(* function of five fields. What they cannot state is the property that      *)
(* matters operationally, because it is about a SEQUENCE of states: the      *)
(* durable offset must never lead the writes -- the owning DBA's invariant,  *)
(* the offset is always late, never early. This module puts the connector in *)
(* flight across a stop and a restart and lets TLC search for a state where  *)
(* the control-record gate is open over a position that is not in ClickHouse. *)
(*                                                                          *)
(* THE MODEL. `nextPos` is the reader's position: 0 .. nextPos-1 have been    *)
(* handed to the writer pool. `covered` holds the positions whose rows are    *)
(* in ClickHouse. `queue` is the set of positions handed off and not yet      *)
(* acknowledged -- the offset FIFO, abstracted as a set because the safety    *)
(* property below does not depend on the acknowledgement ORDER, and a set     *)
(* admits every order at once. `armed` is the latch and `committed` is the    *)
(* binlog position the durable offset holds.                                  *)
(*                                                                          *)
(* THE WINDOW THE DEFECT LIVES IN. `ResetAbandon` is reset(): the queue is    *)
(* emptied in ONE step while positions below the reader's head are not in     *)
(* ClickHouse, and the latch is armed. Between that step and the re-read      *)
(* (`Redeliver`) the queue is EMPTY. The pre-fix gate read that emptiness as  *)
(* quiescence and committed the CURRENT position there, which is the bug.     *)
(* The fixed gate also reads the latch, and the latch is released by         *)
(* `ClearLatch` only once the re-read has delivered every abandoned position  *)
(* -- the effect of the acknowledgement of a unit assigned after the reset,    *)
(* which is the only acknowledgement the code accepts as proof.               *)
(*                                                                          *)
(* The binlog is read sequentially, so `Handoff` is guarded by                *)
(* `Gaps \subseteq queue`: the reader cannot move past a position it has not   *)
(* delivered, and after a reset the replacement engine re-reads the abandoned *)
(* positions before it does. Without that guard the model would let a fresh   *)
(* unit be delivered and acknowledged over an abandoned one, which no reader  *)
(* can do.                                                                   *)
(*                                                                          *)
(* TLC checks it inside the bound MaxPos = 4.                                *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS MaxPos

Positions == 0 .. (MaxPos - 1)

VARIABLES nextPos, covered, queue, armed, committed

vars == <<nextPos, covered, queue, armed, committed>>

\* Positions read so far and not in ClickHouse: the region a reset can abandon and a re-read must cover.
Gaps == { p \in 0 .. (nextPos - 1) : p \notin covered }

\* The fixed control-record gate: the FIFO's emptiness AND the latch (spec 09.04 section 3).
GateOpen == queue = {} /\ armed = FALSE

\* The drain predicates -- drainBeforeStop, awaitPipelineQuiescent and the start-time refusal in
\* setup(). The FIFO is the only thing they read; the latch is deliberately NOT one of them, or the
\* drain would hang and the restart that exists to recover would be refused (spec 09.01 section 3.6).
DrainReady == queue = {}

\* Every position below the reader's head is in ClickHouse.
AllCoveredUpToHead == Gaps = {}

\* The safety property: every position the durable offset covers is in ClickHouse.
CommittedIsCovered == \A p \in Positions : p <= committed => p \in covered

Init == /\ nextPos = 0
        /\ covered = {}
        /\ queue = {}
        /\ armed = FALSE
        /\ committed = -1

\* The reader hands the next position to the writer pool.
Handoff == /\ nextPos < MaxPos
           /\ Gaps \subseteq queue
           /\ queue' = queue \cup { nextPos }
           /\ nextPos' = nextPos + 1
           /\ UNCHANGED <<covered, armed, committed>>

\* A worker reports its unit durably written.
Write == /\ UNCHANGED <<nextPos, queue, armed, committed>>
         /\ \E p \in Positions : p \in queue /\ p \notin covered
                                 /\ covered' = covered \cup { p }

\* A worker reports its unit acknowledged. Only a unit whose rows are in ClickHouse can be acknowledged,
\* which is what keeps an acknowledged unit out of `Gaps`. The latch is NOT released here: an
\* acknowledgement alone says nothing about the units the reset abandoned.
Ack == /\ UNCHANGED <<nextPos, covered, armed, committed>>
       /\ \E p \in queue : p \in covered /\ queue' = queue \ { p }

\* reset(): the engine is stopped or restarted with units in flight. The queue is emptied -- that is the
\* abandonment the durable offset must not commit over -- and the latch is armed. The offset does not move.
ResetAbandon == /\ queue # {}
                /\ queue' = {}
                /\ armed' = TRUE
                /\ UNCHANGED <<nextPos, covered, committed>>

\* The replacement engine re-reads the binlog from the first position not in ClickHouse and hands every
\* abandoned position off again (delivery is at-least-once). This is the step that closes the window
\* `ResetAbandon` opens.
Redeliver == /\ ~(Gaps \subseteq queue)
             /\ queue' = queue \cup Gaps
             /\ UNCHANGED <<nextPos, covered, armed, committed>>

\* The latch is released once the re-read has delivered every abandoned position: from here a commit is
\* again over positions that are all either in ClickHouse or in the queue. A reset that abandoned nothing
\* (`Gaps = {}`) releases it on the next step, which is correct -- there is nothing to prove.
ClearLatch == /\ armed
              /\ Gaps \subseteq queue
              /\ armed' = FALSE
              /\ UNCHANGED <<nextPos, covered, queue, committed>>

\* The heartbeat commits the connector's CURRENT position -- the last one handed off.
Commit == /\ GateOpen
          /\ nextPos > 0
          /\ committed' = nextPos - 1
          /\ UNCHANGED <<nextPos, covered, queue, armed>>

Next == \/ Handoff
        \/ Write
        \/ Ack
        \/ ResetAbandon
        \/ Redeliver
        \/ ClearLatch
        \/ Commit
        \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

\* ---------------------------------------------------------------------------
\* Invariants
\* ---------------------------------------------------------------------------
TypeOK == /\ nextPos \in 0 .. MaxPos
          /\ covered \subseteq Positions
          /\ queue \subseteq Positions
          /\ armed \in BOOLEAN
          /\ committed \in (-1) .. (MaxPos - 1)

\* The safety property: nothing the offset commits is missing from ClickHouse.
CommittedPositionsAreCovered == CommittedIsCovered

\* The gate is sound: whenever it is open, every position below the reader's head has reached
\* ClickHouse. This is the property the pre-fix gate cannot have, because it reads the FIFO alone.
OpenGateMeansEverythingIsCovered == GateOpen => AllCoveredUpToHead

\* Why the two above hold, and the one fact that makes them inductive: an uncovered position below the
\* head is either still in the queue or the latch is standing in front of the gate. `ResetAbandon` is
\* the only action that empties the queue over uncovered positions, and it is the only one that arms.
UncoveredBelowTheHeadIsInTheQueueOrBehindTheLatch ==
    Gaps = {} \/ armed \/ Gaps \subseteq queue

\* The state the 2026-11-10 run visited, and it is reachable: Handoff, Handoff, ResetAbandon. Two
\* positions handed off, none written, the FIFO emptied.
AbandonedState == [ nextPos |-> 2, covered |-> {}, queue |-> {},
                    armed |-> TRUE, committed |-> -1 ]

GapsBelow(s) == { p \in 0 .. (s.nextPos - 1) : p \notin s.covered }
GateOpenAt(s) == s.queue = {} /\ s.armed = FALSE
OldGateOpenAt(s) == s.queue = {}
DrainReadyAt(s) == s.queue = {}

\* A constant, so TLC evaluates it in every state: over that reachable state the pre-fix gate is open
\* while a position below the head is not in ClickHouse, the fixed gate is closed, and the drain still
\* proceeds. This is the defect and the fix in one formula.
TheTwoGatesDisagreeOverTheAbandonedState ==
    /\ OldGateOpenAt(AbandonedState)
    /\ GapsBelow(AbandonedState) # {}
    /\ ~GateOpenAt(AbandonedState)
    /\ DrainReadyAt(AbandonedState)

=============================================================================
