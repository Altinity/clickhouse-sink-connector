-- spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/RecoverySchemaPolicy.java::needsSnapshot
-- spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/HistoryPreservingRecoverySnapshotter.java::shouldSnapshotSchema
-- request: preserve recovered history at a retained streaming checkpoint (spec 01.11).
namespace RecoverySchema

def snapshot (offset running restored : Bool) : Bool := !offset || running || !restored

theorem preserveHistory : snapshot true false true = false := rfl

theorem missingHistory (offset running : Bool) : snapshot offset running false = true := by
  cases offset <;> cases running <;> rfl

theorem incompleteSnapshot (offset restored : Bool) : snapshot offset true restored = true := by
  cases offset <;> cases restored <;> rfl

theorem noOffset (running restored : Bool) : snapshot false running restored = true := by
  cases running <;> cases restored <;> rfl

theorem skipIffSafe (offset running restored : Bool) :
    snapshot offset running restored = false ↔ offset = true ∧ running = false ∧ restored = true := by
  cases offset <;> cases running <;> cases restored <;> simp [snapshot]
end RecoverySchema
