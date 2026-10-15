/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

namespace Replication

/-- Primary key representation for relational tables. -/
def Key := Nat
deriving DecidableEq, Repr

/-- Relational column values supporting integers, strings, booleans, and explicit NULLs. -/
inductive Value where
  | nullVal : Value
  | intVal  : Int → Value
  | strVal  : String → Value
  | boolVal : Bool → Value
deriving DecidableEq, Repr

/-- A relational row represented as an association list of column names to values. -/
def Row := List (String × Value)
deriving DecidableEq, Repr

/-- Column classification reflecting ClickHouse storage models. -/
inductive ColumnKind where
  | ordinary     : ColumnKind
  | defaultExpr  : String → ColumnKind
  | materialized : String → ColumnKind
  | aliasExpr    : String → ColumnKind
deriving DecidableEq, Repr

/-- Table schema mapping column names to their kinds and nullability. -/
structure ColumnDef where
  name     : String
  nullable : Bool
  kind     : ColumnKind
deriving DecidableEq, Repr

def Schema := List ColumnDef
deriving DecidableEq, Repr

/-- The canonical source MySQL table state: a partial mapping from primary keys to rows. -/
def MySQLState := Key → Option Row

/-- The initial empty MySQL table state. -/
def emptyMySQL : MySQLState := fun _ => none

/-- Row lookup helper. -/
def getCol (row : Row) (colName : String) : Option Value :=
  match row.find? (fun (k, _) => k == colName) with
  | some (_, v) => some v
  | none        => none

end Replication
