/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# ALTER TABLE clause classification (Specs 06.03 / 06.04 / 06.05 / 06.07 / 06.09)

A MySQL `ALTER TABLE` is a list of clauses. ClickHouse can represent some of them
(add / drop / modify a data column), cannot represent others at all but loses
nothing by skipping them (indexes, constraints, charset, table options,
a restated `PRIMARY KEY`, a re-declaration of a sorting-key column with a
same-or-narrower type), and follows two kinds with a REBUILD of the table at
the DDL barrier (Spec 06.09: ClickHouse cannot re-key a table in place, so — as
MySQL itself does for these statements — the representable clauses are applied
and the live rows are copied into a table created under the new key;
`PkRebuild.lean` proves that copy preserves every live row exactly once,
versions unchanged):

* an `ADD`/`DROP PRIMARY KEY` that changes the row identity the replica is
  keyed by rebuilds under the NEW sorting key (Spec 06.09 §3.1);
* a `MODIFY`/`CHANGE`/`RENAME` that WIDENS or renames a sorting-key column
  rebuilds under the SAME identity (Spec 06.05 §3.4 rule 3, Spec 06.09 §3.1.2):
  ClickHouse rejects the change on the existing table with `Code: 524` after
  every retry, while MySQL rebuilds its clustered index for it, so the clause
  is deferred to the empty rebuilt table and the copy converts the values.

No clause of the current model is loud. The loud path (`Clause.loud`,
`anyLoud`, the `fail` branch of `translate`, `rebuild_only_when_not_loud`) is
kept because it models future loud clauses and the refusal still wins over a
rebuild whenever one is present; the connector's `ddl.primary.key.rebuild=false`
refusal of a widening is a configuration switch outside this model.

Two production defects motivate this model:

* every clause of a statement fell into the "no branch" case, so the translator
  emitted a bare `ALTER TABLE db.t` (`Code: 62`) — the statement failed, was
  retried forever, and the `ADD COLUMN` it also carried never reached ClickHouse;
* a `MODIFY` of a sorting-key column was emitted as a type change (`Code: 524`),
  again taking the neighbouring `ADD COLUMN` down with it.

This module models the clause classification and proves that the fixed
translator (i) never emits an empty clause list, (ii) turns a widening or
rename of a sorting-key column into a rebuild under the same identity, never a
type change against the existing table (a loud clause, when one is present,
still wins over a rebuild), (iii) never loses an `ADD COLUMN` when it does emit
or rebuild, and (iv) turns a change of row identity into a rebuild that carries
exactly the representable clauses.
-/

namespace Replication

/-- One `alterSpecification` of a MySQL `ALTER TABLE`, already classified. -/
inductive Clause where
  | addColumn (name : String)                      -- ADD COLUMN c <type>
  -- DESTRUCTIVE: none -- a constructor of the abstract model, never an executed statement.
  | dropColumn (name : String)                     -- DROP COLUMN c
  | modifyDataColumn (name : String)               -- MODIFY/CHANGE of a non-key column
  | modifyKeyColumnSameOrNarrower (name : String)  -- MODIFY of a sorting-key column, loss-free to skip
  | modifyKeyColumnWider (name : String)           -- MODIFY/CHANGE/RENAME of a sorting-key column that widens or renames it (deferred to a rebuild)
  | primaryKeyChange (cols : List String)          -- ADD/DROP PRIMARY KEY whose net identity differs from the known sorting key
  | noOp                                           -- index / key restatement / constraint / charset / table option / partition
deriving Repr, DecidableEq

/-- What the translator emits for one statement. -/
inductive Emission where
  | skip                        -- translated query is "" (executeDDL skips it)
  | emit (clauses : List Clause) -- one ClickHouse ALTER carrying exactly these clauses
  | rebuild (clauses : List Clause) -- emit these representable clauses (possibly none), then rebuild the table under the new key (Spec 06.09)
  | fail                        -- DDLReplicationException, nothing emitted
deriving Repr, DecidableEq

/-- A clause ClickHouse can carry in an `ALTER TABLE`. -/
def Clause.representable : Clause → Bool
  | Clause.addColumn _        => true
  | Clause.dropColumn _       => true
  | Clause.modifyDataColumn _ => true
  | _                         => false

/-- A clause the translator must refuse. No clause of the current model is
    loud: a widening or rename of a sorting-key column, formerly the one loud
    kind, is deferred to a rebuild (Spec 06.05 §3.4 rule 3). The classification
    is kept so that a future loud clause slots in here and the refusal keeps
    winning over a rebuild (`translate`, `rebuild_only_when_not_loud`). -/
def Clause.loud : Clause → Bool
  | _ => false

/-- A clause that requires the table to be rebuilt at the DDL barrier: a change
    of row identity rebuilds under the new key (Spec 06.07 §3.1 rule 3,
    Spec 06.09 §3.1); a widening or rename of a sorting-key column rebuilds
    under the same identity (Spec 06.05 §3.4 rule 3, Spec 06.09 §3.1.2). -/
def Clause.rebuilds : Clause → Bool
  | Clause.primaryKeyChange _     => true
  | Clause.modifyKeyColumnWider _ => true
  | _                             => false

/-- Does any clause of the statement have to be refused? -/
def anyLoud : List Clause → Bool
  | []      => false
  | c :: cs => c.loud || anyLoud cs

/-- Does any clause of the statement require a rebuild (a `primaryKeyChange` or a
    `modifyKeyColumnWider` is present)? -/
def anyRebuild : List Clause → Bool
  | []      => false
  | c :: cs => c.rebuilds || anyRebuild cs

/-- The representable clauses of a statement, in source order. -/
def keep : List Clause → List Clause
  | []      => []
  | c :: cs => if c.representable then c :: keep cs else keep cs

/--
The clause-list translation of `enterAlterTable`: refuse the whole statement if
any clause is loud; otherwise, if the statement changes the row identity or
widens/renames a sorting-key column, emit the representable clauses (possibly
none) and rebuild the table (Spec 06.09); otherwise keep the representable
clauses, and if none is left emit nothing at all (never a bare `ALTER TABLE`).
-/
def translate (cs : List Clause) : Emission :=
  if anyLoud cs then Emission.fail
  else if anyRebuild cs then Emission.rebuild (keep cs)
  else if keep cs = [] then Emission.skip
  else Emission.emit (keep cs)

/-! ## Helper lemmas about the classification -/

theorem anyLoud_of_mem {cs : List Clause} {c : Clause} (hc : c ∈ cs) (hl : c.loud = true) :
    anyLoud cs = true := by
  induction cs with
  | nil => simp at hc
  | cons d ds ih =>
    cases List.mem_cons.mp hc with
    | inl heq => subst heq; simp [anyLoud, hl]
    | inr hmem => simp [anyLoud, ih hmem]

theorem anyLoud_eq_false {cs : List Clause} (h : ∀ c ∈ cs, c.loud = false) :
    anyLoud cs = false := by
  induction cs with
  | nil => rfl
  | cons d ds ih =>
    have hd : d.loud = false := h d (by simp)
    have hrest : ∀ c ∈ ds, c.loud = false := fun c hc => h c (by simp [hc])
    simp [anyLoud, hd, ih hrest]

theorem anyRebuild_of_mem {cs : List Clause} {c : Clause} (hc : c ∈ cs) (hr : c.rebuilds = true) :
    anyRebuild cs = true := by
  induction cs with
  | nil => simp at hc
  | cons d ds ih =>
    cases List.mem_cons.mp hc with
    | inl heq => subst heq; simp [anyRebuild, hr]
    | inr hmem => simp [anyRebuild, ih hmem]

theorem anyRebuild_eq_false {cs : List Clause} (h : ∀ c ∈ cs, c.rebuilds = false) :
    anyRebuild cs = false := by
  induction cs with
  | nil => rfl
  | cons d ds ih =>
    have hd : d.rebuilds = false := h d (by simp)
    have hrest : ∀ c ∈ ds, c.rebuilds = false := fun c hc => h c (by simp [hc])
    simp [anyRebuild, hd, ih hrest]

theorem keep_eq_nil {cs : List Clause} (h : ∀ c ∈ cs, c.representable = false) :
    keep cs = [] := by
  induction cs with
  | nil => rfl
  | cons d ds ih =>
    have hd : d.representable = false := h d (by simp)
    have hrest : ∀ c ∈ ds, c.representable = false := fun c hc => h c (by simp [hc])
    simp [keep, hd, ih hrest]

theorem mem_keep_of_mem {cs : List Clause} {c : Clause} (hc : c ∈ cs)
    (hr : c.representable = true) : c ∈ keep cs := by
  induction cs with
  | nil => simp at hc
  | cons d ds ih =>
    cases List.mem_cons.mp hc with
    | inl heq => subst heq; simp [keep, hr]
    | inr hmem =>
      by_cases hd : d.representable = true
      · simp [keep, hd, ih hmem]
      · simp [keep, hd, ih hmem]

/-! ## The properties -/

/-- **(a) No bare ALTER.** The translator never emits an empty clause list
    (the rebuild branch never produces `emit` at all). -/
theorem no_bare_alter (cs : List Clause) : translate cs ≠ Emission.emit [] := by
  unfold translate
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl]
    exact fun h => Emission.noConfusion h
  · rw [if_neg hl]
    by_cases hr : anyRebuild cs = true
    · rw [if_pos hr]
      exact fun h => Emission.noConfusion h
    · rw [if_neg hr]
      by_cases hk : keep cs = []
      · rw [if_pos hk]
        exact fun h => Emission.noConfusion h
      · rw [if_neg hk]
        intro h
        exact hk (Emission.emit.inj h)

/-- An all-no-op statement translates to `skip`, not to `emit []`. (A no-op
    clause is unrepresentable, not loud and not an identity change.) -/
theorem all_noop_skips (cs : List Clause)
    (h : ∀ c ∈ cs, c.representable = false ∧ c.loud = false ∧ c.rebuilds = false) :
    translate cs = Emission.skip := by
  have hl : anyLoud cs = false := anyLoud_eq_false (fun c hc => (h c hc).2.1)
  have hr : anyRebuild cs = false := anyRebuild_eq_false (fun c hc => (h c hc).2.2)
  have hk : keep cs = [] := keep_eq_nil (fun c hc => (h c hc).1)
  unfold translate
  rw [if_neg (by simp [hl]), if_neg (by simp [hr]), if_pos hk]

/--
**(b) A widening or rename of a sorting-key column rebuilds.** ClickHouse
rejects the change on the existing table (`Code: 524`) after every retry, and
MySQL rebuilds its clustered index for it, so — as for a change of row identity
— the representable clauses are applied and the table is rebuilt, here under
the same identity, with the deferred clause applied to the empty rebuilt table
before the copy (Spec 06.05 §3.4 rule 3, Spec 06.09 §3.1.2). The clause is
never emitted as a type change against the existing table. A loud clause
elsewhere in the statement still wins, hence `hl` (trivially satisfiable while
no clause of the model is loud).
-/
theorem wider_key_change_rebuilds (cs : List Clause) (n : String)
    (h : Clause.modifyKeyColumnWider n ∈ cs) (hl : anyLoud cs = false) :
    translate cs = Emission.rebuild (keep cs) := by
  have hr : anyRebuild cs = true := anyRebuild_of_mem h rfl
  unfold translate
  rw [if_neg (by simp [hl]), if_pos hr]

/--
**(b') A change of row identity rebuilds.** An `ADD`/`DROP PRIMARY KEY` whose
net identity differs from the replica's known sorting key turns the statement
into a rebuild (Spec 06.07 §3.1 rule 3, Spec 06.09): ClickHouse cannot re-key a
table in place, and keeping the old key collapses rows the source keeps
distinct, so the representable clauses are applied and the table is rebuilt
under the new key at the DDL barrier. A restatement of the same key, or an
unknown key, is a `noOp` and is skipped. A loud clause still wins over the
rebuild, hence `hl` (trivially satisfiable while no clause of the model is loud).
-/
theorem primary_key_change_rebuilds (cs : List Clause) (cols : List String)
    (h : Clause.primaryKeyChange cols ∈ cs) (hl : anyLoud cs = false) :
    translate cs = Emission.rebuild (keep cs) := by
  have hr : anyRebuild cs = true := anyRebuild_of_mem h rfl
  unfold translate
  rw [if_neg (by simp [hl]), if_pos hr]

/-- A rebuild is only ever planned for a statement without a loud clause: the
    loud refusal wins over the rebuild. (No clause of the current model is
    loud; the theorem pins the precedence for any future loud clause.) -/
theorem rebuild_only_when_not_loud (cs kept : List Clause)
    (hreb : translate cs = Emission.rebuild kept) : anyLoud cs = false := by
  unfold translate at hreb
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl] at hreb
    exact absurd hreb (fun h => Emission.noConfusion h)
  · simpa using hl

/--
**The rebuild branch is never bare either.** Whatever the translator hands to
the rebuild is exactly the representable clauses of the statement, in source
order (possibly none: a lone `DROP PRIMARY KEY` rebuilds without any `ALTER`).
Together with `no_bare_alter` this covers every branch: `emit []` is never
produced, and `rebuild kept` carries precisely `keep cs`.
-/
theorem rebuild_never_bare (cs kept : List Clause)
    (hreb : translate cs = Emission.rebuild kept) : kept = keep cs := by
  unfold translate at hreb
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl] at hreb
    exact absurd hreb (fun h => Emission.noConfusion h)
  · rw [if_neg hl] at hreb
    by_cases hr : anyRebuild cs = true
    · rw [if_pos hr] at hreb
      exact (Emission.rebuild.inj hreb).symm
    · rw [if_neg hr] at hreb
      by_cases hk : keep cs = []
      · rw [if_pos hk] at hreb
        exact absurd hreb (fun h => Emission.noConfusion h)
      · rw [if_neg hk] at hreb
        exact absurd hreb (fun h => Emission.noConfusion h)

/-- The emitted clauses are exactly the representable ones, in source order. -/
theorem emitted_are_representable (cs kept : List Clause)
    (hemit : translate cs = Emission.emit kept) : kept = keep cs := by
  unfold translate at hemit
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl] at hemit
    exact absurd hemit (fun h => Emission.noConfusion h)
  · rw [if_neg hl] at hemit
    by_cases hr : anyRebuild cs = true
    · rw [if_pos hr] at hemit
      exact absurd hemit (fun h => Emission.noConfusion h)
    · rw [if_neg hr] at hemit
      by_cases hk : keep cs = []
      · rw [if_pos hk] at hemit
        exact absurd hemit (fun h => Emission.noConfusion h)
      · rw [if_neg hk] at hemit
        exact (Emission.emit.inj hemit).symm

/--
**(c) ADD COLUMN is preserved.** Whenever the translator emits, every
`addColumn` of the input is among the emitted clauses — skipping a neighbouring
unrepresentable clause never drops it.
-/
theorem add_columns_preserved (cs kept : List Clause) (n : String)
    (hemit : translate cs = Emission.emit kept)
    (hmem : Clause.addColumn n ∈ cs) : Clause.addColumn n ∈ kept := by
  rw [emitted_are_representable cs kept hemit]
  exact mem_keep_of_mem hmem rfl

/--
**(c') ADD COLUMN is preserved across a rebuild.** The `ADD COLUMN new_id ...`
of the migration shape `DROP PRIMARY KEY, ADD COLUMN new_id ..., ADD PRIMARY
KEY (new_id)` is among the clauses applied before the rebuild (Spec 06.09 §3.1).
-/
theorem rebuild_add_columns_preserved (cs kept : List Clause) (n : String)
    (hreb : translate cs = Emission.rebuild kept)
    (hmem : Clause.addColumn n ∈ cs) : Clause.addColumn n ∈ kept := by
  rw [rebuild_never_bare cs kept hreb]
  exact mem_keep_of_mem hmem rfl

end Replication
