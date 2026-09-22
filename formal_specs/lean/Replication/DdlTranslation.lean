/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# ALTER TABLE clause classification (Specs 06.03 / 06.04 / 06.05 / 06.07)

A MySQL `ALTER TABLE` is a list of clauses. ClickHouse can represent some of them
(add / drop / modify a data column), cannot represent others at all but loses
nothing by skipping them (indexes, constraints, charset, table options,
a restated `PRIMARY KEY`, a re-declaration of a sorting-key column with a
same-or-narrower type), and must refuse the rest loudly (a change that WIDENS a
sorting-key column, or an `ADD`/`DROP PRIMARY KEY` that changes the row identity
the replica is keyed by: ClickHouse rejects the former with `Code: 524` and
cannot re-key a table for the latter, and nothing the connector can emit makes
the existing table hold the source rows).

Two production defects motivate this model:

* every clause of a statement fell into the "no branch" case, so the translator
  emitted a bare `ALTER TABLE db.t` (`Code: 62`) — the statement failed, was
  retried forever, and the `ADD COLUMN` it also carried never reached ClickHouse;
* a `MODIFY` of a sorting-key column was emitted as a type change (`Code: 524`),
  again taking the neighbouring `ADD COLUMN` down with it.

This module models the clause classification and proves that the fixed
translator (i) never emits an empty clause list, (ii) always fails loudly when a
widening key change is present, and (iii) never loses an `ADD COLUMN` when it
does emit.
-/

namespace Replication

/-- One `alterSpecification` of a MySQL `ALTER TABLE`, already classified. -/
inductive Clause where
  | addColumn (name : String)                      -- ADD COLUMN c <type>
  -- DESTRUCTIVE: none -- a constructor of the abstract model, never an executed statement.
  | dropColumn (name : String)                     -- DROP COLUMN c
  | modifyDataColumn (name : String)               -- MODIFY/CHANGE of a non-key column
  | modifyKeyColumnSameOrNarrower (name : String)  -- MODIFY of a sorting-key column, loss-free to skip
  | modifyKeyColumnWider (name : String)           -- MODIFY of a sorting-key column that widens it
  | primaryKeyChange (cols : List String)          -- ADD/DROP PRIMARY KEY whose net identity differs from the known sorting key
  | noOp                                           -- index / key restatement / constraint / charset / table option / partition
deriving Repr, DecidableEq

/-- What the translator emits for one statement. -/
inductive Emission where
  | skip                        -- translated query is "" (executeDDL skips it)
  | emit (clauses : List Clause) -- one ClickHouse ALTER carrying exactly these clauses
  | fail                        -- DDLReplicationException, nothing emitted
deriving Repr, DecidableEq

/-- A clause ClickHouse can carry in an `ALTER TABLE`. -/
def Clause.representable : Clause → Bool
  | Clause.addColumn _        => true
  | Clause.dropColumn _       => true
  | Clause.modifyDataColumn _ => true
  | _                         => false

/-- A clause the translator must refuse (Spec 06.05 §3.4 rule 3, Spec 06.07 §3.1 rule 3). -/
def Clause.loud : Clause → Bool
  | Clause.modifyKeyColumnWider _ => true
  | Clause.primaryKeyChange _     => true
  | _                             => false

/-- Does any clause of the statement have to be refused? -/
def anyLoud : List Clause → Bool
  | []      => false
  | c :: cs => c.loud || anyLoud cs

/-- The representable clauses of a statement, in source order. -/
def keep : List Clause → List Clause
  | []      => []
  | c :: cs => if c.representable then c :: keep cs else keep cs

/--
The clause-list translation of `enterAlterTable`: refuse the whole statement if
any clause is loud; otherwise keep the representable clauses, and if none is
left emit nothing at all (never a bare `ALTER TABLE`).
-/
def translate (cs : List Clause) : Emission :=
  if anyLoud cs then Emission.fail
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

/-! ## The three properties -/

/-- **(a) No bare ALTER.** The translator never emits an empty clause list. -/
theorem no_bare_alter (cs : List Clause) : translate cs ≠ Emission.emit [] := by
  unfold translate
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl]
    exact fun h => Emission.noConfusion h
  · rw [if_neg hl]
    by_cases hk : keep cs = []
    · rw [if_pos hk]
      exact fun h => Emission.noConfusion h
    · rw [if_neg hk]
      intro h
      exact hk (Emission.emit.inj h)

/-- An all-no-op statement translates to `skip`, not to `emit []`. -/
theorem all_noop_skips (cs : List Clause)
    (h : ∀ c ∈ cs, c.representable = false ∧ c.loud = false) :
    translate cs = Emission.skip := by
  have hl : anyLoud cs = false := anyLoud_eq_false (fun c hc => (h c hc).2)
  have hk : keep cs = [] := keep_eq_nil (fun c hc => (h c hc).1)
  unfold translate
  rw [if_neg (by simp [hl]), if_pos hk]

/-- **(b) A widening key change is loud.** Any statement containing one fails. -/
theorem wider_key_change_is_loud (cs : List Clause) (n : String)
    (h : Clause.modifyKeyColumnWider n ∈ cs) : translate cs = Emission.fail := by
  have hl : anyLoud cs = true := anyLoud_of_mem h rfl
  unfold translate
  rw [if_pos hl]

/--
**(b') A change of row identity is loud.** An `ADD`/`DROP PRIMARY KEY` whose net
identity differs from the replica's known sorting key fails the whole statement
(Spec 06.07 §3.1 rule 3): ClickHouse cannot re-key a table in place, and keeping
the old key collapses rows the source keeps distinct. A restatement of the same
key, or an unknown key, is a `noOp` and is skipped.
-/
theorem primary_key_change_is_loud (cs : List Clause) (cols : List String)
    (h : Clause.primaryKeyChange cols ∈ cs) : translate cs = Emission.fail := by
  have hl : anyLoud cs = true := anyLoud_of_mem h rfl
  unfold translate
  rw [if_pos hl]

/-- The emitted clauses are exactly the representable ones, in source order. -/
theorem emitted_are_representable (cs kept : List Clause)
    (hemit : translate cs = Emission.emit kept) : kept = keep cs := by
  unfold translate at hemit
  by_cases hl : anyLoud cs = true
  · rw [if_pos hl] at hemit
    exact absurd hemit (fun h => Emission.noConfusion h)
  · rw [if_neg hl] at hemit
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

end Replication
