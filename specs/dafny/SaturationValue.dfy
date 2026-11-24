// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::dateTime64Max
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::DATETIME64_SATURATION_MAX
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::Saturation.saturationValue
// request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
// spec: specs/07-type-system/03-temporal-types.md, section 3.1.8
//
// Two quantities, and the whole rule is that they must not be confused.
//
// ACCEPTANCE (per scale). A DateTime64(p) column holds an instant exactly when it
// lies in [1900-01-01 00:00:00, bound(p)] where bound(p) is 2299-12-31 23:59:59
// followed by p nines for p in 0..8, and 2262-04-11 23:47:16.854775807 for p = 9
// (the Int64 nanosecond limit). A value inside that range is stored unchanged.
//
// SATURATION (one value). A value above bound(p) is stored as
// DATETIME64_SATURATION_MAX = the instant of epoch second DATETIME64_MAX with a
// zero fraction, i.e. 2299-12-31 23:59:59.000 UTC, at EVERY scale p in 0..8. The
// single exception is p = 9, which stores bound(9) instead, because ClickHouse
// refuses any value past the Int64 nanosecond limit (DECIMAL_OVERFLOW, code 407).
//
// Why saturation must not be per scale. A saturated value is a stored value, so
// for a DateTime64 column in a ReplacingMergeTree sorting key, one source value
// must map to ONE stored instant or the row stops being replaceable: the tombstone
// written after an upgrade carries a different key, never replaces the row it
// deletes, and FINAL returns both. Bound(p) is strictly greater than the fixed
// saturation value for every p in 1..8, so using it as the saturation value puts a
// second stored form of the same source value in the key.
//
// Model: time is an int count of microseconds, the same model as
// specs/lean/SaturationValue.lean.
// Run: dafny verify SaturationValue.dfy

const US := 1000000

// 2299-12-31 23:59:59 UTC as microseconds after the epoch: DATETIME64_MAX.
const DATETIME64_MAX_256 := 10413791999 * US

// The fixed saturation value.
const SaturationMax := DATETIME64_MAX_256

// 1900-01-01 00:00:00 UTC as microseconds: CLICKHOUSE_MIN_SUPPORTED_DATETIME64.
const MinInstant := -2208988800 * US

// The scale-9 maximum in whole microseconds (the Java holds the last three digits
// in nanoseconds; the model floors them, so it is only compared against whole-second
// bounds below).
const Scale9MaxUs := 9223372036 * US + 854775

// One unit of scale p in microseconds.
function UnitUs(p: nat): int
{
  match p
  case 0 => 1000000
  case 1 => 100000
  case 2 => 10000
  case 3 => 1000
  case 4 => 100
  case 5 => 10
  case _ => 1
}

// The largest representable value of scale p in whole microseconds.
function Bound(p: nat): int
{
  if p == 9 then Scale9MaxUs else DATETIME64_MAX_256 + (US - UnitUs(p))
}

// saturationValue(int): the fixed value at 0..8, the scale's own bound at 9.
function SaturationValue(p: nat): int
{
  if p == 9 then Bound(9) else SaturationMax
}

// checkIfDateTimeExceedsSupportedRange: the value the connector stores.
function Stored(p: nat, v: int): int
{
  if v < MinInstant then MinInstant
  else if v > Bound(p) then SaturationValue(p)
  else v
}

// S3: the saturation value is 2299-12-31 23:59:59.000 UTC, no fraction.
lemma SaturationIsTheFixedInstant()
  ensures SaturationMax == 10413791999 * US
  ensures SaturationMax % US == 0
{}

// S1: an in-range value is stored unchanged, at any scale.
lemma InRangeIsKept(p: nat, v: int)
  requires p <= 9
  requires MinInstant <= v <= Bound(p)
  ensures Stored(p, v) == v
{}

// S2: scales 0..8 saturate to one and the same instant.
lemma SaturationIsScaleIndependent(a: nat, b: nat)
  requires a < 9
  requires b < 9
  ensures SaturationValue(a) == SaturationValue(b)
{}

// S2: any value past its scale's bound stores exactly the fixed saturation value.
lemma AboveBoundStoresTheFixedValue(p: nat, v: int)
  requires p < 9
  requires Bound(p) < v
  ensures Stored(p, v) == SaturationMax
{
  assert UnitUs(p) <= US;
  assert MinInstant < Bound(p);
}

// S5: scale 9 saturates to its own bound, and that differs from the fixed value.
lemma Scale9SaturatesToItsOwnBound()
  ensures SaturationValue(9) == Scale9MaxUs
  ensures SaturationValue(9) != SaturationMax
{}

// S5: the scale-9 bound is below the fixed value, which is why the fixed value
// cannot be used at scale 9: ClickHouse would refuse it.
lemma Scale9BoundIsBelowTheFixedValue()
  ensures Scale9MaxUs < SaturationMax
{}

// S4: two out-of-range values of two scales below 9 store the same instant, so one
// source value keeps one sorting key and neither sorts before the other.
lemma SaturatedValuesNeverSplitTheKey(a: nat, b: nat, va: int, vb: int)
  requires a < 9
  requires b < 9
  requires Bound(a) < va
  requires Bound(b) < vb
  ensures Stored(a, va) == Stored(b, vb)
{
  AboveBoundStoresTheFixedValue(a, va);
  AboveBoundStoresTheFixedValue(b, vb);
}

// S6: a value below the minimum stores the minimum at every scale, and the minimum
// is not the saturation value, so a low and a high saturation never collide.
lemma BelowMinStoresTheMinimum(p: nat, v: int)
  requires p <= 9
  requires v < MinInstant
  ensures Stored(p, v) == MinInstant
{}

lemma MinIsNotTheSaturationValue()
  ensures MinInstant != SaturationMax
{}
