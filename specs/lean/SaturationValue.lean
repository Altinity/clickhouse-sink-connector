/-!
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::dateTime64Max
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::DATETIME64_SATURATION_MAX
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::Saturation.saturationValue
request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
spec: specs/07-type-system/03-temporal-types.md, section 3.1.8

Two quantities, and the whole rule is that they must not be confused.

ACCEPTANCE (per scale). A DateTime64(p) column holds an instant exactly when it
lies in [1900-01-01 00:00:00, bound(p)] where bound(p) is 2299-12-31 23:59:59
followed by p nines for p in 0..8, and 2262-04-11 23:47:16.854775807 for p = 9
(the Int64 nanosecond limit). A value inside that range is stored unchanged.

SATURATION (one value). A value above bound(p) is stored as
DATETIME64_SATURATION_MAX = the instant of epoch second DATETIME64_MAX with a
zero fraction, i.e. 2299-12-31 23:59:59.000 UTC, at EVERY scale p in 0..8. The
single exception is p = 9, which stores bound(9) instead, because ClickHouse
refuses any value past the Int64 nanosecond limit (DECIMAL_OVERFLOW, code 407).

Why saturation must not be per scale. A saturated value is a stored value, so
for a DateTime64 column in a ReplacingMergeTree sorting key, one source value
must map to ONE stored instant or the row stops being replaceable: the tombstone
written after an upgrade carries a different key, never replaces the row it
deletes, and FINAL returns both. Bound(p) is strictly greater than
DATETIME64_SATURATION_MAX for every p in 1..8, so using it as the saturation
value puts a second stored form of the same source value in the key.

What is proved, for every scale and every instant (all integers, no axiom, no
`sorry`):
  S1  a value inside its own scale's range is never saturated;
  S2  a value above its range saturates to the SAME instant for scales 0..8;
  S3  that instant is exactly 2299-12-31 23:59:59.000 UTC = 10413791999 s;
  S4  therefore two saturated values never split the key;
  S5  scale 9 saturates to its own bound, and that bound is below the fixed value;
  S6  the minimum is one value at every scale, and it is not the saturation value.

Time is an Int count of microseconds, which covers every DateTime64 scale after
truncation. Only Lean core is used; every proof is kernel-checked. The
statements are about the numbers, and section 3.1.8 of the temporal spec carries
the measured ClickHouse behaviour they model.
Run: `lean SaturationValue.lean`.
-/

namespace SaturationValue

/-! ## The constants -/

/-- Microseconds in one second. -/
def US : Int := 1000000

/-- 2299-12-31 23:59:59 UTC as microseconds after the epoch: DATETIME64_MAX. -/
def DATETIME64_MAX_256 : Int := 10413791999 * US

/-- The fixed saturation value: epoch second DATETIME64_MAX, zero fraction. -/
def saturationMax : Int := DATETIME64_MAX_256

/-- The minimum: 1900-01-01 00:00:00 UTC, CLICKHOUSE_MIN_SUPPORTED_DATETIME64. -/
def minInstant : Int := -2208988800 * US

/-- The scale-9 maximum in whole microseconds. The Java holds the last three
    digits in nanoseconds; a microsecond model floors them, so this is compared
    only against whole-second bounds below. -/
def scale9MaxUs : Int := 9223372036 * US + 854775

/-- One unit of scale `p` in microseconds: 10^(6-p), floored at 1 for p >= 6
    because this model has no sub-microsecond digits. -/
def unitUs (p : Nat) : Int :=
  match p with
  | 0 => 1000000
  | 1 => 100000
  | 2 => 10000
  | 3 => 1000
  | 4 => 100
  | 5 => 10
  | _ => 1

/-- `DataTypeRange.dateTime64Max(int)`: the largest representable value of scale
    `p`, in whole microseconds. -/
def bound (p : Nat) : Int :=
  if p = 9 then scale9MaxUs else DATETIME64_MAX_256 + (US - unitUs p)

/-- `DebeziumConverter.saturationValue(int)`: the fixed value at 0..8, the scale's
    own bound at 9. -/
def saturationValue (p : Nat) : Int :=
  if p = 9 then bound 9 else saturationMax

/-- `checkIfDateTimeExceedsSupportedRange`: the value the connector stores. -/
def stored (p : Nat) (v : Int) : Int :=
  if v < minInstant then minInstant
  else if v > bound p then saturationValue p
  else v

/-! ## S3: the saturation value is 2299-12-31 23:59:59.000 UTC -/

theorem saturation_is_the_fixed_instant :
    saturationMax = 10413791999 * US := by
  unfold saturationMax DATETIME64_MAX_256
  rfl

theorem saturation_has_no_fraction :
    saturationMax % US = 0 := by
  unfold saturationMax DATETIME64_MAX_256 US
  decide

/-! ## The scale-9 boundary -/

theorem unitUs_le_US (p : Nat) : unitUs p ≤ US := by
  unfold unitUs US
  split <;> decide

/-- The minimum is strictly below every scale's bound, so no value above a bound
    is also below the minimum. `US` is unfolded first: `omega` treats an
    unreduced definition as an opaque atom, so a product of one is not linear
    to it. -/
theorem min_lt_bound (p : Nat) : minInstant < bound p := by
  unfold bound minInstant DATETIME64_MAX_256 scale9MaxUs US
  by_cases h9 : p = 9
  · rw [if_pos h9]
    decide
  · rw [if_neg h9]
    have hu : unitUs p ≤ 1000000 := by
      unfold unitUs
      split <;> decide
    omega

/-! ## S1: a value inside its own range is stored unchanged -/

theorem in_range_is_kept (p : Nat) (v : Int)
    (hlo : minInstant ≤ v) (hhi : v ≤ bound p) :
    stored p v = v := by
  unfold stored
  rw [if_neg (by omega), if_neg (by omega)]

/-! ## S2: saturation is the SAME instant at every scale below 9 -/

theorem saturation_is_scale_independent (a b : Nat)
    (ha : a < 9) (hb : b < 9) :
    saturationValue a = saturationValue b := by
  unfold saturationValue
  rw [if_neg (by omega), if_neg (by omega)]

theorem above_bound_stores_the_fixed_value (p : Nat) (v : Int)
    (hp : p < 9) (hv : bound p < v) :
    stored p v = saturationMax := by
  have hb := min_lt_bound p
  unfold stored
  rw [if_neg (by omega), if_pos hv]
  unfold saturationValue
  rw [if_neg (by omega)]

/-! ## S5: scale 9 is the exception, and it is forced -/

theorem scale9_saturates_to_its_own_bound :
    saturationValue 9 = scale9MaxUs := by
  unfold saturationValue
  rw [if_pos rfl]
  unfold bound
  rw [if_pos rfl]

theorem scale9_differs_from_the_fixed_value :
    saturationValue 9 ≠ saturationMax := by
  rw [scale9_saturates_to_its_own_bound]
  unfold scale9MaxUs saturationMax DATETIME64_MAX_256 US
  decide

/-- The scale-9 bound is below 2299-12-31, which is why the fixed value cannot be
    used there: ClickHouse would refuse it. -/
theorem scale9_bound_is_below_the_fixed_value :
    scale9MaxUs < saturationMax := by
  unfold scale9MaxUs saturationMax DATETIME64_MAX_256 US
  decide

/-! ## S4: two saturated values never split the key -/

theorem saturated_values_never_split_the_key (a b : Nat) (va vb : Int)
    (ha : a < 9) (hb : b < 9)
    (hva : bound a < va) (hvb : bound b < vb) :
    stored a va = stored b vb := by
  rw [above_bound_stores_the_fixed_value a va ha hva,
      above_bound_stores_the_fixed_value b vb hb hvb]

/-! ## S6: the minimum is one value at every scale -/

theorem below_min_stores_the_minimum (p : Nat) (v : Int)
    (hv : v < minInstant) :
    stored p v = minInstant := by
  unfold stored
  rw [if_pos hv]

theorem min_is_not_the_saturation_value :
    minInstant ≠ saturationMax := by
  unfold minInstant saturationMax DATETIME64_MAX_256 US
  decide

end SaturationValue
