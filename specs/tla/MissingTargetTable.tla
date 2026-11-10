---------------------------- MODULE MissingTargetTable ----------------------------
(***************************************************************************)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java::isTableAbsent *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java::isTargetTableAbsent *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java::processBatchRecords *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java::processRecordsByTopic *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java::classify *)
(* request: a staging connector stopped every 13 minutes for hours on a dropped *)
(*          target table (2026-11-09)                                      *)
(* spec: specs/08-schema-catalog/07-missing-target-table-failure.md, 3.1-3.7  *)
(*                                                                         *)
(* The property in words. An unresolved writer -- an empty column map -- is *)
(* produced by three states: the table does not exist, the catalog could not *)
(* be read, and the connection was never established. Only the first can    *)
(* never settle by retrying, and the empty map does not say which happened. *)
(* So the probe answers three values; both collapse directions are wrong:   *)
(* absence for an unreadable catalog stops a healthy connector on a blip,   *)
(* and "could not answer" read as presence restores the unbounded retry.    *)
(*                                                                         *)
(* This is a BEHAVIOURAL model, the half the pure-function proofs in        *)
(* specs/lean and specs/dafny cannot state: the state is a sequence of      *)
(* batch attempts across a table being dropped and recreated, so it can     *)
(* show that a recreated table is picked up by a LATER attempt with no      *)
(* restart (nothing cached), and that no attempt acknowledges while the     *)
(* table is missing.                                                       *)
(*                                                                         *)
(* TLC checks it inside the bound Attempts = 0..3.                         *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS Attempts

Catalog == {"absent", "present", "unreadable"}
Action == {"stop", "retry"}

\* DBMetadata.isTableAbsent: a pure function of the catalog, never of history.
Probe(c) == IF c = "absent" THEN "TRUE"
           ELSE IF c = "present" THEN "FALSE"
           ELSE "null"

\* DbWriter.isTargetTableAbsent is Boolean.TRUE.equals(...).
IsAbsent(c) == Probe(c) = "TRUE"

\* The batch path: the probe is reached only when the writer is unresolved.
Decide(metadataRetrieved, c) == IF metadataRetrieved THEN "retry"
                               ELSE IF IsAbsent(c) THEN "stop"
                               ELSE "retry"

DecideBefore(metadataRetrieved, c) == IF metadataRetrieved THEN "retry" ELSE "retry"

\* A writer is RESOLVED exactly when the catalog answered "present", which is the
\* condition `Decide` takes as metadataRetrieved. The table being missing and the
\* row failing to read both leave the writer unresolved, so the probe runs.
Resolved(c) == c = "present"

\* The catalogs explored: the table is dropped for a window and recreated, with at
\* most one unreadable attempt inside that window, plus the all-present case.
DroppedWindows ==
  { [i \in 0..Attempts |-> IF i < 1 \/ i >= 3 THEN "present" ELSE "absent"],
    [i \in 0..Attempts |-> IF i < 1 \/ i >= 3 THEN "present" ELSE "unreadable"],
    [i \in 0..Attempts |-> IF i < 1 \/ i >= 3 THEN "present"
                           ELSE IF i = 2 THEN "unreadable" ELSE "absent"],
    [i \in 0..Attempts |-> IF i < 1 \/ i >= 2 THEN "present" ELSE "absent"],
    [i \in 0..Attempts |-> "present"] }

VARIABLES accepted, catalog, acknowledged

vars == <<accepted, catalog, acknowledged>>

Init == /\ accepted = <<>>
        /\ catalog \in DroppedWindows
        /\ acknowledged = 0

\* One batch attempt, `i` = the index just consumed.
Next == \/ \E i \in 0..Attempts - 1 :
            /\ Len(accepted) = i
            /\ accepted' = Append(accepted, Decide(Resolved(catalog[i]), catalog[i]))
            /\ acknowledged' = acknowledged +
                 (IF catalog[i] = "present" THEN 1 ELSE 0)
            /\ UNCHANGED catalog
        \/ UNCHANGED <<accepted, catalog, acknowledged>>

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

\* ---------------------------------------------------------------------------
\* Invariants
\* ---------------------------------------------------------------------------
TypeOK == /\ Len(accepted) \in 0..Attempts
          /\ accepted \in Seq(Action)
          /\ acknowledged \in 0..Attempts
          /\ \A i \in 0..Attempts : catalog[i] \in Catalog

\* Section 3.1/3.2: a stop happens only on proven absence, and proven absence
\* always stops. Recorded per attempt as it is consumed.
StopIffAbsent == \A i \in 1..Len(accepted) :
    (accepted[i] = "stop") <=> (catalog[i - 1] = "absent")

\* A present table never stops the connector.
PresentNeverStops == \A i \in 0..Attempts :
    catalog[i] = "present" => Decide(Resolved(catalog[i]), catalog[i]) = "retry"

\* An unreadable catalog never stops the connector (section 3.5).
UnreadableNeverStops == \A i \in 0..Attempts :
    catalog[i] = "unreadable" => Decide(Resolved(catalog[i]), catalog[i]) = "retry"

\* Section 3.3: nothing is cached, so once the table exists again its attempt is a
\* retry -- the recreated table is picked up without a restart.
RecreatedTableIsPickedUp == \A i \in 1..Attempts :
    catalog[i] = "present" /\ catalog[i - 1] # "present" =>
      Decide(Resolved(catalog[i]), catalog[i]) = "retry"

\* The old decision retried a proven absence forever.
BeforeRetriedForever == \A i \in 0..Attempts :
    catalog[i] = "absent" => DecideBefore(Resolved(catalog[i]), catalog[i]) = "retry"

\* The two decisions differ exactly on the absent attempts, and nowhere else.
ChangeIsExactlyTheAbsentCase == \A i \in 0..Attempts :
    (Decide(Resolved(catalog[i]), catalog[i]) # DecideBefore(Resolved(catalog[i]), catalog[i]))
      = (catalog[i] = "absent")

\* Section 3.7: the offset never leads the writes. Only an attempt whose catalog
\* answered "present" is acknowledged, and an absent table is never retried.
NothingAcknowledgedWhileMissing ==
    /\ acknowledged = Cardinality({ i \in 0..Len(accepted) - 1 : catalog[i] = "present" })
    /\ \A i \in 1..Len(accepted) : catalog[i - 1] = "absent" => accepted[i] = "stop"

=============================================================================
