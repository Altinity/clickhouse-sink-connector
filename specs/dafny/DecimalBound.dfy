// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMax
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMin
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::RangePolicy.boundDecimal
// request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
// spec: specs/07-type-system/02-floating-decimal-types.md, section 3.2.1
//
// A decimal a column cannot hold is replaced by the bound OF THAT COLUMN, and the
// replacement is itself a value the column accepts.
//
// THE BOUND. A Decimal(p, s) column stores a value as an integer count of 10^-s
// units and that integer has at most p digits, so the column accepts exactly the
// counts in [-(10^p - 1), 10^p - 1]. DataTypeRange.decimalMax builds that with
// BigDecimal.TEN.pow(p).subtract(ONE).movePointLeft(s); decimalMin negates it.
//
// THE DEFECT THIS FILE PINS. The replacement value used to be the constant
// BinaryStreamUtils.DECIMAL128_MAX = 2^127 - 1 = 170141183460469231731687303715884105727,
// whatever the column was. It needs 39 digits, so a Decimal128 column holding at
// most 38 significant digits cannot store it: measured on
// 24.8.14.10545.altinitystable, toDecimal128OrNull('170141183460469231731687303715884105727', 0)
// is NULL while toDecimal128OrNull('99999999999999999999999999999999999999', 0)
// returns the value. The same constant IS storable in a Decimal(65, 30), so it was
// neither a bound the server refused nor the column's own ceiling: it was a
// constant chosen for one width and applied to every width.
//
// Model: a decimal value as an int count of 10^-s units, the same model as
// specs/lean/DecimalBound.lean. Only int arithmetic; every proof is checked.
// Run: dafny verify DecimalBound.dfy

// The column maximum for p significant digits: p nines, 10^p - 1.
function ScaledMax(p: nat): int
  decreases p
{
  if p == 0 then 0 else ScaledMax(p - 1) * 10 + 9
}

// ScaledMin(p) is the negation of the same bound.
function ScaledMin(p: nat): int
{
  -ScaledMax(p)
}

// Decimal(p, s) accepts exactly the counts in [ScaledMin(p), ScaledMax(p)].
predicate Accepts(p: nat, x: int)
{
  ScaledMin(p) <= x <= ScaledMax(p)
}

// boundDecimal: the value itself when the column holds it, the column's own
// bound otherwise.
function Bound(p: nat, x: int): int
{
  if x > ScaledMax(p) then ScaledMax(p)
  else if x < ScaledMin(p) then ScaledMin(p)
  else x
}

lemma ScaledMaxIsNonNegative(p: nat)
  ensures ScaledMax(p) >= 0
  decreases p
{
  if p > 0 {
    ScaledMaxIsNonNegative(p - 1);
  }
}

lemma ScaledMaxIsPositive(p: nat)
  requires p >= 1
  ensures ScaledMax(p) > 0
{
  ScaledMaxIsNonNegative(p - 1);
}

// B1: a value the column holds is kept unchanged.
lemma InRangeIsKept(p: nat, x: int)
  requires Accepts(p, x)
  ensures Bound(p, x) == x
{}

// B2: saturation lands on the column's own bound.
lemma AboveMaxStoresMax(p: nat, x: int)
  requires x > ScaledMax(p)
  ensures Bound(p, x) == ScaledMax(p)
{}

lemma BelowMinStoresMin(p: nat, x: int)
  requires x < ScaledMin(p)
  ensures Bound(p, x) == ScaledMin(p)
{
  // A column's maximum is a count of digits, so it is never negative; that is
  // what makes the low bound the smaller of the two and the first branch miss.
  ScaledMaxIsNonNegative(p);
}

// B3: whatever is stored is a value the column accepts. This is exactly what a
// constant chosen for another width does not give.
lemma EveryStoredValueIsAccepted(p: nat, x: int)
  ensures Accepts(p, Bound(p, x))
{
  ScaledMaxIsNonNegative(p);
}

// B4: bounding twice is bounding once, so a redelivered value does not drift.
lemma BoundingIsIdempotent(p: nat, x: int)
  ensures Bound(p, Bound(p, x)) == Bound(p, x)
{
  EveryStoredValueIsAccepted(p, x);
  InRangeIsKept(p, Bound(p, x));
}

// B5: the two bounds are distinct, so a low and a high saturation never collide.
lemma TheTwoBoundsAreDistinct(p: nat)
  requires p >= 1
  ensures ScaledMin(p) != ScaledMax(p)
{
  ScaledMaxIsPositive(p);
}

// The failure mode, abstractly: a constant above the maximum is not accepted.
lemma AConstantAboveTheMaxIsNotAccepted(p: nat, c: int)
  requires c > ScaledMax(p)
  ensures !Accepts(p, c)
{}

// The widths ClickHouse spells: Decimal32(s) is Decimal(9, s), Decimal64(s) is
// Decimal(18, s), Decimal128(s) is Decimal(38, s), Decimal256(s) is Decimal(76, s).
// The concrete values below are ranked by an explicit assertion, so the proof
// is integer comparison rather than an attempt to reason about the recursion.
lemma {:fuel ScaledMax,12} Decimal32Bound()
  ensures ScaledMax(9) == 999999999
{
  assert ScaledMax(9) == 999999999;
}

lemma {:fuel ScaledMax,20} Decimal64Bound()
  ensures ScaledMax(18) == 999999999999999999
{
  assert ScaledMax(18) == 999999999999999999;
}

// B6: the constant the defect saturated to is past the 38-digit bound of a
// Decimal128 — it needs 39 digits.
lemma {:fuel ScaledMax,40} Decimal128RefusesTheOldConstant()
  ensures ScaledMax(38) < 170141183460469231731687303715884105727
{
  assert ScaledMax(38) == 99999999999999999999999999999999999999;
  assert 99999999999999999999999999999999999999 < 170141183460469231731687303715884105727;
}

// And it is below the ceiling of a Decimal(65, 30), so it was not a refusal
// there — it was simply not that column's bound. 39 nines (10^39 - 1) already
// exceeds the constant, and the bound only grows with the digit count.
lemma ScaledMaxIsMonotone(p: nat, q: nat)
  requires p <= q
  ensures ScaledMax(p) <= ScaledMax(q)
  decreases q - p
{
  if p < q {
    ScaledMaxIsMonotone(p, q - 1);
    ScaledMaxIsNonNegative(q - 1);
  }
}

lemma {:fuel ScaledMax,42} TheOldConstantFitsDecimal65()
  ensures 170141183460469231731687303715884105727 < ScaledMax(65)
{
  assert ScaledMax(39) == 999999999999999999999999999999999999999;
  assert 170141183460469231731687303715884105727 < ScaledMax(39);
  ScaledMaxIsMonotone(39, 65);
}
