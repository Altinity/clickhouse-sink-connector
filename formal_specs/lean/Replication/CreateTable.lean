/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# CREATE TABLE sorting-key selection (Specs 06.05 §3.6 / 08.05 §3.2)

A ClickHouse `ReplacingMergeTree` deduplicates on its sorting key. With
`ORDER BY tuple()` every row compares equal, so the whole table collapses to
ONE row — total, silent data loss for any source table that has no declared
identity. Both creation paths (the record-schema auto-create and the DDL
translator) must therefore pick a non-empty sorting key for every table that
can hold a row, with the same precedence:

1. the declared `PRIMARY KEY`;
2. otherwise the first `UNIQUE` key whose every column is `NOT NULL` (a nullable
   `UNIQUE` key admits any number of NULL-keyed rows in MySQL and is not an
   identity);
3. otherwise every non-generated column, in declaration order — and because
   that key may name nullable columns, the CREATE must then carry
   `SETTINGS allow_nullable_key=1` or ClickHouse rejects it (`Code: 44`).

This module models that selection and proves that (i) the key is never empty
when the table has a storable column, (ii) a declared key always wins, (iii) the
fallback is exactly the stored columns, and (iv) the nullable-key setting is
emitted exactly when the fallback names a nullable column — never for a
declared key.
-/

namespace Replication

/-- A source column as the CREATE TABLE translator sees it. -/
structure SrcColumn where
  name : String
  nullable : Bool
  /-- `GENERATED ALWAYS AS (...)`: a pure function of the stored columns. -/
  generated : Bool
deriving Repr, DecidableEq

/-- A source `CREATE TABLE` reduced to what decides its ClickHouse identity. -/
structure SrcTable where
  columns : List SrcColumn
  /-- Columns of the declared `PRIMARY KEY`; `[]` when there is none. -/
  primaryKey : List String
  /-- Columns of the first `UNIQUE` key; `[]` when there is none. -/
  uniqueKey : List String
deriving Repr, DecidableEq

/-- The non-generated (stored) columns, in declaration order. -/
def SrcTable.stored (t : SrcTable) : List SrcColumn :=
  t.columns.filter (fun c => !c.generated)

/-- Names of the columns declared (or implied) `NOT NULL`. -/
def SrcTable.notNullNames (t : SrcTable) : List String :=
  (t.columns.filter (fun c => !c.nullable)).map (·.name)

/-- A `UNIQUE` key is an identity only when it exists and is fully `NOT NULL`. -/
def SrcTable.uniqueKeyIsIdentity (t : SrcTable) : Bool :=
  !t.uniqueKey.isEmpty && t.uniqueKey.all (fun k => t.notNullNames.contains k)

/-- The sorting key both creation paths emit (Spec 06.05 §3.6, Spec 08.05 §3.2). -/
def sortingKey (t : SrcTable) : List String :=
  if !t.primaryKey.isEmpty then t.primaryKey
  else if t.uniqueKeyIsIdentity then t.uniqueKey
  else t.stored.map (·.name)

/-- Whether the emitted CREATE carries `SETTINGS allow_nullable_key=1`. -/
def nullableSortingKey (t : SrcTable) : Bool :=
  t.primaryKey.isEmpty && !t.uniqueKeyIsIdentity && t.stored.any (·.nullable)

/-! ## Helper lemmas -/

theorem stored_ne_nil {t : SrcTable} (h : ∃ c ∈ t.columns, c.generated = false) :
    t.stored ≠ [] := by
  obtain ⟨c, hc, hg⟩ := h
  have hmem : c ∈ t.stored := by
    unfold SrcTable.stored
    exact List.mem_filter.mpr ⟨hc, by simp [hg]⟩
  exact List.ne_nil_of_mem hmem

theorem uniqueKey_ne_nil_of_identity {t : SrcTable} (h : t.uniqueKeyIsIdentity = true) :
    t.uniqueKey ≠ [] := by
  unfold SrcTable.uniqueKeyIsIdentity at h
  intro hnil
  rw [hnil] at h
  simp at h

/-! ## The properties -/

/--
**Never `ORDER BY tuple()`.** A table with at least one non-generated column
always gets a non-empty sorting key, whichever branch of the precedence applies.
-/
theorem sorting_key_nonempty (t : SrcTable) (h : ∃ c ∈ t.columns, c.generated = false) :
    sortingKey t ≠ [] := by
  unfold sortingKey
  cases _hp : t.primaryKey with
  | cons a as => simp
  | nil =>
    rw [if_neg (by simp)]
    by_cases huk : t.uniqueKeyIsIdentity = true
    · rw [if_pos huk]
      exact uniqueKey_ne_nil_of_identity huk
    · rw [if_neg huk]
      intro hnil
      exact stored_ne_nil h (List.map_eq_nil.mp hnil)

/-- **A declared `PRIMARY KEY` always wins.** -/
theorem primary_key_wins (t : SrcTable) (h : t.primaryKey ≠ []) :
    sortingKey t = t.primaryKey := by
  unfold sortingKey
  have hne : t.primaryKey.isEmpty = false := by
    cases hp : t.primaryKey with
    | nil => exact absurd hp h
    | cons _ _ => rfl
  rw [if_pos (by simp [hne])]

/-- **A fully `NOT NULL` `UNIQUE` key is preferred to the value-derived key.** -/
theorem unique_key_wins_when_not_null (t : SrcTable) (hpk : t.primaryKey = [])
    (huk : t.uniqueKeyIsIdentity = true) : sortingKey t = t.uniqueKey := by
  unfold sortingKey
  rw [hpk]
  simp [huk]

/-- **The fallback is every stored column, in declaration order.** -/
theorem fallback_key_is_every_stored_column (t : SrcTable) (hpk : t.primaryKey = [])
    (huk : t.uniqueKeyIsIdentity = false) : sortingKey t = t.stored.map (·.name) := by
  unfold sortingKey
  rw [hpk]
  simp [huk]

/-- **`allow_nullable_key` is never emitted for a declared `PRIMARY KEY`.** -/
theorem declared_key_never_needs_nullable_setting (t : SrcTable) (h : t.primaryKey ≠ []) :
    nullableSortingKey t = false := by
  unfold nullableSortingKey
  have hne : t.primaryKey.isEmpty = false := by
    cases hp : t.primaryKey with
    | nil => exact absurd hp h
    | cons _ _ => rfl
  simp [hne]

/-- Nor for an adopted `NOT NULL` `UNIQUE` key. -/
theorem unique_key_never_needs_nullable_setting (t : SrcTable)
    (huk : t.uniqueKeyIsIdentity = true) : nullableSortingKey t = false := by
  unfold nullableSortingKey
  simp [huk]

/--
**The fallback key that names a nullable column carries the setting**, so the
CREATE is not rejected with `Code: 44` and retried forever.
-/
theorem nullable_fallback_gets_setting (t : SrcTable) (hpk : t.primaryKey = [])
    (huk : t.uniqueKeyIsIdentity = false) (hn : ∃ c ∈ t.stored, c.nullable = true) :
    nullableSortingKey t = true := by
  unfold nullableSortingKey
  obtain ⟨c, hc, hcn⟩ := hn
  have hany : t.stored.any (·.nullable) = true :=
    List.any_eq_true.mpr ⟨c, hc, hcn⟩
  rw [hpk]
  simp [huk, hany]

end Replication
