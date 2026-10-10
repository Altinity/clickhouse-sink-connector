/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# ALTER TABLE generated-column translation (Invariant I13)

A MySQL `ALTER TABLE ... ADD/MODIFY COLUMN c <type> GENERATED ALWAYS AS (expr)`
must translate to a ClickHouse column whose TYPE is the declared `<type>` and
whose generation expression becomes a `DEFAULT` — never a column whose "type" is
the raw expression text. The ALTER path historically had no branch for the
generated-column clause, so it fell into the catch-all that treats any
unrecognised token as the column type and emitted malformed DDL such as
`ADD COLUMN c AS(a+b)`.

This module models the column translation and proves the bug cannot recur: the
emitted ClickHouse type is always the declared data type, and a generation
expression is always mapped to `DEFAULT` and never to the type.
-/

namespace Replication

/-- How a source column is declared. -/
inductive ColKind where
  | plain : ColKind
  | generated : String → ColKind   -- GENERATED ALWAYS AS (expr)
deriving Repr, DecidableEq

/-- A source column definition in an ALTER/CREATE statement. -/
structure MyColDef where
  name : String
  dataType : String
  kind : ColKind
deriving Repr, DecidableEq

/-- The translated ClickHouse column: a name, a type, and an optional DEFAULT. -/
structure CHColumn where
  name : String
  chType : String
  defaultExpr : Option String
deriving Repr, DecidableEq

/--
Column translation for ALTER ADD/MODIFY (and CREATE): the ClickHouse column
ALWAYS takes the DECLARED data type; a `GENERATED ALWAYS AS (expr)` clause maps
the expression to a `DEFAULT`, never to the type. Models the fixed
`GeneratedColumnConstraint` handling and `Constants.GENERATED_COLUMN_KIND = DEFAULT`.
-/
def translateAlterColumn (c : MyColDef) : CHColumn :=
  match c.kind with
  | ColKind.plain       => { name := c.name, chType := c.dataType, defaultExpr := none }
  | ColKind.generated e => { name := c.name, chType := c.dataType, defaultExpr := some e }

/-- The emitted ClickHouse type is ALWAYS the declared data type. -/
theorem alter_preserves_type (c : MyColDef) :
    (translateAlterColumn c).chType = c.dataType := by
  rcases c with ⟨n, dt, k⟩
  cases k <;> rfl

/-- A generated column maps its expression to `DEFAULT`, keeping the declared type. -/
theorem generated_expr_is_default (name dt e : String) :
    translateAlterColumn { name := name, dataType := dt, kind := ColKind.generated e }
      = { name := name, chType := dt, defaultExpr := some e } := rfl

/--
**The bug cannot recur.** For a generated column, the emitted ClickHouse type is
never the raw generation expression (unless the declared type happens to equal it,
excluded here) — it is always the declared data type.
-/
theorem type_is_never_expression (name dt e : String) (h : dt ≠ e) :
    (translateAlterColumn { name := name, dataType := dt, kind := ColKind.generated e }).chType ≠ e := by
  simpa [translateAlterColumn] using h

/-- A generated column always produces a `DEFAULT`, never an absent default. -/
theorem generated_has_default (name dt e : String) :
    (translateAlterColumn { name := name, dataType := dt, kind := ColKind.generated e }).defaultExpr
      = some e := rfl

end Replication
