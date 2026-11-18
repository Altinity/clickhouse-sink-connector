/-!
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/TableFilterConfig.java::removeRedundantInclude
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java::start
request: permit explicit exclusions alongside the deployed universal include.
Property: remove only a universal include with an active exclusion; preserve
restrictive scope and make repeat application a no-op. Regex parsing is outside
this model; the Java property tests exercise the concrete strings.
-/
namespace TableFilterExclusion
inductive Include where
  | absent | universal | restrictive
  deriving DecidableEq

def normalize (i : Include) (excluded : Bool) : Include :=
  match i, excluded with
  | .universal, true => .absent
  | _, _ => i

theorem excludesTakeEffect : normalize .universal true = .absent := rfl

theorem restrictivePreserved (e : Bool) : normalize .restrictive e = .restrictive := by
  cases e <;> rfl

theorem noExclusionPreserves (i : Include) : normalize i false = i := by
  cases i <;> rfl

theorem idempotent (i : Include) (e : Bool) :
    normalize (normalize i e) e = normalize i e := by
  cases i <;> cases e <;> rfl

theorem onlyRedundantIncludeChanges (i : Include) (e : Bool)
    (h : normalize i e ≠ i) : i = .universal ∧ e = true := by
  cases i <;> cases e <;> simp_all [normalize]
end TableFilterExclusion
