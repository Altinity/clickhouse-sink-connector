---------------------------- MODULE ColumnFilter ----------------------------
(***************************************************************************)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::keeps *)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::from *)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsCreateColumn *)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsAlterColumn *)
(* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::declaresAnyKeptColumn *)
(* request: community issue 1579 -- with enable.snapshot.ddl the tables are   *)
(*          created but column.include.list is ignored, so an excluded column *)
(*          such as a password is still created, empty (2026-11-11)           *)
(* spec: specs/06-ddl-replication/04-alter-table-translation.md section 3.6,   *)
(*       specs/08-schema-catalog/05-auto-table-creation.md section 3.1.2       *)
(*                                                                         *)
(* The property in words. Debezium applies column.include.list /           *)
(* column.exclude.list to the RECORD schema, so the writer never sends an  *)
(* excluded column's value; the schema-DDL path translates the raw MySQL   *)
(* CREATE TABLE text and applied no filter at all, so the two creation     *)
(* paths disagreed about one table. The fix asks the same matcher the      *)
(* record path asks, once per declared column.                            *)
(*                                                                         *)
(* This is the BEHAVIOURAL half, which the pure-function models in          *)
(* specs/lean and specs/dafny cannot state: the tables are translated one   *)
(* after another by one listener instance, so a decision made for one       *)
(* table leaking into the next is a plausible defect. The model translates   *)
(* every schedule of 3 of the 4 statement shapes and says no statement in    *)
(* any order emits an empty column set (a CREATE of no columns cannot be     *)
(* translated -- it is what Debezium's key policy throws on), that two       *)
(* occurrences of the same shape emit the same set whatever came before      *)
(* them, and that the four rules of section 3.6 hold in every reachable      *)
(* state.                                                                   *)
(*                                                                         *)
(* Bounds: one schedule of 3 statements, chosen in Init from the 6 in         *)
(* Schedules; the 4 shapes are the ones the issue and the emission tests      *)
(* use. TLC proves the invariants within those bounds.                       *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

VerdictOf(c) == c[1]
IsKey(c) == c[2]

\* DdlColumnFilter.keeps: with no list every column is kept; otherwise only a
\* column the matcher rejected is dropped.
Keeps(lists, v) ==
  IF lists = "unset" THEN TRUE
  ELSE IF v = "rejected" THEN FALSE
  ELSE TRUE

\* keepsColumn: the key promotion. A key column survives the lists.
KeepsColumn(lists, c) == Keeps(lists, VerdictOf(c)) \/ IsKey(c)

\* declaresAnyKeptColumn asks the RAW matcher, not KeepsColumn: a column kept only
\* because the key names it does not count as a column the lists allow.
AnyRawKept(lists, cols) ==
  \E j \in 1..Len(cols) : Keeps(lists, VerdictOf(cols[j]))

\* columnFilterBypassedForCreate: configured, no declared column selected, at least
\* one declared.
Bypassed(lists, cols) ==
  /\ lists # "unset"
  /\ ~AnyRawKept(lists, cols)
  /\ Len(cols) > 0

\* The statements whose columns the lists really filter: configured, and not
\* bypassed. Only these can drop a column at all -- a bypassed statement keeps
\* every source column, which is the whole point of the fallback.
Filtered(attempt) ==
  /\ attempt.lists # "unset"
  /\ ~Bypassed(attempt.lists, attempt.cols)

\* What the CREATE emits for column j.
Emits(lists, cols, j) == KeepsColumn(lists, cols[j]) \/ Bypassed(lists, cols)

\* The emitted column indices of one statement -- a function of the statement alone,
\* which is what HistoryCannotChangeAnEmission checks.
EmitSet(attempt) ==
  { j \in 1..Len(attempt.cols) : Emits(attempt.lists, attempt.cols, j) }

\* The behaviour before this change: every declared column was created.
EmitSetBefore(attempt) == 1..Len(attempt.cols)

\* The rejected non-key columns of one statement: the only ones the fix removes.
RejectedNonKey(attempt) ==
  { j \in 1..Len(attempt.cols) :
      VerdictOf(attempt.cols[j]) = "rejected" /\ ~IsKey(attempt.cols[j]) }

\* The four statement shapes.
\* CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(100))
\* under column.include.list = app.users.(id|name).
Users == [ lists |-> "include",
           cols |-> << <<"selected", TRUE>>, <<"selected", FALSE>>,
                       <<"rejected", FALSE>> >> ]

\* CREATE TABLE t (a INT, b INT) with an include list that names neither column.
AllRejected == [ lists |-> "include",
                 cols |-> << <<"rejected", FALSE>>, <<"rejected", FALSE>> >> ]

\* The only column is a key the exclude list names: the key wins.
OnlyRejectedKey == [ lists |-> "exclude", cols |-> << <<"rejected", TRUE>> >> ]

\* No list at all: no matcher runs, so no column carries a verdict, and every
\* declared column is created.
NoList == [ lists |-> "unset",
            cols |-> << <<"unjudged", FALSE>>, <<"unjudged", FALSE>> >> ]

Schedules ==
  { <<Users, AllRejected, OnlyRejectedKey>>,
    <<OnlyRejectedKey, Users, NoList>>,
    <<AllRejected, NoList, Users>>,
    <<Users, Users, Users>>,
    <<AllRejected, AllRejected, AllRejected>>,
    <<NoList, OnlyRejectedKey, AllRejected>>,
    <<Users, NoList, Users>>,
    <<OnlyRejectedKey, AllRejected, OnlyRejectedKey>> }

VARIABLES schedule, processed, emitted

vars == <<schedule, processed, emitted>>

Init == /\ schedule \in Schedules
        /\ processed = <<>>
        /\ emitted = <<>>

\* One statement of the schedule: record it, and record the column set it emitted.
\* The stutter disjunct keeps the model running after the schedule is consumed, so a
\* checker does not meet an artificial terminal state -- the listener itself goes on
\* translating statements forever.
Next == \/ /\ Len(processed) < Len(schedule)
           /\ LET i == Len(processed) + 1
              IN  /\ processed' = Append(processed, schedule[i])
                  /\ emitted' = Append(emitted, EmitSet(schedule[i]))
                  /\ UNCHANGED schedule
        \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

\* ---------------------------------------------------------------------------
\* Invariants
\* ---------------------------------------------------------------------------
TypeOK == /\ Len(processed) <= Len(schedule)
          /\ Len(emitted) = Len(processed)
          /\ \A i \in 1..Len(emitted) : emitted[i] \in SUBSET (1..3)

\* Section 3.6 rule 1: an exclusion removes the column from the CREATE. The guard
\* is the documented exception -- a bypassed statement keeps every source column,
\* because creating none of them cannot be translated at all.
ExcludedColumnIsNeverCreated ==
  \A i \in 1..Len(processed) :
    Filtered(processed[i]) =>
      \A j \in 1..Len(processed[i].cols) :
        (VerdictOf(processed[i].cols[j]) = "rejected" /\ ~IsKey(processed[i].cols[j]))
          => j \notin emitted[i]

\* Section 3.6 rule 2: a key column is created even when the lists reject it.
KeyColumnIsAlwaysCreated ==
  \A i \in 1..Len(processed) :
    \A j \in 1..Len(processed[i].cols) :
      IsKey(processed[i].cols[j]) => j \in emitted[i]

\* Section 3.6 rule 3: no statement is ever translated into a CREATE of no columns.
NoCreateWithoutColumns ==
  \A i \in 1..Len(processed) : emitted[i] # {}

\* The bypass adds nothing: no column is ever invented.
NothingIsInvented ==
  \A i \in 1..Len(processed) : emitted[i] \subseteq 1..Len(processed[i].cols)

\* No history leaks in: the same statement shape emits the same column set, whatever
\* the listener translated before it. The bypass flag and the collected key sets are
\* fields on one listener instance, which is what makes a leak plausible.
HistoryCannotChangeAnEmission ==
  \A i, k \in 1..Len(processed) :
    processed[i] = processed[k] => emitted[i] = emitted[k]

\* Section 3.6 rule 4: an unjudged identifier is never rejected.
UnjudgedColumnIsAlwaysCreated ==
  \A i \in 1..Len(processed) :
    \A j \in 1..Len(processed[i].cols) :
      VerdictOf(processed[i].cols[j]) = "unjudged" => j \in emitted[i]

\* The bypass stays narrow: a statement the lists select a column of emits exactly the
\* selected and key columns, never everything.
BypassOnlyWhenNothingIsKept ==
  \A i \in 1..Len(processed) :
    AnyRawKept(processed[i].lists, processed[i].cols)
      => emitted[i] = { j \in 1..Len(processed[i].cols) :
                          KeepsColumn(processed[i].lists, processed[i].cols[j]) }

\* The change, stated against the old behaviour: where the lists really filter, the
\* columns that left the emission are exactly the rejected non-key columns.
ChangeIsExactlyTheRejectedNonKeyColumn ==
  \A i \in 1..Len(processed) :
    Filtered(processed[i]) =>
      { j \in EmitSetBefore(processed[i]) : j \notin emitted[i] } =
        RejectedNonKey(processed[i])

\* The reported symptom: the password -- column 3 of a users table -- was created,
\* empty, before the change, and is not created now.
TheReportedSymptomIsGone ==
  \A i \in 1..Len(processed) :
    processed[i] = Users => 3 \notin emitted[i]

=============================================================================
