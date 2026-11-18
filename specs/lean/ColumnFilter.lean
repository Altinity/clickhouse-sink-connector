/-!
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::keeps
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::from
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsCreateColumn
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsAlterColumn
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::declaresAnyKeptColumn
request: community issue 1579 -- with enable.snapshot.ddl the tables are created but
         column.include.list is ignored, so an excluded column such as a password is
         still created, empty (2026-11-11)
spec: specs/06-ddl-replication/04-alter-table-translation.md section 3.6,
      specs/08-schema-catalog/05-auto-table-creation.md section 3.1.2

The property in words. Debezium applies `column.include.list` / `column.exclude.list`
to the RECORD schema, so the writer never sends an excluded column's value. The
schema-DDL path translates the raw MySQL `CREATE TABLE` text instead, and applied no
filter at all, so the two creation paths disagreed about one table: the DDL path built
the column and the record path never filled it. The fix asks the same matcher the
record path asks, once per declared column.

Four things must hold, and each of them is a way the fix could go wrong:

1. An exclusion removes the column. A configured list that rejects a column the key
   does not name must keep it out of the CREATE and out of every ALTER clause.

2. A key column is always kept. Ordering a ReplacingMergeTree by a column that was
   filtered out either references a missing column or silently changes which source
   rows collapse into one, so the key wins over the lists, with a WARN.

3. A CREATE is never stranded. If the lists reject every column the statement declares,
   a CREATE with no columns cannot be translated at all -- Debezium's key policy throws
   -- so that statement falls back to every source column, with a WARN. The fallback
   stays narrow: it fires only when NO declared column is selected by the lists.

4. An unjudged identifier is never rejected. The matcher is called in CATALOG mode,
   where a null catalog matches nothing, so a missing database name would drop every
   column of the table. Unknown identifiers therefore keep the column.

Model. A column is the pair (what the matcher answered, whether the key names it); the
matcher's configuration is one of three states; the CREATE emission and the bypass are
pure functions of (configuration, declared columns). Only Lean core is used and every
proof is kernel-checked (`rfl`, `simp`, `decide`). Run: `lean ColumnFilter.lean`.
-/

namespace ColumnFilter

/-! ## What is configured, and what the filter is built from -/

/-- Which of the two connector properties is set. -/
inductive Configured where
  | neither
  | includeOnly
  | excludeOnly
  | both
  deriving DecidableEq, Repr

/-- The list the filter is actually built from. -/
inductive Lists where
  | unset
  | include
  | exclude
  deriving DecidableEq, Repr

/-- `DdlColumnFilter.from`. Debezium's rule: an include list, when set, is the whole
rule and the exclude list is ignored. (`from` is a Lean keyword, hence the name.) -/
def builtFrom : Configured → Lists
  | .neither => .unset
  | .includeOnly => .include
  | .excludeOnly => .exclude
  | .both => .include

theorem noListIsUnset : builtFrom .neither = .unset := rfl

theorem includeWinsOverExclude : builtFrom .both = builtFrom .includeOnly := rfl

theorem excludeListIsUsedOnItsOwn : builtFrom .excludeOnly = .exclude := rfl

/-! ## What the matcher answers, and what `keeps` does with it -/

/-- What the Debezium matcher answers for one column. `unjudged` is the empty answer:
a null or empty database or column name, which nothing can be decided from. -/
inductive Verdict where
  | unjudged
  | selected
  | rejected
  deriving DecidableEq, Repr

/-- `DdlColumnFilter.keeps`: with no list every column is kept, an unjudged identifier
is never rejected, otherwise the matcher decides. -/
def keeps : Lists → Verdict → Bool
  | .unset, _ => true
  | .include, .unjudged => true
  | .include, .selected => true
  | .include, .rejected => false
  | .exclude, .unjudged => true
  | .exclude, .selected => true
  | .exclude, .rejected => false

theorem noListKeepsEveryColumn (v : Verdict) : keeps .unset v = true := by
  cases v <;> rfl

theorem anUnjudgedColumnIsNeverRejected (lists : Lists) : keeps lists .unjudged = true := by
  cases lists <;> rfl

theorem aSelectedColumnIsKept (lists : Lists) : keeps lists .selected = true := by
  cases lists <;> rfl

theorem aConfiguredListRejects (lists : Lists) (h : lists ≠ .unset) :
    keeps lists .rejected = false := by
  cases lists <;> simp_all [keeps]

/-! ## One declared column, and the key promotion -/

/-- A declared column, at the granularity the translator observes: what the lists say
about it, and whether the key names it -- the table's PRIMARY KEY or first UNIQUE KEY
for a CREATE, the target table's sorting key for an ALTER. -/
structure Col where
  verdict : Verdict
  isKey : Bool
  deriving DecidableEq, Repr

/-- `keepsColumn`: the key promotion. A column the lists reject is still kept when the
key names it, and the skip is logged at WARN instead of INFO. -/
def keepsColumn (lists : Lists) (c : Col) : Bool :=
  keeps lists c.verdict || c.isKey

theorem aKeyColumnIsAlwaysKept (lists : Lists) (c : Col) (h : c.isKey = true) :
    keepsColumn lists c = true := by
  simp [keepsColumn, h]

theorem aRejectedColumnLosesItsClause (lists : Lists) (c : Col)
    (hl : lists ≠ .unset) (hv : c.verdict = .rejected) (hk : c.isKey = false) :
    keepsColumn lists c = false := by
  have hkeep : keeps lists c.verdict = false := by
    rw [hv]
    exact aConfiguredListRejects lists hl
  simp [keepsColumn, hkeep, hk]

/-! ## The CREATE: only the columns the lists allow, unless that would strand it -/

/-- `declaresAnyKeptColumn` asks the RAW matcher, not `keepsColumn`: a column kept only
because the key names it does not count as a column the lists allow. Written as an
explicit recursion (not `List.any`) so a selected column is visible to `simp` and the
empty case is the answer the Java returns for a statement that declares no column. -/
def anyRawKept : Lists → List Col → Bool
  | _, [] => false
  | .include, c :: cs => keeps .include c.verdict || anyRawKept .include cs
  | .exclude, c :: cs => keeps .exclude c.verdict || anyRawKept .exclude cs
  | .unset, _ :: _ => true

/-- `columnFilterBypassedForCreate`. Only a configured list can bypass, and only when the
statement declares columns and none of them is selected. -/
def bypassed : Lists → List Col → Bool
  | .unset, _ => false
  | .include, cols => !anyRawKept .include cols && !cols.isEmpty
  | .exclude, cols => !anyRawKept .exclude cols && !cols.isEmpty

/-- What the CREATE emits for one column. -/
def emitted (lists : Lists) (cols : List Col) (c : Col) : Bool :=
  keepsColumn lists c || bypassed lists cols

/-- The translator before this change: the DDL path applied no filter, so every declared
column was created. -/
def emittedBefore (_lists : Lists) (_cols : List Col) (_c : Col) : Bool := true

theorem unsetEmitsEveryColumn (cols : List Col) (c : Col) :
    emitted .unset cols c = true := by
  simp [emitted, keepsColumn, keeps, bypassed]

theorem anUnjudgedColumnIsAlwaysCreated (lists : Lists) (cols : List Col) :
    emitted lists cols ⟨.unjudged, false⟩ = true := by
  cases lists <;> simp [emitted, keepsColumn, keeps, bypassed, anyRawKept]

theorem aRejectedColumnIsNotCreated (lists : Lists) (cols : List Col) (c : Col)
    (hl : lists ≠ .unset) (hv : c.verdict = .rejected) (hk : c.isKey = false)
    (hb : bypassed lists cols = false) : emitted lists cols c = false := by
  have hkeep : keeps lists c.verdict = false := by
    rw [hv]
    exact aConfiguredListRejects lists hl
  simp [emitted, keepsColumn, hkeep, hk, hb]

theorem aBypassedCreateEmitsEveryColumn (lists : Lists) (cols : List Col) (c : Col)
    (h : bypassed lists cols = true) : emitted lists cols c = true := by
  simp [emitted, h]

/-- A statement that declares columns and selects none of them is bypassed. -/
theorem aCreateWithNoKeptColumnBypasses (lists : Lists) (cols : List Col)
    (hl : lists ≠ .unset) (hne : cols.isEmpty = false)
    (hnone : anyRawKept lists cols = false) : bypassed lists cols = true := by
  cases lists <;> simp_all [bypassed]

/-- The bypass stays narrow: one column the lists select and the statement is not
bypassed. This is what keeps the fallback from widening into "create everything". -/
theorem aSelectedColumnPreventsTheBypass (lists : Lists) (c : Col) (cs : List Col)
    (hv : c.verdict = .selected) : bypassed lists (c :: cs) = false := by
  have hany : anyRawKept lists (c :: cs) = true := by
    cases lists <;> simp [anyRawKept, keeps, hv]
  cases lists <;> simp [bypassed, hany]

/-! ## The table of issue 1579 -/

/-- `CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(100))`
under `column.include.list = app.users.(id|name)`: id and name are selected, the
password is rejected, and the source's key is `id`. -/
def users : List Col :=
  [⟨.selected, true⟩, ⟨.selected, false⟩, ⟨.rejected, false⟩]

theorem theKeyIsCreated : emitted .include users ⟨.selected, true⟩ = true := by decide

theorem theNameIsCreated : emitted .include users ⟨.selected, false⟩ = true := by decide

theorem thePasswordIsNotCreated : emitted .include users ⟨.rejected, false⟩ = false := by
  decide

/-- The password was created, empty, before this change -- the reported symptom. -/
theorem thePasswordWasCreatedBefore : emittedBefore .include users ⟨.rejected, false⟩ = true :=
  rfl

/-- The whole change, on the reported table: the filter removes exactly the rejected
column the key does not name, and nothing else. -/
theorem theFixRemovesExactlyTheRejectedNonKeyColumn (lists : Lists) (c : Col) :
    emitted lists users c = false ↔
      (lists ≠ .unset ∧ c.verdict = .rejected ∧ c.isKey = false) := by
  obtain ⟨v, k⟩ := c
  cases lists <;> cases v <;> cases k <;> decide

/-- `CREATE TABLE t (a INT, b INT)` with an include list that names neither: a CREATE of
no columns cannot be translated, so every source column is kept. -/
def allRejected : List Col := [⟨.rejected, false⟩, ⟨.rejected, false⟩]

theorem everyColumnSurvivesWhenNoneIsKept :
    emitted .include allRejected ⟨.rejected, false⟩ = true := by decide

/-! ## The ALTER: the same predicate, against the target table's sorting key -/

/-- `keepsAlterColumn` is `keepsColumn` with `isKey` set by the sorting key of the
ClickHouse table the statement alters, so an excluded column's ADD / DROP / MODIFY /
RENAME clause is dropped and a sorting-key column's is not. -/
theorem anAlterOnASortingKeyColumnIsApplied (lists : Lists) (c : Col) (h : c.isKey = true) :
    keepsColumn lists c = true :=
  aKeyColumnIsAlwaysKept lists c h

theorem anAlterOnARejectedNonKeyColumnIsSkipped (lists : Lists) (c : Col)
    (hl : lists ≠ .unset) (hv : c.verdict = .rejected) (hk : c.isKey = false) :
    keepsColumn lists c = false :=
  aRejectedColumnLosesItsClause lists c hl hv hk

end ColumnFilter
