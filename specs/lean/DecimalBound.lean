/-
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMax
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMin
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::RangePolicy.boundDecimal
request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
spec: specs/07-type-system/02-floating-decimal-types.md, section 3.2.1

A decimal a column cannot hold is replaced by the bound OF THAT COLUMN, and the
replacement is itself a value the column accepts.

THE BOUND. A `Decimal(p, s)` column stores a value as an integer count of
10^-s units, and that integer has at most p digits, so the column accepts
exactly the values whose count lies in [-M, M] with M = 10^p - 1
(`DataTypeRange.decimalMax`; `decimalMin` is -M). The scale s only decides which
integer a source digit string maps to; the integer bound does not depend on it.
Measured on 24.8.14.10545.altinitystable, `Decimal(18, 6)`:
`999999999999.999999` is stored and `1000000000000.000000` is NULL.

THE DEFECT THIS FILE PINS. The saturation value used to be
`BinaryStreamUtils.DECIMAL128_MAX` = 2^127 - 1 = 170141183460469231731687303715884105727
(39 digits) whatever the column was. ClickHouse accepts at most 38 significant
digits for a Decimal128, so on a Decimal128 column the value the connector
stored in place of an out-of-range source value was one the server refuses:
`toDecimal128OrNull('170141183460469231731687303715884105727', 0)` is NULL while
`toDecimal128OrNull('99999999999999999999999999999999999999', 0)` returns the
value (measured on the same server). One constant was wrong in both
directions: unstorable in a Decimal128, and below the ceiling of a
`Decimal(65, 30)`, so it was neither a bound the server could honour nor the
column's own bound.

WHAT IS PROVED, for every column maximum M and every value (all integers, no
axiom, no `sorry`):
  B1  a value inside [-M, M] is kept unchanged;
  B2  a value above M stores M, and one below -M stores -M;
  B3  every stored value is inside [-M, M] — the substitution is always one the
      column accepts, which is exactly what a constant chosen for another width
      is not;
  B4  bounding is idempotent, so a redelivered value (delivery is at-least-once)
      does not drift;
  B5  the two bounds are distinct, so a low and a high saturation never collide;
  B6  instantiated: the old constant is past the 38-digit bound, so a Decimal128
      column refuses it.

Only Lean core is used; every proof is kernel-checked. Section 3.2.1 of the
decimal spec carries the measured ClickHouse behaviour these statements model.
Run: `lean DecimalBound.lean`.
-/

namespace DecimalBound

/-! ## The model -/

/-- The column's maximum as a count of 10^-s units: `DataTypeRange.decimalMax`,
    ten to the p minus one — p significant digits. -/
def scaledMax (p : Nat) : Int := 10 ^ p - 1

/-- The column's minimum: `DataTypeRange.decimalMin`, the negation of the same
    bound. -/
def scaledMin (p : Nat) : Int := -(scaledMax p)

/-- `Decimal(p, s)` accepts exactly the counts in [-M, M]. -/
def accepts (M : Int) (x : Int) : Prop := -M ≤ x ∧ x ≤ M

/-- `DebeziumConverter.RangePolicy.boundDecimal` for a column whose maximum is
    M: the value itself when the column holds it, the column's own bound
    otherwise. -/
def bound (M : Int) (x : Int) : Int :=
  if M < x then M
  else if x < -M then -M
  else x

/-! ## B1: a value the column holds is kept -/

theorem in_range_is_kept (M x : Int) (h : accepts M x) : bound M x = x := by
  unfold bound
  unfold accepts at h
  omega

/-! ## B2: saturation lands on the column's own bound

The two lemmas carry `0 ≤ M`, because a column's maximum is a count of digits:
`10^p - 1` is never negative. Without it the low-side statement is false (for
`M < 0`, `-M` is the larger of the two). -/

theorem above_the_max_stores_the_max (M x : Int) (h : M < x) : bound M x = M := by
  unfold bound
  omega

theorem below_the_min_stores_the_min (M x : Int) (hM : 0 ≤ M) (h : x < -M) :
    bound M x = -M := by
  unfold bound
  omega

/-! ## B3: what is stored is always a value the column accepts -/

theorem every_stored_value_is_accepted (M x : Int) (hM : 0 ≤ M) :
    accepts M (bound M x) := by
  unfold bound
  by_cases h1 : M < x
  · rw [if_pos h1]
    unfold accepts
    omega
  · rw [if_neg h1]
    by_cases h2 : x < -M
    · rw [if_pos h2]
      unfold accepts
      omega
    · rw [if_neg h2]
      unfold accepts
      omega

/-! ## B4: bounding twice is bounding once (a redelivered value does not drift) -/

theorem bounding_is_idempotent (M x : Int) (hM : 0 ≤ M) :
    bound M (bound M x) = bound M x :=
  in_range_is_kept M (bound M x) (every_stored_value_is_accepted M x hM)

/-! ## B5: a low and a high saturation never collide -/

theorem the_two_bounds_are_distinct (M : Int) (hM : 0 < M) : -M ≠ M := by
  omega

/-! ## The failure mode, abstractly: a constant above the maximum is not accepted -/

theorem a_constant_above_the_max_is_not_accepted (M C : Int) (h : M < C) :
    ¬ accepts M C := by
  unfold accepts
  omega

/-! ## The real widths -/

/-- `Decimal32(s)` declares 9 digits, so its bound is 999999999. -/
theorem decimal32_bound : scaledMax 9 = 999999999 := by
  unfold scaledMax
  decide

/-- `Decimal64(s)` declares 18 digits. -/
theorem decimal64_bound : scaledMax 18 = 999999999999999999 := by
  unfold scaledMax
  decide

/-- The constant the defect saturated to is past the 38-digit bound of a
    Decimal128: 2^127 - 1 needs 39 digits. -/
theorem decimal128_constant_is_past_the_bound : scaledMax 38 < 2 ^ 127 - 1 := by
  unfold scaledMax
  decide

/-- B6: a Decimal128 column does not accept the value the old code stored. -/
theorem decimal128_refuses_the_old_constant :
    ¬ accepts (scaledMax 38) (2 ^ 127 - 1) :=
  a_constant_above_the_max_is_not_accepted _ _ decimal128_constant_is_past_the_bound

/-- And it is storable in a `Decimal(65, 30)`, so the same constant was not a
    refusal there — it was simply not that column's ceiling. -/
theorem the_old_constant_fits_decimal65 :
    (2 ^ 127 - 1 : Int) < scaledMax 65 := by
  unfold scaledMax
  decide

end DecimalBound
