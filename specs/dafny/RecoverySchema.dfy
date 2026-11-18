// spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/RecoverySchemaPolicy.java::needsSnapshot
// spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/HistoryPreservingRecoverySnapshotter.java::shouldSnapshotSchema
// request: preserve recovered history at a retained streaming checkpoint (spec 01.11).
function Snapshot(offset: bool, running: bool, restored: bool): bool {
  !offset || running || !restored
}

lemma Contract(offset: bool, running: bool, restored: bool)
  ensures !Snapshot(true, false, true)
  ensures !restored ==> Snapshot(offset, running, restored)
  ensures running ==> Snapshot(offset, running, restored)
  ensures !offset ==> Snapshot(offset, running, restored)
  ensures !Snapshot(offset, running, restored) <==> offset && !running && restored
{}
