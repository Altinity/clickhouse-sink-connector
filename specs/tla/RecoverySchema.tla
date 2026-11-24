------------------------ MODULE RecoverySchema ------------------------
\* spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/RecoverySchemaPolicy.java::needsSnapshot
\* spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/HistoryPreservingRecoverySnapshotter.java::shouldSnapshotSchema
\* request: preserve recovered history at a retained streaming checkpoint (spec 01.11).
EXTENDS TLC
VARIABLES offset, running, restored
vars == <<offset, running, restored>>
Snapshot == ~offset \/ running \/ ~restored
Init == /\ offset \in BOOLEAN /\ running \in BOOLEAN /\ restored \in BOOLEAN
Next == UNCHANGED vars
Spec == Init /\ [][Next]_vars
PreserveHistory == (offset /\ ~running /\ restored) => ~Snapshot
MissingHistorySnapshots == ~restored => Snapshot
IncompleteSnapshots == running => Snapshot
NoOffsetSnapshots == ~offset => Snapshot
OnlySafeSkip == ~Snapshot <=> (offset /\ ~running /\ restored)
=============================================================================
