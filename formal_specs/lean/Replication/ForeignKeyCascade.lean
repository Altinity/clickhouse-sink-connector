/-
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ForeignKeyCascadePreflight.java::check
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ForeignKeyCascadePreflight.java::belowFixedRelease
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ForeignKeyCascadePreflight.java::captured
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ForeignKeyCascadePreflight.java::CASCADE_QUERY
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlCaptureFilter.java::isCaptured

  Property: the start-time check of foreign-key cascades on a MySQL source names the captured child
  tables of cascading foreign keys in one WARN banner, only when the source is MySQL, its release is
  below 9.6, and at least one such child table is captured. The check never refuses the start: every
  failure is logged and the start continues. A child table is captured when the capture filter accepts
  it, and a set include list is the whole rule. The release gate covers MySQL releases below 9.6 and
  no MariaDB version. The cascade query only reads.

  Model: pure functions over the facts (isMysql, isMariaDb, the parsed version, and whether a captured
  cascade exists) and over the capture lists as string lists. The Java is not executed here.
  Bounds: none on the release; the capture lists are finite lists of names; regular expressions are
  abstracted to membership.
-/

namespace ForeignKeyCascade

/-- The release gate: below MySQL 9.6 when the major is below 9, or the major is 9 and the minor is
    below 6. -/
def belowFixedRelease (major minor : Nat) : Bool :=
  major < 9 || (major == 9 && minor < 6)

/-- The parsed version: `none` when the version string does not parse as MySQL. -/
def versionBelowFixed : Option (Nat × Nat) → Bool
  | some (m, n) => belowFixedRelease m n
  | none => false

/-- The facts the check reads from the source. -/
structure Facts where
  isMysql : Bool
  isMariaDb : Bool
  version : Option (Nat × Nat)
  anyCapturedCascade : Bool
  deriving DecidableEq, Repr

/-- The log decision. There is no constructor that refuses the start. -/
inductive Logged where
  | quiet
  | info
  | banner
  | failed
  deriving DecidableEq, Repr

/-- The banner: MySQL, not MariaDB, a parsed version below 9.6, and a captured cascading child. -/
def bannerDue (f : Facts) : Bool :=
  f.isMysql && !f.isMariaDb && versionBelowFixed f.version && f.anyCapturedCascade

/-- The log decision. `couldNotRun` is a failed read or a missing host or user. A non-MySQL
    connector is never connected to and logs nothing. -/
def logOutcome (f : Facts) (couldNotRun : Bool) : Logged :=
  if !f.isMysql then .quiet
  else if couldNotRun then .failed
  else if bannerDue f then .banner
  else .info

/-! ## The capture filter -/

/-- A capture list: an empty list is unset, and a set list is the whole rule. -/
def passes (inc exc : List String) (id : String) : Bool :=
  match inc with
  | [] => !exc.contains id
  | _ :: _ => inc.contains id

/-- A child table is captured when the database and the qualified table both pass. -/
def isCaptured (db table : String) (dbInc dbExc tblInc tblExc : List String) : Bool :=
  passes dbInc dbExc db && passes tblInc tblExc (db ++ "." ++ table)

/-! ## Theorems -/

/-- The release gate holds exactly for majors below 9 and for 9.0 to 9.5. -/
theorem below_fixed_release_iff (m n : Nat) :
    belowFixedRelease m n = true ↔ m < 9 ∨ (m = 9 ∧ n < 6) := by
  unfold belowFixedRelease
  by_cases h1 : m < 9
  · simp [h1]
  · by_cases h2 : m = 9
    · subst h2
      simp
    · simp [h1, h2, beq_iff_eq]

/-- MySQL 9.6 is not covered. -/
theorem nine_six_is_not_below : belowFixedRelease 9 6 = false := rfl

/-- Every release from 9.6 on is not covered. -/
theorem later_releases_are_not_below (m n : Nat) (h : 9 < m ∨ (m = 9 ∧ 6 ≤ n)) :
    belowFixedRelease m n = false := by
  cases hb : belowFixedRelease m n with
  | false => rfl
  | true =>
    exfalso
    have hgate := (below_fixed_release_iff m n).mp hb
    omega

/-- A MariaDB source is never the banner, whatever its version parses to. -/
theorem mariadb_is_never_banner (f : Facts) (h : f.isMariaDb = true) :
    bannerDue f = false := by
  unfold bannerDue
  simp [h]

/-- The banner is logged exactly when the source is MySQL and not MariaDB, its version parses below
    9.6, a captured cascading child exists, and no read failed. -/
theorem banner_iff (f : Facts) :
    logOutcome f false = .banner ↔
      (f.isMysql = true ∧ f.isMariaDb = false ∧ versionBelowFixed f.version = true ∧
        f.anyCapturedCascade = true) := by
  unfold logOutcome bannerDue
  cases hm : f.isMysql <;> cases hr : f.isMariaDb <;> cases hv : versionBelowFixed f.version <;>
    cases hc : f.anyCapturedCascade <;> simp

/-- A failed read is logged and never refuses: MySQL yields the failure WARN. -/
theorem failure_is_logged (f : Facts) (h : f.isMysql = true) :
    logOutcome f true = .failed := by
  unfold logOutcome
  simp [h]

/-- The log decision has four values, and none of them refuses the start. -/
theorem no_refusal_constructor (o : Logged) :
    o = .quiet ∨ o = .info ∨ o = .banner ∨ o = .failed := by
  cases o <;> simp

/-- A captured child is accepted exactly when both lists accept it. -/
theorem captured_needs_both_lists (db table : String) (dbInc dbExc tblInc tblExc : List String)
    (h : isCaptured db table dbInc dbExc tblInc tblExc = true) :
    passes dbInc dbExc db = true ∧ passes tblInc tblExc (db ++ "." ++ table) = true := by
  unfold isCaptured at h
  simpa only [Bool.and_eq_true] using h

/-- A set include list is the whole rule: the exclude list is ignored. -/
theorem include_list_is_the_whole_rule (i : String) (is exc : List String) (id : String) :
    passes (i :: is) exc id = (i :: is).contains id := rfl

/-- With no include list, the exclude list decides. -/
theorem exclude_list_decides_without_include (exc : List String) (id : String) :
    passes [] exc id = !exc.contains id := rfl

end ForeignKeyCascade
