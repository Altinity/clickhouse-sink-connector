-------------------------- MODULE SaturationValue --------------------------
(***************************************************************************)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::dateTime64Max *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::DATETIME64_SATURATION_MAX *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::Saturation.saturationValue *)
(* request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release" *)
(* spec: specs/07-type-system/03-temporal-types.md, section 3.1.8 *)
(*                                                                         *)
(* ACCEPTANCE is per scale, SATURATION is one value, and the rule is that  *)
(* the two must not be confused.                                           *)
(*                                                                         *)
(* Why this model is temporal. One release cannot show the defect:         *)
(* whatever instant it stores for a source value, it stores the same       *)
(* instant again when it rewrites that value, so a single-release model    *)
(* proves nothing. The defect needs TWO releases. A ReplacingMergeTree     *)
(* collapses two rows only when the WHOLE sorting key is equal, and this   *)
(* column is part of the key, so if the release that wrote the row and the *)
(* release that writes over it saturate the same source value to          *)
(* different instants, the second row never replaces the first, FINAL      *)
(* returns both, and ClickHouse holds MORE rows than MySQL.                *)
(*                                                                         *)
(* The state is therefore `applied`: the set of releases that have written *)
(* this source value so far. Repeated writes by a release already in the   *)
(* set add nothing -- the stored instant is a function of                  *)
(* (release, scale, source value) -- so the set is the whole of the        *)
(* relevant state, and a release absent from it has not written yet.       *)
(*                                                                         *)
(* UNIT AND ORIGIN. Times are microseconds measured FROM the fixed         *)
(* saturation value, so SaturationMax is 0. TLC's integers are 32-bit     *)
(* ("TLC can't handle a number this big" for 10413791999), and the true    *)
(* span from 1900-01-01 to 2299-12-31 is about 1.26e13 us, which does not  *)
(* fit. Measuring from the saturation value is what makes it fit: every    *)
(* per-scale bound is then at most one second above it.                   *)
(*                                                                         *)
(* This keeps the relations the state machine depends on, which is all it  *)
(* uses -- equality, and the order of the bounds:                          *)
(*   Bound(0)     = SaturationMax   (2299-12-31 23:59:59, no fraction)     *)
(*   Bound(1..8)  > SaturationMax   (the same second plus a fraction, so   *)
(*                                   2299-12-31 23:59:59.999999 at p = 6) *)
(*   Bound(9)     < SaturationMax   (2262-04-11, the Int64 nanosecond cap) *)
(*   MinInstant   < every bound                                            *)
(* The two magnitudes that still overflow (1900-01-01 and 2262-04-11,      *)
(* about -1.26e13 and -1.17e15 us) are represented by stand-ins that keep  *)
(* their ORDER, because the model asserts nothing about their distance.   *)
(* The exact values are carried by specs/lean, specs/dafny and             *)
(* specs/hypothesis, which work in unbounded integers.                    *)
(*                                                                         *)
(* `PerScale` selects what the CURRENT release does:                       *)
(*   FALSE -- it saturates to the fixed historical value at every scale    *)
(*            (every release before 2.11.1, and the bulk loader), so it    *)
(*            cannot disagree with the release that wrote the row, and     *)
(*            the invariant HOLDS;                                         *)
(*   TRUE  -- it saturates to the per-scale bound (2.11.1), which is a     *)
(*            different instant at every scale 1..8 for a source value     *)
(*            past that bound, so the invariant is VIOLATED as soon as     *)
(*            such a row is rewritten.                                     *)
(*                                                                         *)
(* TLC runs this module with PerScale = FALSE: the rule the code must      *)
(* satisfy. The TRUE run is the regression demonstration.                  *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANT PerScale

\* Microseconds in one second.
US == 1000000

\* The fixed saturation value, 2299-12-31 23:59:59.000 UTC, as the origin.
SaturationMax == 0

\* 1900-01-01 UTC in the real type is -(2208988800 * US) us from the epoch and
\* overflows here; this stand-in keeps it strictly below every bound, and
\* strictly below the scale-9 bound as it is in the real type.
MinInstant == -(3 * US)

\* 2262-04-11 23:47:16 UTC in the real type is below 2299 by about 1.17e15 us
\* and overflows here; this stand-in keeps it strictly below SaturationMax and
\* strictly above the minimum.
Scale9Bound == -(2 * US)

Scales == 9

\* One unit of scale p in microseconds: 10^(6-p), floored at 1 for p >= 6.
UnitUs(p) ==
  IF p = 0 THEN 1000000
  ELSE IF p = 1 THEN 100000
  ELSE IF p = 2 THEN 10000
  ELSE IF p = 3 THEN 1000
  ELSE IF p = 4 THEN 100
  ELSE IF p = 5 THEN 10
  ELSE 1

\* DataTypeRange.dateTime64Max(int), measured from the saturation value: the
\* scale-9 limit, or 2299-12-31 23:59:59 plus the fraction p allows. At p = 0
\* that fraction is zero, so the bound IS the saturation value -- the one scale
\* the regression cannot reach.
Bound(p) ==
  IF p = 9 THEN Scale9Bound ELSE US - UnitUs(p)

\* The two releases that can touch one row: the one that wrote it (always the
\* historical rule) and the one writing now. They stay DISTINCT whatever
\* `PerScale` is, so the cardinality invariant below is never satisfied merely
\* because there is only one release to compare -- a singleton would make it
\* hold unconditionally and prove nothing.
Historical == "historical"
Current == "current"
Releases == {Historical, Current}

\* The instant a release stores. The historical release stores the fixed value
\* at every scale; the current release stores it too unless `PerScale` says it
\* stores the per-scale bound (2.11.1).
SaturationValue(release, p) ==
  IF p = 9 THEN Bound(9)
  ELSE IF release = Current /\ PerScale THEN Bound(p)
  ELSE SaturationMax

\* checkIfDateTimeExceedsSupportedRange: the instant the connector stores.
Stored(release, p, v) ==
  IF v < MinInstant THEN MinInstant
  ELSE IF v > Bound(p) THEN SaturationValue(release, p)
  ELSE v

\* The source values modelled: both sides of the minimum, the saturation value
\* itself, one unit past the widest bound, and both sides of the scale-9 bound.
SourceValues ==
  {MinInstant - 1, MinInstant, MinInstant + 1,
   SaturationMax - 1, SaturationMax, SaturationMax + 1,
   US - 1, US, US + 1,
   Scale9Bound - 1, Scale9Bound, Scale9Bound + 1}

\* applied: the set of releases that have written this source value.
VARIABLES applied

Init == applied = {}

\* Unguarded on purpose: once every release has written, the union adds nothing
\* and the step stutters. Guarding it on `Releases \ applied` would leave no
\* enabled action in that state, which TLC reports as a deadlock, not as a
\* clean end.
Next == \E r \in Releases : applied' = applied \union {r}

Spec == Init /\ [][Next]_applied

\* ------------------------------------------------------------------ *
\* The invariant ReplacingMergeTree needs: one source value, one key.  *
\* ------------------------------------------------------------------ *

\* The instants the column holds for one source value at one scale, over
\* every release that has written it.
StoredByReleases(p, v) == {Stored(r, p, v) : r \in applied}

OneStoredInstantPerSourceValue ==
  \A p \in 0..Scales, v \in SourceValues :
    IsFiniteSet(StoredByReleases(p, v)) /\ Cardinality(StoredByReleases(p, v)) <= 1

\* S2: the release that runs now saturates to the same instant at every
\* scale below 9. This is what makes it agree with the older release.
SaturationIsFixedBelowNine ==
  \A p \in 0..8 : SaturationValue(Current, p) = SaturationMax

\* S1: an in-range value is never saturated, whatever the release.
InRangeIsKept ==
  \A r \in Releases, p \in 0..Scales, v \in SourceValues :
    (MinInstant <= v /\ v <= Bound(p)) => Stored(r, p, v) = v

\* S5: the scale-9 bound is below the fixed value, which is why the fixed value
\* cannot be stored there.
Scale9IsBelowTheFixedValue == Scale9Bound < SaturationMax

\* S6: the minimum is one value at every scale, and it is not the saturation
\* value.
MinIsFixed ==
  /\ \A p \in 0..Scales : Stored(Historical, p, MinInstant - 1) = MinInstant
  /\ MinInstant # SaturationMax

\* ONE conjunct, deliberately: the property TLC must invalidate under the 2.11.1
\* rule is the write-time key split, and it has to appear only AFTER both
\* releases have written. Folding a static fact such as
\* `SaturationIsFixedBelowNine` in here would make the run fail in the initial
\* state instead -- true, but it would demonstrate the arithmetic rather than
\* the production symptom. The static facts are defined above and are proved
\* over unbounded integers by specs/lean, specs/dafny and specs/hypothesis.
Inv == OneStoredInstantPerSourceValue

=============================================================================
